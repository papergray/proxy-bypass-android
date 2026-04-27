package com.example.proxybypass.api

import com.example.proxybypass.model.Proxy
import com.example.proxybypass.vpn.Socks5Client
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tests proxies sorted by DPI-resistance first, then latency.
 *
 * Priority order:
 *  1. HTTPS (port 443) — TLS-wrapped, passes Sophos DPI as normal HTTPS
 *  2. HTTP  (port 80)  — plaintext but common enough to sometimes pass
 *  3. SOCKS5           — identifiable protocol header, lowest priority
 *
 * Latency is measured as pure TCP connect time to the proxy (first-hop RTT).
 */
object ProxyTester {

    private const val TIMEOUT_MS   = 2_500
    private const val MAX_PARALLEL = 60
    private const val SAMPLE_SIZE  = 300
    private const val TOP_N        = 15

    // Ports that indicate TLS support — these get priority over plaintext proxies
    private val HTTPS_PORTS = setOf(443, 8443, 3128)

    suspend fun testAll(proxies: List<Proxy>, topN: Int = TOP_N): List<Proxy> =
        withContext(Dispatchers.IO) {
            // Split: HTTPS-capable proxies tested first (they bypass Sophos DPI)
            val (httpsProxies, otherProxies) = proxies.shuffled().partition { it.port in HTTPS_PORTS }
            val sample = (httpsProxies + otherProxies).take(SAMPLE_SIZE)

            val semaphore = java.util.concurrent.Semaphore(MAX_PARALLEL)
            sample.map { proxy ->
                async {
                    semaphore.acquire()
                    try { testProxy(proxy) } finally { semaphore.release() }
                }
            }.awaitAll()
                .filterNotNull()
                // Sort: HTTPS proxies first within each latency band, then by latency
                .sortedWith(compareBy({ if (it.port in HTTPS_PORTS) 0 else 1 }, { it.latencyMs }))
                .take(topN)
        }

    private fun testProxy(proxy: Proxy): Proxy? {
        return try {
            val start = System.currentTimeMillis()

            // For HTTPS-capable ports: try TLS-wrapped connect first.
            // This also validates the proxy actually supports HTTPS CONNECT.
            if (proxy.port in HTTPS_PORTS) {
                return tryHttpsProbe(proxy, start)
            }

            // Plain TCP connect + HTTP CONNECT probe for non-TLS ports
            val sock = Socket()
            sock.use {
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(proxy.ip, proxy.port), TIMEOUT_MS)
                val latency = System.currentTimeMillis() - start
                sock.soTimeout = TIMEOUT_MS

                val req = "CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray())
                sock.getOutputStream().flush()

                val buf  = ByteArray(64)
                val read = sock.getInputStream().read(buf)
                if (read > 0) {
                    val resp = String(buf, 0, read)
                    if (resp.contains("200") || resp.contains("HTTP/1."))
                        proxy.copy(latencyMs = latency, isAlive = true)
                    else null
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun tryHttpsProbe(proxy: Proxy, startMs: Long): Proxy? {
        return try {
            val sock = Socks5Client.connectViaHttpsProxy(
                proxy.ip, proxy.port,
                "www.google.com", 443,
                TIMEOUT_MS
            )
            val latency = System.currentTimeMillis() - startMs
            sock.close()
            // Mark protocol as "https" so the VPN service uses TLS when connecting
            proxy.copy(latencyMs = latency, isAlive = true, protocol = "https")
        } catch (_: Exception) {
            // HTTPS failed — fall back to plain HTTP probe
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(proxy.ip, proxy.port), TIMEOUT_MS)
                val latency = System.currentTimeMillis() - startMs
                sock.soTimeout = TIMEOUT_MS
                val req = "CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n"
                sock.getOutputStream().apply { write(req.toByteArray()); flush() }
                val buf = ByteArray(64)
                val read = sock.getInputStream().read(buf)
                sock.close()
                if (read > 0 && (String(buf, 0, read).contains("200") || String(buf, 0, read).contains("HTTP")))
                    proxy.copy(latencyMs = latency, isAlive = true)
                else null
            } catch (_: Exception) { null }
        }
    }
}
