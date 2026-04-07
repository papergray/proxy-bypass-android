package com.example.proxybypass.model

data class Proxy(
    val ip: String,
    val port: Int,
    val protocol: String,
    val country: String? = null,
    val latencyMs: Long = Long.MAX_VALUE,
    val isAlive: Boolean = false
) {
    val address: String get() = "$ip:$port"

    fun latencyLabel(): String = when {
        latencyMs == Long.MAX_VALUE -> "—"
        latencyMs < 200 -> "${latencyMs}ms ●●●"
        latencyMs < 500 -> "${latencyMs}ms ●●○"
        else            -> "${latencyMs}ms ●○○"
    }
}
