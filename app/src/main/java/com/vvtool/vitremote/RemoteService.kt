package com.vvtool.vitremote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class RemoteService : AccessibilityService(), WebServer.Listener {

    companion object {
        @Volatile var instance: RemoteService? = null
            private set
        @Volatile var lastError: String? = null
    }

    private var server: WebServer? = null
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val capThread = HandlerThread("vit-cap").apply { start() }
    private val cap = Handler(capThread.looper)

    @Volatile private var foregroundApp = ""
    @Volatile private var torchOn = false
    private var torchId: String? = null
    private val startedAt = SystemClock.elapsedRealtime()

    // ---- trạng thái chụp màn hình (chỉ truy cập trên luồng cap) ----
    private var capturing = false
    private val fullWaiters = ArrayList<WebServer.Client>()

    val running get() = server != null
    val clientCount get() = server?.clients?.size ?: 0

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        setupTorch()
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
        // Khi hệ thống bind lại, instance mới có thể đã được gán trước khi instance cũ bị huỷ
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Bật/tắt/khởi động lại máy chủ theo cấu hình hiện tại. */
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
            lastError = "Không mở được cổng ${prefs.port}: ${e.message}"
            // Cổng có thể vẫn đang bị instance cũ giữ trong chốc lát => thử lại
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
    }

    override fun onText(c: WebServer.Client, msg: String) {
        if (msg.isEmpty()) return
        val cmd = msg[0]
        val arg = if (msg.length > 2) msg.substring(2) else ""
        try {
            when (cmd) {
                'a' -> globalAct(c, arg)
                't' -> { val p = nums(arg); gesture(p[0], p[1], p[0], p[1], 40) }
                'l' -> { val p = nums(arg); gesture(p[0], p[1], p[0], p[1], 700) }
                's' -> { val p = nums(arg); gesture(p[0], p[1], p[2], p[3], p.getOrElse(4) { 250 }) }
                'v' -> volume(arg)
                'm' -> media(arg)
                'x' -> setText(c, arg, append = true)
                'X' -> setText(c, "", append = false)
                'e' -> imeEnter(c)
                'f' -> toggleTorch(c)
                'w' -> wake()
                'i' -> c.send(info())
                'c' -> requestFull(c)
                'L' -> { c.liveWidth = arg.toIntOrNull()?.coerceIn(0, 1440) ?: 0; c.lastFrameCrc = 0; kickCapture() }
                'p' -> c.send("{\"t\":\"pong\"}")
            }
        } catch (e: Exception) {
            err(c, e.message ?: e.toString())
        }
    }

    private fun nums(s: String) = s.split(',').map { it.trim().toInt() }

    private fun ok(c: WebServer.Client, m: String) = c.send(DeviceInfo.toJson(mapOf("t" to "ok", "m" to m)))
    private fun err(c: WebServer.Client, m: String) = c.send(DeviceInfo.toJson(mapOf("t" to "err", "m" to m)))

    private fun info(): String {
        val sz = DeviceInfo.screenSize(this)
        return DeviceInfo.json(
            this, mapOf(
                "app" to foregroundApp,
                "torch" to torchOn,
                "clients" to clientCount,
                "svcUptime" to (SystemClock.elapsedRealtime() - startedAt) / 1000,
                "canShot" to (Build.VERSION.SDK_INT >= 30),
                "w" to sz.x, "h" to sz.y,
                "ver" to (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" })
            )
        )
    }

    // ================= Hành động =================

    private fun globalAct(c: WebServer.Client, a: String) {
        val id = when (a) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notif" -> GLOBAL_ACTION_NOTIFICATIONS
            "qs" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "split" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else -1
            "shot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            "dismiss" -> if (Build.VERSION.SDK_INT >= 31) GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE else -1
            "apps" -> if (Build.VERSION.SDK_INT >= 31) GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS else -1
            else -> -1
        }
        if (id < 0 || !performGlobalAction(id)) err(c, "Thiết bị không hỗ trợ: $a")
    }

    /** Toạ độ nhận vào theo thang 0..10000 của chiều rộng/cao màn hình. */
    private fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int) {
        val sz = DeviceInfo.screenSize(this)
        fun fx(v: Int) = (v.coerceIn(0, 10000) * (sz.x - 1) / 10000f)
        fun fy(v: Int) = (v.coerceIn(0, 10000) * (sz.y - 1) / 10000f)
        val path = Path().apply { moveTo(fx(x1), fy(y1)); lineTo(fx(x2), fy(y2)) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms.coerceIn(1, 5000).toLong())
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun volume(a: String) {
        val au = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val (stream, dir) = when (a) {
            "up" -> AudioManager.STREAM_MUSIC to AudioManager.ADJUST_RAISE
            "down" -> AudioManager.STREAM_MUSIC to AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.STREAM_MUSIC to AudioManager.ADJUST_TOGGLE_MUTE
            "rup" -> AudioManager.STREAM_RING to AudioManager.ADJUST_RAISE
            "rdown" -> AudioManager.STREAM_RING to AudioManager.ADJUST_LOWER
            else -> return
        }
        au.adjustStreamVolume(stream, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun media(a: String) {
        val code = when (a) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return
        }
        val au = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        au.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        au.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun setText(c: WebServer.Client, text: String, append: Boolean) {
        val n = focusedInput()
        if (n == null || !n.isEditable) return err(c, "Chưa có ô nhập liệu nào được chọn trên điện thoại")
        val cur = if (append && !n.isShowingHintTextCompat()) n.text?.toString() ?: "" else ""
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text)
        }
        if (!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) err(c, "Không nhập được chữ")
    }

    private fun AccessibilityNodeInfo.isShowingHintTextCompat() =
        Build.VERSION.SDK_INT >= 26 && isShowingHintText

    private fun imeEnter(c: WebServer.Client) {
        val n = focusedInput() ?: return err(c, "Chưa có ô nhập liệu nào được chọn")
        if (Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else err(c, "Cần Android 11+")
    }

    private fun setupTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            torchId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id == torchId) torchOn = enabled
                }
            }, main)
        } catch (_: Exception) {}
    }

    private fun toggleTorch(c: WebServer.Client) {
        val id = torchId ?: return err(c, "Không có đèn flash")
        (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, !torchOn)
        ok(c, if (!torchOn) "Đã bật đèn pin" else "Đã tắt đèn pin")
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun wake() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "vit:wake"
        ).acquire(1500)
    }

    // ================= Chụp màn hình / xem trực tiếp =================

    private fun requestFull(c: WebServer.Client) {
        if (Build.VERSION.SDK_INT < 30) return err(c, "Chụp ảnh qua web cần Android 11+")
        cap.post { if (c !in fullWaiters) fullWaiters.add(c); capture() }
    }

    private fun kickCapture() = cap.post { capture() }

    private fun liveClients() = server?.clients?.filter { it.liveWidth > 0 } ?: emptyList()

    /** Chạy trên luồng cap. Chỉ chụp khi có người cần => không tốn CPU khi rảnh. */
    private fun capture() {
        if (capturing || Build.VERSION.SDK_INT < 30) return
        if (fullWaiters.isEmpty() && liveClients().isEmpty()) return
        capturing = true
        takeScreenshot(Display.DEFAULT_DISPLAY, { cap.post(it) }, object : TakeScreenshotCallback {
            override fun onSuccess(res: ScreenshotResult) {
                try { processShot(res) } catch (_: Throwable) {}
                capturing = false
                // ~3 khung/giây là giới hạn của hệ thống (333ms/lần)
                if (liveClients().isNotEmpty() || fullWaiters.isNotEmpty()) cap.postDelayed({ capture() }, 340)
            }

            override fun onFailure(code: Int) {
                capturing = false
                if (code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    cap.postDelayed({ capture() }, 150)
                } else {
                    fullWaiters.forEach { err(it, "Chụp màn hình lỗi (mã $code) – có thể do ứng dụng chặn chụp") }
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

        val live = liveClients()
        if (live.isNotEmpty()) {
            // Nhóm theo độ phân giải để chỉ nén 1 lần cho mỗi mức
            live.groupBy { it.liveWidth }.forEach { (w, cs) ->
                val tw = minOf(w, bmp.width)
                val th = bmp.height * tw / bmp.width
                val scaled = if (tw == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, tw, th, true)
                val data = jpeg(scaled, 55, 1)
                if (scaled !== bmp) scaled.recycle()
                val crc = CRC32().apply { update(data) }.value
                // Màn hình không đổi => không gửi gì, tiết kiệm mạng
                cs.forEach { if (it.lastFrameCrc != crc) { it.lastFrameCrc = crc; it.sendBin(data) } }
            }
        }
        bmp.recycle()
    }

    /** Byte đầu là loại: 1 = khung trực tiếp, 2 = ảnh chụp đầy đủ. */
    private fun jpeg(b: Bitmap, q: Int, type: Int): ByteArray {
        val bo = ByteArrayOutputStream(64 * 1024)
        bo.write(type)
        b.compress(Bitmap.CompressFormat.JPEG, q, bo)
        return bo.toByteArray()
    }
}
