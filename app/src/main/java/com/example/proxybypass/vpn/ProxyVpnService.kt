package com.example.proxybypass.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class ProxyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val proxyIp = intent?.getStringExtra("PROXY_IP")
        val proxyPort = intent?.getIntExtra("PROXY_PORT", 8080) ?: 8080
        val dnsServer = intent?.getStringExtra("DNS_SERVER") ?: "1.1.1.1"

        if (proxyIp != null) {
            startVpn(proxyIp, proxyPort, dnsServer)
        }
        return START_STICKY
    }

    private fun startVpn(proxyIp: String, proxyPort: Int, dnsServer: String) {
        try {
            val builder = Builder()
                .setSession("ProxyBypass")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(dnsServer)
                .setMtu(1500)

            // In a real implementation, we would use a local SOCKS/HTTP proxy 
            // to tunnel the traffic. For this example, we're setting up the interface.
            vpnInterface = builder.establish()
            
            Log.d("ProxyVpnService", "VPN Started with Proxy: $proxyIp:$proxyPort and DNS: $dnsServer")
        } catch (e: Exception) {
            Log.e("ProxyVpnService", "Failed to start VPN", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
