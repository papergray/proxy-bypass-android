package com.example.proxybypass.vpn

import android.net.VpnService
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object Socks5Client {

    private const val SOCK_BUF = 262_144

    // ─── Trust-all TLS factory ────────────────────────────────────────────────
    // We trust any cert because the proxy's cert is irrelevant — we're only
    // using TLS to obfuscate the tunnel from Sophos DPI. The inner traffic
    // to the destination site has its own TLS layer (HTTPS end-to-end).
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val tlsFactory by lazy {
        SSLContext.getInstance("TLS").also { it.init(null, arrayOf(trustAll), java.security.SecureRandom()) }.socketFactory
    }

    // ─── Public connect methods ───────────────────────────────────────────────

    /**
     * HTTPS proxy: wraps the HTTP CONNECT tunnel in TLS.
     * To Sophos DPI this looks like a normal HTTPS connection — no proxy
     * fingerprint visible because the payload is encrypted from byte 1.
     */
    fun connectViaHttpsProxy(
        proxyIp: String,
        proxyPort: Int,
        destHost: String,
        destPort: Int,
        timeoutMs: Int = 10_000,
        vpnService: VpnService? = null
    ): Socket {
        val raw = buildRawSocket(vpnService, timeoutMs)
        raw.connect(InetSocketAddress(proxyIp, proxyPort), timeoutMs)

        // Upgrade to TLS — makes the whole conversation look like HTTPS to DPI
        val ssl = tlsFactory.createSocket(raw, proxyIp, proxyPort, true) as SSLSocket
        ssl.enabledProtocols = ssl.supportedProtocols          // accept any TLS version
        ssl.startHandshake()

        // Now send HTTP CONNECT inside the encrypted TLS channel
        val req = "CONNECT $destHost:$destPort HTTP/1.1\r\nHost: $destHost:$destPort\r\nProxy-Connection: keep-alive\r\n\r\n"
        ssl.getOutputStream().apply { write(req.toByteArray()); flush() }

        val resp = readHttpResponse(ssl.getInputStream())
        if (!resp.startsWith("HTTP/1") || !resp.contains("200"))
            throw Exception("HTTPS proxy CONNECT failed: $resp")
        return ssl
    }

    /**
     * Plain HTTP proxy: sends HTTP CONNECT in cleartext.
     * Sophos DPI can read this — use only when TLS is not available.
     */
    fun connectViaHttpProxy(
        proxyIp: String,
        proxyPort: Int,
        destHost: String,
        destPort: Int,
        timeoutMs: Int = 10_000,
        vpnService: VpnService? = null
    ): Socket {
        val sock = buildRawSocket(vpnService, timeoutMs)
        sock.connect(InetSocketAddress(proxyIp, proxyPort), timeoutMs)

        val req = "CONNECT $destHost:$destPort HTTP/1.1\r\nHost: $destHost:$destPort\r\nProxy-Connection: keep-alive\r\n\r\n"
        sock.getOutputStream().apply { write(req.toByteArray()); flush() }

        val resp = readHttpResponse(sock.getInputStream())
        if (!resp.startsWith("HTTP/1") || !resp.contains("200"))
            throw Exception("HTTP proxy CONNECT failed: $resp")
        return sock
    }

    /**
     * SOCKS5 proxy: plaintext handshake — identifiable by DPI.
     * Use only when TLS wrapping is not possible.
     */
    fun connectViaSocks5(
        proxyIp: String,
        proxyPort: Int,
        destHost: String,
        destPort: Int,
        timeoutMs: Int = 10_000,
        vpnService: VpnService? = null
    ): Socket {
        val sock = buildRawSocket(vpnService, timeoutMs)
        sock.connect(InetSocketAddress(proxyIp, proxyPort), timeoutMs)
        val out: OutputStream = sock.getOutputStream()
        val inn: InputStream  = sock.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val gresp = ByteArray(2); inn.read(gresp)
        if (gresp[0] != 0x05.toByte() || gresp[1] != 0x00.toByte())
            throw Exception("SOCKS5 auth negotiation failed")

        val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        hostBytes.copyInto(req, 5)
        req[5 + hostBytes.size] = (destPort shr 8).toByte()
        req[6 + hostBytes.size] = (destPort and 0xFF).toByte()
        out.write(req); out.flush()

        val rresp = ByteArray(10); inn.read(rresp)
        if (rresp[1] != 0x00.toByte()) throw Exception("SOCKS5 CONNECT refused: ${rresp[1]}")
        return sock
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun buildRawSocket(vpnService: VpnService?, timeoutMs: Int): Socket {
        val sock = Socket()
        vpnService?.protect(sock)
        sock.tcpNoDelay        = true
        sock.sendBufferSize    = SOCK_BUF
        sock.receiveBufferSize = SOCK_BUF
        sock.soTimeout         = timeoutMs
        return sock
    }

    private fun readHttpResponse(inn: InputStream): String {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = inn.read(); if (b == -1) break
            sb.append(b.toChar())
            if (prev == '\n'.code && sb.endsWith("\r\n\r\n")) break
            prev = b
        }
        return sb.toString()
    }
}
