package com.maggd.arabicskills.logic

import com.maggd.arabicskills.model.*

object ProgressLogic {
    const val MAX_BOX = 3
    private const val MAX_SESSIONS = 100

    /** يسجل إجابة: الخطأ يضيف السؤال لقائمة الأخطاء، والصواب اللاحق يزيله. */
    fun recordAnswer(p: Progress, questionId: String, correct: Boolean): Progress = p.copy(
        attempts = p.attempts + (questionId to (p.attempts[questionId] ?: 0) + 1),
        correctCounts = if (correct) p.correctCounts + (questionId to (p.correctCounts[questionId] ?: 0) + 1) else p.correctCounts,
        lastCorrect = p.lastCorrect + (questionId to correct),
        wrongSet = if (correct) p.wrongSet - questionId else p.wrongSet + questionId,
    )

    fun recordSession(p: Progress, rec: SessionRecord): Progress =
        p.copy(sessions = (p.sessions + rec).takeLast(MAX_SESSIONS))

    fun markLessonRead(p: Progress, lessonId: String): Progress = p.copy(lessonsRead = p.lessonsRead + lessonId)

    /** بطاقات المراجعة (نظام لايتنر مبسط): عرفتها ← صندوق أعلى، لم أعرفها ← الصندوق 0. */
    fun reviewCard(p: Progress, questionId: String, knew: Boolean): Progress {
        val cur = p.boxes[questionId] ?: 0
        val next = if (knew) minOf(cur + 1, MAX_BOX) else 0
        return p.copy(boxes = p.boxes + (questionId to next))
    }

    /** ترتيب البطاقات: الأقل إتقانًا أولًا ثم الترتيب الأصلي (حتمي). */
    fun orderDeck(p: Progress, deck: List<Question>): List<Question> =
        deck.withIndex().sortedWith(compareBy({ p.boxes[it.value.id] ?: 0 }, { it.index })).map { it.value }

    fun clearMistake(p: Progress, questionId: String): Progress = p.copy(wrongSet = p.wrongSet - questionId)
    fun reset(): Progress = Progress()
}

data class Stats(
    val totalQuestionsAvailable: Int,
    val distinctAnswered: Int,
    val totalAttempts: Int,
    val totalCorrect: Int,
    val accuracyPercent: Int,
    val mistakesCount: Int,
    val lessonsRead: Int,
    val lessonsTotal: Int,
    val masteredCards: Int,
    val sessionsCount: Int,
)

data class LessonStat(val lessonId: String, val answered: Int, val available: Int, val accuracyPercent: Int)

object StatsLogic {
    fun overall(c: Content, p: Progress): Stats {
        val att = p.attempts.values.sum()
        val cor = p.correctCounts.values.sum()
        return Stats(
            totalQuestionsAvailable = c.questions.count { it.type.autoGraded },
            distinctAnswered = p.attempts.size,
            totalAttempts = att,
            totalCorrect = cor,
            accuracyPercent = QuizEngine.percent(cor, att),
            mistakesCount = p.wrongSet.size,
            lessonsRead = p.lessonsRead.size,
            lessonsTotal = c.lessons.size,
            masteredCards = p.boxes.values.count { it >= ProgressLogic.MAX_BOX },
            sessionsCount = p.sessions.size,
        )
    }

    fun perLesson(c: Content, p: Progress): List<LessonStat> = c.lessons.map { l ->
        val qs = c.questionsOfLesson(l.id).filter { it.type.autoGraded }
        val att = qs.sumOf { p.attempts[it.id] ?: 0 }
        val cor = qs.sumOf { p.correctCounts[it.id] ?: 0 }
        LessonStat(l.id, qs.count { (p.attempts[it.id] ?: 0) > 0 }, qs.size, QuizEngine.percent(cor, att))
    }

    /** الدروس الأضعف: لها محاولات ودقتها الأدنى */
    fun weakest(stats: List<LessonStat>, n: Int = 3): List<LessonStat> =
        stats.filter { it.answered > 0 }.sortedBy { it.accuracyPercent }.take(n)
}
