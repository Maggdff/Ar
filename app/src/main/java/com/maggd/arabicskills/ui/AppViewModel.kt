package com.maggd.arabicskills.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.maggd.arabicskills.data.ContentLoader
import com.maggd.arabicskills.data.ProgressStore
import com.maggd.arabicskills.logic.*
import com.maggd.arabicskills.model.*

sealed class Screen {
    object Home : Screen()
    object Lessons : Screen()
    data class LessonDetail(val lessonId: String) : Screen()
    data class QuizSetup(val scope: QuizScope? = null) : Screen()
    object Quiz : Screen()
    object Result : Screen()
    object Mistakes : Screen()
    object Cards : Screen()
    object Stats : Screen()
}

data class QuizState(
    val label: String,
    val items: List<QuizItem>,
    val index: Int = 0,
    val selected: String? = null,
    val results: List<AnswerResult> = emptyList(),
) {
    val current: QuizItem get() = items[index]
    val answered: Boolean get() = selected != null
    val isLast: Boolean get() = index == items.lastIndex
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val content: Content = ContentLoader.load(app)
    private val store = ProgressStore(app)

    var progress by mutableStateOf(store.load()); private set
    var quiz by mutableStateOf<QuizState?>(null); private set
    var lastResult by mutableStateOf<QuizState?>(null); private set

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    // ---- التنقل ----
    fun navigate(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        if (current == Screen.Quiz) quiz = null
        stack.removeAt(stack.lastIndex)
        return true
    }
    /** تبديل تبويب رئيسي: يعيد المكدس إلى الرئيسية ثم التبويب */
    fun goTab(s: Screen) {
        if (current == s) return
        quiz = null
        stack.clear(); stack.add(Screen.Home)
        if (s != Screen.Home) stack.add(s)
    }

    // ---- الحفظ ----
    private fun update(p: Progress) { progress = p; store.save(p) }

    fun markLessonRead(id: String) { if (id !in progress.lessonsRead) update(ProgressLogic.markLessonRead(progress, id)) }
    fun reviewCard(qid: String, knew: Boolean) = update(ProgressLogic.reviewCard(progress, qid, knew))
    fun dismissMistake(qid: String) = update(ProgressLogic.clearMistake(progress, qid))
    fun resetProgress() { update(ProgressLogic.reset()) }

    // ---- الاختبار ----
    fun scopeLabel(scope: QuizScope): String = when (scope) {
        is QuizScope.OfLesson -> "درس: " + (content.lesson(scope.lessonId)?.title ?: scope.lessonId)
        is QuizScope.OfUnit -> "وحدة: " + (content.unit(scope.unitId)?.title ?: scope.unitId)
        QuizScope.WholeBook -> "الكتاب كاملًا"
        QuizScope.Mistakes -> "إعادة اختبار الأخطاء"
    }

    fun poolSize(scope: QuizScope): Int = QuizEngine.pool(content, scope, progress.wrongSet).size

    fun startQuiz(scope: QuizScope, count: Int) {
        val pool = QuizEngine.pool(content, scope, progress.wrongSet)
        if (pool.isEmpty()) return
        val items = QuizEngine.build(pool, count, System.nanoTime())
        quiz = QuizState(scopeLabel(scope), items)
        // إن كانت شاشة الإعداد في أعلى المكدس نستبدلها بالاختبار
        if (current is Screen.QuizSetup) stack.removeAt(stack.lastIndex)
        stack.add(Screen.Quiz)
    }

    fun answer(option: String) {
        val q = quiz ?: return
        if (q.answered) return
        val item = q.current
        val ok = QuizEngine.isCorrect(item.question, option)
        update(ProgressLogic.recordAnswer(progress, item.question.id, ok))
        quiz = q.copy(selected = option, results = q.results + AnswerResult(item.question.id, option, ok))
    }

    fun next() {
        val q = quiz ?: return
        if (!q.answered) return
        if (q.isLast) {
            val (c, t) = QuizEngine.summarize(q.results)
            update(ProgressLogic.recordSession(progress, SessionRecord(System.currentTimeMillis(), q.label, t, c)))
            lastResult = q
            quiz = null
            stack.removeAt(stack.lastIndex)
            stack.add(Screen.Result)
        } else {
            quiz = q.copy(index = q.index + 1, selected = null)
        }
    }
}
