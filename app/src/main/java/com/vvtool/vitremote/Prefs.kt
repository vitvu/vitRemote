package com.vvtool.vitremote

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 8080)
        set(v) = sp.edit().putInt("port", v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    val pin: String
        get() = sp.getString("pin", null) ?: newPin()

    fun newPin(): String {
        val p = Random.nextInt(100000, 1000000).toString()
        sp.edit().putString("pin", p).apply()
        return p
    }

    companion object {
        /** LAN IPv4 addresses (Wi-Fi, hotspot, USB tethering...) */
        fun localIps(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }
}
