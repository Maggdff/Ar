package com.uniatt.doctor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.uniatt.core.EcKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/** هوية هاتف الدكتور (مفتاح في Android Keystore): تُسجَّل على الخادم عند مسح رمز الربط فلا يعمل الرمز على هاتف آخر. */
object DoctorDeviceKey {
    private const val ALIAS = "uniatt_doctor_key"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** النقطة الخام (65 بايت) للمفتاح العام. */
    fun publicRaw(): ByteArray {
        val s = ks()
        if (!s.containsAlias(ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            g.generateKeyPair()
        }
        return EcKey.rawFromX509(s.getCertificate(ALIAS).publicKey.encoded)
            ?: throw IllegalStateException("unexpected public key encoding")
    }

}
