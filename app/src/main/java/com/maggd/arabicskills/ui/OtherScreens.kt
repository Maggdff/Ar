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
import com.maggd.arabicskills.model.Question

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
                SectionCard("انتهت البطاقات") {
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
