package com.example.proxybypass.api

import com.example.proxybypass.model.Proxy
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tests proxies for raw TCP latency (gaming-focused: low ping wins).
 * Only measures TCP connect time to the proxy itself — this is the true
 * first-hop latency that matters for game traffic. Full HTTP CONNECT adds
 * server round-trip time that varies per test, not per proxy quality.
 */
object ProxyTester {

    private const val TIMEOUT_MS   = 2_500   // anything slower is unplayable — skip fast
    private const val MAX_PARALLEL = 60      // more parallelism = faster scan
    private const val SAMPLE_SIZE  = 300     // larger pool → better chance of a low-ping proxy
    private const val TOP_N        = 15      // show more candidates so user can pick by region

    suspend fun testAll(proxies: List<Proxy>, topN: Int = TOP_N): List<Proxy> =
        withContext(Dispatchers.IO) {
            val sample    = proxies.shuffled().take(SAMPLE_SIZE)
            val semaphore = java.util.concurrent.Semaphore(MAX_PARALLEL)

            sample.map { proxy ->
                async {
                    semaphore.acquire()
                    try { testProxy(proxy) } finally { semaphore.release() }
                }
            }.awaitAll()
                .filterNotNull()
                .sortedBy { it.latencyMs }
                .take(topN)
        }

    private fun testProxy(proxy: Proxy): Proxy? {
        return try {
            // Measure pure TCP connect latency to the proxy.
            // This is the true first-hop ping — what determines gaming feel.
            val start = System.currentTimeMillis()
            val sock  = Socket()
            sock.use {
                sock.tcpNoDelay = true   // no Nagle during test
                sock.connect(InetSocketAddress(proxy.ip, proxy.port), TIMEOUT_MS)
                val latency = System.currentTimeMillis() - start

                // Verify it's a live proxy with a quick HTTP CONNECT probe
                sock.soTimeout = TIMEOUT_MS
                val req = "CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray())
                sock.getOutputStream().flush()

                val buf  = ByteArray(64)
                val read = sock.getInputStream().read(buf)
                if (read > 0) {
                    val resp  = String(buf, 0, read)
                    val alive = resp.contains("200") || resp.contains("HTTP/1.")
                    // Latency is TCP connect time only — not the CONNECT roundtrip
                    if (alive) proxy.copy(latencyMs = latency, isAlive = true) else null
                } else null
            }
        } catch (_: Exception) { null }
    }
}
