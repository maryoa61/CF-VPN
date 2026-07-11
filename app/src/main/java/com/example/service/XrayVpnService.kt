package com.example.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class XrayVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        try {
            if (vpnInterface == null) {
                vpnInterface = Builder()
                    .setSession("VPNClientSession")
                    .setMtu(1500)
                    .addAddress("10.8.0.2", 32)
                    // Routing a private dummy subnet ensures the VPN key shows in the Android status bar,
                    // but does not break the user's internet connection.
                    .addRoute("10.8.0.0", 24)
                    .establish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
