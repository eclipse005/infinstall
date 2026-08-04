package com.infinstall.app.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

data class DiscoveredDevice(
    val host: String,
    val port: Int,
) {
    val endpoint: String get() = "$host:$port"
}

object LanScanner {
    /**
     * Scan common local IPv4 /24 subnets for open TCP ports (default 5555).
     */
    suspend fun scan(
        ports: List<Int> = listOf(5555),
        connectTimeoutMs: Int = 250,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val prefixes = localIpv4Prefixes()
        if (prefixes.isEmpty()) return@withContext emptyList()

        val targets = buildList {
            for (prefix in prefixes) {
                for (hostPart in 1..254) {
                    for (port in ports) {
                        add(prefix to hostPart to port)
                    }
                }
            }
        }
        val total = targets.size
        var scanned = 0
        val semaphore = Semaphore(64)
        val found = Collections.synchronizedList(mutableListOf<DiscoveredDevice>())

        coroutineScope {
            targets.map { (prefixHost, port) ->
                val (prefix, hostPart) = prefixHost
                async {
                    semaphore.withPermit {
                        val host = "$prefix.$hostPart"
                        if (isPortOpen(host, port, connectTimeoutMs)) {
                            found.add(DiscoveredDevice(host, port))
                        }
                        val n = synchronized(found) {
                            scanned += 1
                            scanned
                        }
                        if (n % 32 == 0 || n == total) onProgress(n, total)
                    }
                }
            }.awaitAll()
        }
        found.distinctBy { it.endpoint }.sortedBy { it.host }
    }

    private fun localIpv4Prefixes(): List<String> {
        val prefixes = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.") || host.startsWith("169.254.")) continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return prefixes.toList()
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
