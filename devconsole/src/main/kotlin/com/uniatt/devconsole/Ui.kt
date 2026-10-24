package com.uniatt.devconsole

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.uniatt.core.LicenseOps
import com.uniatt.core.LicenseStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmt(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

@Composable
fun DevApp(vm: DevVM) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    if (vm.busy.value) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.message.value.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(vm.message.value, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!vm.signedIn.value) LoginScreen(vm) else Dashboard(vm)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(vm: DevVM) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Developer Console", style = MaterialTheme.typography.headlineMedium)
        Text("دخول المطوّر فقط. الحساب يجب أن يكون في قائمة devAdmins على Firebase.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(vm.apiKey.value, { vm.apiKey.value = it }, label = { Text("Firebase Web API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vm.dbUrl.value, { vm.dbUrl.value = it }, label = { Text("رابط قاعدة البيانات (Realtime Database URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vm.email.value, { vm.email.value = it }, label = { Text("البريد") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(vm.password.value, { vm.password.value = it }, label = { Text("كلمة المرور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.signIn() }, enabled = !vm.busy.value, modifier = Modifier.fillMaxWidth()) { Text("دخول") }
    }
}

@Composable
private fun Dashboard(vm: DevVM) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("الترخيص") })
            Tab(tab == 1, { tab = 1 }, text = { Text("السجل") })
        }
        if (tab == 0) LicenseTab(vm) else LogTab(vm)
    }
}

@Composable
private fun LicenseTab(vm: DevVM) {
    val d = vm.doc.value
    val v = vm.verdict()
    var org by remember(d?.org) { mutableStateOf(d?.org ?: "") }
    var days by remember { mutableStateOf("365") }
    var grace by remember(d?.graceDays) { mutableStateOf((d?.graceDays ?: 14).toString()) }
    var extend by remember { mutableStateOf("30") }
    var reason by remember { mutableStateOf("") }
    var maintMsg by remember(d?.maintMsg) { mutableStateOf(d?.maintMsg ?: "") }
    var notice by remember(d?.notice) { mutableStateOf(d?.notice ?: "") }
    var offline by remember(d?.offlineDays) { mutableStateOf((d?.offlineDays ?: 0).toString()) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (d == null) "لا يوجد ترخيص مُدار بعد (التطبيقات تعمل بلا قيد)" else "الجامعة: ${d.org}", style = MaterialTheme.typography.titleMedium)
                Text("الحالة: " + when (v.status) {
                    LicenseStatus.UNMANAGED -> "غير مُدار"; LicenseStatus.ACTIVE -> "فعّال"; LicenseStatus.GRACE -> "فترة سماح"
                    LicenseStatus.EXPIRED -> "منتهٍ"; LicenseStatus.SUSPENDED -> "معلّق"; LicenseStatus.MAINTENANCE -> "صيانة"
                    LicenseStatus.OFFLINE_LIMIT -> "يلزم التحقق"; LicenseStatus.TAMPERED -> "نسخة معدّلة"
                })
                if (d != null) {
                    Text("ينتهي: " + (if (d.validUntilMs == 0L) "بلا نهاية" else fmt(d.validUntilMs)) + "   •   السماح: ${d.graceDays} يوم")
                    Text("الصيانة: " + (if (d.maintenance) "مفعّلة" else "متوقفة") + "   •   مراجعة r${d.rev}")
                    if (!d.active && d.reason.isNotBlank()) Text("سبب التعليق: ${d.reason}")
                }
                Text("وقت الخادم: ${fmt(vm.serverMs.value)}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { vm.reload() }) { Text("تحديث") }
            }
        }

        Section("تحديد المدة (من الآن) وتفعيل الترخيص")
        OutlinedTextField(org, { org = it }, label = { Text("اسم الجامعة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(days, { days = it }, label = { Text("المدة بالأيام (0 = بلا نهاية)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
            OutlinedTextField(grace, { grace = it }, label = { Text("السماح (أيام)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
        }
        Button(onClick = {
            vm.apply("تحديد مدة الترخيص", "$org • ${days}ي • سماح ${grace}ي") { p, t ->
                LicenseOps.setTerm(p, t, days.trim().toIntOrNull() ?: throw IllegalArgumentException("المدة رقم صحيح"),
                    grace.trim().toIntOrNull() ?: throw IllegalArgumentException("السماح رقم صحيح"), org)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (d == null) "إنشاء الترخيص وتفعيله" else "ضبط المدة وتفعيل") }

        if (d != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(extend, { extend = it }, label = { Text("تمديد (أيام)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
                Button(onClick = {
                    vm.apply("تمديد الترخيص", "+${extend}ي") { p, t ->
                        LicenseOps.extend(p ?: throw IllegalArgumentException("لا ترخيص للتمديد"), t, extend.trim().toIntOrNull() ?: throw IllegalArgumentException("رقم غير صالح"))
                    }
                }) { Text("تمديد") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(grace, { grace = it }, label = { Text("فترة السماح (أيام)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
                Button(onClick = {
                    vm.apply("تغيير فترة السماح", "${grace}ي") { p, t ->
                        LicenseOps.setGrace(p ?: throw IllegalArgumentException("لا ترخيص"), t, grace.trim().toIntOrNull() ?: throw IllegalArgumentException("رقم غير صالح"))
                    }
                }) { Text("حفظ") }
            }

            Section("التعليق والاستعادة (قابل للعكس؛ لا يُحذف أي سجل)")
            if (d.active) {
                OutlinedTextField(reason, { reason = it }, label = { Text("سبب التعليق (يظهر للمستخدمين)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.apply("تعليق الترخيص", reason) { p, t -> LicenseOps.suspend(p ?: throw IllegalArgumentException("لا ترخيص"), t, reason) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("تعليق الترخيص") }
            } else {
                Button(onClick = { vm.apply("استعادة الترخيص", "") { p, t -> LicenseOps.restore(p ?: throw IllegalArgumentException("لا ترخيص"), t) } },
                    modifier = Modifier.fillMaxWidth()) { Text("استعادة الترخيص") }
            }

            Section("وضع الصيانة")
            OutlinedTextField(maintMsg, { maintMsg = it }, label = { Text("رسالة الصيانة") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.apply("تفعيل الصيانة", maintMsg) { p, t -> LicenseOps.setMaintenance(p ?: throw IllegalArgumentException("لا ترخيص"), t, true, maintMsg) } },
                    modifier = Modifier.weight(1f)) { Text("تفعيل") }
                OutlinedButton(onClick = { vm.apply("إيقاف الصيانة", "") { p, t -> LicenseOps.setMaintenance(p ?: throw IllegalArgumentException("لا ترخيص"), t, false, "") } },
                    modifier = Modifier.weight(1f)) { Text("إيقاف") }
            }

            Section("رسالة لجميع المستخدمين (لا توقف الخدمة)")
            OutlinedTextField(notice, { notice = it }, label = { Text("الرسالة (فارغة = حذفها)") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.apply("تعديل الرسالة العامة", notice) { p, t -> LicenseOps.setNotice(p ?: throw IllegalArgumentException("لا ترخيص"), t, notice) } },
                modifier = Modifier.fillMaxWidth()) { Text("نشر الرسالة") }

            Section("إلزام التحقق الدوري")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(offline, { offline = it }, label = { Text("أقصى أيام بلا اتصال (0 = بلا حد)") }, singleLine = true, keyboardOptions = num, modifier = Modifier.weight(1f))
                Button(onClick = {
                    vm.apply("حد مدة بلا اتصال", "${offline}ي") { p, t ->
                        LicenseOps.setOfflineLimit(p ?: throw IllegalArgumentException("لا ترخيص"), t, offline.trim().toIntOrNull() ?: throw IllegalArgumentException("رقم غير صالح"))
                    }
                }) { Text("حفظ") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("تسجيل الخروج") }
    }
}

@Composable
private fun Section(t: String) {
    Spacer(Modifier.height(6.dp))
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LogTab(vm: DevVM) {
    val logs = vm.logs.value
    if (logs.isEmpty()) { Box(Modifier.fillMaxSize().padding(24.dp)) { Text("لا توجد عمليات مسجّلة بعد") }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(logs, key = { it.key }) { l ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(l.action, style = MaterialTheme.typography.titleSmall)
                    if (l.detail.isNotBlank()) Text(l.detail, style = MaterialTheme.typography.bodyMedium)
                    Text("${fmt(l.timeMs)} • ${l.email} • r${l.rev}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
