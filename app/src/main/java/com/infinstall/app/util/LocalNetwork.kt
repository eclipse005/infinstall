package com.infinstall.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Detect this phone's LAN IPv4 so we can prefill the same subnet as the TV.
 */
object LocalNetwork {

    /**
     * e.g. "192.168.1.105" or null if offline / cellular-only without usable LAN.
     */
    fun primaryIpv4(context: Context): String? {
        // Prefer active Wi‑Fi / ethernet from ConnectivityManager
        fromConnectivityManager(context)?.let { return it }
        // Fallback: enumerate interfaces (wlan*, eth*, etc.)
        return fromNetworkInterfaces()
    }

    /**
     * "192.168.1.105" → "192.168.1."
     */
    fun subnetPrefix(ipv4: String): String? {
        val parts = ipv4.trim().split('.')
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() == null }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    /**
     * Default host field: same subnet, empty last octet so user only types TV's number.
     * e.g. phone 192.168.1.105 → "192.168.1."
     */
    fun suggestedHostInput(context: Context): String {
        val ip = primaryIpv4(context) ?: return ""
        return subnetPrefix(ip).orEmpty()
    }

    private fun fromConnectivityManager(context: Context): String? {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

            // Prefer Wi‑Fi, then Ethernet, then any active non-cellular
            val networks = buildList {
                cm.activeNetwork?.let { add(it) }
                cm.allNetworks.forEach { add(it) }
            }.distinct()

            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val eth = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                if (!wifi && !eth && cell) continue
                val lp: LinkProperties = cm.getLinkProperties(network) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (isUsableLan(host)) return host
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fromNetworkInterfaces(): String? {
        return try {
            val preferred = mutableListOf<String>()
            val others = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.orEmpty().lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("dummy") || name.startsWith("tun") ||
                    name.startsWith("ppp") || name.contains("vpn")
                ) {
                    continue
                }
                val prefer = name.startsWith("wlan") || name.startsWith("wifi") ||
                    name.startsWith("eth") || name.startsWith("en") ||
                    name.startsWith("ap") || name.startsWith("swlan")
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (!isUsableLan(host)) continue
                        if (prefer) preferred.add(host) else others.add(host)
                    }
                }
            }
            preferred.firstOrNull() ?: others.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun isUsableLan(host: String): Boolean {
        if (host.startsWith("127.") || host.startsWith("169.254.")) return false
        // typical private ranges
        return host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
    }
}
