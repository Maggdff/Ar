package com.maggd.arabicskills

import com.maggd.arabicskills.logic.*
import com.maggd.arabicskills.model.*
import org.junit.Assert.*
import org.junit.Test

class QuizEngineTest {
    private val c = TestSupport.content

    @Test fun lessonPoolOnlyAutoGradedAndInLesson() {
        val pool = QuizEngine.pool(c, QuizScope.OfLesson("U1-L01"), emptySet())
        assertTrue(pool.isNotEmpty())
        assertTrue(pool.all { it.type.autoGraded && "U1-L01" in it.lessonIds })
    }

    @Test fun wholeBookPoolIs88() {
        assertEquals(88, QuizEngine.pool(c, QuizScope.WholeBook, emptySet()).size)
    }

    @Test fun buildIsDeterministicAndKeepsOptionsSet() {
        val pool = QuizEngine.pool(c, QuizScope.WholeBook, emptySet())
        val a = QuizEngine.build(pool, 10, 42)
        val b = QuizEngine.build(pool, 10, 42)
        assertEquals(10, a.size)
        assertEquals(a.map { it.question.id }, b.map { it.question.id })
        assertEquals(a.map { it.options }, b.map { it.options })
        for (it in a) assertEquals(it.question.options.toSet(), it.options.toSet())
    }

    @Test fun trueFalseOrderNeverShuffled() {
        val pool = QuizEngine.pool(c, QuizScope.WholeBook, emptySet()).filter { it.type == QType.TRUE_FALSE }
        for (it in QuizEngine.build(pool, 0, 7)) assertEquals(listOf("صحيح", "خطأ"), it.options)
    }

    @Test fun gradingUsesBookAnswer() {
        val q = c.questions.first { it.type == QType.TRUE_FALSE }
        assertTrue(QuizEngine.isCorrect(q, q.answer))
        assertFalse(QuizEngine.isCorrect(q, q.options.first { it != q.answer }))
    }

    @Test fun percentRounding() {
        assertEquals(0, QuizEngine.percent(0, 0))
        assertEquals(67, QuizEngine.percent(2, 3))
        assertEquals(100, QuizEngine.percent(5, 5))
    }

    @Test fun mistakesPoolFollowsWrongSet() {
        val pool = QuizEngine.pool(c, QuizScope.Mistakes, setOf("Q0001", "Q0002"))
        assertTrue(pool.all { it.id in setOf("Q0001", "Q0002") })
    }
}
