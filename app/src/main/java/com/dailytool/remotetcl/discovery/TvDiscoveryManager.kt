package com.dailytool.remotetcl.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.Socket

class TvDiscoveryManager(private val context: Context) {

    private val TAG = "TvDiscoveryManager"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val discoveredDevices = mutableMapOf<String, TvDevice>()
    private var discoveryListener: DiscoveryCallback? = null
    private var scanJob: Job? = null

    interface DiscoveryCallback {
        fun onDeviceFound(device: TvDevice)
        fun onDiscoveryStarted()
        fun onDiscoveryFinished(devices: List<TvDevice>)
    }

    private val serviceTypes = listOf(
        "_androidtvremote2._tcp.",
        "_roku:ecp._tcp.",
        "_googlecast._tcp."
    )

    private val nsdListeners = mutableListOf<NsdManager.DiscoveryListener>()

    fun startDiscovery(callback: DiscoveryCallback) {
        this.discoveryListener = callback
        discoveredDevices.clear()
        callback.onDiscoveryStarted()

        acquireMulticastLock()

        // 1. Start mDNS NSD Discovery
        serviceTypes.forEach { serviceType ->
            val listener = createNsdListener()
            nsdListeners.add(listener)
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NSD for $serviceType: ${e.message}")
            }
        }

        // 2. Run background subnet scan as a reliable fallback
        scanJob?.cancel()
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            scanSubnet()
            delay(4000)
            stopDiscovery()
        }
    }

    private fun createNsdListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery failed for $serviceType: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        resolvedInfo?.let { info ->
                            val host = info.host?.hostAddress ?: return@let
                            val name = info.serviceName ?: "TCL TV"
                            val port = info.port
                            val serviceType = info.serviceType

                            val type = when {
                                serviceType.contains("roku", ignoreCase = true) -> TvType.ROKU_TV
                                serviceType.contains("androidtv", ignoreCase = true) -> TvType.ANDROID_TV
                                else -> TvType.ANDROID_TV
                            }

                            val device = TvDevice(
                                name = name,
                                ipAddress = host,
                                port = port,
                                type = type
                            )
                            addDevice(device)
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
        }
    }

    private fun scanSubnet() {
        try {
            val ip = wifiManager.connectionInfo.ipAddress
            if (ip == 0) return

            val prefix = String.format(
                "%d.%d.%d.",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff
            )

            // Scan common TV ports in local network
            for (i in 1..254) {
                val host = "$prefix$i"
                checkHostForTv(host)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Subnet scan error: ${e.message}")
        }
    }

    private fun checkHostForTv(host: String) {
        // Check Roku port (8060)
        try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, 8060), 100)
                val device = TvDevice(
                    name = "TCL Roku TV ($host)",
                    ipAddress = host,
                    port = 8060,
                    type = TvType.ROKU_TV
                )
                addDevice(device)
                return
            }
        } catch (_: Exception) {}

        // Check Android TV Remote port (6466 or 6467)
        try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, 6467), 100)
                val device = TvDevice(
                    name = "TCL Android TV ($host)",
                    ipAddress = host,
                    port = 6467,
                    type = TvType.ANDROID_TV
                )
                addDevice(device)
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun addDevice(device: TvDevice) {
        if (!discoveredDevices.containsKey(device.ipAddress)) {
            discoveredDevices[device.ipAddress] = device
            CoroutineScope(Dispatchers.Main).launch {
                discoveryListener?.onDeviceFound(device)
            }
        }
    }

    fun stopDiscovery() {
        nsdListeners.forEach { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {}
        }
        nsdListeners.clear()
        releaseMulticastLock()

        CoroutineScope(Dispatchers.Main).launch {
            discoveryListener?.onDiscoveryFinished(discoveredDevices.values.toList())
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("TclDiscoveryMulticastLock").apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
    }
}
