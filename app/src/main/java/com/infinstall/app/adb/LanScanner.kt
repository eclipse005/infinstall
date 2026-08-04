package com.infinstall.app.adb

import dadb.AdbKeyPair
import dadb.Dadb
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
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveredDevice(
    val host: String,
    val port: Int,
) {
    val endpoint: String get() = "$host:$port"
}

/**
 * Discover TVs/boxes with network debugging on the LAN.
 *
 * Important: open TCP port ≠ 电视. Many networks/false positives would otherwise
 * report hundreds of "devices". We only list hosts that speak the ADB protocol.
 */
object LanScanner {

    suspend fun scan(
        keyPair: AdbKeyPair,
        ports: List<Int> = listOf(5555),
        tcpTimeoutMs: Int = 200,
        onProgress: (phase: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val localIps = linkedSetOf<String>()
        val prefixes = localScanPrefixes(localIps)
        if (prefixes.isEmpty()) return@withContext emptyList()

        val candidates = buildList {
            for (prefix in prefixes) {
                for (hostPart in 1..254) {
                    val host = "$prefix.$hostPart"
                    if (host in localIps) continue
                    for (port in ports) {
                        add(DiscoveredDevice(host, port))
                    }
                }
            }
        }

        // Phase 1: cheap TCP probe
        onProgress("tcp", 0, candidates.size)
        val tcpOpen = Collections.synchronizedList(mutableListOf<DiscoveredDevice>())
        val tcpDone = AtomicInteger(0)
        val tcpSem = Semaphore(48)
        coroutineScope {
            candidates.map { target ->
                async {
                    tcpSem.withPermit {
                        if (isTcpOpen(target.host, target.port, tcpTimeoutMs)) {
                            tcpOpen.add(target)
                        }
                        val n = tcpDone.incrementAndGet()
                        if (n % 16 == 0 || n == candidates.size) {
                            onProgress("tcp", n, candidates.size)
                        }
                    }
                }
            }.awaitAll()
        }

        val tcpList = tcpOpen.distinctBy { it.endpoint }
        if (tcpList.isEmpty()) return@withContext emptyList()

        // Phase 2: only keep hosts that actually speak ADB (filters router noise / false opens)
        onProgress("adb", 0, tcpList.size)
        val verified = Collections.synchronizedList(mutableListOf<DiscoveredDevice>())
        val adbDone = AtomicInteger(0)
        val adbSem = Semaphore(8)
        // dadb is not documented as multi-thread safe for parallel different hosts with same keyPair;
        // serialize key usage lightly via semaphore 8 is usually OK for separate connections.
        coroutineScope {
            tcpList.map { target ->
                async {
                    adbSem.withPermit {
                        if (speaksAdb(target.host, target.port, keyPair)) {
                            verified.add(target)
                        }
                        val n = adbDone.incrementAndGet()
                        onProgress("adb", n, tcpList.size)
                    }
                }
            }.awaitAll()
        }

        verified.distinctBy { it.endpoint }.sortedBy { it.host }
    }

    /**
     * Prefer real Wi‑Fi / Ethernet; skip VPN, cellular, docker-like virtual NICs
     * so we don't spray-scan 2–3 subnets and surface hundreds of junk hits.
     */
    private fun localScanPrefixes(outLocalIps: MutableSet<String>): List<String> {
        val preferred = linkedSetOf<String>()
        val fallback = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in Collections.list(interfaces)) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.orEmpty().lowercase()
                if (isSkippedInterface(name)) continue
                val prefer = isPreferredInterface(name)
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.") || host.startsWith("169.254.")) continue
                    // typical container / virt ranges — skip unless it's the only thing we have
                    val parts = host.split(".")
                    if (parts.size != 4) continue
                    outLocalIps.add(host)
                    val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                    // docker default bridge
                    if (prefix == "172.17.0" || prefix.startsWith("172.18.") || prefix == "10.0.2") {
                        // 10.0.2.x often emulator; skip for physical LAN scan
                        if (!prefer) continue
                    }
                    if (prefer) preferred.add(prefix) else fallback.add(prefix)
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return when {
            preferred.isNotEmpty() -> preferred.toList()
            else -> fallback.toList()
        }
    }

    private fun isPreferredInterface(name: String): Boolean {
        return name.startsWith("wlan") ||
            name.startsWith("wifi") ||
            name.startsWith("eth") ||
            name.startsWith("en") || // en0 style
            name.startsWith("ap") ||
            name.startsWith("swlan") ||
            name.startsWith("wl")
    }

    private fun isSkippedInterface(name: String): Boolean {
        return name.startsWith("rmnet") || // cellular
            name.startsWith("ccmni") ||
            name.startsWith("pdp") ||
            name.startsWith("ppp") ||
            name.startsWith("tun") ||
            name.startsWith("tap") ||
            name.startsWith("wg") ||
            name.startsWith("vpn") ||
            name.contains("vpn") ||
            name.startsWith("dummy") ||
            name.startsWith("docker") ||
            name.startsWith("veth") ||
            name.startsWith("br-") ||
            name.startsWith("lxc") ||
            name.startsWith("clat") ||
            name.startsWith("ipsec") ||
            name == "lo"
    }

    private fun isTcpOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True if the host looks like network debugging (ADB daemon), including
     * "unauthorized" TVs that still need the on-screen allow prompt.
     */
    private fun speaksAdb(host: String, port: Int, keyPair: AdbKeyPair): Boolean {
        return try {
            Dadb.create(host, port, keyPair).use { dadb ->
                // Connection + auth path proves adbd; shell may fail if unauthorized
                try {
                    dadb.shell("echo infinstall_probe")
                } catch (t: Throwable) {
                    val m = (t.message ?: "").lowercase()
                    if (m.contains("unauthorized") || m.contains("not authorized")) {
                        return true
                    }
                    // some devices reject shell but are still adb
                    if (m.contains("closed") || m.contains("connection")) {
                        return false
                    }
                }
                true
            }
        } catch (t: Throwable) {
            val m = (t.message ?: "").lowercase()
            m.contains("unauthorized") || m.contains("not authorized")
        }
    }
}
