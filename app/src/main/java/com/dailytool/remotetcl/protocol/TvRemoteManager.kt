package com.dailytool.remotetcl.protocol

import android.content.Context
import android.content.SharedPreferences
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import com.dailytool.remotetcl.model.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TvRemoteManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tcl_remote_prefs", Context.MODE_PRIVATE)

    val rokuProtocol = RokuProtocol()
    val androidTvProtocol = AndroidTvProtocol(context)
    val tclCastProtocol = TclCastProtocol()

    var currentDevice: TvDevice? = null
        private set

    init {
        loadSavedDevice()
    }

    fun loadSavedDevice(): TvDevice? {
        val ip = prefs.getString("tv_ip", null) ?: return null
        val name = prefs.getString("tv_name", "TCL TV") ?: "TCL TV"
        val typeStr = prefs.getString("tv_type", TvType.ANDROID_TV.name) ?: TvType.ANDROID_TV.name
        val type = try {
            TvType.valueOf(typeStr)
        } catch (_: Exception) {
            TvType.ANDROID_TV
        }
        val port = prefs.getInt("tv_port", if (type == TvType.ROKU_TV) 8060 else 6466)

        currentDevice = TvDevice(name = name, ipAddress = ip, port = port, type = type, isPaired = true)
        return currentDevice
    }

    fun saveDevice(device: TvDevice) {
        currentDevice = device.copy(isPaired = true)
        prefs.edit()
            .putString("tv_ip", device.ipAddress)
            .putString("tv_name", device.name)
            .putString("tv_type", device.type.name)
            .putInt("tv_port", device.port)
            .apply()
    }

    fun clearSavedDevice() {
        currentDevice = null
        prefs.edit().clear().apply()
    }

    suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        return@withContext if (device.type == TvType.ROKU_TV) {
            saveDevice(device)
            true
        } else {
            val connected = androidTvProtocol.connect(device)
            if (connected) {
                saveDevice(device)
            }
            connected
        }
    }

    suspend fun sendKey(key: TvKey): Boolean = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext false

        if (device.type == TvType.ROKU_TV) {
            return@withContext rokuProtocol.sendKey(device, key)
        }

        // 1. Try Android TV TLS Socket
        var sent = androidTvProtocol.sendKey(key)
        if (!sent) {
            // Reconnect and retry
            if (androidTvProtocol.connect(device)) {
                sent = androidTvProtocol.sendKey(key)
            }
        }

        // 2. Try TCL T-Cast HTTP API if Android TV protocol was not accepted
        if (!sent) {
            sent = tclCastProtocol.sendKey(device, key)
        }

        // 3. Try Roku ECP API as final fallback
        if (!sent) {
            sent = rokuProtocol.sendKey(device, key)
        }

        return@withContext sent
    }
}
