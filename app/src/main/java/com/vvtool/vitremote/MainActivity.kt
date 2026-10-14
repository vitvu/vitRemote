package com.vvtool.vitremote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }

        findViewById<MaterialButton>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Chọn \"vitRemote\" và bật lên", Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialSwitch>(R.id.swServer).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; restart() }
        }
        findViewById<MaterialButton>(R.id.btnPin).setOnClickListener { prefs.newPin(); restart() }
        val portEt = findViewById<TextInputEditText>(R.id.port)
        portEt.setText(prefs.port.toString())
        findViewById<MaterialButton>(R.id.btnPort).setOnClickListener {
            val p = portEt.text?.toString()?.toIntOrNull()
            if (p == null || p !in 1024..65535) {
                Toast.makeText(this, "Cổng phải từ 1024 đến 65535", Toast.LENGTH_SHORT).show()
            } else { prefs.port = p; restart() }
        }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("url", urls().firstOrNull() ?: ""))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Đã sao chép", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener {
            val t = "vitRemote: ${urls().joinToString(" ")}  PIN: ${prefs.pin}"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null))
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun restart() {
        RemoteService.instance?.applyConfig()
        refresh()
    }

    private fun urls() = Prefs.localIps().map { "http://$it:${prefs.port}" }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, RemoteService::class.java)
        return s.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        val svc = RemoteService.instance
        val enabled = a11yEnabled()
        val running = svc?.running == true
        val (title, sub, color) = when {
            !enabled -> Triple("Chưa bật Trợ năng", "Bật dịch vụ vitRemote trong Cài đặt › Trợ năng để bắt đầu.", R.color.bad)
            !prefs.enabled -> Triple("Máy chủ đang tắt", "Bật công tắc \"Máy chủ web\" bên dưới.", R.color.bad)
            !running -> Triple("Lỗi máy chủ", RemoteService.lastError ?: "Đang khởi động…", R.color.bad)
            else -> Triple("Đang hoạt động", "${svc!!.clientCount} trình duyệt đang kết nối", R.color.ok)
        }
        findViewById<TextView>(R.id.status).text = title
        findViewById<TextView>(R.id.statusSub).text = sub
        findViewById<android.view.View>(R.id.dot).background =
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(color)) }
        findViewById<MaterialButton>(R.id.btnA11y).visibility =
            if (enabled) android.view.View.GONE else android.view.View.VISIBLE

        val u = urls()
        findViewById<TextView>(R.id.url).text =
            if (u.isEmpty()) "Chưa kết nối mạng" else u.joinToString("\n")
        findViewById<TextView>(R.id.pin).text = prefs.pin

        findViewById<TextView>(R.id.info).text = buildString {
            append("• Thiết bị: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("• Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("• Xem màn hình/chụp ảnh qua web: ${if (Build.VERSION.SDK_INT >= 30) "Có" else "Cần Android 11+"}\n")
            append("• Giao thức: WebSocket nhị phân, ảnh chỉ gửi khi màn hình thay đổi\n")
            append("• Mẹo: tắt tối ưu pin cho vitRemote để không bị hệ thống dừng")
        }
    }
}
