@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maggd.arabicskills.ui

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
import com.maggd.arabicskills.model.Diagram
import com.maggd.arabicskills.model.DiagramItem

/** رسم تفاعلي واحد داخل شاشة الدرس. النوع يحدد طريقة العرض. */
@Composable
fun DiagramView(d: Diagram) {
    SectionCard("رسم تفاعلي: " + d.title, container = MaterialTheme.colorScheme.surface) {
        if (d.hint.isNotBlank()) {
            Text(d.hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (d.kind) {
            "flow" -> FlowDiagram(d)
            "reveal" -> RevealDiagram(d)
            "timeline" -> TimelineDiagram(d)
            else -> ChipsDiagram(d)
        }
    }
}

@Composable
private fun DetailBox(item: DiagramItem) {
    val fg = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = fg)
            if (item.body.isNotBlank()) Text(item.body, fontSize = 15.sp, lineHeight = 26.sp, color = fg)
            if (item.example.isNotBlank()) {
                Text(item.example, fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = fg)
            }
        }
    }
}

/** شرائح اختيار: اضغط شريحة فيظهر تفصيلها. */
@Composable
private fun ChipsDiagram(d: Diagram) {
    var sel by remember(d.id) { mutableStateOf(0) }
    val idx = sel.coerceIn(0, (d.items.size - 1).coerceAtLeast(0))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        d.items.forEachIndexed { i, item ->
            FilterChip(idx == i, { sel = i }, label = { Text(item.label) })
        }
    }
    val current = d.items.getOrNull(idx)
    if (current != null) DetailBox(current)
}

/** بطاقات اختبار ذاتي: اقرأ السؤال ثم اضغط لإظهار الجواب. */
@Composable
private fun RevealDiagram(d: Diagram) {
    var shown by remember(d.id) { mutableStateOf(setOf<Int>()) }
    val ink = Color(0xFF1B1B1B)
    d.items.forEachIndexed { i, item ->
        val open = i in shown
        Card(
            onClick = { shown = if (open) shown - i else shown + i },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (open) OkBg else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (open) ink else MaterialTheme.colorScheme.onSurface,
                )
                if (open) {
                    if (item.body.isNotBlank()) Text(item.body, fontSize = 14.sp, lineHeight = 24.sp, color = ink)
                    if (item.example.isNotBlank()) {
                        Text(item.example, fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Ok)
                    }
                } else {
                    Text("اضغط لإظهار الجواب", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { shown = d.items.indices.toSet() }) { Text("إظهار الكل") }
        TextButton(onClick = { shown = emptySet() }) { Text("إخفاء الإجابات") }
    }
}

/** شجرة قرار: أسئلة بنعم / لا تقود إلى النتيجة. */
@Composable
private fun FlowDiagram(d: Diagram) {
    val byId = remember(d.id) { d.nodes.associateBy { it.id } }
    val first = d.nodes.firstOrNull()?.id ?: return
    var path by remember(d.id) { mutableStateOf(listOf(first)) }
    var choices by remember(d.id) { mutableStateOf(listOf<Boolean>()) }
    val node = byId[path.last()] ?: return

    path.dropLast(1).forEachIndexed { i, id ->
        val prev = byId[id]
        if (prev != null) {
            val yes = choices.getOrNull(i) == true
            Text(
                "• " + prev.text + "  ←  " + (if (yes) "نعم" else "لا"),
                fontSize = 13.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (node.answer.isNotBlank()) {
        Surface(shape = RoundedCornerShape(8.dp), color = OkBg, modifier = Modifier.fillMaxWidth()) {
            Text(
                node.answer, Modifier.padding(14.dp), fontSize = 17.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFF1B3A1C),
            )
        }
    } else {
        Text(node.text, fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { path = path + node.yes; choices = choices + true },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
            ) { Text("نعم", fontSize = 16.sp) }
            OutlinedButton(
                onClick = { path = path + node.no; choices = choices + false },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
            ) { Text("لا", fontSize = 16.sp) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (path.size > 1) {
            TextButton(onClick = { path = path.dropLast(1); choices = choices.dropLast(1) }) { Text("الخطوة السابقة") }
            TextButton(onClick = { path = listOf(first); choices = emptyList() }) { Text("ابدأ من جديد") }
        }
    }
}

/** شريط زمني: اضغط على مرحلة لتفاصيلها. عرض الشرائح للتوضيح فقط. */
@Composable
private fun TimelineDiagram(d: Diagram) {
    var sel by remember(d.id) { mutableStateOf(0) }
    val idx = sel.coerceIn(0, (d.items.size - 1).coerceAtLeast(0))
    Row(
        Modifier.fillMaxWidth().height(60.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        d.items.forEachIndexed { i, item ->
            val active = i == idx
            Card(
                onClick = { sel = i },
                modifier = Modifier.weight(item.weight.coerceAtLeast(0.1f)).fillMaxHeight(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        item.label, fontSize = 13.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    val current = d.items.getOrNull(idx)
    if (current != null) DetailBox(current)
}
