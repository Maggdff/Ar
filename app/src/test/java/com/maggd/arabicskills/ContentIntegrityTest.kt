package com.maggd.arabicskills

import com.maggd.arabicskills.model.QType
import org.junit.Assert.*
import org.junit.Test

class ContentIntegrityTest {
    private val c = TestSupport.content

    @Test fun countsMatchGeneratedData() {
        assertEquals(3, c.units.size)
        assertEquals(33, c.lessons.size)
        assertEquals(148, c.questions.size)
        assertEquals(18, c.corrections.size)
        assertFalse(c.meta.isCompleteBook)
    }

    @Test fun idsAreUnique() {
        assertEquals(c.questions.size, c.questions.map { it.id }.toSet().size)
        assertEquals(c.lessons.size, c.lessons.map { it.id }.toSet().size)
    }

    @Test fun everyAutoGradedAnswerIsAmongOptions() {
        for (q in c.questions.filter { it.type.autoGraded }) {
            assertTrue("${q.id} answer not in options", q.answer in q.options)
            if (q.type == QType.TRUE_FALSE) assertEquals(listOf("صحيح", "خطأ"), q.options)
            if (q.type == QType.MCQ) assertEquals("${q.id} options", 4, q.options.size)
        }
    }

    @Test fun referencesResolve() {
        for (q in c.questions) {
            q.lessonIds.forEach { assertNotNull("${q.id}->$it", c.lesson(it)) }
            q.unitIds.forEach { assertNotNull("${q.id}->$it", c.unit(it)) }
        }
        for (u in c.units) u.lessonIds.forEach { assertEquals(u.id, c.lesson(it)!!.unitId) }
    }

    @Test fun everyLessonHasQuiz() {
        for (l in c.lessons) assertTrue(l.id, c.questionsOfLesson(l.id).any { it.type.autoGraded })
    }

    @Test fun documentedOverridesApplied() {
        val q = c.questions.first { it.id == "Q0142" }
        assertTrue(q.appNote.isNotBlank())
    }

    @Test fun noInternalEditorialNotesInVisibleText() {
        val bad = Regex("⚠|corrections_log|review_notes|COR-\\d|\\bU\\d-L\\d\\d\\b|المدقق|تلف النص|الاستخراج الضوئي|نص الكتاب الأصلي")
        val texts = c.lessons.flatMap { l ->
            listOf(l.title, l.ruleText, l.summary, l.differences, l.commonErrors) + l.conditions + l.examples.map { it.text + " " + it.note }
        } + c.questions.flatMap { listOf(it.text, it.answer, it.explanation, it.correctionIfFalse) + it.options }
        for (t in texts) assertFalse("internal note: " + t.take(50), bad.containsMatchIn(t))
    }

    @Test fun correctedFactsPresent() {
        assertTrue(c.lesson("U3-L06")!!.commonErrors.contains("جرير"))
        assertTrue(c.lesson("U3-L07")!!.commonErrors.contains("1258"))
        assertFalse(c.lesson("U2-L07")!!.differences.contains("متقاربان جدًا في المخرج"))
    }
}
