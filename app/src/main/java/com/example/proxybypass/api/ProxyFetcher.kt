package com.example.proxybypass.api

import com.example.proxybypass.model.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ProxyFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchProxies(): List<Proxy> = withContext(Dispatchers.IO) {
        val proxies = mutableListOf<Proxy>()
        listOf(
            ::fetchProxyScrapeHttp,
            ::fetchProxyScrapeHttps,
            ::fetchGitHubSocks5,
            ::fetchProxyListOrg
        ).forEach { it(proxies) }
        proxies.distinctBy { it.address }
    }

    private fun fetchProxyScrapeHttp(out: MutableList<Proxy>) = fetch(
        "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all&ssl=all&anonymity=elite,anonymous",
        "http", out
    )

    private fun fetchProxyScrapeHttps(out: MutableList<Proxy>) = fetch(
        "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=https&timeout=5000&country=all&anonymity=elite,anonymous",
        "http", out
    )

    private fun fetchGitHubSocks5(out: MutableList<Proxy>) = fetch(
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
        "socks5", out
    )

    private fun fetchProxyListOrg(out: MutableList<Proxy>) {
        val url = "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"
        fetch(url, "http", out)
    }

    private fun fetch(url: String, protocol: String, out: MutableList<Proxy>) {
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()?.lines()?.forEach { line ->
                        val clean = line.trim().removePrefix("$protocol://")
                        val parts = clean.split(":")
                        if (parts.size == 2) {
                            val port = parts[1].toIntOrNull() ?: return@forEach
                            if (port in 1..65535) out.add(Proxy(parts[0], port, protocol))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
