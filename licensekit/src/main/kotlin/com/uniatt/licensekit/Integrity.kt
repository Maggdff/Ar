package com.uniatt.licensekit

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * فحص سلامة التطبيق: يقارن بصمة شهادة توقيع الـAPK المثبَّت ببصمة مفتاحنا. من يفكّ الـAPK ويعدّله ويعيد توقيعه
 * بمفتاح آخر تتغير البصمة فيُغلق التطبيق (الحالة TAMPERED) ويبقى التصدير متاحًا للمستخدم الشرعي.
 * هذا يرفع الكلفة كثيرًا مع تعقيد R8 لكنه ليس مطلقًا: من يبتر الفحص نفسه من الشيفرة يتجاوزه؛
 * لذلك الإيقاف الفعلي مفروض أيضًا على الخادم (قواعد Firebase).
 */
internal object Integrity {
    @Volatile private var cached: Boolean? = null

    fun ok(ctx: Context): Boolean = cached ?: compute(ctx.applicationContext).also { cached = it }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun compute(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            when { s == null -> null; s.hasMultipleSigners() -> s.apkContentsSigners; else -> s.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val want = BuildConfig.CERT_SHA256
        sigs != null && sigs.isNotEmpty() && sigs.any { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) == want }
    } catch (_: Exception) { false }
}
