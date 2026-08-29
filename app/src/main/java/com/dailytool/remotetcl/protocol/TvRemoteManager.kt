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

        currentDevice = TvDevice(name = name, ipAddress = ip, port = port, type = type)
        return currentDevice
    }

    fun saveDevice(device: TvDevice) {
        currentDevice = device
        prefs.edit()
            .putString("tv_ip", device.ipAddress)
            .putString("tv_name", device.name)
            .putString("tv_type", device.type.name)
            .putInt("tv_port", device.port)
            .apply()
    }

    suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        saveDevice(device)
        return@withContext if (device.type == TvType.ROKU_TV) {
            // For Roku, test ping with a query or dummy key
            true
        } else {
            androidTvProtocol.connect(device)
        }
    }

    suspend fun sendKey(key: TvKey): Boolean = withContext(Dispatchers.IO) {
        val device = currentDevice ?: return@withContext false
        return@withContext when (device.type) {
            TvType.ROKU_TV -> rokuProtocol.sendKey(device, key)
            TvType.ANDROID_TV, TvType.UNKNOWN -> {
                // Try Android TV socket first, if not connected try reconnecting
                val sent = androidTvProtocol.sendKey(key)
                if (!sent) {
                    if (androidTvProtocol.connect(device)) {
                        androidTvProtocol.sendKey(key)
                    } else {
                        // Fallback to Roku HTTP command just in case
                        rokuProtocol.sendKey(device, key)
                    }
                } else {
                    true
                }
            }
        }
    }
}
