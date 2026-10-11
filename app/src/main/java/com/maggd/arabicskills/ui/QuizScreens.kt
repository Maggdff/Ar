@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maggd.arabicskills.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggd.arabicskills.logic.QuizEngine
import com.maggd.arabicskills.logic.QuizScope
import com.maggd.arabicskills.model.Question

private enum class Kind { LESSON, UNIT, BOOK }

@Composable
fun QuizSetupScreen(vm: AppViewModel, preset: QuizScope?) {
    val c = vm.content
    var kind by remember { mutableStateOf<Kind>(when (preset) { is QuizScope.OfLesson -> Kind.LESSON; is QuizScope.OfUnit -> Kind.UNIT; else -> Kind.BOOK }) }
    var unitId by remember { mutableStateOf((preset as? QuizScope.OfUnit)?.unitId ?: c.units.first().id) }
    var lessonId by remember { mutableStateOf((preset as? QuizScope.OfLesson)?.lessonId ?: c.lessons.first().id) }
    var count by remember { mutableStateOf(10) }

    val scope: QuizScope = when (kind) {
        Kind.LESSON -> QuizScope.OfLesson(lessonId)
        Kind.UNIT -> QuizScope.OfUnit(unitId)
        Kind.BOOK -> QuizScope.WholeBook
    }
    val available = vm.poolSize(scope)

    AppScaffold("اختبار", onBack = { vm.back() }, bottomBar = { BottomTabs(vm) }) { pad ->
        ScrollColumn(pad) {
            SectionCard("نطاق الاختبار") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == Kind.LESSON, { kind = Kind.LESSON }, label = { Text("درس") })
                    FilterChip(kind == Kind.UNIT, { kind = Kind.UNIT }, label = { Text("وحدة") })
                    FilterChip(kind == Kind.BOOK, { kind = Kind.BOOK }, label = { Text("الكتاب كاملًا") })
                }
                when (kind) {
                    Kind.UNIT -> c.units.forEach { u ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(unitId == u.id, { unitId = u.id })
                            Text(u.title, fontSize = 15.sp)
                        }
                    }
                    Kind.LESSON -> c.units.forEach { u ->
                        Text(u.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        c.lessonsOf(u.id).forEach { l ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(lessonId == l.id, { lessonId = l.id })
                                Text(l.title, fontSize = 15.sp)
                            }
                        }
                    }
                    Kind.BOOK -> Body("أسئلة من جميع الدروس.")
                }
            }
            SectionCard("عدد الأسئلة") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20, 0).forEach { n ->
                        FilterChip(count == n, { count = n }, label = { Text(if (n == 0) "الكل" else "$n") })
                    }
                }
                Text("المتاح: $available سؤالًا (صح/خطأ واختيار من متعدد)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BigButton("ابدأ الاختبار", enabled = available > 0) { vm.startQuiz(scope, count) }
            BigButton("إعادة اختبار أخطائي", secondary = true, enabled = vm.progress.wrongSet.isNotEmpty()) { vm.navigate(Screen.Mistakes) }
        }
    }
}

@Composable
fun QuizScreen(vm: AppViewModel) {
    val q = vm.quiz
    if (q == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val item = q.current
    val question = item.question
    AppScaffold(q.label, onBack = { vm.back() }) { pad ->
        ScrollColumn(pad) {
            Text("السؤال ${q.index + 1} من ${q.items.size}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PercentBar(((q.index + if (q.answered) 1 else 0) * 100) / q.items.size)
            SectionCard { Text(question.text, fontSize = 19.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium) }

            item.options.forEach { opt ->
                OptionRow(
                    text = opt,
                    answered = q.answered,
                    isCorrectOption = QuizEngine.isCorrect(question, opt),
                    isSelected = q.selected == opt,
                    onClick = { vm.answer(opt) },
                )
            }

            if (q.answered) {
                val ok = QuizEngine.isCorrect(question, q.selected ?: "")
                SectionCard(
                    if (ok) "إجابة صحيحة ✓" else "إجابة خاطئة ✗",
                    container = if (ok) OkBg else BadBg,
                ) {
                    CompositionLocalProviderDark {
                        if (!ok) Body("الإجابة الصحيحة: ${question.answer}")
                        if (question.explanation.isNotBlank()) Body("الشرح: ${question.explanation}")
                        if (!ok && question.correctionIfFalse.isNotBlank()) Body("التصحيح: ${question.correctionIfFalse}")
                    }
                }
                BigButton(if (q.isLast) "إنهاء وعرض النتيجة" else "السؤال التالي") { vm.next() }
            }
        }
    }
}

/** نصوص بطاقات التغذية الراجعة تبقى داكنة فوق الخلفيتين الفاتحتين حتى في الوضع الليلي */
@Composable
private fun CompositionLocalProviderDark(content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Color(0xFF1B1B1B)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun OptionRow(text: String, answered: Boolean, isCorrectOption: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val bg = when {
        answered && isCorrectOption -> OkBg
        answered && isSelected -> BadBg
        else -> MaterialTheme.colorScheme.surface
    }
    val border = when {
        answered && isCorrectOption -> Ok
        answered && isSelected -> Bad
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val textColor = if (answered && (isCorrectOption || isSelected)) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick, enabled = !answered,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bg, disabledContainerColor = bg),
        border = BorderStroke(if (answered && (isCorrectOption || isSelected)) 2.dp else 1.dp, border),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 28.sp, color = textColor)
            if (answered && isCorrectOption) Text("✓", color = Ok, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            else if (answered && isSelected) Text("✗", color = Bad, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ResultScreen(vm: AppViewModel) {
    val r = vm.lastResult
    if (r == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val (correct, total) = QuizEngine.summarize(r.results)
    val pct = QuizEngine.percent(correct, total)
    val wrong = r.results.filter { !it.correct }
    AppScaffold("النتيجة", onBack = { vm.back() }) { pad ->
        ScrollColumn(pad) {
            SectionCard(r.label) {
                Text("$correct من $total", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Text("$pct%", fontSize = 20.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                PercentBar(pct)
                Text(
                    when { pct >= 90 -> "ممتاز! إتقان رائع."; pct >= 70 -> "جيد جدًا، راجع الأخطاء لتثبيتها."; pct >= 50 -> "مقبول، أعد قراءة الدروس ثم أعد المحاولة."; else -> "تحتاج مزيدًا من المراجعة؛ ابدأ بالدروس ثم أعد الاختبار." },
                    Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 15.sp,
                )
            }
            if (wrong.isNotEmpty()) {
                SectionCard("الأسئلة التي أخطأت فيها (${wrong.size})") {
                    wrong.forEachIndexed { i, w ->
                        val q = vm.content.questions.firstOrNull { it.id == w.questionId } ?: return@forEachIndexed
                        Text(q.text, fontWeight = FontWeight.Bold)
                        Body("إجابتك: ${w.selected}")
                        Body("الصحيحة: ${q.answer}")
                        if (q.explanation.isNotBlank()) Body("الشرح: ${q.explanation}")
                        if (i != wrong.lastIndex) HorizontalDivider()
                    }
                }
                BigButton("إعادة اختبار الأخطاء المحفوظة") { vm.startQuiz(QuizScope.Mistakes, 0) }
            }
            BigButton("العودة للرئيسية", secondary = true) { vm.goTab(Screen.Home) }
        }
    }
}

@Composable
fun MistakesScreen(vm: AppViewModel) {
    val wrong: List<Question> = vm.content.questions.filter { it.id in vm.progress.wrongSet }
    AppScaffold("أخطائي", onBack = { vm.back() }) { pad ->
        ScrollColumn(pad) {
            if (wrong.isEmpty()) EmptyState("لا توجد أخطاء محفوظة. أحسنت!")
            else {
                BigButton("أعد اختبار الأخطاء (${wrong.count { it.type.autoGraded }})") { vm.startQuiz(QuizScope.Mistakes, 0) }
                wrong.forEach { q ->
                    SectionCard {
                        Text(q.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Body("الصحيحة: ${q.answer}")
                        if (q.explanation.isNotBlank()) Body("الشرح: ${q.explanation}")
                        TextButton(onClick = { vm.dismissMistake(q.id) }) { Text("إزالة من القائمة") }
                    }
                }
            }
        }
    }
}
