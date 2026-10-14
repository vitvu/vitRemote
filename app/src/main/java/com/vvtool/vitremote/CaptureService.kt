package com.vvtool.vitremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

/**
 * Streams the screen through MediaProjection (works on every Android version).
 * The virtual display only produces frames when the screen changes, and is detached
 * entirely when nobody is watching, so it costs nothing while idle.
 */
class CaptureService : Service() {

    companion object {
        @Volatile var instance: CaptureService? = null
            private set

        private const val MIN_FRAME_MS = 66L // ~15 fps cap

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java).putExtra("code", code).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CaptureService::class.java))
        }
    }

    private val thread = HandlerThread("vit-proj").apply { start() }
    private val h = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    private var wantW = 0
    private val fullWaiters = ArrayList<WebServer.Client>()
    private var lastSent = 0L
    private var scheduled = false
    private var rowBmp: Bitmap? = null

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
            .setContentText("Screen streaming is available")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        instance = null
        h.post { release() }
        thread.quitSafely()
        RemoteService.instance?.onCaptureChanged()
        super.onDestroy()
    }

    private fun release() {
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        rowBmp?.recycle(); rowBmp = null
    }

    /** Live stream width; 0 pauses the stream. */
    fun setWidth(w: Int) = h.post { wantW = w; reconfigure() }

    fun requestFull(c: WebServer.Client) = h.post { if (c !in fullWaiters) fullWaiters.add(c); reconfigure() }

    fun dropClient(c: WebServer.Client) = h.post { fullWaiters.remove(c) }

    private fun reconfigure() {
        val p = projection ?: return
        val sz = DeviceInfo.screenSize(this)
        val target = if (fullWaiters.isNotEmpty()) sz.x else wantW
        if (target <= 0) { display?.surface = null; return }
        val tw = minOf(target, sz.x) and 1.inv()
        val th = (sz.y.toLong() * tw / sz.x).toInt() and 1.inv()
        val dpi = resources.displayMetrics.densityDpi
        if (display != null && tw == curW && th == curH) { display?.surface = reader?.surface; return }
        val old = reader
        val r = ImageReader.newInstance(tw, th, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, h)
        reader = r; curW = tw; curH = th
        val d = display
        if (d == null) {
            display = p.createVirtualDisplay(
                "vitRemote", tw, th, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
            )
        } else {
            d.resize(tw, th, dpi)
            d.surface = r.surface
        }
        old?.close()
    }

    private fun onFrame(r: ImageReader) {
        if (scheduled) return
        val wait = MIN_FRAME_MS - (SystemClock.uptimeMillis() - lastSent)
        if (wait > 0 && fullWaiters.isEmpty()) {
            // Throttle, but always deliver the latest frame afterwards
            scheduled = true
            h.postDelayed({ scheduled = false; process(r) }, wait)
        } else process(r)
    }

    private fun process(r: ImageReader) {
        if (r !== reader) return
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val w = img.width
        val hh = img.height
        val plane = img.planes[0]
        val stride = plane.rowStride / plane.pixelStride
        var bmp = rowBmp
        if (bmp == null || bmp.width != stride || bmp.height != hh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(stride, hh, Bitmap.Config.ARGB_8888)
            rowBmp = bmp
        }
        bmp!!.copyPixelsFromBuffer(plane.buffer)
        img.close()

        // Rotation changed => rebuild the display with the new aspect ratio
        val sz = DeviceInfo.screenSize(this)
        if ((sz.x > sz.y) != (w > hh)) { reconfigure(); return }

        val frame = if (stride == w) bmp else Bitmap.createBitmap(bmp, 0, 0, w, hh)
        lastSent = SystemClock.uptimeMillis()
        if (fullWaiters.isNotEmpty() && w >= sz.x - 1) {
            val data = RemoteService.jpeg(frame, 88, 2)
            fullWaiters.forEach { it.sendBin(data) }
            fullWaiters.clear()
            reconfigure()
        } else if (wantW > 0) {
            RemoteService.instance?.broadcastLive(RemoteService.jpeg(frame, 55, 1))
        }
        if (frame !== bmp) frame.recycle()
    }
}
