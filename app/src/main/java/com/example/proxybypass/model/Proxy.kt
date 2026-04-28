package com.example.proxybypass.model

data class Proxy(
    val ip: String,
    val port: Int,
    val protocol: String = "http",   // "http" | "https" | "socks5"
    val latencyMs: Long  = 0,
    val isAlive: Boolean = false
) {
    val address: String get() = "$ip:$port"
    val label: String   get() = protocol.uppercase()
    fun latencyLabel(): String = "${latencyMs}ms"
}
