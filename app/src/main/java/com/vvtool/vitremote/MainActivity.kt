package com.vvtool.vitremote

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var swCapture: MaterialSwitch
    private lateinit var btnA11y: MaterialButton
    private lateinit var btnBattery: MaterialButton
    private lateinit var btnAdmin: MaterialButton
    private val admin by lazy { ComponentName(this, AdminReceiver::class.java) }
    private val dpm by lazy { getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager }
    private lateinit var info: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
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

        info = findViewById(R.id.info)
        btnA11y = findViewById(R.id.btnA11y)
        btnA11y.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        btnBattery = findViewById(R.id.btnBattery)
        btnBattery.setOnClickListener { requestBattery() }

        // Android 9+ locks through accessibility; older versions need device admin
        btnAdmin = findViewById(R.id.btnAdmin)
        findViewById<View>(R.id.rowAdmin).visibility = if (Build.VERSION.SDK_INT >= 28) View.GONE else View.VISIBLE
        btnAdmin.setOnClickListener {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets the web power button turn the screen off."))
        }

        swCapture = findViewById(R.id.swCapture)
        swCapture.setOnClickListener {
            if (swCapture.isChecked) startActivity(Intent(this, ProjectionActivity::class.java))
            else CaptureService.stop(this)
        }

        findViewById<MaterialSwitch>(R.id.swServer).apply {
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; restart() }
        }
        findViewById<MaterialButton>(R.id.btnPin).setOnClickListener { prefs.newPin(); restart() }

        findViewById<TextView>(R.id.url).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("url", urls().firstOrNull() ?: ""))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        val port = findViewById<EditText>(R.id.port)
        port.setText(prefs.port.toString())
        port.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { savePort(v as EditText); v.clearFocus() }
            false
        }
        port.setOnFocusChangeListener { v, has -> if (!has) savePort(v as EditText) }
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    private fun savePort(et: EditText) {
        val p = et.text.toString().toIntOrNull()
        if (p == null || p !in 1024..65535) {
            et.setText(prefs.port.toString())
            Toast.makeText(this, "Port: 1024–65535", Toast.LENGTH_SHORT).show()
        } else if (p != prefs.port) { prefs.port = p; restart() }
    }

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

    private fun batteryOk() = Build.VERSION.SDK_INT < 23 ||
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        val svc = RemoteService.instance
        val a11y = a11yEnabled()
        val running = svc?.running == true
        val (status, ok) = when {
            !a11y -> "Inactive" to false
            !prefs.enabled -> "Server off" to false
            !running -> (RemoteService.lastError?.let { "Port busy" } ?: "Starting") to false
            else -> (if (svc!!.clientCount > 0) "${svc.clientCount} connected" else "Ready") to true
        }
        findViewById<TextView>(R.id.status).text = status
        findViewById<View>(R.id.dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(getColor(if (ok) R.color.ok else R.color.bad))
        }

        findViewById<TextView>(R.id.url).text = urls().firstOrNull() ?: "No network"
        findViewById<TextView>(R.id.pin).text = prefs.pin

        btnA11y.text = if (a11y) "On" else "Enable"
        btnA11y.isEnabled = !a11y
        swCapture.isChecked = CaptureService.instance != null
        val bat = batteryOk()
        btnBattery.text = if (bat) "On" else "Allow"
        btnBattery.isEnabled = !bat

        val adm = dpm.isAdminActive(admin)
        btnAdmin.text = if (adm) "On" else "Allow"
        btnAdmin.isEnabled = !adm

        fillInfo()
    }

    private fun fillInfo() {
        val rows = ArrayList<Pair<String, String>>()
        rows += "Model" to "${Build.MANUFACTURER} ${Build.MODEL}"
        rows += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        if (Build.VERSION.SDK_INT >= 23) rows += "Security patch" to Build.VERSION.SECURITY_PATCH
        rows += "CPU" to "${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · ${Runtime.getRuntime().availableProcessors()} cores"

        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val chg = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            rows += "Battery" to "$lvl%${if (chg) " · charging" else ""} · ${b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0}°C"
        }
        val mi = ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        rows += "RAM" to "${gb(mi.totalMem - mi.availMem)} / ${gb(mi.totalMem)}"
        val st = StatFs(Environment.getDataDirectory().path)
        rows += "Storage" to "${gb(st.totalBytes - st.availableBytes)} / ${gb(st.totalBytes)}"
        val sz = DeviceInfo.screenSize(this)
        rows += "Display" to "${sz.x}×${sz.y} · ${resources.displayMetrics.densityDpi} dpi"
        rows += "IP" to (Prefs.localIps().joinToString(", ").ifEmpty { "—" })
        rows += "Uptime" to uptime(SystemClock.elapsedRealtime() / 1000)

        // Reuse rows to avoid re-inflating every tick
        while (info.childCount < rows.size) info.addView(row())
        rows.forEachIndexed { i, (k, v) ->
            val r = info.getChildAt(i) as LinearLayout
            (r.getChildAt(0) as TextView).text = k
            (r.getChildAt(1) as TextView).text = v
        }
    }

    private fun row(): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            addView(TextView(this@MainActivity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(getColor(android.R.color.darker_gray))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, -2, 1.6f))
        }
    }

    private fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "${b / 1_000_000} MB"

    private fun uptime(s: Long): String {
        val d = s / 86400; val h = s % 86400 / 3600; val m = s % 3600 / 60
        return (if (d > 0) "${d}d " else "") + "${h}h ${m}m"
    }
}
