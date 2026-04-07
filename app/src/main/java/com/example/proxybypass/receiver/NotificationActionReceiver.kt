package com.example.proxybypass.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.proxybypass.vpn.ProxyVpnService

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ProxyVpnService.ACTION_STOP) {
            context.stopService(Intent(context, ProxyVpnService::class.java))
        }
    }
}
