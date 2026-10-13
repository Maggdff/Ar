package com.duoprog.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.duoprog.app.data.model.ContentBlock
import com.duoprog.app.data.model.Exercise
import com.duoprog.app.data.model.ExerciseType
import com.duoprog.app.data.model.Lesson
import com.duoprog.app.engine.runMiniCpp
import kotlinx.coroutines.launch

private val Green = Color(0xFF58CC02)
private val Red = Color(0xFFFF4B4B)

private val latinRun = Regex(
    "[A-Za-z0-9#<>_+*/%=(){}\\[\\]\"'&|!\\\\.:;~^-]+(?:[ ,]+[A-Za-z0-9#<>_+*/%=(){}\\[\\]\"'&|!\\\\.:;~^-]+)*"
)

/** يعزل المقاطع الإنجليزية/البرمجية داخل النص العربي حتى لا يختلط ترتيب العرض */
fun bidi(s: String): String = latinRun.replace(s) { m ->
    if (m.value.any { it.isLetterOrDigit() }) "\u2066" + m.value + "\u2069" else m.value
}

private fun norm(s: String) = s.replace(Regex("\\s+"), "")

@Composable
fun LessonScreen(lesson: Lesson, onExit: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { (context.applicationContext as? DuoProgApplication)?.repository }
    val scope = rememberCoroutineScope()

    // 0 = شرح الدرس، 1 = التمارين، 2 = النتيجة
    var phase by remember(lesson.id) { mutableStateOf(0) }
    var index by remember(lesson.id) { mutableStateOf(0) }
    var wrong by remember(lesson.id) { mutableStateOf(0) }
    var xp by remember(lesson.id) { mutableStateOf(0) }

    BackHandler(enabled = phase == 1) { phase = 0 }

    fun finish() {
        val stars = when { wrong == 0 -> 3; wrong <= 2 -> 2; else -> 1 }
        scope.launch {
            try { repo?.markLessonCompleted(lesson.id, lesson.unitId, stars) } catch (_: Exception) {}
            onExit()
        }
        phase = 2
    }

    when (phase) {
        0 -> LessonContent(lesson) {
            if (lesson.exercises.isEmpty()) finish() else phase = 1
        }
        1 -> {
            val ex = lesson.exercises[index]
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                LinearProgressIndicator(
                    progress = { index.toFloat() / lesson.exercises.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = Green
                )
                Spacer(Modifier.height(4.dp))
                Text("تمرين ${index + 1} من ${lesson.exercises.size}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
                key(ex.id) {
                    ExerciseView(
                        ex = ex,
                        isLast = index == lesson.exercises.size - 1,
                        onChecked = { correct, answer ->
                            if (!correct) wrong++ else xp += 1
                            scope.launch {
                                try {
                                    repo?.recordAttempt(ex.id, lesson.id, correct, answer)
                                    if (correct) {
                                        repo?.addXp(if (ex.xpReward > 0) ex.xpReward else 10)
                                        repo?.incrementCorrectAnswers()
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        onNext = { if (index == lesson.exercises.size - 1) finish() else index++ }
                    )
                }
            }
        }
        else -> Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun LessonContent(lesson: Lesson, onStart: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { Text(bidi(lesson.title), style = MaterialTheme.typography.headlineSmall) }
        items(lesson.contentBlocks) { block -> ContentBlockView(block) }
        item {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) { Text(if (lesson.exercises.isEmpty()) "إنهاء الدرس" else "ابدأ التمارين") }
        }
    }
}

@Composable
private fun ContentBlockView(block: ContentBlock) {
    when (block.type) {
        "code" -> CodeBox(block.lines)
        "note" -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4CC))
        ) {
            Column(Modifier.padding(12.dp)) {
                block.lines.forEach { Text(bidi("💡 $it"), color = Color.Black) }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.lines.forEach { Text(bidi(it), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun CodeBox(lines: List<String>, highlight: Int? = null, onLineClick: ((Int) -> Unit)? = null) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.fillMaxWidth()
                .background(Color(0xFF1E1E2E), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            lines.forEachIndexed { i, line ->
                val bg = if (highlight == i) Color(0x55FF4B4B) else Color.Transparent
                Text(
                    line.ifEmpty { " " },
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFE0E0E0),
                    modifier = Modifier.fillMaxWidth()
                        .background(bg, RoundedCornerShape(4.dp))
                        .let { if (onLineClick != null) it.clickable { onLineClick(i) } else it }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ExerciseView(
    ex: Exercise,
    isLast: Boolean,
    onChecked: (Boolean, String) -> Unit,
    onNext: () -> Unit
) {
    var selOption by remember { mutableStateOf<Int?>(null) }
    val blanks = remember { mutableStateListOf<String>().apply { repeat(ex.blankAnswers?.size ?: 0) { add("") } } }
    var text by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Int>() }
    var selLine by remember { mutableStateOf<Int?>(null) }
    var runOut by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Boolean?>(null) }

    val locked = result != null

    val canCheck = when (ex.type) {
        ExerciseType.MCQ -> selOption != null
        ExerciseType.CODE_COMPLETION -> blanks.all { it.isNotBlank() }
        ExerciseType.LINE_ORDERING -> picked.size == (ex.shuffledLines?.size ?: 0)
        ExerciseType.BUG_FINDING -> selLine != null
        ExerciseType.OUTPUT_PREDICTION -> text.isNotBlank()
        ExerciseType.RUN_CODE -> true
    }

    fun check() {
        val (ok, answer) = when (ex.type) {
            ExerciseType.MCQ -> (selOption == ex.correctOptionIndex) to (selOption?.toString() ?: "")
            ExerciseType.CODE_COMPLETION -> {
                val expected = ex.blankAnswers ?: emptyList()
                expected.indices.all { norm(blanks[it]) == norm(expected[it]) } to blanks.joinToString("|")
            }
            ExerciseType.LINE_ORDERING -> (picked.toList() == ex.correctOrder) to picked.joinToString(",")
            ExerciseType.BUG_FINDING -> (selLine == ex.correctLineIndex) to (selLine?.toString() ?: "")
            ExerciseType.OUTPUT_PREDICTION -> (norm(text) == norm(ex.expectedOutput ?: "")) to text
            ExerciseType.RUN_CODE -> {
                val r = runMiniCpp((ex.code ?: emptyList()).joinToString("\n"), ex.stdin ?: emptyList())
                val out = if (r.error != null) "خطأ: ${r.error}" else r.output
                runOut = out
                (r.error == null && norm(r.output) == norm(ex.expectedOutput ?: "")) to out
            }
        }
        result = ok
        onChecked(ok, answer)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(bidi(ex.question), style = MaterialTheme.typography.titleMedium)

            when (ex.type) {
                ExerciseType.MCQ -> ex.options.orEmpty().forEachIndexed { i, opt ->
                    val chosen = selOption == i
                    val border = when {
                        locked && i == ex.correctOptionIndex -> Green
                        locked && chosen -> Red
                        chosen -> MaterialTheme.colorScheme.primary
                        else -> Color.LightGray
                    }
                    OutlinedCard(
                        Modifier.fillMaxWidth().clickable(enabled = !locked) { selOption = i },
                        border = BorderStroke(2.dp, border)
                    ) { Text(bidi(opt), Modifier.padding(14.dp)) }
                }

                ExerciseType.CODE_COMPLETION -> {
                    CodeBox(ex.codeTemplate.orEmpty())
                    blanks.forEachIndexed { i, v ->
                        OutlinedTextField(
                            value = v,
                            onValueChange = { blanks[i] = it },
                            enabled = !locked,
                            singleLine = true,
                            label = { Text("الفراغ ${i + 1}") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ExerciseType.LINE_ORDERING -> {
                    val lines = ex.shuffledLines.orEmpty()
                    Text("اضغط على الأسطر بالترتيب الصحيح:", style = MaterialTheme.typography.labelMedium)
                    lines.forEachIndexed { i, line ->
                        val pos = picked.indexOf(i)
                        OutlinedCard(
                            Modifier.fillMaxWidth().clickable(enabled = !locked) {
                                if (pos >= 0) picked.remove(i) else picked.add(i)
                            },
                            border = BorderStroke(2.dp, if (pos >= 0) MaterialTheme.colorScheme.primary else Color.LightGray)
                        ) {
                            Row(Modifier.padding(12.dp)) {
                                if (pos >= 0) Text("${pos + 1}.  ", color = MaterialTheme.colorScheme.primary)
                                Text(bidi(line))
                            }
                        }
                    }
                    if (picked.isNotEmpty() && !locked) {
                        TextButton(onClick = { picked.clear() }) { Text("إعادة الترتيب") }
                    }
                }

                ExerciseType.BUG_FINDING -> {
                    Text("اضغط على السطر الذي فيه الخطأ:", style = MaterialTheme.typography.labelMedium)
                    CodeBox(
                        ex.buggyCode.orEmpty(),
                        highlight = selLine,
                        onLineClick = if (locked) null else { i -> selLine = i }
                    )
                }

                ExerciseType.OUTPUT_PREDICTION -> {
                    CodeBox(ex.code.orEmpty())
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !locked,
                        label = { Text("الناتج") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                ExerciseType.RUN_CODE -> {
                    CodeBox(ex.code.orEmpty())
                    if (!ex.stdin.isNullOrEmpty()) Text(bidi("المدخلات: " + ex.stdin.joinToString("، ")))
                    runOut?.let {
                        CodeBox(listOf("الناتج: $it"))
                    }
                }
            }

            result?.let { ok ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (ok) Color(0xFFD7FFB8) else Color(0xFFFFDFE0))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (ok) "✅ إجابة صحيحة!" else "❌ إجابة خاطئة",
                            color = Color.Black,
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (!ok) {
                            val correct = when (ex.type) {
                                ExerciseType.MCQ -> ex.options?.getOrNull(ex.correctOptionIndex ?: -1)
                                ExerciseType.CODE_COMPLETION -> ex.blankAnswers?.joinToString("  ،  ")
                                ExerciseType.LINE_ORDERING -> ex.correctOrder?.joinToString("\n") { ex.shuffledLines?.getOrNull(it) ?: "" }
                                ExerciseType.BUG_FINDING -> ex.fixedLineText
                                else -> ex.expectedOutput
                            }
                            if (!correct.isNullOrBlank()) Text(bidi("الإجابة الصحيحة:\n$correct"), color = Color.Black)
                        }
                        ex.explanation?.let { Text(bidi(it), color = Color.Black) }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (!locked) {
            Button(
                onClick = { check() },
                enabled = canCheck,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Green)
            ) { Text(if (ex.type == ExerciseType.RUN_CODE) "شغّل وتحقق" else "تحقق") }
        } else {
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (result == true) Green else Red)
            ) { Text(if (isLast) "إنهاء الدرس" else "التالي") }
        }
    }
}
