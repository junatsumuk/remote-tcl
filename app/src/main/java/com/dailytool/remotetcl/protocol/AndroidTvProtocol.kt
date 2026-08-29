package com.dailytool.remotetcl.protocol

import android.content.Context
import android.util.Log
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

class AndroidTvProtocol(private val context: Context) {

    private val TAG = "AndroidTvProtocol"
    private val keyStoreHelper = KeyStoreHelper(context)

    private var remoteSocket: SSLSocket? = null
    private var pairingSocket: SSLSocket? = null
    private var serverCert: X509Certificate? = null

    // Android KeyEvent KeyCodes
    object AndroidKeyCodes {
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

    suspend fun startPairing(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val sslContext = keyStoreHelper.getSslContext()
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.soTimeout = 8000
            socket.connect(InetSocketAddress(device.ipAddress, 6467), 5000)
            socket.startHandshake()

            pairingSocket = socket

            // Retrieve server certificate for secret hash computation
            val certs = socket.session.peerCertificates
            if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                serverCert = certs[0] as X509Certificate
            }

            val out = socket.outputStream
            val input = socket.inputStream

            // Step 1: Send PairingRequest
            // Protobuf message: PairingRequest { protocol_version: 2, status: STATUS_OK (200), service_name: "Remote TCL", client_role: 1 }
            val pairingReq = buildPairingRequestPacket()
            sendPacket(out, pairingReq)
            readPacket(input) // Read TV Ack

            // Step 2: Send PairingOption
            val pairingOption = buildPairingOptionPacket()
            sendPacket(out, pairingOption)
            readPacket(input) // Read TV Option Ack

            // Step 3: Send PairingConfiguration
            val pairingConfig = buildPairingConfigurationPacket()
            sendPacket(out, pairingConfig)
            readPacket(input) // Read TV Config Ack -> TV now shows PIN on screen

            Log.d(TAG, "Pairing sequence initiated successfully. Waiting for user PIN.")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "startPairing failed on ${device.ipAddress}: ${e.message}", e)
            try {
                pairingSocket?.close()
            } catch (_: Exception) {}
            pairingSocket = null
            return@withContext false
        }
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = pairingSocket ?: return@withContext false
            val out = socket.outputStream
            val input = socket.inputStream

            // Calculate secret hash: SHA-256(client_cert_encoded + server_cert_encoded + pin_bytes)
            val clientCert = keyStoreHelper.clientCertificate
            val sCert = serverCert

            val secretHash = if (clientCert != null && sCert != null) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(clientCert.encoded)
                md.update(sCert.encoded)
                md.update(pin.toByteArray(Charsets.UTF_8))
                md.digest()
            } else {
                pin.toByteArray(Charsets.UTF_8)
            }

            // Step 4: Send PairingSecret
            val secretPacket = buildPairingSecretPacket(secretHash)
            sendPacket(out, secretPacket)

            // Read Secret Ack
            val response = readPacket(input)
            socket.close()
            pairingSocket = null

            Log.d(TAG, "Pairing verification completed. Response size: ${response.size}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "verifyPin error: ${e.message}", e)
            try {
                pairingSocket?.close()
            } catch (_: Exception) {}
            pairingSocket = null
            return@withContext false
        }
    }

    suspend fun connect(device: TvDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val sslContext = keyStoreHelper.getSslContext()
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(device.ipAddress, 6466), 4000)
            socket.startHandshake()

            remoteSocket = socket

            // Send initial ping packet
            val out = socket.outputStream
            val pingPacket = byteArrayOf(0x02, 0x08, 0x01)
            out.write(pingPacket)
            out.flush()

            Log.d(TAG, "Connected to Android TV at ${device.ipAddress}:6466")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to ${device.ipAddress}:6466: ${e.message}")
            disconnect()
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
            TvKey.NETFLIX, TvKey.YOUTUBE, TvKey.PRIME_VIDEO -> AndroidKeyCodes.KEYCODE_HOME
        }

        try {
            val socket = remoteSocket ?: return@withContext false
            val out = socket.outputStream

            // Android TV Remote v2 Key Injection Packet:
            // Outer Message tag 0x52 (RemoteKeyInject) -> inner keycode & action (2 = PRESS)
            val payload = ByteArrayOutputStream()
            payload.write(0x08) // field 1: keycode (varint)
            writeVarint(payload, keyCode)
            payload.write(0x10) // field 2: direction (varint)
            writeVarint(payload, 2) // SHORT_PRESS

            val payloadBytes = payload.toByteArray()
            val outer = ByteArrayOutputStream()
            outer.write(0x52) // Tag 10 (RemoteKeyInject)
            writeVarint(outer, payloadBytes.size)
            outer.write(payloadBytes)

            val outerBytes = outer.toByteArray()
            sendPacket(out, outerBytes)
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "sendKey error: ${e.message}")
            disconnect()
            return@withContext false
        }
    }

    private fun sendPacket(out: OutputStream, data: ByteArray) {
        val len = data.size
        out.write(len)
        out.write(data)
        out.flush()
    }

    private fun readPacket(input: InputStream): ByteArray {
        val len = input.read()
        if (len <= 0) return byteArrayOf()
        val buf = ByteArray(len)
        var total = 0
        while (total < len) {
            val read = input.read(buf, total, len - total)
            if (read == -1) break
            total += read
        }
        return buf
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0xFFFFFF80.toInt()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun buildPairingRequestPacket(): ByteArray {
        val nameBytes = "Remote TCL".toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        baos.write(0x08); writeVarint(baos, 2) // protocol_version = 2
        baos.write(0x10); writeVarint(baos, 200) // status = STATUS_OK (200)
        baos.write(0x1a); writeVarint(baos, nameBytes.size); baos.write(nameBytes) // service_name
        baos.write(0x20); writeVarint(baos, 1) // client_role = 1
        return baos.toByteArray()
    }

    private fun buildPairingOptionPacket(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0x08); writeVarint(baos, 2) // protocol_version = 2
        baos.write(0x10); writeVarint(baos, 200) // status = 200
        baos.write(0x18); writeVarint(baos, 1) // preferred_role = 1
        baos.write(0x22); writeVarint(baos, 4) // input_encodings
        baos.write(byteArrayOf(0x08, 0x01, 0x10, 0x06)) // type: 1 (HEX), length: 6
        return baos.toByteArray()
    }

    private fun buildPairingConfigurationPacket(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0x08); writeVarint(baos, 2) // protocol_version = 2
        baos.write(0x10); writeVarint(baos, 200) // status = 200
        baos.write(0x18); writeVarint(baos, 1) // client_role = 1
        baos.write(0x22); writeVarint(baos, 4) // encoding
        baos.write(byteArrayOf(0x08, 0x01, 0x10, 0x06)) // type: 1 (HEX), length: 6
        return baos.toByteArray()
    }

    private fun buildPairingSecretPacket(secret: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(0x08); writeVarint(baos, 2) // protocol_version = 2
        baos.write(0x10); writeVarint(baos, 200) // status = 200
        baos.write(0x1a); writeVarint(baos, secret.size); baos.write(secret)
        return baos.toByteArray()
    }

    fun disconnect() {
        try {
            remoteSocket?.close()
            pairingSocket?.close()
        } catch (_: Exception) {}
        remoteSocket = null
        pairingSocket = null
    }
}
