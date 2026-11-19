package com.gamepadman.adb

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RSA key + self-signed X.509 certificate that identify GamepadMan to the phone's ADB daemon.
 * The certificate is encoded by hand (tiny DER writer) to avoid pulling a certificate library.
 */
class AdbKeys private constructor(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        @Volatile private var cached: AdbKeys? = null

        @Synchronized
        fun get(ctx: Context): AdbKeys {
            cached?.let { return it }
            val dir = File(ctx.noBackupFilesDir, "adb").apply { mkdirs() }
            val keyFile = File(dir, "key.pk8")
            val certFile = File(dir, "cert.der")
            val loaded = runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream())
                AdbKeys(key, cert)
            }.getOrNull()
            val keys = loaded ?: run {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(2048, SecureRandom())
                val kp = gen.generateKeyPair()
                val certBytes = selfSignedCert(kp.public, kp.private)
                keyFile.writeBytes(kp.private.encoded)
                certFile.writeBytes(certBytes)
                AdbKeys(kp.private, CertificateFactory.getInstance("X.509").generateCertificate(certBytes.inputStream()))
            }
            cached = keys
            return keys
        }

        /** Forgets the identity (the phone will ask to pair again). */
        @Synchronized
        fun reset(ctx: Context) {
            cached = null
            File(ctx.noBackupFilesDir, "adb").deleteRecursively()
        }

        // ---------------------------------------------------------------- DER

        private fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
            val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = body.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                n < 0x10000 -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
                else -> { out.write(0x83); out.write(n shr 16); out.write((n shr 8) and 0xFF); out.write(n and 0xFF) }
            }
            out.write(body)
            return out.toByteArray()
        }

        private fun seq(vararg p: ByteArray) = tlv(0x30, *p)
        private fun set(vararg p: ByteArray) = tlv(0x31, *p)
        private fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
        private fun raw(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

        private val SHA256_RSA = seq(raw(0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), raw(0x05, 0x00))
        private val OID_CN = raw(0x06, 0x03, 0x55, 0x04, 0x03)

        private fun utcTime(d: Date): ByteArray {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x17, f.format(d).toByteArray(Charsets.US_ASCII))
        }

        private fun selfSignedCert(pub: PublicKey, priv: PrivateKey): ByteArray {
            val name = seq(set(seq(OID_CN, tlv(0x0C, "GamepadMan".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = seq(
                utcTime(Date(now - 86_400_000L)),
                utcTime(Date(now + 20L * 365 * 86_400_000L).let { if (it.after(Date(2493072000000L))) Date(2493072000000L) else it }),
            )
            val tbs = seq(
                tlv(0xA0, int(BigInteger.valueOf(2))), // v3
                int(BigInteger(63, SecureRandom()).add(BigInteger.ONE)),
                SHA256_RSA,
                name,
                validity,
                name,
                pub.encoded, // SubjectPublicKeyInfo
            )
            val sig = Signature.getInstance("SHA256withRSA").run { initSign(priv); update(tbs); sign() }
            return seq(tbs, SHA256_RSA, tlv(0x03, byteArrayOf(0) + sig))
        }
    }
}
