package com.uniatt.student

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.uniatt.core.Codes
import com.uniatt.core.Schedule
import com.uniatt.core.TEntry
import com.uniatt.core.TKind
import com.uniatt.core.Timetable
import com.uniatt.core.QrProtocol
import com.uniatt.core.Status
import com.uniatt.core.StudentProfile
import com.uniatt.core.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
fun StudentApp(store: StudentStore, tick: Int) {
    var hasCode by remember { mutableStateOf(store.credentials() != null) }
    MaterialTheme {
        AppBackground {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                if (!hasCode) Activation(store) { hasCode = true }
                else MainScreen(store, tick) { store.clearAll(); hasCode = false }
            }
        }
    }
}

/** التفعيل أول مرة: يلزم إنترنت للتحقق من الكود لدى الإدارة (لا يُقبل كود غير مسجَّل)، ثم يصل الجدول والبيانات. */
@Composable
fun Activation(store: StudentStore, onDone: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("تفعيل التطبيق", style = MaterialTheme.typography.headlineMedium)
        Text("اكتب الكود الذي سلّمته لك الإدارة (12 خانة، مثل AB12-CD34-EF56). يلزم اتصال بالإنترنت هذه المرة فقط للتحقق من الكود وتنزيل بياناتك وجدولك؛ بعدها يعمل تسجيل الحضور بدون إنترنت.")
        OutlinedTextField(
            input, { input = it.take(20); error = null },
            label = { Text("كود الطالب") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = Red) }
        Button(
            onClick = {
                val code = Codes.normalize(input)
                if (code == null) { error = "الكود غير صحيح — راجع الخانات وأعد الكتابة"; return@Button }
                busy = true; error = null
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching { OnlineSync.activate(store, code) }
                            .getOrElse { OnlineSync.Activation.Failed("تعذّر التفعيل (${it.javaClass.simpleName})") }
                    }
                    busy = false
                    when (res) {
                        is OnlineSync.Activation.Ok -> onDone()
                        is OnlineSync.Activation.Failed -> error = res.msg
                    }
                }
            },
            enabled = input.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "جارٍ التحقق من الكود…" else "تحقّق وفعّل") }
        Text(
            "بعد التفعيل يظهر جدولك وبياناتك، ثم سجّل حضورك في أول محاضرة (بلوتوث تلقائي أو QR) ليرتبط هاتفك بهويتك.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun MainScreen(store: StudentStore, tick: Int, onReset: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val version by ResultBus.profileVersion.collectAsState()
    val profile = remember(version) { store.profile() }
    val group = remember(version) { store.group }
    val titles = listOf("الرئيسية", "بياناتي", "المواد", "الجدول", "السجل", "إعدادات")
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i }, icon = { Text("•") },
                        label = { Text(t, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            when (tab) {
                0 -> Home(profile)
                1 -> ProfileView(profile, group)
                2 -> CoursesView(profile)
                3 -> TimetableView(store)
                4 -> HistoryView(store)
                else -> SettingsView(store, onReset)
            }
        }
    }
}

private const val WAITING = "لم تصلك بياناتك بعد. اتصل بالإنترنت وافتح التطبيق وستظهر هنا تلقائيًا."

@Composable
fun Home(profile: StudentProfile?) {
    val ctx = LocalContext.current
    val last by ResultBus.last.collectAsState()
    val store = remember { StudentStore(ctx) }
    var auto by remember { mutableStateOf(store.autoBle && blePermissionsGranted(ctx)) }
    var autoMsg by remember { mutableStateOf<String?>(null) }
    val bleState by ResultBus.ble.collectAsState()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (blePermissionsGranted(ctx)) { store.autoBle = true; auto = true; autoMsg = null; AutoAttendService.start(ctx) }
        else { store.autoBle = false; auto = false; autoMsg = "لم تُمنح صلاحية البلوتوث — اسمح بها من إعدادات التطبيق ليعمل التسجيل التلقائي." }
    }
    var qrReply by remember { mutableStateOf<String?>(null) }
    var qrError by remember { mutableStateOf<String?>(null) }
    val qrScope = rememberCoroutineScope()
    val challengeScanner = rememberLauncherForActivityResult(ScanContract()) { res ->
        val t = res.contents
        if (t != null) {
            val req = QrProtocol.parseChallenge(t)
            if (req == null) {
                qrError = "هذا ليس رمز الدكتور. اطلب منه تفعيل «تسجيل بالـQR» في شاشة الحضور المباشر ثم امسح الرمز الذي يظهر في هاتفه."
            } else qrScope.launch {
                val text = withContext(Dispatchers.Default) { runCatching { StudentQr.reply(ctx, req) }.getOrNull() }
                if (text == null) qrError = "تعذّر تجهيز الإجابة على هذا الجهاز" else { qrError = null; qrReply = text }
            }
        }
    }
    qrReply?.let { txt ->
        AlertDialog(
            onDismissRequest = { qrReply = null },
            confirmButton = { TextButton(onClick = { qrReply = null }) { Text("تم") } },
            title = { Text("اعرض هذا الرمز للدكتور") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(txt, Modifier.size(300.dp))
                    Text("وجّه شاشتك نحو كاميرا هاتف الدكتور وأبقِها ثابتة. الرمز صالح لدقيقة واحدة؛ إن انتهت امسح رمز الدكتور من جديد.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(profile?.let { "أهلًا ${it.name}" } ?: "تسجيل الحضور", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("طريقة التسجيل: فعّل «التسجيل التلقائي بالبلوتوث» ليُسجَّل حضورك فور بدء الدكتور، وإن لم يعمل فاستخدم «تسجيل بالـQR».")
                if (profile == null) Text(WAITING, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = auto, onCheckedChange = { on ->
                        if (on) permLauncher.launch(blePermissions())
                        else { store.autoBle = false; auto = false; AutoAttendService.stop(ctx) }
                    })
                    Text("التسجيل التلقائي بالبلوتوث (بلا أي لمسة)", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    "يعمل في الخلفية بإشعار دائم: فور بدء الدكتور للجلسة يتصل هاتفك به ويُسجَّل حضورك تلقائيًا. أبقِ البلوتوث مفعّلًا. قد يستغرق ذلك حتى نصف دقيقة في القاعات الكبيرة." +
                        (if (auto && bleState.isNotBlank()) "\nالحالة: $bleState" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
                autoMsg?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = {
                    qrError = null
                    challengeScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("امسح الرمز المعروض على هاتف الدكتور").setBeepEnabled(false).setOrientationLocked(false))
                }, modifier = Modifier.fillMaxWidth()) { Text("تسجيل بالـQR (بديل للبلوتوث)") }
                qrError?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                Text("جهازك: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} — Android ${android.os.Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodySmall)
            }
        }
        last?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(Status.arabic(it.status), style = MaterialTheme.typography.titleLarge,
                        color = if (Status.ok(it.status)) Green else Red)
                    Text(fmtTime(it.time))
                }
            }
        }
    }
}

@Composable
fun ProfileView(p: StudentProfile?, group: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("بيانات الطالب", style = MaterialTheme.typography.headlineSmall)
        if (p == null) { Text(WAITING); return }
        listOf("الاسم" to p.name, "الرقم الجامعي" to p.studentId, "الكلية" to p.faculty,
            "التخصص" to p.major, "المستوى" to p.level, "الشعبة" to p.section)
            .let { if (group.isNotBlank()) it + ("مجموعة العملي" to group) else it }
            .forEach { Text("${it.first}: ${it.second}") }
    }
}

@Composable
fun CoursesView(p: StudentProfile?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("المواد", style = MaterialTheme.typography.headlineSmall)
        if (p == null) { Text(WAITING); return }
        if (p.courses.isEmpty()) Text("لا توجد مواد مسجلة")
        Text("تظهر هنا موادك كما سجّلتها الإدارة، وتتحدّث عند اتصالك بالإنترنت.", style = MaterialTheme.typography.bodySmall)
        p.courses.forEach { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }
    }
}

@Composable
fun HistoryView(store: StudentStore) {
    val last by ResultBus.last.collectAsState()
    val items = remember(last) { store.history() }
    Column {
        Text("سجل الحضور", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(Status.arabic(it.status), color = if (Status.ok(it.status)) Green else Red)
                        Text(fmtTime(it.time), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsView(store: StudentStore, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val tag = remember { store.credentials()?.tag ?: "" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("إعدادات", style = MaterialTheme.typography.headlineSmall)
        Text("رمز الجهاز: $tag", style = MaterialTheme.typography.bodySmall)
        Text("ارتباط الكود بهذا الجهاز يتم عند أول تسجيل حضور ناجح مع الدكتور. إن غيّرت هاتفك أو فقدته اطلب كودًا جديدًا من الإدارة.")
        OutlinedButton(onClick = { confirm = true }) { Text("إعادة إدخال الكود / مسح البيانات") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("مسح") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } },
        text = { Text("سيُمسح الكود وبياناتك وسجلك من هذا الهاتف. لن تستطيع تسجيل الحضور حتى تُدخل كودك من جديد (يلزم إنترنت للتحقق منه).") }
    )
}

// ---------- الجدول ----------
/** ألوان جدول الكلية: أزرق فاتح للنظري، أزرق أغمق للعملي، أصفر للتعويضية، برتقالي للاختبارات. */
private fun ttColor(e: TEntry): Color = when {
    e.kind == TKind.EXAM -> Color(0xFFFFC000)
    e.kind == TKind.MAKEUP -> Color(0xFFFFEE58)
    e.practical -> Color(0xFF8EAADB)
    else -> Color(0xFFC9DAF8)
}

private val DAY_ORDER = listOf(6, 0, 1, 2, 3, 4, 5)   // السبت أولًا كجدول الكلية

@Composable
private fun TtCard(e: TEntry) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ttColor(e), contentColor = Color.Black)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.title + (if (e.practical) " (عملي)" else "") + when (e.kind) { TKind.MAKEUP -> " — تعويضية"; TKind.EXAM -> " — اختبار"; else -> "" },
                style = MaterialTheme.typography.titleSmall)
            Text(Timetable.timeRange(e) + if (e.section.isNotBlank() || e.group.isNotBlank()) " • ${Timetable.audience(e)}" else "", style = MaterialTheme.typography.bodySmall)
            if (e.teacher.isNotBlank()) Text(e.teacher, style = MaterialTheme.typography.bodySmall)
            if (e.room.isNotBlank()) Text(e.room, style = MaterialTheme.typography.bodySmall)
            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TimetableView(store: StudentStore) {
    val version by ResultBus.profileVersion.collectAsState()
    val status by ResultBus.sync.collectAsState()
    val entries = remember(version) { store.timetable() }
    val updated = remember(version) { store.ttUpdated }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("جدولي", style = MaterialTheme.typography.headlineSmall)
            Text(if (updated > 0) "آخر تحديث: ${fmtTime(updated)}" else "لم يصل جدولك بعد", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("نظري" to Color(0xFFC9DAF8), "عملي" to Color(0xFF8EAADB), "تعويضية" to Color(0xFFFFEE58), "اختبار" to Color(0xFFFFC000)).forEach { (t, c) ->
                    Surface(color = c, shape = MaterialTheme.shapes.small) {
                        Text(t, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            OutlinedButton(onClick = {
                loading = true
                scope.launch { ResultBus.sync.value = OnlineSync.refreshNow(store); loading = false }
            }, enabled = !loading) { Text(if (loading) "جارٍ التحديث…" else "تحديث الجدول (يلزم إنترنت)") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (entries.isEmpty()) Text("لا توجد خانات في جدول تخصصك ومستواك بعد. يضيفها المسؤول وتصلك تلقائيًا عند اتصالك بالإنترنت.", style = MaterialTheme.typography.bodySmall)
        }
        DAY_ORDER.forEach { d ->
            val es = entries.filter { it.day == d }
            if (es.isNotEmpty()) {
                item { Text(Schedule.DAYS[d], style = MaterialTheme.typography.titleMedium) }
                items(es) { e -> TtCard(e) }
            }
        }
    }
}
