package com.dailytool.remotetcl.protocol

import android.util.Log
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class TclCastProtocol {

    private val TAG = "TclCastProtocol"
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun sendKey(device: TvDevice, key: TvKey): Boolean = withContext(Dispatchers.IO) {
        val action = when (key) {
            TvKey.POWER -> "power"
            TvKey.VOLUME_UP -> "volume_up"
            TvKey.VOLUME_DOWN -> "volume_down"
            TvKey.MUTE -> "mute"
            TvKey.HOME -> "home"
            TvKey.BACK -> "back"
            TvKey.MENU -> "menu"
            TvKey.DPAD_UP -> "up"
            TvKey.DPAD_DOWN -> "down"
            TvKey.DPAD_LEFT -> "left"
            TvKey.DPAD_RIGHT -> "right"
            TvKey.DPAD_CENTER -> "ok"
            TvKey.CHANNEL_UP -> "channel_up"
            TvKey.CHANNEL_DOWN -> "channel_down"
            TvKey.PLAY_PAUSE -> "play"
            TvKey.NETFLIX -> "netflix"
            TvKey.YOUTUBE -> "youtube"
            TvKey.PRIME_VIDEO -> "prime"
        }

        // Port 4123 or 7983 (T-Cast MagiConnect port on TCL TVs)
        val urls = listOf(
            "http://${device.ipAddress}:4123/action?val=$action",
            "http://${device.ipAddress}:7983/action?val=$action"
        )

        for (url in urls) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext true
                }
            } catch (_: Exception) {}
        }
        return@withContext false
    }
}
