package com.dailytool.remotetcl.protocol

import android.content.Context
import android.util.Log
import com.dailytool.remotetcl.model.PairingResult
import com.dailytool.remotetcl.model.TvDevice
import com.dailytool.remotetcl.model.TvKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
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
        const val KEYCODE_UNKNOWN = 0
        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4
        const val KEYCODE_0 = 7
        const val KEYCODE_1 = 8
        const val KEYCODE_2 = 9
        const val KEYCODE_3 = 10
        const val KEYCODE_4 = 11
        const val KEYCODE_5 = 12
        const val KEYCODE_6 = 13
        const val KEYCODE_7 = 14
        const val KEYCODE_8 = 15
        const val KEYCODE_9 = 16
        const val KEYCODE_DPAD_UP = 19
        const val KEYCODE_DPAD_DOWN = 20
        const val KEYCODE_DPAD_LEFT = 21
        const val KEYCODE_DPAD_RIGHT = 22
        const val KEYCODE_DPAD_CENTER = 23
        const val KEYCODE_VOLUME_UP = 24
        const val KEYCODE_VOLUME_DOWN = 25
        const val KEYCODE_POWER = 26
        const val KEYCODE_A = 29
        const val KEYCODE_B = 30
        const val KEYCODE_C = 31
        const val KEYCODE_D = 32
        const val KEYCODE_E = 33
        const val KEYCODE_F = 34
        const val KEYCODE_G = 35
        const val KEYCODE_H = 36
        const val KEYCODE_I = 37
        const val KEYCODE_J = 38
        const val KEYCODE_K = 39
        const val KEYCODE_L = 40
        const val KEYCODE_M = 41
        const val KEYCODE_N = 42
        const val KEYCODE_O = 43
        const val KEYCODE_P = 44
        const val KEYCODE_Q = 45
        const val KEYCODE_R = 46
        const val KEYCODE_S = 47
        const val KEYCODE_T = 48
        const val KEYCODE_U = 49
        const val KEYCODE_V = 50
        const val KEYCODE_W = 51
        const val KEYCODE_X = 52
        const val KEYCODE_Y = 53
        const val KEYCODE_Z = 54
        const val KEYCODE_COMMA = 55
        const val KEYCODE_PERIOD = 56
        const val KEYCODE_SPACE = 62
        const val KEYCODE_ENTER = 66
        const val KEYCODE_DEL = 67
        const val KEYCODE_MINUS = 69
        const val KEYCODE_EQUALS = 70
        const val KEYCODE_SLASH = 76
        const val KEYCODE_AT = 77
        const val KEYCODE_PLUS = 81
        const val KEYCODE_MENU = 82
        const val KEYCODE_MEDIA_PLAY_PAUSE = 85
        const val KEYCODE_CHANNEL_UP = 166
        const val KEYCODE_CHANNEL_DOWN = 167
        const val KEYCODE_VOLUME_MUTE = 164
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun extractStatus(ack: ByteArray): Int {
        for (i in 0 until ack.size - 1) {
            if (ack[i].toInt() == 0x10) {
                var result = 0
                var shift = 0
                var idx = i + 1
                while (idx < ack.size) {
                    val b = ack[idx++].toInt()
                    result = result or ((b and 0x7F) shl shift)
                    if ((b and 0x80) == 0) return result
                    shift += 7
                }
                return result
            }
        }
        return 0
    }

    suspend fun startPairing(device: TvDevice): PairingResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        val targetIp = device.ipAddress
        log.append("Target IP: $targetIp\n")

        // 1. Check open ports on target TV
        val testPorts = listOf(6467, 6466, 8060, 4123, 7983, 5555, 8008)
        val openPorts = mutableListOf<Int>()
        for (p in testPorts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(targetIp, p), 300)
                    openPorts.add(p)
                }
            } catch (_: Exception) {}
        }
        log.append("Port Terbuka di TV: ${if (openPorts.isEmpty()) "Tidak ada" else openPorts.joinToString(", ")}\n")

        // Candidates for service_name in Google TV Remote v2 pairing
        val candidateServiceNames = listOf(
            "com.google.android.tv.remote.service",
            "",
            device.name,
            "androidtvremote",
            "atvremote"
        )

        var lastErrorMsg = ""

        for (candidateName in candidateServiceNames) {
            try {
                disconnect()
                log.append("\n--- Mencoba pairing dengan service_name: \"$candidateName\" ---\n")
                val sslContext = keyStoreHelper.getSslContext()
                val socket = sslContext.socketFactory.createSocket() as SSLSocket
                socket.soTimeout = 8000
                socket.useClientMode = true

                socket.connect(InetSocketAddress(targetIp, 6467), 5000)
                socket.startHandshake()
                log.append("TLS Handshake Sukses! Cipher: ${socket.session.cipherSuite}\n")

                pairingSocket = socket

                val certs = socket.session.peerCertificates
                if (certs.isNotEmpty() && certs[0] is X509Certificate) {
                    serverCert = certs[0] as X509Certificate
                    log.append("Sertifikat TV: ${serverCert?.subjectDN}\n")
                }

                val out = socket.outputStream
                val input = socket.inputStream

                // Step 1: Send PairingRequest
                log.append("Mengirim PairingRequest...\n")
                val pairingReq = buildPairingRequestPacket(candidateName)
                sendPacket(out, pairingReq)

                val ack1 = readPacket(input)
                val status1 = extractStatus(ack1)
                log.append("Menerima PairingRequestAck (${ack1.size} bytes: ${bytesToHex(ack1)}) -> Status: $status1\n")

                if (status1 != 200) {
                    log.append("TV merespons error status $status1, mencoba nama service lain...\n")
                    socket.close()
                    continue
                }

                // Step 2: Send PairingOption (Single exact HEXADECIMAL 6 chars)
                log.append("Mengirim PairingOption (HEXADECIMAL 6 chars)...\n")
                val pairingOption = buildPairingOptionPacket()
                sendPacket(out, pairingOption)

                val ack2 = readPacket(input)
                val status2 = extractStatus(ack2)
                log.append("Menerima PairingOptionAck (${ack2.size} bytes: ${bytesToHex(ack2)}) -> Status: $status2\n")

                // Step 3: Send PairingConfiguration
                log.append("Mengirim PairingConfiguration...\n")
                val pairingConfig = buildPairingConfigurationPacket()
                sendPacket(out, pairingConfig)

                val ack3 = readPacket(input)
                val status3 = extractStatus(ack3)
                log.append("Menerima PairingConfigurationAck (${ack3.size} bytes: ${bytesToHex(ack3)}) -> Status: $status3\n")

                if (status3 == 200 || ack3.isNotEmpty()) {
                    log.append("Sukses! Kode PIN sekarang muncul di layar TV.\n")
                    return@withContext PairingResult(
                        success = true,
                        message = "Berhasil meminta PIN",
                        diagnosticLog = log.toString(),
                        openPorts = openPorts
                    )
                }
            } catch (e: Exception) {
                lastErrorMsg = "${e.javaClass.simpleName}: ${e.message}"
                log.append("Percobaan gagal: $lastErrorMsg\n")
                try {
                    pairingSocket?.close()
                } catch (_: Exception) {}
                pairingSocket = null
            }
        }

        return@withContext PairingResult(
            success = false,
            message = lastErrorMsg.ifEmpty { "Gagal meminta PIN dari TV" },
            diagnosticLog = log.toString(),
            openPorts = openPorts
        )
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = pairingSocket ?: return@withContext false
            val out = socket.outputStream
            val input = socket.inputStream

            val cleanPin = pin.trim().uppercase()
            val clientCert = keyStoreHelper.clientCertificate
            val sCert = serverCert

            // Parse hex PIN (e.g. "9F4E12" -> 3 bytes)
            val pinBytes = try {
                if (cleanPin.length % 2 == 0 && cleanPin.matches(Regex("^[0-9A-F]+$"))) {
                    cleanPin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } else {
                    cleanPin.toByteArray(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                cleanPin.toByteArray(Charsets.UTF_8)
            }

            // Calculate secret hash: SHA-256(client_cert_der + server_cert_der + pin_bytes)
            val secretHash = if (clientCert != null && sCert != null) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(clientCert.encoded)
                md.update(sCert.encoded)
                md.update(pinBytes)
                md.digest()
            } else {
                pinBytes
            }

            // Step 4: PairingSecret
            val secretPacket = buildPairingSecretPacket(secretHash)
            sendPacket(out, secretPacket)

            val response = readPacket(input)
            socket.close()
            pairingSocket = null

            Log.d(TAG, "Pairing verification completed with response length: ${response.size} bytes: ${bytesToHex(response)}")
            return@withContext response.isNotEmpty()
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
            socket.useClientMode = true
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
        return@withContext sendKeyCode(keyCode)
    }

    suspend fun sendKeyCode(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = remoteSocket ?: return@withContext false
            val out = socket.outputStream

            val payload = ByteArrayOutputStream()
            payload.write(0x08) // field 1: key_code
            writeVarint(payload, keyCode)
            payload.write(0x10) // field 2: direction (2 = SHORT_PRESS)
            writeVarint(payload, 2)

            val payloadBytes = payload.toByteArray()
            val outer = ByteArrayOutputStream()
            outer.write(0x52) // Tag 10 (remote_key_inject)
            writeVarint(outer, payloadBytes.size)
            outer.write(payloadBytes)

            sendPacket(out, outer.toByteArray())
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyCode error: ${e.message}")
            disconnect()
            return@withContext false
        }
    }

    suspend fun sendText(text: String): Boolean = withContext(Dispatchers.IO) {
        for (char in text) {
            val keyCode = getKeyCodeForChar(char)
            if (keyCode != AndroidKeyCodes.KEYCODE_UNKNOWN) {
                sendKeyCode(keyCode)
                delay(60)
            }
        }
        return@withContext true
    }

    private fun getKeyCodeForChar(c: Char): Int {
        return when (c) {
            in 'a'..'z' -> AndroidKeyCodes.KEYCODE_A + (c - 'a')
            in 'A'..'Z' -> AndroidKeyCodes.KEYCODE_A + (c - 'A')
            in '0'..'9' -> AndroidKeyCodes.KEYCODE_0 + (c - '0')
            ' ' -> AndroidKeyCodes.KEYCODE_SPACE
            '\n' -> AndroidKeyCodes.KEYCODE_ENTER
            ',' -> AndroidKeyCodes.KEYCODE_COMMA
            '.' -> AndroidKeyCodes.KEYCODE_PERIOD
            '-' -> AndroidKeyCodes.KEYCODE_MINUS
            '=' -> AndroidKeyCodes.KEYCODE_EQUALS
            '/' -> AndroidKeyCodes.KEYCODE_SLASH
            '@' -> AndroidKeyCodes.KEYCODE_AT
            '+' -> AndroidKeyCodes.KEYCODE_PLUS
            else -> AndroidKeyCodes.KEYCODE_UNKNOWN
        }
    }

    private fun sendPacket(out: OutputStream, data: ByteArray) {
        writeVarint(out, data.size)
        out.write(data)
        out.flush()
    }

    private fun readPacket(input: InputStream): ByteArray {
        val len = readVarint(input)
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

    private fun writeVarint(out: OutputStream, value: Int) {
        var v = value
        while ((v and 0xFFFFFF80.toInt()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun readVarint(input: InputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = input.read()
            if (b == -1) {
                if (shift == 0) return -1
                throw IOException("Truncated varint")
            }
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IOException("Varint too long")
    }

    // Step 1: PairingRequest (Protobuf Structure)
    private fun buildPairingRequestPacket(serviceName: String = "com.google.android.tv.remote.service", clientName: String = "Android"): ByteArray {
        val serviceBytes = serviceName.toByteArray(Charsets.UTF_8)
        val clientBytes = clientName.toByteArray(Charsets.UTF_8)

        val inner = ByteArrayOutputStream()
        if (serviceBytes.isNotEmpty()) {
            inner.write(0x0A) // Tag 1: service_name
            writeVarint(inner, serviceBytes.size)
            inner.write(serviceBytes)
        }

        inner.write(0x12) // Tag 2: client_name
        writeVarint(inner, clientBytes.size)
        inner.write(clientBytes)

        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x08); writeVarint(outer, 2) // protocol_version = 2
        outer.write(0x10); writeVarint(outer, 200) // status = 200
        outer.write(0x1A) // Tag 3: pairing_request
        writeVarint(outer, innerBytes.size)
        outer.write(innerBytes)

        return outer.toByteArray()
    }

    // Step 2: PairingOption (Single exact HEXADECIMAL encoding)
    private fun buildPairingOptionPacket(): ByteArray {
        val encoding = ByteArrayOutputStream()
        encoding.write(0x08); writeVarint(encoding, 3) // type: 3 (ENCODING_TYPE_HEXADECIMAL)
        encoding.write(0x10); writeVarint(encoding, 6) // symbol_length: 6
        val encodingBytes = encoding.toByteArray()

        val inner = ByteArrayOutputStream()
        inner.write(0x08); writeVarint(inner, 1) // preferred_role: 1 (ROLE_TYPE_INPUT)
        inner.write(0x12) // Tag 2: input_encodings
        writeVarint(inner, encodingBytes.size)
        inner.write(encodingBytes)
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x08); writeVarint(outer, 2)
        outer.write(0x10); writeVarint(outer, 200)
        outer.write(0x2A) // Tag 5: pairing_option
        writeVarint(outer, innerBytes.size)
        outer.write(innerBytes)

        return outer.toByteArray()
    }

    // Step 3: PairingConfiguration
    private fun buildPairingConfigurationPacket(): ByteArray {
        val encoding = ByteArrayOutputStream()
        encoding.write(0x08); writeVarint(encoding, 3) // type: 3 (HEXADECIMAL)
        encoding.write(0x10); writeVarint(encoding, 6) // symbol_length: 6
        val encodingBytes = encoding.toByteArray()

        val inner = ByteArrayOutputStream()
        inner.write(0x08); writeVarint(inner, 1) // client_role: 1
        inner.write(0x12) // Tag 2: encoding
        writeVarint(inner, encodingBytes.size)
        inner.write(encodingBytes)
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x08); writeVarint(outer, 2)
        outer.write(0x10); writeVarint(outer, 200)
        outer.write(0x3A) // Tag 7: pairing_configuration
        writeVarint(outer, innerBytes.size)
        outer.write(innerBytes)

        return outer.toByteArray()
    }

    // Step 4: PairingSecret
    private fun buildPairingSecretPacket(secret: ByteArray): ByteArray {
        val inner = ByteArrayOutputStream()
        inner.write(0x0A) // Tag 1: secret
        writeVarint(inner, secret.size)
        inner.write(secret)
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x08); writeVarint(outer, 2)
        outer.write(0x10); writeVarint(outer, 200)
        outer.write(0x4A) // Tag 9: pairing_secret
        writeVarint(outer, innerBytes.size)
        outer.write(innerBytes)

        return outer.toByteArray()
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
