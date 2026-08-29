package com.dailytool.remotetcl.protocol

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class KeyStoreHelper(private val context: Context) {

    private val TAG = "KeyStoreHelper"
    private val PREFS_NAME = "tcl_keystore_prefs_v2"
    private val KEY_CERT = "client_cert"
    private val KEY_PRIV = "client_priv_key"
    private val KEY_STORE_PASSWORD = "remote_tcl_password".toCharArray()

    private var sslContext: SSLContext? = null
    var clientCertificate: X509Certificate? = null
        private set

    fun getSslContext(): SSLContext {
        if (sslContext != null) return sslContext!!

        val keyStore = loadOrCreateKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, KEY_STORE_PASSWORD)

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, trustAllCerts, SecureRandom())
        sslContext = sc
        return sc
    }

    private fun loadOrCreateKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, KEY_STORE_PASSWORD)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val certBase64 = prefs.getString(KEY_CERT, null)
        val privKeyBase64 = prefs.getString(KEY_PRIV, null)

        if (certBase64 != null && privKeyBase64 != null) {
            try {
                val certBytes = Base64.decode(certBase64, Base64.DEFAULT)
                val privKeyBytes = Base64.decode(privKeyBase64, Base64.DEFAULT)

                val certFactory = CertificateFactory.getInstance("X.509")
                val cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate

                val keySpec = java.security.spec.PKCS8EncodedKeySpec(privKeyBytes)
                val keyFactory = java.security.KeyFactory.getInstance("RSA")
                val privKey = keyFactory.generatePrivate(keySpec)

                keyStore.setKeyEntry("remote_client", privKey, KEY_STORE_PASSWORD, arrayOf(cert))
                clientCertificate = cert
                Log.d(TAG, "Loaded existing client certificate: ${cert.subjectDN}")
                return keyStore
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stored certificate, generating new one: ${e.message}")
            }
        }

        // Generate new KeyPair and Certificate
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()

        val cert = generateSelfSignedCertificate(keyPair)
        clientCertificate = cert

        keyStore.setKeyEntry("remote_client", keyPair.private, KEY_STORE_PASSWORD, arrayOf(cert))

        // Save to SharedPreferences
        prefs.edit()
            .putString(KEY_CERT, Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            .putString(KEY_PRIV, Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP))
            .apply()

        Log.d(TAG, "Generated and saved new client certificate with X509v3 extensions")
        return keyStore
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val startDate = Date(now - 86400000L) // Yesterday
        val endDate = Date(now + 15L * 365 * 86400000L) // 15 years

        // Google TV Remote v2 expects CN=atvremote or similar
        val dnName = X500Name("CN=atvremote, O=Google, C=US")
        val serialNumber = BigInteger.valueOf(now)

        val contentSigner = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certBuilder = JcaX509v3CertificateBuilder(
            dnName,
            serialNumber,
            startDate,
            endDate,
            dnName,
            keyPair.public
        )

        // Essential X.509 extensions for Android TV SSL Client Authentication
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(false)
        )
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment or KeyUsage.dataEncipherment)
        )
        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth))
        )

        return JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner))
    }
}
