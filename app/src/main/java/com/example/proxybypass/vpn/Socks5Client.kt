package com.example.proxybypass.vpn

import android.net.VpnService
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

object Socks5Client {

    // 256 KB buffers — reduces kernel round-trips for burst game traffic
    private const val SOCK_BUF = 262_144

    fun connectViaHttpProxy(
        proxyIp: String,
        proxyPort: Int,
        destHost: String,
        destPort: Int,
        timeoutMs: Int = 10_000,
        vpnService: VpnService? = null
    ): Socket {
        val sock = buildSocket(vpnService, timeoutMs)
        sock.connect(InetSocketAddress(proxyIp, proxyPort), timeoutMs)

        val req = "CONNECT $destHost:$destPort HTTP/1.1\r\nHost: $destHost:$destPort\r\nProxy-Connection: keep-alive\r\n\r\n"
        sock.getOutputStream().apply { write(req.toByteArray()); flush() }

        val resp = readHttpResponse(sock.getInputStream())
        if (!resp.startsWith("HTTP/1") || !resp.contains("200"))
            throw Exception("HTTP proxy CONNECT failed: $resp")
        return sock
    }

    fun connectViaSocks5(
        proxyIp: String,
        proxyPort: Int,
        destHost: String,
        destPort: Int,
        timeoutMs: Int = 10_000,
        vpnService: VpnService? = null
    ): Socket {
        val sock = buildSocket(vpnService, timeoutMs)
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

    /**
     * Creates a socket tuned for low-latency gaming:
     * - TCP_NODELAY disables Nagle's algorithm (no packet coalescing delay)
     * - Large send/recv buffers reduce kernel round-trips for burst traffic
     * - protect() prevents the socket looping back into the VPN tunnel
     */
    private fun buildSocket(vpnService: VpnService?, timeoutMs: Int): Socket {
        val sock = Socket()
        vpnService?.protect(sock)
        sock.tcpNoDelay        = true      // KEY for gaming — kills Nagle delay
        sock.sendBufferSize    = SOCK_BUF
        sock.receiveBufferSize = SOCK_BUF
        sock.soTimeout         = timeoutMs
        return sock
    }

    private fun readHttpResponse(inn: InputStream): String {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = inn.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (prev == '\n'.code && sb.endsWith("\r\n\r\n")) break
            prev = b
        }
        return sb.toString()
    }
}
