package com.vvtool.vitremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface

/**
 * Streams the screen as H.264 video: MediaProjection -> VirtualDisplay -> hardware encoder
 * surface (zero copy, almost no CPU). A static screen produces (almost) no data, and the
 * display is torn down entirely when nobody is watching.
 */
class CaptureService : Service() {

    companion object {
        @Volatile var instance: CaptureService? = null
            private set

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).putExtra("code", code).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }

        /** Message types (first byte of a binary WebSocket message). */
        const val MSG_CONFIG = 3 // [3][w:2][h:2][SPS+PPS annex-B]
        const val MSG_FRAME = 4  // [4][key:1][annex-B access unit]
    }

    private val thread = HandlerThread("vit-enc").apply { start() }
    private val h = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    // Requested stream (all accessed on h)
    private var wantW = 0
    private var kbps = 2000
    private var fps = 30
    private var curKey = ""
    @Volatile var config: ByteArray? = null
        private set
    /** Average capture->encoded latency (ms) of recent frames. */
    @Volatile var encMs = 0f
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (id == 0) h.post { reconfigure() } } // rotation
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null || projection != null) return START_NOT_STICKY
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = try { mpm.getMediaProjection(code, data) } catch (_: Exception) { null }
        if (p == null) { stopSelf(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { h.post { release() }; stopSelf() }
        }, h)
        projection = p
        instance = this
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, h)
        RemoteService.instance?.onCaptureChanged()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("cap", "Screen streaming", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, "cap")
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = b.setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("vitRemote")
            .setContentText("Screen streaming ready")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        instance = null
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        h.post { release(); projection?.stop(); projection = null }
        thread.quitSafely()
        RemoteService.instance?.onCaptureChanged()
        super.onDestroy()
    }

    /** width 0 stops the stream. */
    fun setStream(w: Int, kbps: Int, fps: Int) = h.post {
        wantW = w; this.kbps = kbps; this.fps = fps
        reconfigure()
    }

    fun requestKeyFrame() = h.post {
        try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {}
    }

    private fun release() {
        display?.release(); display = null
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release(); codec = null
        surface?.release(); surface = null
        config = null
        curKey = ""
    }

    private fun reconfigure() {
        val p = projection ?: return
        if (wantW <= 0) { release(); return }
        val sz = DeviceInfo.screenSize(this)
        // Encoders want multiples of 16
        val tw = (minOf(wantW, sz.x) / 16 * 16).coerceAtLeast(160)
        val th = (sz.y.toLong() * tw / sz.x).toInt() / 16 * 16
        val key = "$tw:$th:$kbps:$fps"
        if (key == curKey && codec != null) {
            // Same encoder: just change bitrate on the fly if possible
            return
        }
        if (codec != null && curKey.startsWith("$tw:$th:")) {
            try {
                codec!!.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
                curKey = key
                return
            } catch (_: Exception) {}
        }
        release()
        val c = try { createEncoder(tw, th) } catch (e: Exception) {
            RemoteService.instance?.notifyAll("err", "Video encoder error: ${e.message}")
            return
        }
        codec = c
        curKey = key
        val s = c.createInputSurface()
        surface = s
        c.start()
        display = p.createVirtualDisplay(
            "vitRemote", tw, th, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, h
        )
    }

    private fun createEncoder(w: Int, hh: Int): MediaCodec {
        fun fmt(profile: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, hh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // Repeat the last frame every 40 ms while idle: tiny P-frames that push the newest image
            // through encoder/decoder pipelines immediately instead of waiting for the next change
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 40_000)
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= 29) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
            if (profile) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                val sps = format.getByteBuffer("csd-0") ?: return
                val pps = format.getByteBuffer("csd-1") ?: return
                setConfig(w, hh, bytes(sps) + bytes(pps))
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (codec !== this@CaptureService.codec) return
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (isConfig) {
                            if (config == null) setConfig(w, hh, bytes(buf))
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            val lat = (System.nanoTime() / 1000 - info.presentationTimeUs) / 1000f
                            if (lat in 0f..2000f) encMs = encMs * 0.9f + lat * 0.1f
                            val msg = ByteArray(2 + info.size)
                            msg[0] = MSG_FRAME.toByte(); msg[1] = if (key) 1 else 0
                            buf.get(msg, 2, info.size)
                            RemoteService.instance?.broadcastVideo(msg, key)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {}
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                h.post { if (codec === this@CaptureService.codec) { release(); reconfigure() } }
            }
        }, h)
        try {
            c.configure(fmt(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (_: Exception) {
            c.reset()
            c.configure(fmt(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        return c
    }

    private fun bytes(b: java.nio.ByteBuffer): ByteArray {
        val d = b.duplicate()
        return ByteArray(d.remaining()).also { d.get(it) }
    }

    private fun setConfig(w: Int, hh: Int, raw: ByteArray) {
        val csd = SpsFix.annexB(raw)
        val msg = ByteArray(5 + csd.size)
        msg[0] = MSG_CONFIG.toByte()
        msg[1] = (w shr 8).toByte(); msg[2] = w.toByte()
        msg[3] = (hh shr 8).toByte(); msg[4] = hh.toByte()
        System.arraycopy(csd, 0, msg, 5, csd.size)
        config = msg
        RemoteService.instance?.broadcastConfig(msg)
    }
}
