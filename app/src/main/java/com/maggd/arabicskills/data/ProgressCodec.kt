package com.maggd.arabicskills.data

import com.maggd.arabicskills.model.Progress
import com.maggd.arabicskills.model.SessionRecord
import kotlinx.serialization.json.*

/** ترميز/فك ترميز التقدم إلى JSON للحفظ المحلي. */
object ProgressCodec {
    private fun intMap(m: Map<String, Int>) = JsonObject(m.mapValues { JsonPrimitive(it.value) })

    fun encode(p: Progress): String = buildJsonObject {
        put("v", 1)
        put("attempts", intMap(p.attempts))
        put("correctCounts", intMap(p.correctCounts))
        put("lastCorrect", JsonObject(p.lastCorrect.mapValues { JsonPrimitive(it.value) }))
        put("wrongSet", JsonArray(p.wrongSet.sorted().map { JsonPrimitive(it) }))
        put("boxes", intMap(p.boxes))
        put("lessonsRead", JsonArray(p.lessonsRead.sorted().map { JsonPrimitive(it) }))
        put("sessions", JsonArray(p.sessions.map {
            buildJsonObject {
                put("t", it.timestamp); put("label", it.label)
                put("total", it.total); put("correct", it.correct)
            }
        }))
    }.toString()

    /** أي تلف في الملف يُرجع تقدمًا فارغًا بدل إسقاط التطبيق. */
    fun decode(s: String?): Progress {
        if (s.isNullOrBlank()) return Progress()
        return try {
            val o = Json.parseToJsonElement(s).jsonObject
            fun ints(k: String): Map<String, Int> =
                (o[k] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).int } ?: emptyMap()
            fun strs(k: String): Set<String> =
                (o[k] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: emptySet()
            Progress(
                attempts = ints("attempts"),
                correctCounts = ints("correctCounts"),
                lastCorrect = (o["lastCorrect"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).boolean } ?: emptyMap(),
                wrongSet = strs("wrongSet"),
                boxes = ints("boxes"),
                lessonsRead = strs("lessonsRead"),
                sessions = (o["sessions"] as? JsonArray)?.map {
                    val so = it.jsonObject
                    SessionRecord(so["t"]!!.jsonPrimitive.long, so["label"]!!.jsonPrimitive.content,
                        so["total"]!!.jsonPrimitive.int, so["correct"]!!.jsonPrimitive.int)
                } ?: emptyList(),
            )
        } catch (e: Exception) {
            Progress()
        }
    }
}
