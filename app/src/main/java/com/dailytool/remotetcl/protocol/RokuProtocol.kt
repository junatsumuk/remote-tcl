package com.dailytool.remotetcl.protocol

import android.util.Log
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RokuProtocol {

    private val TAG = "RokuProtocol"
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val emptyBody = "".toRequestBody("text/plain".toMediaType())

    suspend fun sendKey(device: TvDevice, key: TvKey): Boolean = withContext(Dispatchers.IO) {
        val rokuKey = when (key) {
            TvKey.POWER -> "Power"
            TvKey.VOLUME_UP -> "VolumeUp"
            TvKey.VOLUME_DOWN -> "VolumeDown"
            TvKey.MUTE -> "VolumeMute"
            TvKey.HOME -> "Home"
            TvKey.BACK -> "Back"
            TvKey.MENU -> "Info"
            TvKey.DPAD_UP -> "Up"
            TvKey.DPAD_DOWN -> "Down"
            TvKey.DPAD_LEFT -> "Left"
            TvKey.DPAD_RIGHT -> "Right"
            TvKey.DPAD_CENTER -> "Select"
            TvKey.CHANNEL_UP -> "ChannelUp"
            TvKey.CHANNEL_DOWN -> "ChannelDown"
            TvKey.PLAY_PAUSE -> "Play"
            TvKey.NETFLIX -> "launch/12"
            TvKey.YOUTUBE -> "launch/837"
            TvKey.PRIME_VIDEO -> "launch/13"
        }

        val url = if (rokuKey.startsWith("launch/")) {
            "http://${device.ipAddress}:8060/$rokuKey"
        } else {
            "http://${device.ipAddress}:8060/keypress/$rokuKey"
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key $key to ${device.ipAddress}: ${e.message}")
            return@withContext false
        }
    }
}
