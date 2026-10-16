package com.uniatt.doctor

import android.content.Context
import com.uniatt.core.CountCheck
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.uniatt.core.AttendanceSheet
import com.uniatt.core.AttendanceSheetData
import com.uniatt.core.SheetLecture
import com.uniatt.core.fmtTime
import com.uniatt.core.pdf.SheetPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

@Composable
private fun F(label: String, v: String, pw: Boolean = false, on: (String) -> Unit) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None)

private fun shareFile(ctx: Context, f: File, title: String, mime: String = "application/octet-stream") {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, title))
}

@Composable
fun DoctorApp(vm: DoctorVM) {
    val logged by vm.loggedIn.collectAsState()
    val msg by vm.message.collectAsState()
    MaterialTheme {
        AppBackground {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    if (!logged) LoginScreen(vm) else MainScreen(vm)
                    msg?.let {
                        AlertDialog(onDismissRequest = { vm.message.value = null },
                            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text("حسنًا") } },
                            text = { Text(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(vm: DoctorVM) {
    val has = remember { vm.hasAccount() }
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(48.dp))
        Text(if (has) "تسجيل الدخول" else "إنشاء الرمز السري", style = MaterialTheme.typography.headlineMedium)
        if (!has) Text("رمز يقفل التطبيق على هذا الهاتف (4 خانات على الأقل). مواد الدكتور وطلابه تصل تلقائيًا من المسؤول بعد مسح QR.")
        F("الرمز السري", pin, pw = true) { pin = it }
        Button(
            onClick = { if (has) vm.login(pin) else vm.register(pin) },
            enabled = pin.length >= 4,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (has) "دخول" else "إنشاء") }
    }
}

@Composable
fun MainScreen(vm: DoctorVM) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("المواد", "مباشر", "الكشف", "السجل", "التقارير")
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text("•") },
                        label = { Text(t, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            when (tab) {
                0 -> CoursesScreen(vm)
                1 -> LiveScreen(vm)
                2 -> SheetScreen(vm)
                3 -> HistoryScreen(vm)
                else -> ReportsScreen(vm)
            }
        }
    }
}

@Composable
fun SelectedCourse(vm: DoctorVM): CourseSection? {
    val courses by vm.courses.collectAsState()
    val sel by vm.selectedCourseId.collectAsState()
    return courses.firstOrNull { it.id == sel }
}

@Composable
fun CoursesScreen(vm: DoctorVM) {
    val courses by vm.courses.collectAsState()
    val sel by vm.selectedCourseId.collectAsState()
    val paired by vm.paired.collectAsState()
    val linked by vm.linked.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val info by vm.syncInfo.collectAsState()
    val ver by vm.version.collectAsState()
    var confirmWipe by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { res -> res.contents?.let { vm.pairFromQr(it) } }
    fun scan() = scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt("وجّه الكاميرا نحو QR في هاتف المسؤول").setBeepEnabled(false).setOrientationLocked(false))

    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        confirmButton = { TextButton(onClick = { confirmWipe = false; vm.unpairAndWipe() }) { Text("مسح") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("إلغاء") } },
        text = { Text("سيُفك ارتباط هذا الهاتف بالمسؤول وتُمسح المواد والطلاب وسجلات الحضور منه. تأكد أن الحضور رُفع للمسؤول أولًا.") }
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الربط بالمسؤول", style = MaterialTheme.typography.titleSmall)
                    if (!paired) {
                        Text("اطلب من المسؤول أن يعرض لك QR من تطبيقه (تبويب الدكاترة)، ثم صوّره هنا مرة واحدة فقط. بعدها يصلك اسمك ومواد شعبك وطلابك وأي تعديل تلقائيًا عند اتصالك بالإنترنت.")
                        Button(onClick = { scan() }, modifier = Modifier.fillMaxWidth()) { Text("مسح QR") }
                    } else {
                        key(ver) {
                            Text(
                                if (linked) "✓ ${vm.doctorName.ifBlank { vm.doctorId }} — إصدار الكشف ${vm.epoch}"
                                else "✓ تمّ الربط — بانتظار وصول كشفك",
                                color = if (linked) Green else Color.Unspecified
                            )
                        }
                        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.syncNow(manual = true) }, enabled = !syncing) { Text(if (syncing) "جارٍ المزامنة…" else "مزامنة الآن") }
                            OutlinedButton(onClick = { scan() }) { Text("مسح QR جديد") }
                        }
                        TextButton(onClick = { confirmWipe = true }) { Text("فك الارتباط ومسح البيانات", color = Red) }
                    }
                }
            }
            Text("المواد والشعب والجدول", style = MaterialTheme.typography.headlineSmall)
            Text("المواد ومواعيدها يحدّدها المسؤول وتتحدّث تلقائيًا.", style = MaterialTheme.typography.bodySmall)
            if (courses.isEmpty()) Text("لا توجد مواد بعد.")
            else Text("اضغط على مادة لاختيارها:")
        }
        items(courses) { c ->
            Card(Modifier.fillMaxWidth().clickable { vm.selectedCourseId.value = c.id },
                colors = CardDefaults.cardColors(
                    containerColor = if (c.id == sel) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${c.code} — ${c.name} / شعبة ${c.section}")
                    val sched = key(ver) { vm.scheduleText(c.id) }
                    Text(if (sched.isBlank()) "لا مواعيد محدّدة بعد" else sched, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * عدّ القاعة: يعدّ الدكتور الطلاب الموجودين فعليًا ويقارنهم بالمسجَّلين (تنبيه فقط).
 * يكشف الطالب الواقف خارج القاعة أو المسجَّل عن بُعد؛ ويُحفظ العدد مع المحاضرة ويصل المسؤول.
 */
@Composable
fun HeadcountCard(registered: Int, counted: Int, onChange: (Int) -> Unit) {
    val r = CountCheck.check(registered, counted)
    val color = when (r.level) {
        CountCheck.Level.OK -> Green
        CountCheck.Level.MORE, CountCheck.Level.LESS -> Red
        CountCheck.Level.NONE -> Color.Unspecified
    }
    var text by remember(counted) { mutableStateOf(if (counted >= 0) counted.toString() else "") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("عدّ القاعة", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onChange(((if (counted < 0) 0 else counted) - 1).coerceAtLeast(0)) }) { Text("−") }
                OutlinedTextField(value = text, onValueChange = { v ->
                    text = v.filter(Char::isDigit).take(4)
                    text.toIntOrNull()?.let(onChange)
                }, label = { Text("عدد الحاضرين فعليًا") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onChange((if (counted < 0) 0 else counted) + 1) }) { Text("＋") }
            }
            Text(r.text, style = MaterialTheme.typography.bodySmall, color = color)
            if (counted >= 0) TextButton(onClick = { onChange(-1) }) { Text("مسح العدد") }
        }
    }
}

@Composable
fun LiveScreen(vm: DoctorVM) {
    val active by vm.active.collectAsState()
    val events by vm.events.collectAsState()
    val live by vm.liveAttendance.collectAsState()
    val liveStudents by vm.liveStudents.collectAsState()
    val liveCounted by vm.liveCounted.collectAsState()
    var manage by remember { mutableStateOf(false) }
    val course = SelectedCourse(vm)
    var minutes by remember { mutableStateOf("15") }
    val bleOn by vm.bleOn.collectAsState()
    val bleState by vm.bleState.collectAsState()
    val bleClients by vm.bleClients.collectAsState()
    val qrOn by vm.qrOn.collectAsState()
    val qrText by vm.qrChallenge.collectAsState()
    val scanHolder = remember { mutableListOf<() -> Unit>() }
    val scanOptions = remember {
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("وجّه الكاميرا نحو رمز إجابة الطالب").setBeepEnabled(true).setOrientationLocked(false)
    }
    val replyScanner = rememberLauncherForActivityResult(ScanContract()) { res ->
        // متتابع: بعد كل طالب يُعاد فتح الماسح تلقائيًا حتى تضغط رجوع
        res.contents?.let { vm.onQrReply(it); scanHolder.firstOrNull()?.invoke() }
    }
    scanHolder.clear(); scanHolder.add { replyScanner.launch(scanOptions) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الحضور المباشر", style = MaterialTheme.typography.headlineSmall)
        if (active == null) {
            val ver by vm.version.collectAsState()
            var now by remember { mutableStateOf<Pair<CourseSection, Int>?>(null) }
            LaunchedEffect(ver, active) { now = vm.nowSlot() }
            now?.let { (c, left) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("حسب الجدول الآن: ${c.code} ${c.name} / شعبة ${c.section}", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = { vm.selectedCourseId.value = c.id; vm.startSession(c, left) },
                            modifier = Modifier.fillMaxWidth()) { Text("بدء محاضرة الآن (حتى نهاية الموعد: $left دقيقة)") }
                    }
                }
            }
            if (now == null) Text("لا محاضرة الآن حسب الجدول (أو لم يُتأكد من الوقت بعد): لا يُبدأ الحضور إلا داخل موعد المادة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(course?.let { "المادة: ${it.name} / شعبة ${it.section}" } ?: "اختر مادة من تبويب «المواد»")
            F("مدة الجلسة (دقائق)", minutes) { minutes = it.filter(Char::isDigit) }
            Text("تُقصّ المدة تلقائيًا عند نهاية موعد المحاضرة، ويُعتمد وقت الخادم لا ساعة الهاتف.", style = MaterialTheme.typography.bodySmall)
            val mins = minutes.toIntOrNull()?.coerceIn(1, 240) ?: 15
            var today by remember { mutableIntStateOf(0) }
            var confirmNew by remember { mutableStateOf(false) }
            var byTable by remember { mutableStateOf(false) }
            LaunchedEffect(course?.id, ver) { today = course?.let { vm.lecturesToday(it) } ?: 0; byTable = course?.let { vm.nextByScheduleNow(it) } ?: false }
            Button(onClick = { vm.startSession(course!!, mins) },
                enabled = course != null, modifier = Modifier.fillMaxWidth()) {
                Text(if (byTable) "بدء المحاضرة التالية حسب الجدول (المحاضرة ${today + 1} اليوم)" else if (today > 0) "استئناف محاضرة اليوم" else "بدء الحضور")
            }
            if (byTable) Text("الجدول يُظهر أنك الآن في موعد لاحق لمحاضرة سبق أن بدأتها اليوم، فتُحتسب محاضرة متتالية جديدة تلقائيًا.", style = MaterialTheme.typography.bodySmall)
            Text("إن كانت لهذه المادة جلسة اليوم (أوقفتها) فتُستأنف نفسها: لا يتكرر تسجيل أي طالب ولا تُحتسب المحاضرة مرتين.", style = MaterialTheme.typography.bodySmall)
            if (today > 0 && !byTable) {
                OutlinedButton(onClick = { confirmNew = true }, enabled = course != null, modifier = Modifier.fillMaxWidth()) { Text("بدء محاضرة متتالية جديدة (المحاضرة ${today + 1} اليوم)") }
                Text("إن كانت لديك محاضرتان متتاليتان لنفس الشعبة/المجموعة في اليوم نفسه فابدأ الثانية من هنا لتُحتسب عمودًا مستقلًا في الكشف (وسم «(2)»).", style = MaterialTheme.typography.bodySmall)
            }
            if (confirmNew && course != null) AlertDialog(
                onDismissRequest = { confirmNew = false },
                title = { Text("محاضرة متتالية جديدة؟") },
                text = { Text("ستُسجَّل المحاضرة ${today + 1} لهذه المادة اليوم كعمود مستقل في الكشف، ويحضر الطلاب فيها من جديد. لا يمكن دمجها مع المحاضرة السابقة بعد ذلك.") },
                confirmButton = { TextButton(onClick = { confirmNew = false; vm.startSession(course, mins, newLecture = true) }) { Text("نعم، ابدأ") } },
                dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("إلغاء") } }
            )
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("وضع استقبال الحضور", style = MaterialTheme.typography.titleMedium)
                    Text("المادة: ${active!!.course.name} | حضر: ${live.count { it.status != AttStatus.CANCELLED }} من ${liveStudents.size}")
                    Text("تنتهي: ${fmtTime(active!!.endsAt)}")
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { manage = true }, modifier = Modifier.fillMaxWidth()) { Text("الحاضرون — تحضير يدوي / إلغاء تحضير") }
                }
            }
            if (manage) active?.let { a ->
                AttendanceManageDialog(vm, a.sessionId.toString(), a.course, liveStudents, live) { manage = false }
            }
            active?.let { a ->
                HeadcountCard(live.count { it.status != AttStatus.CANCELLED }, liveCounted) { vm.setCounted(a.sessionId.toString(), it) }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = bleOn, onCheckedChange = { vm.bleOn.value = it })
                        Text("الحضور التلقائي بالبلوتوث", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "الحالة: ${bleState.ifBlank { "—" }} — اتصالات وصلت: $bleClients\nالطلاب (المفعّل عندهم التسجيل التلقائي) يُسجَّلون دون أي لمسة؛ قد تستغرق القاعات الكبيرة حتى نصف دقيقة. أبقِ هذه الشاشة مفتوحة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bleState.startsWith("شغّال")) Green else Color.Unspecified
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = qrOn, onCheckedChange = { vm.qrOn.value = it; vm.setQrMode(it) })
                        Text("تسجيل بالـQR (بديل للبلوتوث)", style = MaterialTheme.typography.titleSmall)
                    }
                    if (qrOn) {
                        Text("① الطالب يفتح تطبيقه ← «تسجيل بالـQR» ← يمسح الرمز أدناه (يتغيّر كل 10 ثوانٍ).\n② يعرض الطالب رمز إجابة ← اضغط «مسح إجابة طالب» ووجّه الكاميرا نحوه. يبقى الماسح مفتوحًا للطالب التالي حتى تضغط رجوع.",
                            style = MaterialTheme.typography.bodySmall)
                        qrText?.let { QrCode(it, Modifier.size(260.dp)) }
                        Button(onClick = { scanHolder.firstOrNull()?.invoke() }, modifier = Modifier.fillMaxWidth()) { Text("مسح إجابة طالب") }
                    }
                }
            }
            OutlinedButton(onClick = { vm.endSession() }, modifier = Modifier.fillMaxWidth()) { Text("إنهاء الجلسة") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(events) { Text(it.text, color = if (it.ok) Green else Red) }
        }
    }
}

// ---------- كشف حضور وغياب الطلاب (كالكشف الورقي) ----------
private val SheetBlue = Color(0xFF1F3FD0)
private val SheetGreen = Color(0xFFE2EFDA)
private val SheetLine = Color(0xFF9E9E9E)

@Composable
private fun SheetCell(
    text: String, w: Dp, h: Dp = 34.dp, bg: Color = Color.Transparent, bold: Boolean = false,
    color: Color = Color.Black, start: Boolean = false, onClick: (() -> Unit)? = null
) {
    Box(
        Modifier.width(w).height(h).background(bg).border(0.5.dp, SheetLine)
            .let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 3.dp),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else null, color = color, maxLines = 2,
            textAlign = if (start) TextAlign.Start else TextAlign.Center)
    }
}

/** جدول الكشف: م | اسم الطالب | 1…16 (محاضرات) | ملاحظات. ✓ = حضر. يمرَّر أفقيًا. */
@Composable
private fun SheetTable(data: AttendanceSheetData, page: Int, notes: Map<String, String>, onLecture: ((SheetLecture) -> Unit)?) {
    val from = page * AttendanceSheet.PAGE
    Column(Modifier.horizontalScroll(rememberScrollState()).border(2.dp, SheetBlue, RoundedCornerShape(8.dp))) {
        Row {
            SheetCell("م", 32.dp, 56.dp, SheetGreen, true)
            SheetCell("اسم الطالب", 170.dp, 56.dp, SheetGreen, true)
            for (i in 0 until AttendanceSheet.PAGE) {
                val l = data.lectures.getOrNull(from + i)
                Box(
                    Modifier.width(34.dp).height(56.dp).background(SheetGreen).border(0.5.dp, SheetLine)
                        .let { if (l != null && onLecture != null) it.clickable { onLecture(l) } else it },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${from + i + 1}${l?.tag ?: ""}", fontSize = if (l != null && l.part > 0) 10.sp else 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        if (l != null) Text(l.dayKey.substring(5).replace('-', '/'), fontSize = 9.sp, color = Color.Black)
                    }
                }
            }
            SheetCell("ملاحظات", 130.dp, 56.dp, SheetGreen, true)
        }
        data.rows.forEach { r ->
            Row {
                SheetCell("${r.no}", 32.dp)
                SheetCell(r.name, 170.dp, start = true)
                for (i in 0 until AttendanceSheet.PAGE) {
                    val idx = from + i
                    SheetCell(if (idx < data.total && r.present[idx]) "✓" else "", 34.dp, color = Green, bold = true)
                }
                SheetCell(notes[r.studentId] ?: "", 130.dp)
            }
        }
    }
}

@Composable
fun SheetScreen(vm: DoctorVM) {
    val course = SelectedCourse(vm)
    val students by vm.students.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val records by vm.courseAttendance.collectAsState()
    val att by vm.historyAttendance.collectAsState()
    var page by remember { mutableIntStateOf(0) }
    var manage by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = remember(sessions, students, records) {
        AttendanceSheet.build(
            sessions.map { it.sessionId to it.startedAt }, students.map { it.studentId to it.name },
            records.map { it.sessionId to it.studentId }.toHashSet(),
            sessions.associate { it.sessionId to it.part }
        )
    }
    // ملاحظات PDF بلا رموز خاصة (⏳ قد لا يحملها خط الطباعة)
    val pdfNotes = remember(data) { data.rows.associate { r -> r.studentId to "${r.count} من ${data.total} (${AttendanceSheet.percent(r.count, data.total)}%)" } }
    val notes = remember(data, students) {
        val byId = students.associateBy { it.studentId }
        data.rows.associate { r -> r.studentId to pdfNotes[r.studentId].orEmpty() + if (byId[r.studentId]?.publicKeyHex == null) " ⏳" else "" }
    }
    val pdfTitle = course?.let { "كشف حضور وغياب الطلاب — ${it.name}" } ?: ""
    val pdfSub = course?.let { "شعبة/مجموعة ${it.section}" + (if (it.code.isNotBlank()) " — ${it.code}" else "") + " — الطلاب: ${students.size}" } ?: ""
    val pdfName = course?.let { SheetPdf.fileName("attendance_${it.name}_${it.section}") } ?: "attendance.pdf"
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { u -> scope.launch { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(u)?.use { SheetPdf.write(it, pdfTitle, pdfSub, data, pdfNotes) } } } }
    }
    val cur = page.coerceIn(0, data.pages - 1)
    manage?.let { sid -> if (course != null) AttendanceManageDialog(vm, sid, course, students, att.filter { it.sessionId == sid }) { manage = null } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("كشف حضور وغياب الطلاب", style = MaterialTheme.typography.headlineSmall)
        if (course == null) { Text("اختر مادة من تبويب «المواد».") ; return@Column }
        Text("${course.code} — ${course.name} / شعبة ${course.section}", style = MaterialTheme.typography.titleSmall)
        Text("الطلاب: ${students.size} — المحاضرات المنعقدة: ${data.total} — أجهزة مرتبطة: ${students.count { it.publicKeyHex != null }} (⏳ = لم يرتبط جهازه بعد)",
            style = MaterialTheme.typography.bodySmall)
        Text("المحاضرة = يوم واحد للمادة: لا يتكرر الطالب فيها حتى لو أُوقف الحضور ثم أُعيد، والمحاضرة المتتالية الثانية في اليوم عمود مستقل (2). اضغط رقم محاضرة لتحضير أو إلغاء تحضير يدويًا.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pdfSaver.launch(pdfName) }, enabled = students.isNotEmpty()) { Text("تصدير PDF") }
            OutlinedButton(onClick = {
                scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        File(ctx.cacheDir, "exports").apply { mkdirs() }.let { d -> File(d, pdfName).also { out -> out.outputStream().use { SheetPdf.write(it, pdfTitle, pdfSub, data, pdfNotes) } } }
                    }
                    shareFile(ctx, f, "مشاركة الكشف", "application/pdf")
                }
            }, enabled = students.isNotEmpty()) { Text("مشاركة PDF") }
        }
        if (data.pages > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in 0 until data.pages) {
                val a = p * AttendanceSheet.PAGE + 1; val b = minOf((p + 1) * AttendanceSheet.PAGE, data.total)
                FilterChip(selected = cur == p, onClick = { page = p }, label = { Text("$a–$b") })
            }
        }
        if (students.isEmpty()) Text("لا طلاب في هذه المادة بعد.")
        else SheetTable(data, cur, notes) { l -> vm.historySession.value = l.sessionIds.last(); manage = l.sessionIds.last() }
    }
}

@Composable
fun HistoryScreen(vm: DoctorVM) {
    val sessions by vm.sessions.collectAsState()
    val open by vm.historySession.collectAsState()
    val att by vm.historyAttendance.collectAsState()
    val students by vm.students.collectAsState()
    val courses by vm.courses.collectAsState()
    var manage by remember { mutableStateOf<SessionEntity?>(null) }
    manage?.let { ms ->
        val c = courses.firstOrNull { it.id == ms.courseSectionId }
        if (c != null && open == ms.sessionId) AttendanceManageDialog(vm, ms.sessionId, c, students, att) { manage = null }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("سجل المحاضرات", style = MaterialTheme.typography.headlineSmall) }
        items(sessions) { s ->
            Card(Modifier.fillMaxWidth().clickable { vm.historySession.value = if (open == s.sessionId) null else s.sessionId }) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmtTime(s.startedAt))
                    Text("المدة: ${s.durationMin} دقيقة", style = MaterialTheme.typography.bodySmall)
                    if (open == s.sessionId) {
                        val present = att.filter { it.status != AttStatus.CANCELLED }
                        Text("الحاضرون: ${present.size}")
                        HeadcountCard(present.size, s.countedCount) { vm.setCounted(s.sessionId, it) }
                        present.forEach {
                            Text("• ${it.studentId} ${it.studentName} — ${fmtTime(it.timestamp)}" + if (it.status == AttStatus.MANUAL) " (يدوي)" else "",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = { manage = s }, modifier = Modifier.fillMaxWidth()) { Text("تعديل الحضور — تحضير / إلغاء") }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportsScreen(vm: DoctorVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by vm.report.collectAsState()
    val sel by vm.selectedCourseId.collectAsState()
    val linked by vm.linked.collectAsState()
    val course = SelectedCourse(vm)
    LaunchedEffect(sel) { vm.loadReport() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.csv(rows)).toByteArray()) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("تقارير الحضور" + (course?.let { " — ${it.name}" } ?: ""), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.loadReport() }) { Text("تحديث") }
            Button(onClick = { launcher.launch("attendance_${course?.code ?: "report"}.csv") }, enabled = rows.isNotEmpty()) { Text("تصدير CSV") }
        }
        val info by vm.syncInfo.collectAsState()
        val syncing by vm.syncing.collectAsState()
        Text("يُرفع الحضور للمسؤول تلقائيًا عند اتصال هاتفك بالإنترنت.", style = MaterialTheme.typography.bodySmall)
        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.syncNow(manual = true) }, enabled = linked && !syncing, modifier = Modifier.fillMaxWidth()) {
            Text(if (syncing) "جارٍ المزامنة…" else "رفع الحضور الآن")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows) {
                val pct = if (it.total == 0) 0 else it.attended * 100 / it.total
                Text("${it.name}: ${it.attended}/${it.total} ($pct%)")
            }
        }
    }
}

// ---------- تحضير يدوي وإلغاء التحضير ----------
private class AttRow(val id: String, val name: String, val student: StudentEntity?)

/**
 * يدير حضور جلسة (جارية أو سابقة): يحضّر أي طالب من كشف المادة، أو يلغي تحضير من سُجّل خطأً، أو يعيد من أُلغي.
 * الإلغاء يزيل الطالب من التقارير ومن بيانات المسؤول عند الرفع التالي، ولا يعيد هاتفه تسجيل نفسه تلقائيًا.
 */
@Composable
private fun AttendanceManageDialog(
    vm: DoctorVM, sessionId: String, course: CourseSection,
    students: List<StudentEntity>, records: List<AttendanceRecord>, onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }          // 0 الكل، 1 الحاضرون، 2 غير الحاضرين
    val byId = records.associateBy { it.studentId }
    // كشف المادة + أي طالب له سجل وخرج من الكشف
    val rows = students.map { AttRow(it.studentId, it.name, it) } +
        records.filter { r -> students.none { it.studentId == r.studentId } }.map { AttRow(it.studentId, it.studentName, null) }
    fun present(r: AttRow) = byId[r.id]?.let { it.status != AttStatus.CANCELLED } ?: false
    val shown = rows.filter { r ->
        (query.isBlank() || r.name.contains(query.trim(), ignoreCase = true) || r.id.contains(query.trim())) &&
            (filter == 0 || (filter == 1 && present(r)) || (filter == 2 && !present(r)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الحضور — ${course.name}") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("حاضر ${rows.count { present(it) }} من ${rows.size}", style = MaterialTheme.typography.titleSmall)
                F("بحث بالاسم أو الرقم", query) { query = it }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("الكل") })
                    FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("الحاضرون") })
                    FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("غير الحاضرين") })
                }
                LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(shown, key = { it.id }) { r ->
                        val rec = byId[r.id]
                        val here = present(r)
                        val cancelled = rec?.status == AttStatus.CANCELLED
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${r.id} — " + when {
                                    here && rec?.status == AttStatus.MANUAL -> "حاضر (يدوي)"
                                    here -> "حاضر ✓"
                                    cancelled -> "أُلغي تحضيره"
                                    else -> "غائب"
                                }, style = MaterialTheme.typography.bodySmall, color = if (here) Green else if (cancelled) Red else Color.Unspecified)
                            }
                            if (here) TextButton(onClick = { vm.cancelAttendance(sessionId, r.id, r.name) }) { Text("إلغاء", color = Red) }
                            else r.student?.let { st -> TextButton(onClick = { vm.markPresent(sessionId, course, st) }) { Text(if (cancelled) "إعادة" else "تحضير") } }
                        }
                    }
                }
            }
        }
    )
}
