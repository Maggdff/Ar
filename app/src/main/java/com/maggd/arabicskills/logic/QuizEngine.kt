package com.maggd.arabicskills.logic

import com.maggd.arabicskills.model.*
import kotlin.random.Random

sealed class QuizScope {
    data class OfLesson(val lessonId: String) : QuizScope()
    data class OfUnit(val unitId: String) : QuizScope()
    object WholeBook : QuizScope()
    object Mistakes : QuizScope()
}

/** سؤال جاهز للعرض: الخيارات مرتبة (وقد تكون مخلوطة) */
data class QuizItem(val question: Question, val options: List<String>)

object QuizEngine {

    fun pool(content: Content, scope: QuizScope, wrongSet: Set<String>): List<Question> {
        val base = when (scope) {
            is QuizScope.OfLesson -> content.questionsOfLesson(scope.lessonId)
            is QuizScope.OfUnit -> content.questionsOfUnit(scope.unitId)
            QuizScope.WholeBook -> content.questions
            QuizScope.Mistakes -> content.questions.filter { it.id in wrongSet }
        }
        return base.filter { it.type.autoGraded }
    }

    /**
     * يبني اختبارًا. count<=0 يعني كل الأسئلة. الخلط حتمي عند تثبيت seed.
     * خلط الخيارات لأسئلة الاختيار من متعدد فقط؛ صح/خطأ تبقى بترتيبها.
     */
    fun build(pool: List<Question>, count: Int, seed: Long): List<QuizItem> {
        val rnd = Random(seed)
        val picked = pool.shuffled(rnd).let { if (count > 0) it.take(count) else it }
        return picked.map { q ->
            val opts = if (q.type == QType.MCQ) q.options.shuffled(rnd) else q.options
            QuizItem(q, opts)
        }
    }

    fun isCorrect(q: Question, selected: String): Boolean = selected.trim() == q.answer.trim()

    fun summarize(results: List<AnswerResult>): Pair<Int, Int> = results.count { it.correct } to results.size

    fun percent(correct: Int, total: Int): Int =
        if (total <= 0) 0 else Math.round(correct * 100f / total)
}
