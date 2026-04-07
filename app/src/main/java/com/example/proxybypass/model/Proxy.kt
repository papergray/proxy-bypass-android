package com.example.proxybypass.model

data class Proxy(
    val ip: String,
    val port: Int,
    val protocol: String, // "http", "socks4", "socks5"
    val country: String? = null,
    val latency: Long = -1
)
