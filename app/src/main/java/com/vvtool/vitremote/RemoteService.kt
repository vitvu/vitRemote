package com.vvtool.vitremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class RemoteService : AccessibilityService(), WebServer.Listener {

    companion object {
        @Volatile var instance: RemoteService? = null
            private set
        @Volatile var lastError: String? = null

        /** First byte is the type: 1 = live frame, 2 = full screenshot. Rest is JPEG. */
        fun jpeg(b: Bitmap, q: Int, type: Int): ByteArray {
            val bo = ByteArrayOutputStream(64 * 1024)
            bo.write(type)
            b.compress(Bitmap.CompressFormat.JPEG, q, bo)
            return bo.toByteArray()
        }
    }

    private var server: WebServer? = null
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val capThread = HandlerThread("vit-cap").apply { start() }
    private val cap = Handler(capThread.looper)

    @Volatile private var foregroundApp = ""
    @Volatile private var lastLive: ByteArray? = null
    private var lastConsentAsk = 0L
    private val startedAt = SystemClock.elapsedRealtime()

    // Accessibility screenshot loop state (only touched on the cap thread)
    private var capturing = false
    private val fullWaiters = ArrayList<WebServer.Client>()

    val running get() = server != null
    val clientCount get() = server?.clients?.size ?: 0

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        applyConfig()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            e.packageName?.toString()?.let { if (it != foregroundApp) foregroundApp = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopServer()
        capThread.quitSafely()
        main.removeCallbacksAndMessages(null)
        // On rebind the new instance may be assigned before the old one is destroyed
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Start/stop/restart the server according to the current settings. */
    fun applyConfig(attempt: Int = 0) {
        main.removeCallbacksAndMessages(null)
        stopServer()
        if (!prefs.enabled) return
        try {
            val html = assets.open("index.html").use { it.readBytes() }
            server = WebServer(prefs.port, prefs.pin, html, this).also { it.start() }
            lastError = null
        } catch (e: Exception) {
            server = null
            lastError = "Cannot open port ${prefs.port}: ${e.message}"
            // The old instance may still hold the port for a moment => retry
            if (attempt < 5) main.postDelayed({ applyConfig(attempt + 1) }, 1000)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    // ================= WebSocket =================

    override fun onOpen(c: WebServer.Client) {
        c.send(info())
    }

    override fun onClose(c: WebServer.Client) {
        cap.post { fullWaiters.remove(c) }
        CaptureService.instance?.dropClient(c)
        updateCapture()
    }

    override fun onText(c: WebServer.Client, msg: String) {
        if (msg.isEmpty()) return
        val arg = if (msg.length > 2) msg.substring(2) else ""
        try {
            when (msg[0]) {
                'a' -> globalAct(c, arg)
                't' -> { val p = nums(arg); gesture(p[0], p[1], p[0], p[1], 40) }
                'l' -> { val p = nums(arg); gesture(p[0], p[1], p[0], p[1], 700) }
                's' -> { val p = nums(arg); gesture(p[0], p[1], p[2], p[3], p.getOrElse(4) { 250 }) }
                'v' -> volume(arg)
                'x' -> setText(c, arg, append = true)
                'X' -> setText(c, "", append = false)
                'e' -> imeEnter(c)
                'i' -> c.send(info())
                'c' -> requestFull(c)
                'L' -> {
                    c.liveWidth = arg.toIntOrNull()?.coerceIn(0, 1440) ?: 0
                    c.lastFrameCrc = 0
                    // Show the last known frame immediately (a static screen produces no new frames)
                    if (c.liveWidth > 0) lastLive?.let { c.sendBin(it) }
                    updateCapture()
                }
                'p' -> c.send("{\"t\":\"pong\"}")
            }
        } catch (e: Exception) {
            err(c, e.message ?: e.toString())
        }
    }

    private fun nums(s: String) = s.split(',').map { it.trim().toInt() }

    private fun err(c: WebServer.Client, m: String) = c.send(DeviceInfo.toJson(mapOf("t" to "err", "m" to m)))

    fun notifyAll(type: String, m: String) {
        val s = DeviceInfo.toJson(mapOf("t" to type, "m" to m))
        server?.clients?.forEach { it.send(s) }
    }

    private fun captureMode() = when {
        CaptureService.instance != null -> "MediaProjection"
        Build.VERSION.SDK_INT >= 30 -> "Accessibility"
        else -> "Needs permission"
    }

    private fun info(): String {
        val sz = DeviceInfo.screenSize(this)
        return DeviceInfo.json(
            this, mapOf(
                "app" to foregroundApp,
                "clients" to clientCount,
                "svcUptime" to (SystemClock.elapsedRealtime() - startedAt) / 1000,
                "capture" to captureMode(),
                "w" to sz.x, "h" to sz.y,
                "ver" to (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" })
            )
        )
    }

    // ================= Actions =================

    private fun globalAct(c: WebServer.Client, a: String) {
        val id = when (a) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notif" -> GLOBAL_ACTION_NOTIFICATIONS
            "qs" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else -1
            "shot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (id < 0 || !performGlobalAction(id)) err(c, "Not supported on this device: $a")
    }

    /** Coordinates are on a 0..10000 scale of the screen width/height. */
    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        val sz = DeviceInfo.screenSize(this)
        fun fx(v: Int) = (v.coerceIn(0, 10000) * (sz.x - 1) / 10000f)
        fun fy(v: Int) = (v.coerceIn(0, 10000) * (sz.y - 1) / 10000f)
        val path = Path().apply { moveTo(fx(x1), fy(y1)); lineTo(fx(x2), fy(y2)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(1, 5000).toLong())
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun volume(a: String) {
        val dir = when (a) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return
        }
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun setText(c: WebServer.Client, text: String, append: Boolean) {
        val n = focusedInput()
        if (n == null || !n.isEditable) return err(c, "Tap a text field on the phone first")
        val hint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val cur = if (append && !hint) n.text?.toString() ?: "" else ""
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text)
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) err(c, "Could not type into this field")
    }

    private fun imeEnter(c: WebServer.Client) {
        val n = focusedInput() ?: return err(c, "Tap a text field on the phone first")
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else err(c, "Enter key requires Android 11+")
    }

    // ================= Screen capture =================

    private fun liveClients() = server?.clients?.filter { it.liveWidth > 0 } ?: emptyList()

    /** Called when MediaProjection starts or stops. */
    fun onCaptureChanged() {
        lastLive = null
        updateCapture()
        server?.clients?.forEach { it.send(info()) }
    }

    /** Pick the capture backend and the stream size from current viewers. */
    private fun updateCapture() {
        val w = liveClients().maxOfOrNull { it.liveWidth } ?: 0
        val cs = CaptureService.instance
        when {
            cs != null -> cs.setWidth(w)
            w == 0 -> {}
            Build.VERSION.SDK_INT >= 30 -> cap.post { capture() }
            else -> askConsent()
        }
    }

    /** Opens the system screen-capture dialog on the phone (once per 15 s at most). */
    private fun askConsent() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConsentAsk < 15000) return
        lastConsentAsk = now
        notifyAll("ok", "Tap \"Start now\" on the phone to allow screen streaming")
        main.post {
            startActivity(Intent(this, ProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun broadcastLive(data: ByteArray) {
        lastLive = data
        val crc = CRC32().apply { update(data) }.value
        // Unchanged frame => send nothing
        liveClients().forEach { if (it.lastFrameCrc != crc) { it.lastFrameCrc = crc; it.sendBin(data) } }
    }

    private fun requestFull(c: WebServer.Client) {
        val cs = CaptureService.instance
        when {
            cs != null -> cs.requestFull(c)
            Build.VERSION.SDK_INT >= 30 -> cap.post { if (c !in fullWaiters) fullWaiters.add(c); capture() }
            else -> { askConsent(); err(c, "Allow screen capture on the phone, then try again") }
        }
    }

    /** Accessibility screenshot loop (Android 11+, ~3 fps). Runs on the cap thread. */
    private fun capture() {
        if (capturing || Build.VERSION.SDK_INT < 30 || CaptureService.instance != null) return
        if (fullWaiters.isEmpty() && liveClients().isEmpty()) return
        capturing = true
        takeScreenshot(Display.DEFAULT_DISPLAY, { cap.post(it) }, object : TakeScreenshotCallback {
            override fun onSuccess(res: ScreenshotResult) {
                try { processShot(res) } catch (_: Throwable) {}
                capturing = false
                if (liveClients().isNotEmpty() || fullWaiters.isNotEmpty()) cap.postDelayed({ capture() }, 340)
            }

            override fun onFailure(code: Int) {
                capturing = false
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    cap.postDelayed({ capture() }, 150)
                } else {
                    fullWaiters.forEach { err(it, "Screenshot failed (code $code), the app may block capture") }
                    fullWaiters.clear()
                    if (liveClients().isNotEmpty()) cap.postDelayed({ capture() }, 1000)
                }
            }
        })
    }

    private fun processShot(res: ScreenshotResult) {
        val hb = res.hardwareBuffer
        val hw = Bitmap.wrapHardwareBuffer(hb, res.colorSpace)
        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
        hb.close(); hw?.recycle()
        if (bmp == null) return

        if (fullWaiters.isNotEmpty()) {
            val data = jpeg(bmp, 88, 2)
            fullWaiters.forEach { it.sendBin(data) }
            fullWaiters.clear()
        }
        val w = liveClients().maxOfOrNull { it.liveWidth } ?: 0
        if (w > 0) {
            val tw = minOf(w, bmp.width)
            val scaled = if (tw == bmp.width) bmp
            else Bitmap.createScaledBitmap(bmp, tw, bmp.height * tw / bmp.width, true)
            broadcastLive(jpeg(scaled, 55, 1))
            if (scaled !== bmp) scaled.recycle()
        }
        bmp.recycle()
    }
}
