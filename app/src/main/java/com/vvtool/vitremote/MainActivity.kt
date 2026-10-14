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
import android.view.View
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
    private lateinit var swCapture: MaterialSwitch
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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }

        findViewById<MaterialButton>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Select \"vitRemote\" and turn it on", Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialSwitch>(R.id.swServer).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; restart() }
        }
        swCapture = findViewById(R.id.swCapture)
        swCapture.setOnClickListener {
            if (swCapture.isChecked) startActivity(Intent(this, ProjectionActivity::class.java))
            else CaptureService.stop(this)
        }
        findViewById<MaterialButton>(R.id.btnPin).setOnClickListener { prefs.newPin(); restart() }
        val portEt = findViewById<TextInputEditText>(R.id.port)
        portEt.setText(prefs.port.toString())
        findViewById<MaterialButton>(R.id.btnPort).setOnClickListener {
            val p = portEt.text?.toString()?.toIntOrNull()
            if (p == null || p !in 1024..65535) {
                Toast.makeText(this, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
            } else { prefs.port = p; restart() }
        }
        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("url", urls().firstOrNull() ?: ""))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
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
            !enabled -> Triple("Accessibility is off", "Turn on vitRemote in Settings › Accessibility to start.", R.color.bad)
            !prefs.enabled -> Triple("Server is off", "Turn on \"Web server\" below.", R.color.bad)
            !running -> Triple("Server error", RemoteService.lastError ?: "Starting…", R.color.bad)
            else -> Triple("Running", "${svc!!.clientCount} browser(s) connected", R.color.ok)
        }
        findViewById<TextView>(R.id.status).text = title
        findViewById<TextView>(R.id.statusSub).text = sub
        findViewById<View>(R.id.dot).background =
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(color)) }
        findViewById<MaterialButton>(R.id.btnA11y).visibility = if (enabled) View.GONE else View.VISIBLE

        val u = urls()
        findViewById<TextView>(R.id.url).text = if (u.isEmpty()) "No network" else u.joinToString("\n")
        findViewById<TextView>(R.id.pin).text = prefs.pin

        val capOn = CaptureService.instance != null
        swCapture.isChecked = capOn
        findViewById<TextView>(R.id.captureSub).text = when {
            capOn -> "Smooth streaming up to 15 fps. Frames are only sent when the screen changes."
            Build.VERSION.SDK_INT >= 30 -> "Off: the web view uses the Accessibility screenshot API (~3 fps). Turn on for smoother streaming."
            else -> "Required on Android ${Build.VERSION.RELEASE} to view the screen. It is also requested automatically when you open the web view."
        }

        findViewById<TextView>(R.id.info).text = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Tip: disable battery optimization for vitRemote so the system doesn't stop it.")
        }
    }
}
