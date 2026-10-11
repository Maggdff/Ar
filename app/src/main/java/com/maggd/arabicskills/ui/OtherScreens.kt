@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maggd.arabicskills.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggd.arabicskills.logic.ProgressLogic
import com.maggd.arabicskills.logic.QuizEngine
import com.maggd.arabicskills.logic.StatsLogic
import com.maggd.arabicskills.model.Question
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CardsScreen(vm: AppViewModel) {
    val c = vm.content
    var scopeKey by remember { mutableStateOf("all") }
    var restart by remember { mutableStateOf(0) }

    val base: List<Question> = when {
        scopeKey == "all" -> c.questions
        scopeKey == "mistakes" -> c.questions.filter { it.id in vm.progress.wrongSet }
        else -> c.questionsOfUnit(scopeKey)
    }
    // الترتيب يُحسب مرة لكل (نطاق/إعادة) حتى لا تقفز البطاقات أثناء المراجعة
    val deck = remember(scopeKey, restart) { ProgressLogic.orderDeck(vm.progress, base) }
    var index by remember(scopeKey, restart) { mutableStateOf(0) }
    var revealed by remember(scopeKey, restart) { mutableStateOf(false) }

    AppScaffold("بطاقات المراجعة", onBack = { vm.back() }, bottomBar = { BottomTabs(vm) }) { pad ->
        ScrollColumn(pad) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(scopeKey == "all", { scopeKey = "all" }, label = { Text("الكل") })
                FilterChip(scopeKey == "mistakes", { scopeKey = "mistakes" }, label = { Text("أخطائي") })
                c.units.forEachIndexed { i, u -> FilterChip(scopeKey == u.id, { scopeKey = u.id }, label = { Text("الوحدة ${i + 1}") }) }
            }
            if (deck.isEmpty()) {
                EmptyState("لا توجد بطاقات في هذا النطاق.")
            } else if (index >= deck.size) {
                SectionCard("انتهت البطاقات 🎉") {
                    Body("راجعت ${deck.size} بطاقة. البطاقات التي لم تعرفها ستظهر أولًا في الجولة القادمة.")
                    BigButton("جولة جديدة") { restart++ }
                }
            } else {
                val q = deck[index]
                val box = vm.progress.boxes[q.id] ?: 0
                Text("بطاقة ${index + 1} من ${deck.size}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PercentBar(index * 100 / deck.size)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(q.type.label); Tag("مستوى الإتقان: $box/${ProgressLogic.MAX_BOX}")
                }
                SectionCard("السؤال") { Text(q.text, fontSize = 19.sp, lineHeight = 32.sp) }
                if (!revealed) {
                    BigButton("اعرض الإجابة") { revealed = true }
                } else {
                    SectionCard("الإجابة", container = MaterialTheme.colorScheme.primaryContainer) {
                        Text(q.answer, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                        if (q.explanation.isNotBlank()) Body("الشرح: ${q.explanation}")
                        if (q.correctionIfFalse.isNotBlank()) Body("التصحيح: ${q.correctionIfFalse}")
                        if (q.appNote.isNotBlank()) Text("ملاحظة توثيق: ${q.appNote}", fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { BigButton("لم أعرفها", secondary = true) { vm.reviewCard(q.id, false); index++; revealed = false } }
                        Box(Modifier.weight(1f)) { BigButton("عرفتها") { vm.reviewCard(q.id, true); index++; revealed = false } }
                    }
                }
            }
        }
    }
}

@Composable
fun StatsScreen(vm: AppViewModel) {
    val c = vm.content
    val s = StatsLogic.overall(c, vm.progress)
    val per = StatsLogic.perLesson(c, vm.progress)
    val weak = StatsLogic.weakest(per)
    var confirmReset by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.ENGLISH) }

    AppScaffold("إحصائيات التقدم", onBack = { vm.back() }, bottomBar = { BottomTabs(vm) }) { pad ->
        ScrollColumn(pad) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${s.accuracyPercent}%", "نسبة الصواب", Modifier.weight(1f))
                StatTile("${s.totalAttempts}", "إجمالي الإجابات", Modifier.weight(1f))
                StatTile("${s.sessionsCount}", "اختبارات", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${s.distinctAnswered}/${s.totalQuestionsAvailable}", "أسئلة جرّبتها", Modifier.weight(1f))
                StatTile("${s.lessonsRead}/${s.lessonsTotal}", "دروس مقروءة", Modifier.weight(1f))
                StatTile("${s.masteredCards}", "بطاقات متقنة", Modifier.weight(1f))
            }
            if (s.mistakesCount > 0) BigButton("أخطائي (${s.mistakesCount}) — أعد اختبارها") { vm.navigate(Screen.Mistakes) }

            if (weak.isNotEmpty()) SectionCard("دروس تحتاج مراجعة") {
                weak.forEach { w ->
                    Text(c.lesson(w.lessonId)?.title ?: w.lessonId, fontSize = 15.sp)
                    PercentBar(w.accuracyPercent)
                    Text("${w.accuracyPercent}%", fontSize = 12.sp)
                }
            }

            SectionCard("التقدم حسب الدرس") {
                c.units.forEach { u ->
                    Text(u.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    c.lessonsOf(u.id).forEach { l ->
                        val st = per.first { it.lessonId == l.id }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l.title, Modifier.weight(1f), fontSize = 14.sp)
                            Text(if (st.answered == 0) "—" else "${st.accuracyPercent}% (${st.answered}/${st.available})", fontSize = 13.sp)
                        }
                    }
                }
            }

            SectionCard("آخر الاختبارات") {
                if (vm.progress.sessions.isEmpty()) Text("لا توجد اختبارات بعد.")
                vm.progress.sessions.takeLast(10).reversed().forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.label, fontSize = 14.sp)
                            Text(fmt.format(Date(it.timestamp)), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${it.correct}/${it.total}  (${QuizEngine.percent(it.correct, it.total)}%)", fontWeight = FontWeight.Bold)
                    }
                }
            }
            TextButton(onClick = { confirmReset = true }) { Text("مسح التقدم والبدء من جديد") }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("مسح التقدم؟") },
        text = { Text("سيُحذف سجل الإجابات والأخطاء والبطاقات والاختبارات من هذا الجهاز. لا يمكن التراجع.") },
        confirmButton = { TextButton(onClick = { vm.resetProgress(); confirmReset = false }) { Text("مسح") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("إلغاء") } },
    )
}

@Composable
fun NotesScreen(vm: AppViewModel) {
    val c = vm.content
    val annotated = c.questions.filter { it.appNote.isNotBlank() }
    AppScaffold("ملاحظات المراجعة", onBack = { vm.back() }) { pad ->
        ScrollColumn(pad) {
            SectionCard("اكتمال الكتاب", container = MaterialTheme.colorScheme.secondaryContainer) {
                Body(c.meta.completenessNote)
                Body(c.meta.extractionStatus)
            }
            SectionCard("مبدأ التطبيق") {
                Body("المحتوى مبني على ملفات التحليل المرفقة فقط. لم تُضَف قواعد من خارج الكتاب، وكل تعديل على إجابة أو شرح موثّق أدناه مع سببه.")
            }
            if (annotated.isNotEmpty()) SectionCard("أسئلة عُدّل شرحها أو إجابتها (موثّق)") {
                annotated.forEach { q ->
                    Text(q.id, fontWeight = FontWeight.Bold)
                    Body(q.text)
                    Body(q.appNote)
                    HorizontalDivider()
                }
            }
            Text("تصحيحات النص المستخرج (${c.corrections.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            c.corrections.forEach { k ->
                SectionCard("${k.id} — ${k.lessonRef}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Tag(k.status); Tag("الثقة: ${k.confidence}") }
                    Body("الأصل: ${k.original}")
                    Body("المقترح: ${k.suggested}")
                    Body("السبب: ${k.reason}")
                    if (k.notes.isNotBlank()) Body("ملاحظات: ${k.notes}")
                }
            }
        }
    }
}
