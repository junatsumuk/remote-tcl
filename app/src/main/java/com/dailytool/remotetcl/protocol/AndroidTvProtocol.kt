package com.dailytool.remotetcl.protocol

import android.content.Context
import android.util.Log
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AndroidTvProtocol(private val context: Context) {

    private val TAG = "AndroidTvProtocol"
    private var remoteSocket: SSLSocket? = null
    private var pairingSocket: SSLSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    // Android KeyEvent KeyCodes
    private object AndroidKeyCodes {
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
        const val KEYCODE_MENU = 82
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_CHANNEL_UP = 166
        const val KEYCODE_CHANNEL_DOWN = 167
        const val KEYCODE_VOLUME_MUTE = 164
    }

    private fun getTrustAllSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext
    }

    suspend fun startPairing(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val sslContext = getTrustAllSslContext()
            val socketFactory = sslContext.socketFactory
            val socket = socketFactory.createSocket(device.ipAddress, 6467) as SSLSocket
            socket.soTimeout = 10000
            socket.startHandshake()
            pairingSocket = socket

            // Send pairing request packet
            val out = socket.outputStream
            // Android TV Remote v2 Pairing Request Magic Header + Payload
            val pairingReq = byteArrayOf(
                0x08, 0x01, 0x12, 0x0b, 0x52, 0x65, 0x6d, 0x6f, 0x74, 0x65, 0x20, 0x54, 0x43, 0x4c, 0x00
            )
            out.write(pairingReq)
            out.flush()

            Log.d(TAG, "Pairing request sent to ${device.ipAddress}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error during startPairing: ${e.message}")
            return@withContext false
        }
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = pairingSocket ?: return@withContext false
            val out = socket.outputStream
            val pinBytes = pin.toByteArray(Charsets.UTF_8)

            // Send secret / PIN confirmation
            out.write(pinBytes)
            out.flush()

            Log.d(TAG, "PIN sent for verification: $pin")
            socket.close()
            pairingSocket = null
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error during verifyPin: ${e.message}")
            return@withContext false
        }
    }

    suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val sslContext = getTrustAllSslContext()
            val socketFactory = sslContext.socketFactory
            val socket = socketFactory.createSocket(device.ipAddress, 6466) as SSLSocket
            socket.soTimeout = 5000
            socket.startHandshake()

            remoteSocket = socket
            outputStream = socket.outputStream
            inputStream = socket.inputStream

            Log.d(TAG, "Connected successfully to ${device.ipAddress}:6466")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed to ${device.ipAddress}: ${e.message}")
            return@withContext false
        }
    }

    suspend fun sendKey(key: TvKey): Boolean = withContext(Dispatchers.IO) {
        val keyCode = when (key) {
            TvKey.POWER -> AndroidKeyCodes.KEYCODE_POWER
            TvKey.VOLUME_UP -> AndroidKeyCodes.KEYCODE_VOLUME_UP
            TvKey.VOLUME_DOWN -> AndroidKeyCodes.KEYCODE_VOLUME_DOWN
            TvKey.MUTE -> AndroidKeyCodes.KEYCODE_VOLUME_MUTE
            TvKey.HOME -> AndroidKeyCodes.KEYCODE_HOME
            TvKey.BACK -> AndroidKeyCodes.KEYCODE_BACK
            TvKey.MENU -> AndroidKeyCodes.KEYCODE_MENU
            TvKey.DPAD_UP -> AndroidKeyCodes.KEYCODE_DPAD_UP
            TvKey.DPAD_DOWN -> AndroidKeyCodes.KEYCODE_DPAD_DOWN
            TvKey.DPAD_LEFT -> AndroidKeyCodes.KEYCODE_DPAD_LEFT
            TvKey.DPAD_RIGHT -> AndroidKeyCodes.KEYCODE_DPAD_RIGHT
            TvKey.DPAD_CENTER -> AndroidKeyCodes.KEYCODE_DPAD_CENTER
            TvKey.CHANNEL_UP -> AndroidKeyCodes.KEYCODE_CHANNEL_UP
            TvKey.CHANNEL_DOWN -> AndroidKeyCodes.KEYCODE_CHANNEL_DOWN
            TvKey.PLAY_PAUSE -> AndroidKeyCodes.KEYCODE_MEDIA_PLAY_PAUSE
            TvKey.NETFLIX -> AndroidKeyCodes.KEYCODE_HOME // Fallback
            TvKey.YOUTUBE -> AndroidKeyCodes.KEYCODE_HOME
            TvKey.PRIME_VIDEO -> AndroidKeyCodes.KEYCODE_HOME
        }

        try {
            val out = outputStream ?: return@withContext false
            // Construct Remote Key Packet: [Length, Type, KeyCode, Direction]
            val packet = byteArrayOf(
                0x02, 0x52, keyCode.toByte(), 0x01
            )
            out.write(packet)
            out.flush()
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send key: ${e.message}")
            return@withContext false
        }
    }

    fun disconnect() {
        try {
            outputStream?.close()
            inputStream?.close()
            remoteSocket?.close()
            pairingSocket?.close()
        } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        remoteSocket = null
        pairingSocket = null
    }
}
