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
    private var isConnected  = false
    private var isConnecting = false   // true between Connect tap and first CONNECTED broadcast

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingProxy?.let { startVpnService(it) }
        } else {
            isConnecting = false
            setConnectingUI(false)
            toast("VPN permission denied")
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(ProxyVpnService.EXTRA_STATE) ?: return
            val addr  = intent.getStringExtra(ProxyVpnService.EXTRA_PROXY_ADDR) ?: ""
            val ping  = intent.getLongExtra(ProxyVpnService.EXTRA_PING, 0)
            val speed = intent.getStringExtra(ProxyVpnService.EXTRA_SPEED) ?: "—"
            when (state) {
                ProxyVpnService.STATE_CONNECTED    -> { isConnected = true;  isConnecting = false; updateConnectedUI(addr, ping, speed) }
                ProxyVpnService.STATE_DISCONNECTED -> { isConnected = false; isConnecting = false; updateDisconnectedUI() }
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

        // Sync UI with actual service state — fixes the case where the activity
        // was paused during the VPN permission dialog and missed the broadcast.
        when {
            ProxyVpnService.isRunning -> {
                isConnected  = true
                isConnecting = false
                updateConnectedUI(ProxyVpnService.lastAddr, ProxyVpnService.lastPing, ProxyVpnService.lastSpeed)
            }
            isConnecting -> {
                // Still waiting for the service — keep spinner visible
                setConnectingUI(true)
            }
            else -> {
                isConnected = false
                // Only reset to disconnected UI if we weren't in mid-scan
                if (!binding.btnScan.isEnabled.not()) updateDisconnectedUI()
            }
        }
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
            if (isConnected) disconnect() else if (!isConnecting) connect()
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ─── Scan ─────────────────────────────────────────────────────────────────

    private fun scanProxies() {
        binding.btnScan.isEnabled       = false
        binding.btnConnect.isEnabled    = false
        binding.progressBar.visibility  = View.VISIBLE
        binding.tvScanStatus.visibility = View.VISIBLE
        binding.tvScanStatus.text       = "Fetching proxy lists…"
        binding.proxyListCard.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val raw  = fetcher.fetchProxies()
                binding.tvScanStatus.text = "Testing ${raw.size} proxies for speed…"
                val best = ProxyTester.testAll(raw, topN = 10)

                binding.progressBar.visibility = View.GONE
                binding.btnScan.isEnabled      = true

                if (best.isEmpty()) {
                    binding.tvScanStatus.text = "No live proxies found. Try again."
                    return@launch
                }

                binding.tvScanStatus.text     = "Found ${best.size} live proxies  •  Fastest: ${best.first().latencyMs}ms"
                adapter.submitList(best)
                pendingProxy                  = best.first()
                binding.proxyListCard.visibility = View.VISIBLE
                binding.btnConnect.isEnabled  = !isConnected
                binding.btnConnect.text       = "Connect"
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
        pendingProxy  = proxy
        isConnecting  = true
        setConnectingUI(true)

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermLauncher.launch(vpnIntent)   // activity pauses here — static flag saves us on resume
        } else {
            startVpnService(proxy)
        }
    }

    private fun disconnect() {
        stopService(Intent(this, ProxyVpnService::class.java))
        // UI update arrives via STATE_DISCONNECTED broadcast
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

    /** Shows spinner + "Connecting…" button while VPN is being established. */
    private fun setConnectingUI(connecting: Boolean) {
        if (connecting) {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnConnect.apply {
                text      = "Connecting…"
                isEnabled = false
                setBackgroundColor(Color.parseColor("#757575"))
            }
            binding.btnScan.isEnabled = false
        } else {
            binding.progressBar.visibility = View.GONE
            binding.btnScan.isEnabled      = true
        }
    }

    private fun updateConnectedUI(addr: String, pingMs: Long, speed: String) {
        setConnectingUI(false)
        binding.statusCard.visibility = View.VISIBLE
        binding.tvStatus.apply { text = "● Connected"; setTextColor(Color.parseColor("#2E7D32")) }
        binding.tvProxyAddr.text = addr
        binding.tvPing.text      = "${pingMs}ms"
        binding.tvSpeed.text     = speed
        binding.tvScanStatus.text = "Connected  •  $addr"
        binding.btnConnect.apply {
            text      = "Disconnect"
            setBackgroundColor(Color.parseColor("#C62828"))
            isEnabled = true
        }
    }

    private fun updateDisconnectedUI() {
        setConnectingUI(false)
        binding.statusCard.visibility = View.GONE
        binding.tvStatus.apply { text = "● Disconnected"; setTextColor(Color.parseColor("#C62828")) }
        binding.tvScanStatus.text = "Tap Scan to find fastest proxies"
        binding.btnConnect.apply {
            text      = "Connect"
            setBackgroundColor(Color.parseColor("#1565C0"))
            isEnabled = adapter.itemCount > 0
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
