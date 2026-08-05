package com.infinstall.app.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * RSA key + cert for wireless pair / connect (libadb-android).
 */
class InfinstallAdbManager private constructor(
    context: Context,
) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        // libadb uses this for protocol max-payload / TLS path selection.
        // Floor at 30 (Android 11) so wireless debugging TLS works even if controller is older.
        // Cap is fine: larger max-payload speeds sync SEND on modern links.
        setApi(max(Build.VERSION.SDK_INT, 30))
        setTimeout(30, TimeUnit.SECONDS)
        setThrowOnUnauthorised(false)
        val pair = loadOrCreateKeys(context.applicationContext)
        privateKey = pair.first
        certificate = pair.second
        Log.i(TAG, "ADB manager ready, api=${max(Build.VERSION.SDK_INT, 30)}")
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    /** ASCII only — some peer-info paths are picky about names */
    override fun getDeviceName(): String = "Infinstall"

    companion object {
        private const val TAG = "InfinstallAdb"
        // Bump suffix to force regenerate keys after cert-format fixes
        private const val KEY_FILE = "adbkey_v3.pk8"
        private const val CERT_FILE = "adbkey_v3.crt"

        @Volatile
        private var instance: InfinstallAdbManager? = null

        fun get(context: Context): InfinstallAdbManager {
            return instance ?: synchronized(this) {
                instance ?: InfinstallAdbManager(context.applicationContext).also { instance = it }
            }
        }

        private fun loadOrCreateKeys(context: Context): Pair<PrivateKey, Certificate> {
            val dir = File(context.filesDir, "tv_keys").apply { mkdirs() }
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                return try {
                    val keyBytes = keyFile.readBytes()
                    val privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                    val cert = FileInputStream(certFile).use { fis ->
                        CertificateFactory.getInstance("X.509").generateCertificate(fis)
                    }
                    privateKey to cert
                } catch (e: Exception) {
                    Log.w(TAG, "reload keys failed, regenerating", e)
                    generateAndStore(keyFile, certFile)
                }
            }
            return generateAndStore(keyFile, certFile)
        }

        private fun generateAndStore(keyFile: File, certFile: File): Pair<PrivateKey, Certificate> {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            // Match libadb sample algorithm (SHA512withRSA) + useful extensions
            val cert = selfSignedCert(kp.private, kp.public)
            FileOutputStream(keyFile).use { it.write(kp.private.encoded) }
            FileOutputStream(certFile).use { it.write(cert.encoded) }
            Log.i(TAG, "generated new ADB key pair")
            return kp.private to cert
        }

        private fun selfSignedCert(
            privateKey: PrivateKey,
            publicKey: java.security.PublicKey,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 86_400_000L)
            val notAfter = Date(now + 86_400_000L * 3650)
            val subject = X500Name("CN=Infinstall")
            val serial = BigInteger(64, SecureRandom()).abs()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                subject,
                publicKey,
            )
            val extUtils = JcaX509ExtensionUtils()
            builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extUtils.createSubjectKeyIdentifier(publicKey),
            )
            builder.addExtension(
                Extension.basicConstraints,
                true,
                BasicConstraints(false),
            )
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            val signer = JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
            val holder = builder.build(signer)
            return JcaX509CertificateConverter().getCertificate(holder)
        }
    }
}
