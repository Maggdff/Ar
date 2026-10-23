package com.uniatt.admin

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.uniatt.core.AttendanceSheet
import com.uniatt.core.AttendanceSheetData
import com.uniatt.core.Codes
import com.uniatt.core.GroupSheet
import com.uniatt.core.NameFix
import com.uniatt.core.Schedule
import com.uniatt.core.TEntry
import com.uniatt.core.TKind
import com.uniatt.core.Timetable
import com.uniatt.core.pdf.SheetPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFB26A00)

@Composable
private fun F(label: String, v: String, lines: Int = 1, on: (String) -> Unit) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        modifier = Modifier.fillMaxWidth())

private fun shareFile(ctx: Context, f: File, title: String, mime: String = "application/octet-stream") {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val i = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, title))
}

private fun readUri(ctx: Context, uri: android.net.Uri): ByteArray? =
    try { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }

@Composable
fun AdminApp(vm: AdminVM) {
    val msg by vm.message.collectAsState()
    MaterialTheme {
        AppBackground {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.fillMaxSize()) {
                    Main(vm)
                    msg?.let {
                        AlertDialog(onDismissRequest = { vm.message.value = null },
                            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text("حسنًا") } },
                            text = { SelectionContainer { Text(it) } })
                    }
                }
            }
        }
    }
}

@Composable
fun Main(vm: AdminVM) {
    val st0 = vm.state.value
    // أول مرة: ابدأ من الإعدادات (تخصصات وشعب)، وإلا من المواد
    var tab by remember { mutableIntStateOf(if (st0.sectionNames.isEmpty()) 0 else 1) }
    val titles = listOf("الإعدادات", "المواد", "الجداول", "الدكاترة", "الطلاب", "الكشوفات", "التقارير")
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
            when (tab) { 0 -> SettingsTab(vm); 1 -> CoursesTab(vm); 2 -> TimetableTab(vm); 3 -> DoctorsTab(vm); 4 -> StudentsTab(vm); 5 -> RostersTab(vm); else -> ReportsTab(vm) }
        }
    }
}

// ---------- عناصر مشتركة ----------
@Composable
private fun Picker(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), enabled = options.isNotEmpty()) {
            Text(if (selected.isBlank()) label else "$label: $selected")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

/** قائمة مواد بخانات اختيار (يمكن اختيار أكثر من مادة). */
@Composable
private fun CoursePicks(courses: List<Course>, picked: Set<Long>, onToggle: (Long) -> Unit) {
    if (courses.isEmpty()) Text("لا توجد مواد بعد — أضفها من تبويب «المواد».", style = MaterialTheme.typography.bodySmall, color = Amber)
    courses.forEach { c ->
        Row(Modifier.fillMaxWidth().clickable { onToggle(c.id) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = c.id in picked, onCheckedChange = { onToggle(c.id) })
            Text(c.label)
        }
    }
}

private fun <T> toggle(set: Set<T>, x: T) = if (x in set) set - x else set + x

// ---------- الإعدادات: التخصصات والشعب ----------
@Composable
private fun NameListCard(title: String, hint: String, label: String, items: List<String>, onAdd: (String) -> Unit, onDelete: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { onAdd(text); text = "" }, enabled = text.isNotBlank()) { Text("إضافة") }
            }
            if (items.isEmpty()) Text("لا شيء بعد.", style = MaterialTheme.typography.bodySmall, color = Amber)
            items.forEach { n ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("• $n")
                    TextButton(onClick = { onDelete(n) }) { Text("حذف", color = Color(0xFFC62828)) }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("الإعدادات", style = MaterialTheme.typography.headlineSmall)
            Text("ابدأ من هنا: أنشئ التخصصات والشعب مرة واحدة، ثم تختارها من قوائم عند إضافة المواد والطلاب.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            NameListCard("التخصصات", "مثل: علوم الحاسب، نظم المعلومات، الهندسة…", "اسم التخصص",
                st.majors, { vm.addMajor(it) }, { vm.removeMajor(it) })
        }
        item {
            NameListCard("الشعب", "مثل: A، B، C (أو 1، 2). تُستخدم عند إضافة المواد والطلاب.", "اسم الشعبة",
                st.sectionNames, { vm.addSectionName(it) }, { vm.removeSectionName(it) })
        }
        item {
            NameListCard("المستويات", "مثل: الأول، الثاني، الثالث… (تُستخدم لربط الطالب بجدول مستواه).", "اسم المستوى",
                st.levelNames, { vm.addLevelName(it) }, { vm.removeLevelName(it) })
        }
        item {
            NameListCard("مجموعات العملي", "مثل: مج1، مج2، مج3 (لمحاضرات المعمل؛ لكل طالب مجموعته).", "اسم المجموعة",
                st.groupNames, { vm.addGroupName(it) }, { vm.removeGroupName(it) })
        }
        item {
            Text("ثم: ① أضف المواد من تبويب «المواد» ② أضف الدكاترة واختر موادهم ③ أضف الطلاب واختر تخصصهم.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- المواد والجدول ----------
@Composable
fun CoursesTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var major by remember { mutableStateOf("") }
    var practical by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf(setOf<String>()) }
    var shared by remember { mutableStateOf(false) }
    var slotFor by remember { mutableStateOf<Long?>(null) }
    var delCourse by remember { mutableStateOf<Course?>(null) }

    slotFor?.let { cid ->
        val c = st.courses.firstOrNull { it.id == cid }
        if (c != null) SlotDialog(c, onDismiss = { slotFor = null }) { day, a, b, room ->
            vm.addSlot(cid, day, a, b, room); slotFor = null
        }
    }
    delCourse?.let { c ->
        AlertDialog(
            onDismissRequest = { delCourse = null },
            confirmButton = { TextButton(onClick = { vm.deleteCourse(c.id); delCourse = null }) { Text("حذف", color = Color(0xFFC62828)) } },
            dismissButton = { TextButton(onClick = { delCourse = null }) { Text("إلغاء") } },
            text = { Text("حذف ${c.short} مع مواعيدها، وإزالتها من الطلاب والدكاترة؟ (سجلات الحضور السابقة تبقى)") }
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("المواد والجدول", style = MaterialTheme.typography.headlineSmall)
            Text("أنت من يحدّد المواد ومواعيدها؛ تصل للدكتور تلقائيًا ولا يستطيع تعديلها.", style = MaterialTheme.typography.bodySmall)
            val options = if (practical) st.groupNames else st.sectionNames
            if (st.majors.isEmpty()) Text("⚠ أنشئ التخصصات أولًا من تبويب «الإعدادات».", color = Amber)
            if (options.isEmpty())
                Text(if (practical) "⚠ أنشئ مجموعات العملي أولًا من تبويب «الإعدادات»." else "⚠ أنشئ الشعب أولًا من تبويب «الإعدادات».", color = Amber)
            F("اسم المادة", name) { name = it }
            Picker("التخصص", st.majors, major) { major = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("النوع:")
                FilterChip(selected = !practical, onClick = { if (practical) { practical = false; picks = emptySet<String>(); shared = false } }, label = { Text("نظري") })
                FilterChip(selected = practical, onClick = { if (!practical) { practical = true; picks = emptySet<String>(); shared = false } }, label = { Text("عملي") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (practical) "رقم المجموعة (اختر واحدة أو أكثر):" else "رقم الشعبة (اختر واحدة أو أكثر):",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (options.size > 1) TextButton(onClick = { picks = if (picks.size == options.size) emptySet<String>() else options.toSet() }) {
                    Text(if (picks.size == options.size) "إلغاء الكل" else "تحديد الكل")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { o -> FilterChip(selected = o in picks, onClick = { picks = toggle(picks, o) }, label = { Text(o) }) }
            }
            if (!practical && picks.size >= 2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = shared, onCheckedChange = { shared = it })
                    Text("مادة مشتركة بين الشعب المختارة: موعد وجلسة حضور واحدة يحضرها طلاب كل الشعب معًا (بدل سجل منفصل لكل شعبة)",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            Button(onClick = {
                vm.addCourseForm(name, major, practical, options.filter { it in picks }, shared && !practical && picks.size >= 2)
                name = ""; picks = emptySet<String>(); shared = false
            }, enabled = name.isNotBlank() && major.isNotBlank() && picks.isNotEmpty()) { Text("إضافة المادة") }
            Text(
                if (shared && !practical && picks.size >= 2) "يُنشأ سجل واحد مشترك بين الشعب المختارة، ويصل الدكتور المسنَد إليه تلقائيًا."
                else "يُنشأ سجل لكل شعبة (نظري) أو مجموعة (عملي) تختارها، ويصل الدكتور المسنَد إليه تلقائيًا.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(st.courses.sortedWith(compareBy({ it.major }, { it.name }, { it.practical }, { it.section }))) { c ->
            val slots = st.slots.filter { it.courseId == c.id }.sortedWith(compareBy({ it.day }, { it.startMin }))
            val docs = st.doctors.filter { c.id in it.courseIds }.joinToString("، ") { it.name }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (c.isLegacy) c.short else c.name, style = MaterialTheme.typography.titleSmall)
                    if (!c.isLegacy) Text(listOf(c.kindName, c.place, c.major).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodyMedium)
                    Text(if (docs.isBlank()) "لا دكتور مسنَد بعد (من تبويب الدكاترة)" else "الدكتور: $docs", style = MaterialTheme.typography.bodySmall)
                    if (slots.isEmpty()) Text("لا مواعيد بعد", style = MaterialTheme.typography.bodySmall, color = Amber)
                    slots.forEach { sl ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("• ${Schedule.DAYS[sl.day]} ${Schedule.fmt(sl.startMin)}–${Schedule.fmt(sl.endMin)}" +
                                if (sl.room.isNotBlank()) " (${sl.room})" else "")
                            TextButton(onClick = { vm.deleteSlot(sl.id) }) { Text("حذف") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { slotFor = c.id }) { Text("＋ موعد") }
                        TextButton(onClick = { delCourse = c }) { Text("حذف المادة", color = Color(0xFFC62828)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotDialog(c: Course, onDismiss: () -> Unit, onAdd: (Int, String, String, String) -> Unit) {
    var day by remember { mutableIntStateOf(0) }
    var from by remember { mutableStateOf("08:00") }
    var to by remember { mutableStateOf("09:30") }
    var room by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onAdd(day, from, to, room) }, enabled = from.isNotBlank() && to.isNotBlank()) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        title = { Text("موعد ${c.short}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Schedule.DAYS.forEachIndexed { i, d -> FilterChip(selected = day == i, onClick = { day = i }, label = { Text(d) }) }
                }
                F("من (مثل 08:00)", from) { from = it }
                F("إلى (مثل 09:30)", to) { to = it }
                F("القاعة (اختياري)", room) { room = it }
            }
        }
    )
}

// ---------- الدكاترة ----------
@Composable
private fun DoctorDialog(existing: Doctor?, courses: List<Course>, onDismiss: () -> Unit, onSave: (String, List<Long>) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var picked by remember { mutableStateOf(existing?.courseIds?.toSet() ?: emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة دكتور" else "تعديل الدكتور") },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), picked.toList()) }, enabled = name.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                F("اسم الدكتور", name) { name = it }
                Text("المواد التي يدرّسها (اختر واحدة أو أكثر):", style = MaterialTheme.typography.titleSmall)
                CoursePicks(courses, picked) { picked = toggle(picked, it) }
            }
        }
    )
}

private fun fmtTime(t: Long): String = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date(t))

@Composable
fun DoctorsTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val info by vm.syncInfo.collectAsState()
    var qrFor by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Doctor?>(null) }
    var adding by remember { mutableStateOf(false) }
    var delDoc by remember { mutableStateOf<Doctor?>(null) }

    if (adding) DoctorDialog(null, st.courses, { adding = false }) { n, ids -> vm.saveDoctor(null, n, ids); adding = false }
    editing?.let { d -> DoctorDialog(d, st.courses, { editing = null }) { n, ids -> vm.saveDoctor(d.doctorId, n, ids); editing = null } }
    delDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { delDoc = null },
            confirmButton = { TextButton(onClick = { vm.deleteDoctor(d.doctorId); delDoc = null }) { Text("حذف", color = Color(0xFFC62828)) } },
            dismissButton = { TextButton(onClick = { delDoc = null }) { Text("إلغاء") } },
            text = { Text("حذف ${d.name}؟ سينقطع هاتفه. سجلات حضوره السابقة تبقى عندك.") }
        )
    }
    qrFor?.let { id ->
        val d = st.doctors.firstOrNull { it.doctorId == id }
        val payload = remember(id, st.relayUrl, d?.mailbox, d?.code) { vm.pairingText(id) }
        if (d != null && payload != null) AlertDialog(
            onDismissRequest = { qrFor = null },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text("تم") } },
            title = { Text("QR ${d.name}") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QrCode(payload, Modifier.size(260.dp))
                    Text("يفتح الدكتور تطبيقه ← تبويب «المواد» ← «مسح QR» ويصوّر هذا الرمز مرة واحدة فقط. بعدها يتحدّث كل شيء تلقائيًا.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("⚠ الرمز يحوي مفتاح الدكتور — لا تصوّره لأحد غيره.", style = MaterialTheme.typography.bodySmall, color = Amber)
                }
            }
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("الدكاترة (${st.doctors.size})", style = MaterialTheme.typography.headlineSmall)
            Text("اكتب اسم الدكتور واختر موادّه من القائمة، ثم اعرض له QR ليصوّره مرة واحدة.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { adding = true }) { Text("＋ إضافة دكتور") }
                OutlinedButton(onClick = { vm.syncNow(manual = true) }, enabled = !syncing) { Text(if (syncing) "جارٍ المزامنة…" else "مزامنة الآن") }
            }
            if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
            if (st.courses.isEmpty()) Text("⚠ أضف المواد أولًا من تبويب «المواد».", color = Amber)
        }
        items(st.doctors) { d ->
            val mine = st.courses.filter { it.id in d.courseIds }
            val n = st.students.count { s -> s.courseIds.any { it in d.courseIds } }
            val status = when {
                d.courseIds.isEmpty() -> "اختر له مادة واحدة على الأقل (تعديل)"
                d.pushedEpoch == 0L -> "⏳ لم يُنشر كشفه بعد (يلزم اتصال بالإنترنت)"
                d.ackEpoch == 0L -> "⏳ لم يمسح QR بعد (أو لم يتصل بالإنترنت)"
                d.ackEpoch < d.pushedEpoch -> "⏳ بانتظار وصول آخر تحديث إلى هاتفه"
                else -> "✓ مرتبط ومحدَّث"
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (mine.isEmpty()) "بلا مواد" else mine.joinToString("، ") { it.short } + " • $n طالب",
                        style = MaterialTheme.typography.bodySmall)
                    Text(status, color = if (status.startsWith("✓")) Green else Color.Unspecified, style = MaterialTheme.typography.bodySmall)
                    if (d.lastPullAt > 0) Text("آخر حضور مستلم: ${fmtTime(d.lastPullAt)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { qrFor = d.doctorId }) { Text("عرض QR") }
                        OutlinedButton(onClick = { editing = d }) { Text("تعديل") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.resetDoctor(d.doctorId) }) { Text("إلغاء الربط (QR جديد)") }
                        TextButton(onClick = { delDoc = d }) { Text("حذف", color = Color(0xFFC62828)) }
                    }
                }
            }
        }
    }
}

// ---------- الطلاب ----------
@Composable
private fun StudentDialog(
    existing: Student?, majors: List<String>, sections: List<String>, courses: List<Course>,
    groups: List<String>, levels: List<String>,
    defMajor: String = "", defLevel: String = "",
    onDismiss: () -> Unit, onSave: (String, String, String, String, String, List<Long>, String) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var sid by remember { mutableStateOf("") }
    var major by remember { mutableStateOf(existing?.major ?: defMajor) }
    var section by remember { mutableStateOf(existing?.section ?: "") }
    var level by remember { mutableStateOf(existing?.level ?: defLevel) }
    var group by remember { mutableStateOf(existing?.group ?: "") }
    var picked by remember { mutableStateOf(existing?.courseIds?.toSet() ?: emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "إضافة طالب" else "تعديل الطالب") },
        confirmButton = { TextButton(onClick = { onSave(sid, name, major, section, level, picked.toList(), group) }, enabled = name.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                F("اسم الطالب", name) { name = it }
                if (existing == null) F("الرقم الجامعي (اختياري — يُولَّد تلقائيًا إن تُرك فارغًا)", sid) { sid = it }
                else Text("الرقم الجامعي: ${existing.studentId}", style = MaterialTheme.typography.bodySmall)
                Picker("التخصص", majors, major) { major = it }
                if (majors.isEmpty()) Text("أنشئ التخصصات من «الإعدادات».", style = MaterialTheme.typography.bodySmall, color = Amber)
                Picker("الشعبة", sections, section) { section = it }
                if (levels.isNotEmpty()) Picker("المستوى", levels, level) { level = it }
                else F("المستوى (اختياري)", level) { level = it }
                Picker("مجموعة العملي (اختياري)", groups, group) { group = it }
                if (group.isNotBlank()) TextButton(onClick = { group = "" }) { Text("إلغاء المجموعة") }
                Text("المواد المسجَّل فيها:", style = MaterialTheme.typography.titleSmall)
                if (section.isNotBlank() && courses.any { it.section == section })
                    TextButton(onClick = { picked = courses.filter { it.section == section }.map { it.id }.toSet() }) { Text("تسجيله في كل مواد الشعبة $section") }
                CoursePicks(courses, picked) { picked = toggle(picked, it) }
            }
        }
    )
}

@Composable
fun StudentsTab(vm: AdminVM) {
    val ctx = LocalContext.current
    val st by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val preview by vm.importPreview.collectAsState()
    var major by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AdminOps.ImportKind.GROUP) }
    var menu by remember { mutableStateOf(false) }
    var assign by remember { mutableStateOf(false) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.parsePdf(it, kind, major, level) } }
    val clipboard = LocalClipboardManager.current
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Student?>(null) }
    var bulk by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.studentsCsv()).toByteArray()) } }
    }
    val ready = major.isNotBlank() && level.isNotBlank()
    val inScope = remember(st.students, major, level) {
        st.students.filter { (major.isBlank() || it.major.trim().equals(major, true)) && (level.isBlank() || it.level.trim().equals(level, true)) }
    }

    preview?.let { p ->
        ImportDialog(st, p, { vm.importPreview.value = null }) { groups -> vm.confirmImport(groups, p.kind, p.major, p.level) }
    }
    if (assign) AssignCoursesDialog(st, major, level, { assign = false }) { ids -> vm.assignCourses(major, level, ids); assign = false }
    if (adding) StudentDialog(null, st.majors, st.sectionNames, st.courses, st.groupNames, st.levelNames,
        defMajor = major, defLevel = level, onDismiss = { adding = false }) { id, n, m, sec, lv, ids, grp ->
        vm.addStudentForm(id, n, m, sec, lv, ids, grp); adding = false
    }
    editing?.let { s ->
        StudentDialog(s, st.majors, st.sectionNames, st.courses, st.groupNames, st.levelNames, onDismiss = { editing = null }) { _, n, m, sec, lv, ids, grp ->
            vm.editStudentForm(s.studentId, n, m, sec, lv, ids, grp); editing = null
        }
    }
    val shown = remember(inScope, query) {
        if (query.isBlank()) inScope
        else inScope.filter { it.name.contains(query.trim(), true) || it.studentId.contains(query.trim(), true) }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("الطلاب (${st.students.size})", style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("① اختر التخصص ← ② اختر المستوى", style = MaterialTheme.typography.titleSmall)
                    if (st.majors.isEmpty() || st.levelNames.isEmpty())
                        Text("⚠ أنشئ التخصصات والمستويات من تبويب «الإعدادات» أولًا.", color = Amber, style = MaterialTheme.typography.bodySmall)
                    Picker("التخصص", st.majors, major) { major = it }
                    Picker("المستوى", st.levelNames, level) { level = it }
                    if (major.isNotBlank() || level.isNotBlank()) TextButton(onClick = { major = ""; level = "" }) { Text("عرض كل الطلاب") }
                    if (ready) {
                        Text("طلاب $major — $level: ${inScope.size}", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box {
                                FilledTonalIconButton(onClick = { menu = true }, enabled = !busy) { Text("📄", fontSize = 22.sp) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("كشف شعب (نظري) PDF") }, onClick = {
                                        menu = false; kind = AdminOps.ImportKind.SECTION; pdfLauncher.launch(arrayOf("application/pdf"))
                                    })
                                    DropdownMenuItem(text = { Text("كشف مجموعات عملي PDF") }, onClick = {
                                        menu = false; kind = AdminOps.ImportKind.GROUP; pdfLauncher.launch(arrayOf("application/pdf"))
                                    })
                                }
                            }
                            Text("استيراد كشف PDF: اختر منه الشعب أو مجموعات العملي", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                        Text("يمكن أن يرد الطالب في كشف الشعب وفي كشف المجموعات معًا: يُطابَق بالاسم فيأخذ شعبته ومجموعته دون أن يتكرر.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { assign = true }, enabled = inScope.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("تسجيل مواد لكل طلاب $level")
                        }
                    } else Text("اختر التخصص والمستوى لتظهر أيقونة استيراد الكشف وتسجيل المواد.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }, enabled = !busy) { Text(if (busy) "جارٍ التوليد…" else "＋ إضافة طالب") }
                OutlinedButton(onClick = { launcher.launch("student_codes.csv") }, enabled = st.students.isNotEmpty()) { Text("تصدير الأكواد") }
            }
            TextButton(onClick = { bulk = !bulk }) { Text(if (bulk) "إخفاء الإضافة الجماعية" else "إضافة جماعية (لصق من Excel)") }
            if (bulk) {
                Text("سطر لكل طالب:\nالرقم الجامعي,الاسم,الكلية,التخصص,المستوى,الشعبة,المواد (CS101-A;MATH1-B)", style = MaterialTheme.typography.bodySmall)
                F("الطلاب", text, lines = 4) { text = it }
                Button(onClick = { vm.addStudents(text); text = "" }, enabled = text.isNotBlank() && !busy) { Text("إضافة الطلاب") }
            }
            if (inScope.size > 5) F("بحث بالاسم أو الرقم", query) { query = it }
        }
        items(shown) { s ->
            val seen = st.bindings.any { it.studentId == s.studentId }
            val cs = st.courses.filter { it.id in s.courseIds }.joinToString("، ") { it.short }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(listOf(s.studentId, s.major, s.level, if (s.section.isNotBlank()) "شعبة ${s.section}" else "", s.group).filter { it.isNotBlank() }.joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (cs.isBlank()) "بلا مواد" else cs, style = MaterialTheme.typography.bodySmall)
                        Text("الكود: ${Codes.format(s.code)}  ${if (seen) "✓ ظهر جهازه" else "⏳ لم يظهر بعد"}",
                            color = if (seen) Green else Color.Unspecified, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(Codes.format(s.code)))
                            Toast.makeText(ctx, "تم نسخ كود ${s.name}", Toast.LENGTH_SHORT).show()
                        }) { Text("نسخ الكود") }
                        TextButton(onClick = { editing = s }) { Text("تعديل") }
                        TextButton(onClick = { vm.resetStudent(s.studentId) }, enabled = !busy) { Text("كود جديد") }
                        TextButton(onClick = { vm.deleteStudent(s.studentId) }, enabled = !busy) { Text("حذف", color = Color(0xFFC62828)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportsTab(vm: AdminVM) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val info by vm.syncInfo.collectAsState()
    val summary = remember(st) { vm.courseSummary() }
    val conflicts = remember(st) { vm.conflicts() }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreCode by remember { mutableStateOf("") }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { restoreBytes = readUri(ctx, it) }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(("\uFEFF" + vm.attendanceCsv()).toByteArray()) } }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("التقارير", style = MaterialTheme.typography.headlineSmall)
            Text("يصلك الحضور من الدكاترة تلقائيًا عند اتصال هاتفيكما بالإنترنت.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.syncNow(manual = true) }, enabled = !syncing) {
                    Text(if (syncing) "جارٍ المزامنة…" else "مزامنة الآن")
                }
                OutlinedButton(onClick = { csvLauncher.launch("attendance_report.csv") }, enabled = st.records.isNotEmpty()) { Text("تصدير CSV") }
            }
            if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
            if (summary.isEmpty()) Text("لا توجد مواد بعد.")
            summary.forEach { Text(it) }
            Spacer(Modifier.height(8.dp))
            AttendanceSheetSection(vm, st)
            if (conflicts.isNotEmpty()) {
                Text("⚠ طلاب بجهازين مختلفين (قد يعني كودًا مشاركًا):", color = Amber, style = MaterialTheme.typography.titleSmall)
                conflicts.forEach { (s, docs) -> Text("• ${s.name} (${s.studentId}) — عند: ${docs.joinToString("، ")}", color = Amber) }
                Text("الحل: «كود جديد» للطالب من تبويب الطلاب (يصل الكشف المحدَّث لدكاترته تلقائيًا).", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Text("النسخ الاحتياطي", style = MaterialTheme.typography.titleMedium)
            Text("هذا الهاتف هو المرجع الوحيد؛ إن ضاع ضاعت الأكواد. خذ نسخة مشفّرة واحفظها خارج الهاتف، واحفظ كودها منفصلًا.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                scope.launch {
                    vm.backup()?.let { (f, pass) ->
                        vm.message.value = "كود النسخة الاحتياطية (لا تفقده، لا استعادة بدونه):\n${Codes.format(pass)}"
                        shareFile(ctx, f, "نسخة احتياطية")
                    }
                }
            }, enabled = !busy) { Text("إنشاء نسخة احتياطية مشفّرة") }
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("اختيار نسخة للاستعادة") }
            if (restoreBytes != null) {
                F("كود النسخة (12 خانة)", restoreCode) { restoreCode = it.take(20) }
                Button(onClick = { vm.restore(restoreBytes, restoreCode); restoreBytes = null; restoreCode = "" },
                    enabled = restoreCode.isNotBlank() && !busy) { Text("استعادة (تستبدل البيانات الحالية)") }
            }
        }
    }
}

// ---------- كشوف الطلاب (تخصص ← مستوى ← شعبة للنظري | مجموعة للعملي) ----------
@Composable
fun RostersTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    var major by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("") }
    var practical by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }          // اسم الشعبة (نظري) أو المجموعة (عملي)؛ فارغ = الكل
    var courseId by remember { mutableStateOf(0L) }
    val ready = major.isNotBlank() && level.isNotBlank()
    val options = if (practical) st.groupNames else st.sectionNames
    val students = remember(st, major, level, practical, target) {
        if (ready) vm.rosterStudents(major, level, practical, target) else emptyList<Student>()
    }
    val courses = remember(st, major, practical, target) {
        if (ready) vm.rosterCourses(major, practical, target) else emptyList<Course>()
    }
    val course = courses.firstOrNull { it.id == courseId }
    val data = remember(st, students, course?.id) { vm.rosterSheet(students, course?.id) }
    var page by remember(course?.id) { mutableIntStateOf(0) }
    val p = page.coerceIn(0, data.pages - 1)
    val kindWord = if (practical) "مجموعة" else "شعبة"

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("كشوف الطلاب", style = MaterialTheme.typography.headlineSmall)
            Text("اختر التخصص والمستوى، ثم الشعبة (للنظري) أو المجموعة (للعملي) لعرض كشف طلابها كما في تطبيق الدكتور.", style = MaterialTheme.typography.bodySmall)
            if (st.majors.isEmpty() || st.levelNames.isEmpty())
                Text("⚠ أنشئ التخصصات والمستويات من تبويب «الإعدادات» أولًا.", color = Amber, style = MaterialTheme.typography.bodySmall)
            Picker("التخصص", st.majors, major) { major = it; courseId = 0 }
            Picker("المستوى", st.levelNames, level) { level = it; courseId = 0 }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("النوع:")
                FilterChip(selected = !practical, onClick = { if (practical) { practical = false; target = ""; courseId = 0 } }, label = { Text("نظري") })
                FilterChip(selected = practical, onClick = { if (!practical) { practical = true; target = ""; courseId = 0 } }, label = { Text("عملي") })
            }
            Text(if (practical) "المجموعة:" else "الشعبة:", style = MaterialTheme.typography.titleSmall)
            if (options.isEmpty())
                Text(if (practical) "لا مجموعات عملي بعد — أنشئها من «الإعدادات» أو بالاستيراد من كشف PDF." else "لا شعب بعد — أنشئها من «الإعدادات» أو بالاستيراد من كشف PDF.",
                    color = Amber, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = target.isBlank(), onClick = { target = ""; courseId = 0 }, label = { Text(if (practical) "كل المجموعات" else "كل الشعب") })
                options.forEach { o -> FilterChip(selected = target == o, onClick = { target = o; courseId = 0 }, label = { Text(o) }) }
            }
        }
        if (!ready) {
            item { Text("اختر التخصص والمستوى لعرض الكشف.", color = Amber) }
        } else {
            item {
                val where = if (target.isBlank()) (if (practical) "كل المجموعات" else "كل الشعب") else "$kindWord $target"
                Text("$major — $level — $where: ${students.size} طالب", style = MaterialTheme.typography.titleSmall)
                if (courses.isNotEmpty()) {
                    val labels = courses.map { it.short }
                    Picker("المادة (اختياري — لإظهار حضورها)", labels, course?.short ?: "") { l -> courses.getOrNull(labels.indexOf(l))?.let { courseId = it.id } }
                    if (course != null) TextButton(onClick = { courseId = 0 }) { Text("بدون مادة (كشف فارغ)") }
                }
                Text(
                    if (course == null) "كشف بأسماء الطلاب وأعمدة المحاضرات فارغة (كالكشف الورقي). اختر مادة لإظهار ✓ الحضور."
                    else "${course.short}: المحاضرات المنعقدة ${data.total}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (data.pages > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0 until data.pages) {
                        val a = i * AttendanceSheet.PAGE + 1; val b = minOf((i + 1) * AttendanceSheet.PAGE, data.total)
                        FilterChip(selected = p == i, onClick = { page = i }, label = { Text("$a–$b") })
                    }
                }
                if (students.isEmpty())
                    Text("لا طلاب مطابقون. تأكد من شعبة/مجموعة الطلاب في تبويب «الطلاب» (أو استوردها من كشف PDF).", color = Amber)
                else {
                    SheetPdfButtons(
                        data, "كشف حضور وغياب الطلاب — $major — المستوى $level",
                        listOfNotNull(where, course?.label).joinToString(" — ") + " — الطلاب: ${students.size}",
                        "attendance_${major}_${level}_${if (target.isBlank()) "all" else target}"
                    )
                    SheetTable(data, p)
                }
            }
        }
    }
}

// ---------- جداول التخصصات ----------
/** ألوان الجدول كما في جدول الكلية: أزرق فاتح للنظري، أزرق أغمق للعملي، أصفر للتعويضية، برتقالي للاختبارات. */
private fun ttColor(e: TEntry): Color = when {
    e.kind == TKind.EXAM -> Color(0xFFFFC000)
    e.kind == TKind.MAKEUP -> Color(0xFFFFEE58)
    e.practical -> Color(0xFF8EAADB)
    else -> Color(0xFFC9DAF8)
}

private val DAY_ORDER = listOf(6, 0, 1, 2, 3, 4, 5)   // السبت أولًا كجدول الكلية

@Composable
private fun TtLegend() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("نظري" to Color(0xFFC9DAF8), "عملي" to Color(0xFF8EAADB), "تعويضية" to Color(0xFFFFEE58), "اختبار" to Color(0xFFFFC000)).forEach { (t, c) ->
            Surface(color = c, shape = MaterialTheme.shapes.small) { Text(t, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.Black, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun TtCard(e: TEntry, onDelete: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ttColor(e), contentColor = Color.Black)) {
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(e.title + (if (e.practical) " (عملي)" else "") + when (e.kind) { TKind.MAKEUP -> " — تعويضية"; TKind.EXAM -> " — اختبار"; else -> "" },
                    style = MaterialTheme.typography.titleSmall)
                Text("${Timetable.timeRange(e)} • ${Timetable.audience(e)}", style = MaterialTheme.typography.bodySmall)
                if (e.teacher.isNotBlank()) Text(e.teacher, style = MaterialTheme.typography.bodySmall)
                if (e.room.isNotBlank()) Text(e.room, style = MaterialTheme.typography.bodySmall)
                if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
            }
            if (onDelete != null) TextButton(onClick = onDelete) { Text("حذف", color = Color(0xFFC62828)) }
        }
    }
}

@Composable
private fun TimetableDialog(
    sections: List<String>, groups: List<String>, onDismiss: () -> Unit,
    onAdd: (Int, String, String, String, Int, Boolean, String, String, String, String, String) -> Unit
) {
    var day by remember { mutableIntStateOf(6) }
    var from by remember { mutableStateOf("08:00") }
    var to by remember { mutableStateOf("10:00") }
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(TKind.NORMAL) }
    var practical by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة خانة للجدول") },
        confirmButton = {
            TextButton(onClick = { onAdd(day, from, to, title, kind, practical, section, group, teacher, room, note) },
                enabled = title.isNotBlank() && from.isNotBlank() && to.isNotBlank()) { Text("إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اليوم", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_ORDER.forEach { d -> FilterChip(selected = day == d, onClick = { day = d }, label = { Text(Schedule.DAYS[d]) }) }
                }
                Text("الفترة", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Timetable.PERIODS.forEach { (a, b) ->
                        val fa = Schedule.fmt(a); val fb = Schedule.fmt(b)
                        FilterChip(selected = from == fa && to == fb, onClick = { from = fa; to = fb }, label = { Text("${a / 60}–${b / 60}") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(from, { from = it }, label = { Text("من") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(to, { to = it }, label = { Text("إلى") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                F("المادة (أو العنوان: إجازة، اختبار…)", title) { title = it }
                Text("النوع", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == TKind.NORMAL, onClick = { kind = TKind.NORMAL }, label = { Text("عادية") })
                    FilterChip(selected = kind == TKind.MAKEUP, onClick = { kind = TKind.MAKEUP }, label = { Text("تعويضية (أصفر)") })
                    FilterChip(selected = kind == TKind.EXAM, onClick = { kind = TKind.EXAM }, label = { Text("اختبار (برتقالي)") })
                }
                Row(Modifier.fillMaxWidth().clickable { practical = !practical }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = practical, onCheckedChange = { practical = it })
                    Text("عملي (معمل)")
                }
                Picker("الشعبة (بلا اختيار = كل الشعب)", listOf("— الكل —") + sections, section) { section = if (it.startsWith("—")) "" else it }
                Picker("مجموعة العملي (بلا اختيار = الكل)", listOf("— الكل —") + groups, group) { group = if (it.startsWith("—")) "" else it }
                F("الدكتور/المدرّس (اختياري)", teacher) { teacher = it }
                F("القاعة/المعمل (اختياري)", room) { room = it }
                F("ملاحظة (اختياري)", note) { note = it }
            }
        }
    )
}

@Composable
fun TimetableTab(vm: AdminVM) {
    val st by vm.state.collectAsState()
    val majors = (st.majors + st.timetable.map { it.major }).distinct()
    val levels = (st.levelNames + st.timetable.map { it.level }.filter { it.isNotBlank() }).distinct()
    var major by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val shown = st.timetable.filter { it.major == major && it.level == level }.sortedWith(compareBy({ it.day }, { it.startMin }))

    if (adding) TimetableDialog(st.sectionNames, st.groupNames, { adding = false }) { day, a, b, title, kind, prac, sec, grp, teacher, room, note ->
        vm.addTimetableEntry(major, level, day, a, b, title, kind, prac, sec, grp, teacher, room, note); adding = false
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("جداول التخصصات", style = MaterialTheme.typography.headlineSmall)
            Text("أنشئ جدولًا لكل تخصص ومستوى (كجدول الكلية). يظهر عند كل طالب تلقائيًا عند اتصاله بالإنترنت، بحسب تخصصه ومستواه وشعبته ومجموعته في العملي.",
                style = MaterialTheme.typography.bodySmall)
            if (st.majors.isEmpty()) Text("⚠ أنشئ التخصصات من تبويب «الإعدادات» أولًا.", color = Amber)
            Picker("التخصص", majors, major) { major = it }
            if (levels.isNotEmpty()) Picker("المستوى", levels, level) { level = it }
            else Text("(يمكنك إنشاء المستويات من «الإعدادات»؛ بدونها يكون الجدول لكل مستويات التخصص)", style = MaterialTheme.typography.bodySmall)
            TtLegend()
            Button(onClick = { adding = true }, enabled = major.isNotBlank()) { Text("＋ إضافة خانة للجدول") }
            if (major.isNotBlank() && shown.isEmpty()) Text("لا خانات بعد لهذا التخصص/المستوى.", style = MaterialTheme.typography.bodySmall, color = Amber)
        }
        if (major.isNotBlank()) {
            DAY_ORDER.forEach { d ->
                val es = shown.filter { it.day == d }
                if (es.isNotEmpty()) {
                    item { Text(Schedule.DAYS[d], style = MaterialTheme.typography.titleMedium) }
                    items(es) { e -> TtCard(e) { vm.deleteTimetableEntry(e.id) } }
                }
            }
        }
    }
}

// ---------- استيراد كشف شعب أو مجموعات عملي من PDF ----------
@Composable
private fun ImportSheetCard(s: GroupSheet, title: String, options: List<String>, name: String, onName: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("«${s.label}» في الملف — ${s.names.size} طالبًا" + if (s.whenText.isNotBlank()) " — ${s.whenText}" else "", style = MaterialTheme.typography.titleSmall)
            F(title, name, on = onName)
            if (options.isNotEmpty()) Picker("أو اختر من الموجودة", options, "") { onName(it) }
            TextButton(onClick = { open = !open }) { Text(if (open) "إخفاء الأسماء" else "عرض الأسماء (${s.names.size})") }
            if (open) SelectionContainer { Text(s.names.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ImportDialog(
    st: AdminState, p: ImportPreview, onDismiss: () -> Unit, onConfirm: (List<AdminOps.ImportGroup>) -> Unit
) {
    val sheets = p.sheets
    val isSection = p.kind == AdminOps.ImportKind.SECTION
    val options = if (isSection) st.sectionNames else st.groupNames
    val names = remember { mutableStateListOf<String>().apply {
        addAll(sheets.map { if (isSection) AdminOps.suggestSectionName(it.label, st.sectionNames) else AdminOps.suggestGroupName(it.label, st.groupNames) })
    } }
    val groups = sheets.mapIndexed { i, s -> AdminOps.ImportGroup(names[i], s.names) }
    val plan = AdminOps.planGroupImport(st, groups, p.kind)
    val fixed = sheets.sumOf { it.fixed }
    val mismatch = sheets.any {
        (it.major.isNotBlank() && NameFix.key(it.major) != NameFix.key(p.major)) || (it.level.isNotBlank() && NameFix.key(it.level) != NameFix.key(p.level))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isSection) "استيراد كشف شعب" else "استيراد كشف مجموعات عملي") },
        confirmButton = { TextButton(onClick = { onConfirm(groups) }, enabled = names.all { it.isNotBlank() }) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.major} — ${p.level}", style = MaterialTheme.typography.titleSmall)
                Text("وُجد ${sheets.sumOf { it.names.size }} طالبًا في ${sheets.size} ${if (isSection) "شعبة" else "مجموعة"}. الجدد: ${plan.add.size} • موجودون: ${plan.update.size + plan.same}" +
                    if (plan.duplicates > 0) " • مكرر في الملف: ${plan.duplicates}" else "", style = MaterialTheme.typography.bodyMedium)
                if (mismatch) Text("⚠ الكشف يبدو لتخصص/مستوى مختلف عمّا اخترته (${sheets.first().major} — ${sheets.first().level}). تأكد أنه الكشف الصحيح قبل الإضافة.",
                    style = MaterialTheme.typography.bodySmall, color = Amber)
                sheets.forEachIndexed { i, s ->
                    ImportSheetCard(s, if (isSection) "اسم الشعبة عندك" else "اسم المجموعة عندك", options, names[i]) { names[i] = it }
                }
                if (fixed > 0) Text("⚠ أُصلح تلقائيًا نقص حروف في $fixed كلمة (مثل «عبدا» ← «عبدالله»)؛ هذا نقص في ملف PDF نفسه. راجع الأسماء، وعدّل أي اسم لاحقًا بزر «تعديل».",
                    style = MaterialTheme.typography.bodySmall, color = Amber)
                Text("الطلاب الجدد يُنشأ لهم كود تلقائيًا. الموجودون (بالاسم) تُضبط ${if (isSection) "شعبتهم" else "مجموعتهم"} فقط دون تكرار، وبقية بياناتهم لا تتغير.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    )
}

/** تسجيل مواد لكل طلاب تخصص ومستوى: تُختار المواد (وسجلات شعبها/مجموعاتها) ويُطابَق كل طالب بشعبته/مجموعته. */
@Composable
private fun AssignCoursesDialog(st: AdminState, major: String, level: String, onDismiss: () -> Unit, onConfirm: (List<Long>) -> Unit) {
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val n = AdminOps.studentsIn(st, major, level).size
    val avail = st.courses.filter { it.major.isBlank() || it.major.trim().equals(major.trim(), true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مواد $major — $level") },
        confirmButton = { TextButton(onClick = { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty()) { Text("تسجيل $n طالب") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("اختر المواد التي تُسجَّل لكل طلاب هذا المستوى. اختر سجلات المادة كلها (شعب النظري ومجموعات العملي): يُسجَّل كل طالب في السجل المطابق لشعبته (نظري) أو مجموعته (عملي). لا يُحذف شيء من موادهم الحالية.",
                    style = MaterialTheme.typography.bodySmall)
                if (avail.isEmpty()) Text("لا توجد مواد لهذا التخصص. أنشئها من تبويب «المواد».", color = Amber)
                Row {
                    TextButton(onClick = { picked = avail.map { it.id }.toSet() }) { Text("تحديد الكل") }
                    TextButton(onClick = { picked = emptySet() }) { Text("إلغاء التحديد") }
                }
                avail.forEach { c ->
                    val on = c.id in picked
                    Row(Modifier.fillMaxWidth().clickable { picked = if (on) picked - c.id else picked + c.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(c.short, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    )
}

// ---------- كشف حضور وغياب الطلاب (كالكشف الورقي) ----------
private val SheetBlue = Color(0xFF1F3FD0)
private val SheetGreen = Color(0xFFE2EFDA)
private val SheetLine = Color(0xFF9E9E9E)

@Composable
private fun SheetCell(text: String, w: Dp, h: Dp = 34.dp, bg: Color = Color.Transparent, bold: Boolean = false, color: Color = Color.Black, start: Boolean = false) {
    Box(
        Modifier.width(w).height(h).background(bg).border(0.5.dp, SheetLine).padding(horizontal = 3.dp),
        contentAlignment = if (start) Alignment.CenterStart else Alignment.Center
    ) {
        Text(text, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else null, color = color, maxLines = 2,
            textAlign = if (start) TextAlign.Start else TextAlign.Center)
    }
}

/** زرّا «تصدير PDF» (يحفظ في مكان تختاره) و«مشاركة PDF» لكشف حضور جاهز. */
@Composable
private fun SheetPdfButtons(data: AttendanceSheetData, title: String, subtitle: String, baseName: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = SheetPdf.fileName(baseName)
    val notes = remember(data) {
        if (data.total == 0) emptyMap() else data.rows.associate { r -> r.studentId to "${r.count} من ${data.total} (${AttendanceSheet.percent(r.count, data.total)}%)" }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { u -> scope.launch { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(u)?.use { SheetPdf.write(it, title, subtitle, data, notes) } } } }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { saver.launch(name) }, enabled = data.rows.isNotEmpty()) { Text("تصدير PDF") }
        OutlinedButton(onClick = {
            scope.launch {
                val f = withContext(Dispatchers.IO) {
                    File(ctx.cacheDir, "exports").apply { mkdirs() }.let { d -> File(d, name).also { out -> out.outputStream().use { SheetPdf.write(it, title, subtitle, data, notes) } } }
                }
                shareFile(ctx, f, "مشاركة الكشف", "application/pdf")
            }
        }, enabled = data.rows.isNotEmpty()) { Text("مشاركة PDF") }
    }
}

/** جدول الكشف: م | اسم الطالب | 1…16 (محاضرات) | ملاحظات. ✓ = حضر. يمرَّر أفقيًا. */
@Composable
private fun SheetTable(data: AttendanceSheetData, page: Int) {
    val from = page * AttendanceSheet.PAGE
    Column(Modifier.horizontalScroll(rememberScrollState()).border(2.dp, SheetBlue, RoundedCornerShape(8.dp))) {
        Row {
            SheetCell("م", 32.dp, 56.dp, SheetGreen, true)
            SheetCell("اسم الطالب", 170.dp, 56.dp, SheetGreen, true)
            for (i in 0 until AttendanceSheet.PAGE) {
                val l = data.lectures.getOrNull(from + i)
                Box(Modifier.width(34.dp).height(56.dp).background(SheetGreen).border(0.5.dp, SheetLine), contentAlignment = Alignment.Center) {
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
                SheetCell(if (data.total == 0) "" else "${r.count} من ${data.total} (${AttendanceSheet.percent(r.count, data.total)}%)", 130.dp)
            }
        }
    }
}

@Composable
private fun AttendanceSheetSection(vm: AdminVM, st: AdminState) {
    var courseId by remember { mutableStateOf(0L) }
    val labels = st.courses.map { it.label }
    val cur = st.courses.firstOrNull { it.id == courseId }
    Text("كشف حضور وغياب الطلاب", style = MaterialTheme.typography.titleMedium)
    Picker("اختر المادة", labels, cur?.label ?: "") { l -> st.courses.getOrNull(labels.indexOf(l))?.let { courseId = it.id } }
    if (cur == null) {
        Text("اختر مادة لعرض كشفها: الطلاب المسجَّلون ومحاضراتها (يومًا يومًا) وحضورهم.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val data = remember(st, cur.id) { vm.courseSheet(cur.id) }
    var page by remember(cur.id) { mutableIntStateOf(0) }
    val p = page.coerceIn(0, data.pages - 1)
    Text("${cur.label}: الطلاب ${data.rows.size} • المحاضرات المنعقدة ${data.total}", style = MaterialTheme.typography.bodySmall)
    Text("المحاضرة = يوم واحد للمادة: لا يتكرر الطالب فيها حتى لو أوقف الدكتور الحضور وأعاده، والمحاضرة المتتالية الثانية في اليوم عمود مستقل (2).", style = MaterialTheme.typography.bodySmall)
    SheetPdfButtons(data, "كشف حضور وغياب الطلاب — ${cur.label}", "الطلاب: ${data.rows.size} — المحاضرات المنعقدة: ${data.total}", "attendance_${cur.label}")
    if (data.pages > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until data.pages) {
            val a = i * AttendanceSheet.PAGE + 1; val b = minOf((i + 1) * AttendanceSheet.PAGE, data.total)
            FilterChip(selected = p == i, onClick = { page = i }, label = { Text("$a–$b") })
        }
    }
    if (data.rows.isEmpty()) Text("لا طلاب مسجَّلون في هذه المادة.", color = Amber) else SheetTable(data, p)
}
