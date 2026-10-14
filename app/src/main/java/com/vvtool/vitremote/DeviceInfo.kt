package com.vvtool.vitremote

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowManager

object DeviceInfo {

    @Suppress("DEPRECATION")
    fun screenSize(ctx: Context): Point {
        val p = Point()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(p)
        return p
    }

    fun json(ctx: Context, extra: Map<String, Any?>): String {
        val m = LinkedHashMap<String, Any?>()
        m["t"] = "info"
        m["model"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        m["device"] = Build.DEVICE
        m["android"] = Build.VERSION.RELEASE
        m["sdk"] = Build.VERSION.SDK_INT
        m["patch"] = if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else ""

        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            m["battery"] = if (lvl >= 0) lvl * 100 / sc else -1
            m["charging"] = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            m["batTemp"] = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            m["batVolt"] = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        }

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        m["ramTotal"] = mi.totalMem
        m["ramAvail"] = mi.availMem

        val st = StatFs(Environment.getDataDirectory().path)
        m["diskTotal"] = st.totalBytes
        m["diskFree"] = st.availableBytes

        val sz = screenSize(ctx)
        m["screen"] = "${sz.x}x${sz.y}"
        m["dpi"] = ctx.resources.displayMetrics.densityDpi
        m["screenOn"] = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        val au = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        m["vol"] = au.getStreamVolume(AudioManager.STREAM_MUSIC)
        m["volMax"] = au.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        m["ringer"] = when (au.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Ring"
        }

        m["ip"] = Prefs.localIps().joinToString(", ")
        m["uptime"] = SystemClock.elapsedRealtime() / 1000
        m["cpu"] = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        m["cores"] = Runtime.getRuntime().availableProcessors()
        m.putAll(extra)
        return toJson(m)
    }

    fun toJson(m: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(','); first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> esc(sb, v.toString())
            }
        }
        return sb.append('}').toString()
    }

    private fun esc(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
