package com.example.proxybypass.vpn

import android.app.*
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.*
import androidx.core.app.NotificationCompat
import com.example.proxybypass.model.Proxy
import com.example.proxybypass.receiver.NotificationActionReceiver
import kotlinx.coroutines.*
import java.io.IOException

class ProxyVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID         = "proxy_bypass_channel"
        const val NOTIF_ID           = 1
        const val ACTION_STOP        = "com.example.proxybypass.STOP"
        const val EXTRA_PROXY_IP     = "PROXY_IP"
        const val EXTRA_PROXY_PORT   = "PROXY_PORT"
        const val EXTRA_PROXY_PROT   = "PROXY_PROT"
        const val EXTRA_PROXY_PING   = "PROXY_PING"

        const val BROADCAST_STATE    = "com.example.proxybypass.STATE"
        const val STATE_CONNECTED    = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val EXTRA_STATE        = "state"
        const val EXTRA_PING         = "ping"
        const val EXTRA_SPEED        = "speed"
        const val EXTRA_PROXY_ADDR   = "proxy_addr"

        // Sophos packages — excluded so Sophos bypasses VPN and doesn't block it
        private val SOPHOS_PACKAGES = listOf(
            "com.sophos.smsec",
            "com.sophos.endpoint.nativeagent",
            "com.sophos.mobilecontrol.client.android",
            "com.sophos.intercept.x.endpoint"
        )

        // Gaming MTU: 1400 avoids fragmentation through proxy tunnel overhead.
        // Standard 1500 causes packets to be split → higher effective ping.
        private const val GAMING_MTU = 1400
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var statsJob: Job? = null

    private var currentProxy: Proxy? = null
    private var currentPingMs: Long   = 0
    private var connectTime: Long     = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val ip   = intent?.getStringExtra(EXTRA_PROXY_IP)   ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PROXY_PORT, 8080)
        val prot = intent.getStringExtra(EXTRA_PROXY_PROT) ?: "http"
        val ping = intent.getLongExtra(EXTRA_PROXY_PING, 0L)

        currentProxy  = Proxy(ip, port, prot, latencyMs = ping, isAlive = true)
        currentPingMs = ping
        connectTime   = System.currentTimeMillis()

        startForeground(NOTIF_ID, buildNotification(ip, port, ping, "—"))
        establishVpn(ip, port)
        startStatsLoop()
        broadcastState(STATE_CONNECTED, ip, port, ping, "—")
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        teardownVpn()
        broadcastState(STATE_DISCONNECTED)
        super.onDestroy()
    }

    private fun establishVpn(proxyIp: String, proxyPort: Int) {
        try {
            val builder = Builder()
                .setSession("ProxyBypass")
                .addAddress("10.99.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(GAMING_MTU)   // 1400 — avoids tunnel fragmentation
                .setBlocking(false)

            // Exclude Sophos — lets it reach its servers directly so it
            // doesn't detect VPN as an MDM policy violation
            for (pkg in SOPHOS_PACKAGES) {
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
            }
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
                    .setHttpProxy(ProxyInfo.buildDirectProxy(proxyIp, proxyPort))
            }

            vpnInterface?.close()
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun teardownVpn() {
        try { vpnInterface?.close() } catch (_: IOException) {}
        vpnInterface = null
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                // 30s interval — measuring every 10s adds jitter during gameplay
                delay(30_000)
                val proxy = currentProxy ?: break
                val (ping, speedLabel) = measureStats(proxy)
                currentPingMs = ping
                updateNotification(proxy.ip, proxy.port, ping, speedLabel)
                broadcastState(STATE_CONNECTED, proxy.ip, proxy.port, ping, speedLabel)
            }
        }
    }

    private fun measureStats(proxy: Proxy): Pair<Long, String> {
        return try {
            val start = System.currentTimeMillis()
            // protect() so these sockets don't loop back into the VPN tunnel
            val sock = when (proxy.protocol) {
                "socks5" -> Socks5Client.connectViaSocks5(
                    proxy.ip, proxy.port, "www.gstatic.com", 80, 6_000, vpnService = this
                )
                else     -> Socks5Client.connectViaHttpProxy(
                    proxy.ip, proxy.port, "www.gstatic.com", 80, 6_000, vpnService = this
                )
            }
            sock.use {
                val req = "GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n"
                it.getOutputStream().apply { write(req.toByteArray()); flush() }

                val buf = ByteArray(4096)
                var total = 0
                val t0 = System.currentTimeMillis()
                while (true) {
                    val n = it.getInputStream().read(buf)
                    if (n == -1) break
                    total += n
                }
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                val kbps    = total * 1000L / elapsed
                Pair(System.currentTimeMillis() - start, formatSpeed(kbps))
            }
        } catch (_: Exception) {
            Pair(currentPingMs, "—")
        }
    }

    private fun formatSpeed(kbps: Long): String = when {
        kbps <= 0   -> "—"
        kbps < 1024 -> "$kbps KB/s"
        else        -> "${"%.1f".format(kbps / 1024.0)} MB/s"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "ProxyBypass VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows proxy connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(ip: String, port: Int, pingMs: Long, speed: String): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, NotificationActionReceiver::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("🎮 ProxyBypass Connected")
            .setContentText("$ip:$port  •  Ping: ${pingMs}ms  •  $speed")
            .setSubText("Tap to open app")
            .setContentIntent(mainIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(ip: String, port: Int, pingMs: Long, speed: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(ip, port, pingMs, speed))
    }

    private fun broadcastState(state: String, ip: String = "", port: Int = 0, ping: Long = 0, speed: String = "") {
        sendBroadcast(Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE,      state)
            putExtra(EXTRA_PROXY_ADDR, if (ip.isNotEmpty()) "$ip:$port" else "")
            putExtra(EXTRA_PING,       ping)
            putExtra(EXTRA_SPEED,      speed)
        })
    }
}
