package com.maggd.smartstudy

import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray

private val Navy = Color(0xFF0A1730)
private val Blue = Color(0xFF2563EB)
private val Teal = Color(0xFF14B8A6)
private val LightBg = Color(0xFFF4F7FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 23, 48)
        window.navigationBarColor = android.graphics.Color.rgb(10, 23, 48)
        setContent { SmartStudyApp() }
    }
}

@Composable
fun SmartStudyApp() {
    var dark by remember { mutableStateOf(false) }
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF7CA6FF), secondary = Teal, background = Color(0xFF0B1220), surface = Color(0xFF152238))
                 else lightColorScheme(primary = Blue, secondary = Teal, background = LightBg, surface = Color.White)
    MaterialTheme(colorScheme = scheme) {
        var page by remember { mutableStateOf("home") }
        var selected by remember { mutableStateOf<Chapter?>(null) }
        var quizIndex by remember { mutableIntStateOf(0) }
        var score by remember { mutableIntStateOf(0) }
        var chosen by remember { mutableIntStateOf(-1) }
        var answered by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val chapters = remember {
            val json = JSONArray(context.assets.open("chapters.json").bufferedReader().use { it.readText() })
            (0 until json.length()).map { i ->
                val obj = json.getJSONObject(i)
                Chapter(obj.getInt("id"), obj.getString("title"), obj.getString("content"))
            }
        }
        val questions = remember { quizQuestions }
        var activeQuestions by remember { mutableStateOf(questions) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                "home" -> HomeScreen(chapters, dark, { dark = !dark }, { page = "chapters" }, {
                    activeQuestions = questions; quizIndex = 0; score = 0; chosen = -1; answered = false; page = "quiz"
                })
                "chapters" -> ChaptersScreen(chapters, onBack = { page = "home" }, onOpen = { selected = it; page = "lesson" })
                "lesson" -> selected?.let { LessonScreen(it, { page = "chapters" }, {
                    activeQuestions = questions.filter { q -> q.chapterId == it.id }.ifEmpty { questions }
                    quizIndex = 0; score = 0; chosen = -1; answered = false; page = "quiz"
                }) }
                "quiz" -> {
                    val q = activeQuestions.getOrNull(quizIndex)
                    if (q == null) ResultScreen(score, activeQuestions.size, { page = "home" }, { quizIndex = 0; score = 0; chosen = -1; answered = false })
                    else QuizScreen(q, quizIndex + 1, activeQuestions.size, chosen, answered,
                        onChoose = { if (!answered) chosen = it },
                        onCheck = { if (!answered && chosen >= 0) { answered = true; if (chosen == q.answerIndex) score++ } },
                        onNext = { quizIndex++; chosen = -1; answered = false })
                }
                else -> HomeScreen(chapters, dark, { dark = !dark }, { page = "chapters" }, { page = "quiz" })
            }
        }
    }
}

@Composable
private fun HomeScreen(chapters: List<Chapter>, dark: Boolean, onToggleDark: () -> Unit, onChapters: () -> Unit, onQuiz: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().background(Navy).padding(horizontal = 22.dp, vertical = 26.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Color.White.copy(alpha = 0.13f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.School, null, tint = Color.White, modifier = Modifier.padding(12.dp).size(28.dp))
                    }
                    IconButton(onClick = onToggleDark) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "تبديل المظهر", tint = Color.White) }
                }
                Spacer(Modifier.height(22.dp))
                Text("Smart Study", fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("ذاكر بذكاء", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7DD3FC))
                Spacer(Modifier.height(8.dp))
                Text("مادة مقدمة في الحاسوب", color = Color.White.copy(alpha = .85f), fontSize = 15.sp)
                Spacer(Modifier.height(20.dp))
                Text("Developed by Maggd Alosimi", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            }
        }
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("${chapters.size}", "فصل دراسي", Icons.Default.MenuBook, Modifier.weight(1f))
                StatCard("${quizQuestions.size}", "سؤال تدريبي", Icons.Default.Quiz, Modifier.weight(1f))
            }
            Text("ابدأ رحلتك", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            ActionCard("تصفح الفصول", "اقرأ محتوى المادة كاملًا حسب الفصول", Icons.Default.AutoStories, Blue, onChapters)
            ActionCard("اختبر معلوماتك", "أسئلة مع تصحيح وشرح ورسوم ثلاثية الأبعاد قابلة للتدوير", Icons.Default.Psychology, Teal, onQuiz)
            Text("مميزات التطبيق", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FeaturePill("واجهة عربية RTL")
                FeaturePill("وضع ليلي")
                FeaturePill("مجسمات تفاعلية")
            }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(25.dp))
            Column { Text(value, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = tint.copy(alpha = .12f), shape = RoundedCornerShape(15.dp)) { Icon(icon, null, tint = tint, modifier = Modifier.padding(13.dp).size(27.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeaturePill(label: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(50), tonalElevation = 1.dp) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChaptersScreen(chapters: List<Chapter>, onBack: () -> Unit, onOpen: (Chapter) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("الفصول الدراسية", onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chapters) { chapter ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(chapter) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        Surface(color = Blue.copy(alpha = .1f), shape = RoundedCornerShape(14.dp)) {
                            Text(chapter.id.toString().padStart(2, '0'), color = Blue, fontWeight = FontWeight.Bold, modifier = Modifier.padding(13.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(chapter.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("اضغط لقراءة الدرس", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronLeft, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonScreen(chapter: Chapter, onBack: () -> Unit, onQuiz: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(chapter.title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Surface(color = Blue.copy(alpha = .09f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoStories, null, tint = Blue)
                    Spacer(Modifier.width(10.dp))
                    Text("محتوى من الملف المرفق", color = Blue, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(chapter.content, fontSize = 15.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(20.dp))
        }
        Button(onClick = onQuiz, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Quiz, null); Spacer(Modifier.width(8.dp)); Text("ابدأ الاختبار")
        }
    }
}

@Composable
private fun QuizScreen(q: QuizQuestion, number: Int, total: Int, chosen: Int, answered: Boolean, onChoose: (Int) -> Unit, onCheck: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("اختبار المعرفة", {})
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("السؤال $number من $total", fontWeight = FontWeight.Bold)
                Text("${(number * 100 / total).coerceAtMost(100)}%", color = Blue, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = number.toFloat() / total, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text(q.prompt, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
            Spacer(Modifier.height(12.dp))
            Text("المجسم التوضيحي — المس واسحب للتدوير، واستخدم إصبعين للتكبير", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(20.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> Study3DView(ctx, q.scene).apply { preserveEGLContextOnPause = true } },
                    update = { }
                )
            }
            Spacer(Modifier.height(14.dp))
            q.options.forEachIndexed { i, option ->
                val selected = chosen == i
                val isCorrect = answered && i == q.answerIndex
                val isWrong = answered && selected && i != q.answerIndex
                val bg = when { isCorrect -> Color(0xFFDCFCE7); isWrong -> Color(0xFFFEE2E2); selected -> Blue.copy(alpha = .12f); else -> MaterialTheme.colorScheme.surface }
                val fg = when { isCorrect -> Color(0xFF166534); isWrong -> Color(0xFF991B1B); else -> MaterialTheme.colorScheme.onSurface }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !answered) { onChoose(i) },
                    shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = bg)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { if (!answered) onChoose(i) }, enabled = !answered)
                        Spacer(Modifier.width(8.dp))
                        Text(option, color = fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (isCorrect) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF16A34A))
                        if (isWrong) Icon(Icons.Default.Cancel, null, tint = Color(0xFFDC2626))
                    }
                }
            }
            if (answered) {
                Spacer(Modifier.height(8.dp))
                Text(if (chosen == q.answerIndex) "إجابة صحيحة، أحسنت!" else "الإجابة الصحيحة: ${q.options[q.answerIndex]}",
                    color = if (chosen == q.answerIndex) Color(0xFF16A34A) else Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                Text(q.explanation, modifier = Modifier.padding(top = 6.dp), fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!answered) {
                Button(onClick = onCheck, enabled = chosen >= 0, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("تحقق من الإجابة") }
            } else {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(if (number == total) "عرض النتيجة" else "السؤال التالي") }
            }
        }
    }
}

@Composable
private fun ResultScreen(score: Int, total: Int, onHome: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = Teal.copy(alpha = .12f), shape = RoundedCornerShape(30.dp)) {
            Icon(Icons.Default.EmojiEvents, null, tint = Teal, modifier = Modifier.padding(22.dp).size(58.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("أكملت الاختبار!", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("$score من $total", fontSize = 35.sp, fontWeight = FontWeight.Bold, color = Blue, modifier = Modifier.padding(10.dp))
        Text("راجع الدروس التي تحتاج إلى مزيد من المذاكرة ثم حاول مرة أخرى.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("إعادة الاختبار") }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("العودة للرئيسية") }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Navy).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowForward, "رجوع", tint = Color.White) }
        Text(title, modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2)
    }
}
