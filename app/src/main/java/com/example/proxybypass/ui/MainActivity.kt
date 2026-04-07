package com.example.proxybypass.ui

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.proxybypass.api.ProxyFetcher
import com.example.proxybypass.api.ProxyTester
import com.example.proxybypass.databinding.ActivityMainBinding
import com.example.proxybypass.model.Proxy
import com.example.proxybypass.vpn.ProxyVpnService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProxyAdapter
    private val fetcher = ProxyFetcher()

    private var pendingProxy: Proxy? = null
    private var isConnected = false

    // VPN permission launcher
    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingProxy?.let { startVpnService(it) }
        } else {
            toast("VPN permission denied")
        }
    }

    // Notification permission (Android 13+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    // Receive broadcasts from service
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ProxyVpnService.EXTRA_STATE) ?: return
            val addr  = intent.getStringExtra(ProxyVpnService.EXTRA_PROXY_ADDR) ?: ""
            val ping  = intent.getLongExtra(ProxyVpnService.EXTRA_PING, 0)
            val speed = intent.getStringExtra(ProxyVpnService.EXTRA_SPEED) ?: "—"

            when (state) {
                ProxyVpnService.STATE_CONNECTED -> {
                    isConnected = true
                    updateConnectedUI(addr, ping, speed)
                }
                ProxyVpnService.STATE_DISCONNECTED -> {
                    isConnected = false
                    updateDisconnectedUI()
                }
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecycler()
        setupButtons()
        requestNotifPermission()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(stateReceiver, IntentFilter(ProxyVpnService.BROADCAST_STATE), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = ProxyAdapter { proxy ->
            binding.btnConnect.isEnabled = true
            pendingProxy = proxy
        }
        binding.proxyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter       = this@MainActivity.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener { scanProxies() }
        binding.btnConnect.setOnClickListener {
            if (isConnected) disconnect() else connect()
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ─── Scan ─────────────────────────────────────────────────────────────────

    private fun scanProxies() {
        binding.btnScan.isEnabled    = false
        binding.btnConnect.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvScanStatus.visibility = View.VISIBLE
        binding.tvScanStatus.text = "Fetching proxy lists…"
        binding.proxyListCard.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val raw = fetcher.fetchProxies()
                binding.tvScanStatus.text = "Testing ${raw.size} proxies for speed…"

                val best = ProxyTester.testAll(raw, topN = 10)

                binding.progressBar.visibility  = View.GONE
                binding.btnScan.isEnabled       = true

                if (best.isEmpty()) {
                    binding.tvScanStatus.text = "No live proxies found. Try again."
                    return@launch
                }

                binding.tvScanStatus.text = "Found ${best.size} live proxies  •  Fastest: ${best.first().latencyMs}ms"
                adapter.submitList(best)
                pendingProxy = best.first()
                binding.proxyListCard.visibility  = View.VISIBLE
                binding.btnConnect.isEnabled      = true
                binding.btnConnect.text           = "Connect"

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnScan.isEnabled      = true
                binding.tvScanStatus.text      = "Error: ${e.message}"
            }
        }
    }

    // ─── Connect / Disconnect ─────────────────────────────────────────────────

    private fun connect() {
        val proxy = pendingProxy ?: adapter.getSelected() ?: return
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermLauncher.launch(vpnIntent)
        } else {
            startVpnService(proxy)
        }
    }

    private fun disconnect() {
        stopService(Intent(this, ProxyVpnService::class.java))
        // UI update will come via broadcast
    }

    private fun startVpnService(proxy: Proxy) {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            putExtra(ProxyVpnService.EXTRA_PROXY_IP,   proxy.ip)
            putExtra(ProxyVpnService.EXTRA_PROXY_PORT, proxy.port)
            putExtra(ProxyVpnService.EXTRA_PROXY_PROT, proxy.protocol)
            putExtra(ProxyVpnService.EXTRA_PROXY_PING, proxy.latencyMs)
        }
        ContextCompat.startForegroundService(this, intent)
        binding.tvScanStatus.text = "Connecting to ${proxy.address}…"
    }

    // ─── UI State ─────────────────────────────────────────────────────────────

    private fun updateConnectedUI(addr: String, pingMs: Long, speed: String) {
        binding.statusCard.visibility = View.VISIBLE
        binding.tvStatus.apply {
            text      = "● Connected"
            setTextColor(Color.parseColor("#2E7D32"))
        }
        binding.tvProxyAddr.text = addr
        binding.tvPing.text      = "${pingMs}ms"
        binding.tvSpeed.text     = speed
        binding.btnConnect.apply {
            text      = "Disconnect"
            setBackgroundColor(Color.parseColor("#C62828"))
            isEnabled = true
        }
    }

    private fun updateDisconnectedUI() {
        binding.statusCard.visibility = View.GONE
        binding.tvStatus.apply {
            text      = "● Disconnected"
            setTextColor(Color.parseColor("#C62828"))
        }
        binding.btnConnect.apply {
            text      = "Connect"
            setBackgroundColor(Color.parseColor("#1565C0"))
            isEnabled = adapter.itemCount > 0
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
