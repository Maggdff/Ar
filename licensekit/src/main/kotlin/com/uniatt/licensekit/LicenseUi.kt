package com.uniatt.licensekit

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.uniatt.core.LicenseStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** عمليات التصدير والاستعادة التي يوفّرها كل تطبيق (تبقى متاحة وقت إيقاف الخدمة). */
interface BackupActions {
    /** ينشئ نسخة مشفّرة داخل cacheDir/exports ويعيد (الملف، كود النسخة)؛ null عند الفشل. */
    suspend fun export(): Pair<File, String>?
    /** يستعيد النسخة (دمج دون حذف ما هو موجود في تطبيقي الدكتور والطالب)، ويعيد رسالة النتيجة. */
    suspend fun restore(bytes: ByteArray, code: String): String
}

object LicenseFiles {
    fun exportsDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    /** مشاركة ملف عبر FileProvider بسلطة «‎{packageName}.files» (معرَّف في manifest كل تطبيق). */
    fun share(ctx: Context, f: File, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun readUri(ctx: Context, uri: android.net.Uri): ByteArray? =
    try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }

/**
 * يلفّ واجهة التطبيق: شريط تنبيه فوقها عند وجود إشعار/اقتراب نهاية/سماح،
 * وشاشة إيقاف كاملة (قابلة للعكس) عند التعليق أو الانتهاء أو الصيانة. لا يمسّ البيانات.
 */
@Composable
fun LicenseHost(backup: BackupActions? = null, content: @Composable () -> Unit) {
    val v by LicenseManager.verdict.collectAsState()
    LaunchedEffect(Unit) { while (true) { LicenseManager.recompute(); delay(30_000) } }
    LaunchedEffect(Unit) { while (true) { delay(15 * 60_000L); LicenseManager.refresh() } }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            if (v.blocked) {
                LockScreen(v.status, v.org, v.title, v.message, backup)
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (v.banner.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(v.banner, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Column(Modifier.weight(1f)) { content() }
                }
            }
        }
    }
}

@Composable
private fun LockScreen(status: LicenseStatus, org: String, title: String, message: String, backup: BackupActions?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreCode by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreBytes = readUri(ctx, uri) ?: run { info = "تعذّرت قراءة الملف"; null }
    }
    val icon = when (status) { LicenseStatus.MAINTENANCE -> "🛠"; LicenseStatus.OFFLINE_LIMIT -> "📶"; else -> "🔒" }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Text(icon, style = MaterialTheme.typography.displayMedium)
            if (org.isNotBlank()) Text(org, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            SelectionContainer { Text(message, textAlign = TextAlign.Center) }
            HorizontalDivider()
            Button(onClick = {
                busy = true
                scope.launch {
                    val v = LicenseManager.refresh()
                    busy = false
                    info = if (v.blocked) "الحالة لم تتغير بعد. إن كان المطوّر قد استعاد الترخيص فتأكد من اتصال الإنترنت وأعد المحاولة." else null
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "جارٍ التحقق…" else "إعادة التحقق من الترخيص") }

            if (backup != null) {
                Text("بياناتك محفوظة. يمكنك أخذ نسخة مشفّرة منها أو استعادة نسخة سابقة:", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        val r = try { backup.export() } catch (e: Exception) { null }
                        busy = false
                        if (r == null) info = "تعذّر إنشاء النسخة"
                        else {
                            info = "كود النسخة (لا تفقده، لا استعادة بدونه):\n${r.second}"
                            LicenseFiles.share(ctx, r.first, "نسخة احتياطية")
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("تصدير نسخة احتياطية مشفّرة") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("اختيار نسخة للاستعادة") }
                if (restoreBytes != null) {
                    OutlinedTextField(value = restoreCode, onValueChange = { restoreCode = it.take(20) }, label = { Text("كود النسخة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        val b = restoreBytes ?: return@Button
                        busy = true
                        scope.launch {
                            info = try { backup.restore(b, restoreCode) } catch (e: Exception) { "تعذّرت الاستعادة: ${e.message}" }
                            busy = false; restoreBytes = null; restoreCode = ""
                        }
                    }, enabled = restoreCode.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("استعادة النسخة") }
                }
            }
            info?.let { SelectionContainer { Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}
