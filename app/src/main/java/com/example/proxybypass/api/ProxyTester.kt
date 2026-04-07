package com.example.proxybypass.api

import com.example.proxybypass.model.Proxy
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

/**
 * Tests a list of proxies concurrently, returns the [topN] fastest live ones.
 * Uses a direct socket CONNECT to the proxy host to measure TCP round-trip latency,
 * then verifies the proxy responds to an HTTP CONNECT tunnel request.
 */
object ProxyTester {

    private const val TEST_HOST = "www.google.com"
    private const val TEST_PORT = 443
    private const val TIMEOUT_MS = 6_000
    private const val MAX_PARALLEL = 40

    suspend fun testAll(proxies: List<Proxy>, topN: Int = 10): List<Proxy> =
        withContext(Dispatchers.IO) {
            val semaphore = java.util.concurrent.Semaphore(MAX_PARALLEL)
            proxies.map { proxy ->
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
            val start = System.currentTimeMillis()
            val sock = Socket()
            sock.use {
                val addr: SocketAddress = InetSocketAddress(proxy.ip, proxy.port)
                sock.connect(addr, TIMEOUT_MS)
                sock.soTimeout = TIMEOUT_MS

                // Send HTTP CONNECT (works for both http and socks5 proxies as an initial reachability check)
                val req = "CONNECT $TEST_HOST:$TEST_PORT HTTP/1.1\r\nHost: $TEST_HOST:$TEST_PORT\r\n\r\n"
                sock.getOutputStream().write(req.toByteArray())
                sock.getOutputStream().flush()

                val buf = ByteArray(64)
                val read = sock.getInputStream().read(buf)
                val latency = System.currentTimeMillis() - start

                if (read > 0) {
                    val resp = String(buf, 0, read)
                    // Accept 200 (tunnel established) or 407 (auth required but proxy is alive)
                    val alive = resp.contains("200") || resp.contains("HTTP/1.")
                    if (alive) proxy.copy(latencyMs = latency, isAlive = true) else null
                } else null
            }
        } catch (_: Exception) { null }
    }
}
