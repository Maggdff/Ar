package com.maggd.arabicskills

import com.maggd.arabicskills.data.ProgressCodec
import com.maggd.arabicskills.logic.*
import com.maggd.arabicskills.model.*
import org.junit.Assert.*
import org.junit.Test

class ProgressLogicTest {
    private val c = TestSupport.content

    @Test fun wrongThenCorrectRemovesFromMistakes() {
        var p = Progress()
        p = ProgressLogic.recordAnswer(p, "Q0001", false)
        assertTrue("Q0001" in p.wrongSet)
        p = ProgressLogic.recordAnswer(p, "Q0001", true)
        assertFalse("Q0001" in p.wrongSet)
        assertEquals(2, p.attempts["Q0001"])
        assertEquals(1, p.correctCounts["Q0001"])
    }

    @Test fun leitnerBoxes() {
        var p = Progress()
        repeat(5) { p = ProgressLogic.reviewCard(p, "Q1", true) }
        assertEquals(ProgressLogic.MAX_BOX, p.boxes["Q1"])
        p = ProgressLogic.reviewCard(p, "Q1", false)
        assertEquals(0, p.boxes["Q1"])
    }

    @Test fun deckOrderLowBoxFirst() {
        val deck = c.questions.take(3)
        val p = Progress(boxes = mapOf(deck[0].id to 2, deck[1].id to 0, deck[2].id to 1))
        assertEquals(listOf(deck[1].id, deck[2].id, deck[0].id), ProgressLogic.orderDeck(p, deck).map { it.id })
    }

    @Test fun statsAccuracy() {
        var p = Progress()
        p = ProgressLogic.recordAnswer(p, "Q0001", true)
        p = ProgressLogic.recordAnswer(p, "Q0002", false)
        val s = StatsLogic.overall(c, p)
        assertEquals(2, s.totalAttempts); assertEquals(50, s.accuracyPercent); assertEquals(1, s.mistakesCount)
    }

    @Test fun sessionsCapped() {
        var p = Progress()
        repeat(130) { p = ProgressLogic.recordSession(p, SessionRecord(it.toLong(), "x", 10, 5)) }
        assertEquals(100, p.sessions.size)
        assertEquals(30L, p.sessions.first().timestamp)
    }

    @Test fun codecRoundTrip() {
        var p = Progress()
        p = ProgressLogic.recordAnswer(p, "Q0001", false)
        p = ProgressLogic.reviewCard(p, "Q0003", true)
        p = ProgressLogic.markLessonRead(p, "U1-L01")
        p = ProgressLogic.recordSession(p, SessionRecord(123L, "اختبار «الكتاب»", 10, 7))
        assertEquals(p, ProgressCodec.decode(ProgressCodec.encode(p)))
    }

    @Test fun corruptedStorageFallsBackToEmpty() {
        assertEquals(Progress(), ProgressCodec.decode("{not json"))
        assertEquals(Progress(), ProgressCodec.decode(null))
    }
}
