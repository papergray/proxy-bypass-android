package com.example.proxybypass.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Establishes a SOCKS5 or HTTP-CONNECT tunnel through [proxyIp]:[proxyPort]
 * to [destHost]:[destPort]. Returns the connected Socket ready for data.
 */
object Socks5Client {

    fun connectViaHttpProxy(proxyIp: String, proxyPort: Int, destHost: String, destPort: Int, timeoutMs: Int = 10_000): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress(proxyIp, proxyPort), timeoutMs)
        sock.soTimeout = timeoutMs

        val req = "CONNECT $destHost:$destPort HTTP/1.1\r\nHost: $destHost:$destPort\r\nProxy-Connection: keep-alive\r\n\r\n"
        sock.getOutputStream().apply { write(req.toByteArray()); flush() }

        val resp = readHttpResponse(sock.getInputStream())
        if (!resp.startsWith("HTTP/1") || !resp.contains("200"))
            throw Exception("HTTP proxy CONNECT failed: $resp")
        return sock
    }

    fun connectViaSocks5(proxyIp: String, proxyPort: Int, destHost: String, destPort: Int, timeoutMs: Int = 10_000): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress(proxyIp, proxyPort), timeoutMs)
        sock.soTimeout = timeoutMs
        val out: OutputStream = sock.getOutputStream()
        val inn: InputStream  = sock.getInputStream()

        // Greeting: version=5, 1 method, no-auth
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val gresp = ByteArray(2); inn.read(gresp)
        if (gresp[0] != 0x05.toByte() || gresp[1] != 0x00.toByte())
            throw Exception("SOCKS5 auth negotiation failed")

        // Request: CONNECT, domain
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

    private fun readHttpResponse(inn: InputStream): String {
        val sb = StringBuilder()
        var prev = 0
        while (true) {
            val b = inn.read()
            if (b == -1) break
            sb.append(b.toChar())
            // Detect end of HTTP header (\r\n\r\n)
            if (prev == '\n'.code && sb.endsWith("\r\n\r\n")) break
            prev = b
        }
        return sb.toString()
    }
}
