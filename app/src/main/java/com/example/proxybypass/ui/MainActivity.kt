package com.example.proxybypass.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.proxybypass.api.ProxyFetcher
import com.example.proxybypass.databinding.ActivityMainBinding
import com.example.proxybypass.model.Proxy
import com.example.proxybypass.vpn.ProxyVpnService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val proxyFetcher = ProxyFetcher()
    private var selectedProxy: Proxy? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        binding.fetchButton.setOnClickListener {
            fetchProxies()
        }

        binding.proxyRecyclerView.layoutManager = LinearLayoutManager(this)
        // Note: In a real app, we'd use an adapter here.
    }

    private fun fetchProxies() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val proxies = proxyFetcher.fetchProxies()
                binding.progressBar.visibility = View.GONE
                if (proxies.isNotEmpty()) {
                    Toast.makeText(this@MainActivity, "Fetched ${proxies.size} proxies", Toast.LENGTH_SHORT).show()
                    // Update UI with proxies
                    selectedProxy = proxies.first() // Auto-select first for demo
                    startVpn()
                } else {
                    Toast.makeText(this@MainActivity, "No proxies found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 0)
        } else {
            onActivityResult(0, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            val vpnIntent = Intent(this, ProxyVpnService::class.java).apply {
                putExtra("PROXY_IP", selectedProxy?.ip)
                putExtra("PROXY_PORT", selectedProxy?.port)
                putExtra("DNS_SERVER", binding.dnsInput.text.toString())
            }
            startService(vpnIntent)
            binding.statusText.text = "Status: Connected to ${selectedProxy?.ip}"
            binding.statusText.setTextColor(android.graphics.Color.GREEN)
        }
    }
}
