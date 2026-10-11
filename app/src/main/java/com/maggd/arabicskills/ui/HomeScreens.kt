@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maggd.arabicskills.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.maggd.arabicskills.logic.QuizScope
import com.maggd.arabicskills.logic.StatsLogic

/** شريط المطوّر: يظهر أعلى الشاشة الرئيسية فقط (وليس في الإعدادات). */
@Composable
fun DeveloperCredit() {
    Surface(color = Green, modifier = Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                "Developed by Maggd Alosimi",
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 10.dp),
                textAlign = TextAlign.Center, color = Color.White,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
            )
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val stats = StatsLogic.overall(vm.content, vm.progress)
    Scaffold(
        topBar = { DeveloperCredit() },
        bottomBar = { BottomTabs(vm) },
    ) { pad ->
        ScrollColumn(pad) {
            Text(vm.content.meta.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("مذاكرة وتدريب على قواعد الكتاب ومهاراته، دون حاجة إلى إنترنت.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${stats.lessonsRead}/${stats.lessonsTotal}", "دروس قرأتها", Modifier.weight(1f))
                StatTile("${stats.accuracyPercent}%", "نسبة الصواب", Modifier.weight(1f))
                StatTile("${stats.mistakesCount}", "أخطاء للمراجعة", Modifier.weight(1f))
            }

            BigButton("اختبار سريع (10 أسئلة من الكتاب كاملًا)") { vm.startQuiz(QuizScope.WholeBook, 10) }
            BigButton("تصفّح الدروس", secondary = true) { vm.goTab(Screen.Lessons) }
            BigButton(
                if (stats.mistakesCount > 0) "إعادة اختبار أخطائي (${stats.mistakesCount})" else "لا توجد أخطاء محفوظة بعد",
                enabled = stats.mistakesCount > 0, secondary = true,
            ) { vm.navigate(Screen.Mistakes) }
            BigButton("بطاقات المراجعة", secondary = true) { vm.goTab(Screen.Cards) }

        }
    }
}

@Composable
fun LessonsScreen(vm: AppViewModel) {
    AppScaffold("الدروس", onBack = { vm.back() }, bottomBar = { BottomTabs(vm) }) { pad ->
        ScrollColumn(pad) {
            for (u in vm.content.units) {
                Text(u.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                for (l in vm.content.lessonsOf(u.id)) {
                    val read = l.id in vm.progress.lessonsRead
                    Card(
                        onClick = { vm.navigate(Screen.LessonDetail(l.id)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(l.title, Modifier.weight(1f), fontSize = 16.sp)
                            if (read) Tag("تمت القراءة")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LessonDetailScreen(vm: AppViewModel, lessonId: String) {
    val lesson = vm.content.lesson(lessonId)
    LaunchedEffect(lessonId) { vm.markLessonRead(lessonId) }
    AppScaffold(lesson?.title ?: "درس", onBack = { vm.back() }) { pad ->
        if (lesson == null) { EmptyState("الدرس غير موجود"); return@AppScaffold }
        val quizCount = vm.poolSize(QuizScope.OfLesson(lessonId))
        ScrollColumn(pad) {
            Text(lesson.position, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (lesson.ruleText.isNotBlank()) SectionCard("القاعدة") { Body(lesson.ruleText) }
            if (lesson.conditions.isNotEmpty()) SectionCard("الشروط والتفاصيل") {
                lesson.conditions.forEach { Body("• $it") }
            }
            if (lesson.examples.isNotEmpty()) SectionCard("الأمثلة") {
                lesson.examples.forEachIndexed { i, e ->
                    Text(e.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (e.note.isNotBlank()) Text(e.note, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (i != lesson.examples.lastIndex) HorizontalDivider()
                }
            }
            if (lesson.summary.isNotBlank()) SectionCard("الخلاصة") { Body(lesson.summary) }
            if (lesson.differences.isNotBlank()) SectionCard("فروق مهمة") { Body(lesson.differences) }
            if (lesson.commonErrors.isNotBlank()) SectionCard("أخطاء شائعة") { Body(lesson.commonErrors) }
            BigButton(
                if (quizCount > 0) "اختبر نفسك في هذا الدرس ($quizCount سؤالًا)" else "لا أسئلة مصححة تلقائيًا لهذا الدرس",
                enabled = quizCount > 0,
            ) { vm.startQuiz(QuizScope.OfLesson(lessonId), 0) }
        }
    }
}
