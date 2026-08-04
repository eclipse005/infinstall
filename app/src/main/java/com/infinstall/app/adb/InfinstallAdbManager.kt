package com.infinstall.app.adb

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
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

/**
 * Holds RSA key + cert for network debugging (pairing + connect).
 * Not shown in product UI.
 */
class InfinstallAdbManager private constructor(
    context: Context,
) : AbsAdbConnectionManager() {

    private val appContext = context.applicationContext
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(android.os.Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
        val pair = loadOrCreateKeys(appContext)
        privateKey = pair.first
        certificate = pair.second
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "无限安装"

    companion object {
        @Volatile
        private var instance: InfinstallAdbManager? = null

        fun get(context: Context): InfinstallAdbManager {
            return instance ?: synchronized(this) {
                instance ?: InfinstallAdbManager(context.applicationContext).also { instance = it }
            }
        }

        private fun loadOrCreateKeys(context: Context): Pair<PrivateKey, Certificate> {
            val dir = File(context.filesDir, "tv_keys").apply { mkdirs() }
            val keyFile = File(dir, "adbkey.pk8")
            val certFile = File(dir, "adbkey.crt")
            if (keyFile.exists() && certFile.exists()) {
                return try {
                    val keyBytes = keyFile.readBytes()
                    val privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                    val cert = FileInputStream(certFile).use { fis ->
                        CertificateFactory.getInstance("X.509").generateCertificate(fis)
                    }
                    privateKey to cert
                } catch (_: Exception) {
                    generateAndStore(keyFile, certFile)
                }
            }
            return generateAndStore(keyFile, certFile)
        }

        private fun generateAndStore(keyFile: File, certFile: File): Pair<PrivateKey, Certificate> {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val cert = selfSignedCert(kp.private, kp.public)
            FileOutputStream(keyFile).use { it.write(kp.private.encoded) }
            FileOutputStream(certFile).use { it.write(cert.encoded) }
            return kp.private to cert
        }

        private fun selfSignedCert(
            privateKey: PrivateKey,
            publicKey: java.security.PublicKey,
        ): X509Certificate {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 86_400_000L)
            val notAfter = Date(now + 86_400_000L * 3650) // ~10y
            val subject = X500Name("CN=Infinstall")
            val serial = BigInteger(64, SecureRandom())
            val builder = JcaX509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                subject,
                publicKey,
            )
            val signer = JcaContentSignerBuilder("SHA256WithRSA").build(privateKey)
            val holder = builder.build(signer)
            return JcaX509CertificateConverter().getCertificate(holder)
        }
    }
}
