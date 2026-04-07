package com.example.proxybypass.api

import com.example.proxybypass.model.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class ProxyFetcher {
    private val client = OkHttpClient()

    suspend fun fetchProxies(): List<Proxy> = withContext(Dispatchers.IO) {
        val proxies = mutableListOf<Proxy>()
        
        // Source 1: ProxyScrape (HTTP)
        fetchFromProxyScrape(proxies)
        
        // Source 2: GitHub (SOCKS5)
        fetchFromGitHub(proxies)

        return@withContext proxies.distinctBy { "${it.ip}:${it.port}" }
    }

    private fun fetchFromProxyScrape(proxies: MutableList<Proxy>) {
        val url = "https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=all"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.lines()?.forEach { line ->
                        val parts = line.trim().split(":")
                        if (parts.size == 2) {
                            proxies.add(Proxy(parts[0], parts[1].toInt(), "http"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchFromGitHub(proxies: MutableList<Proxy>) {
        val url = "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.string()?.lines()?.forEach { line ->
                        val parts = line.trim().split(":")
                        if (parts.size == 2) {
                            proxies.add(Proxy(parts[0], parts[1].toInt(), "socks5"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
