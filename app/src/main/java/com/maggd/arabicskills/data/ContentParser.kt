package com.maggd.arabicskills.data

import com.maggd.arabicskills.model.*
import kotlinx.serialization.json.*

/** تحليل ملفات JSON المضمّنة (دون أي اعتماد على أندرويد ليمكن اختبارها). */
object ContentParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.strList(k: String): List<String> =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    fun parse(
        lessonsJson: String, questionsJson: String, correctionsJson: String, metaJson: String,
        diagramsJson: String = "[]",
    ): Content {
        val lroot = json.parseToJsonElement(lessonsJson).jsonObject
        val units = lroot["units"]!!.jsonArray.map {
            val o = it.jsonObject
            BookUnit(o.str("id"), o.str("title"), o.strList("lessonIds"))
        }
        val lessons = lroot["lessons"]!!.jsonArray.map {
            val o = it.jsonObject
            Lesson(
                id = o.str("id"), unitId = o.str("unitId"), title = o.str("title"),
                position = o.str("position"), ruleText = o.str("ruleText"),
                conditions = o.strList("conditions"),
                examples = (o["examples"] as? JsonArray)?.map { e ->
                    val eo = e.jsonObject
                    Example(eo.str("text"), eo.str("note"))
                } ?: emptyList(),
                summary = o.str("summary"), differences = o.str("differences"),
                commonErrors = o.str("commonErrors"),
                verificationStatus = o.str("verificationStatus"),
                verificationNotes = o.str("verificationNotes"),
                excerpt = o.str("excerpt"), extractionNote = o.str("extractionNote"),
            )
        }
        val questions = json.parseToJsonElement(questionsJson).jsonArray.map {
            val o = it.jsonObject
            Question(
                id = o.str("id"), text = o.str("text"), type = QType.fromKey(o.str("type")),
                options = o.strList("options"), answer = o.str("answer"),
                explanation = o.str("explanation"), correctionIfFalse = o.str("correctionIfFalse"),
                unitIds = o.strList("unitIds"), lessonIds = o.strList("lessonIds"),
                difficulty = o.str("difficulty"), sourceType = o.str("sourceType"),
                verification = o.str("verification"), appNote = o.str("appNote"),
            )
        }
        val corrections = json.parseToJsonElement(correctionsJson).jsonArray.map {
            val o = it.jsonObject
            Correction(
                o.str("id"), o.str("category"), o.str("lessonRef"), o.strList("lessonIds"),
                o.str("original"), o.str("suggested"), o.str("reason"),
                o.str("confidence"), o.str("status"), o.str("notes"),
            )
        }
        val mo = json.parseToJsonElement(metaJson).jsonObject
        val meta = BookMeta(
            mo.str("title"),
            (mo["isCompleteBook"] as? JsonPrimitive)?.booleanOrNull ?: false,
            mo.str("completenessNote"), mo.str("extractionStatus"),
        )
        val diagrams = json.parseToJsonElement(diagramsJson).jsonArray.map {
            val o = it.jsonObject
            Diagram(
                id = o.str("id"), lessonId = o.str("lessonId"), kind = o.str("kind"),
                title = o.str("title"), hint = o.str("hint"),
                items = (o["items"] as? JsonArray)?.map { e ->
                    val eo = e.jsonObject
                    DiagramItem(
                        eo.str("label"), eo.str("body"), eo.str("example"),
                        (eo["weight"] as? JsonPrimitive)?.floatOrNull ?: 1f,
                    )
                } ?: emptyList(),
                nodes = (o["nodes"] as? JsonArray)?.map { e ->
                    val eo = e.jsonObject
                    DiagramNode(eo.str("id"), eo.str("text"), eo.str("yes"), eo.str("no"), eo.str("answer"))
                } ?: emptyList(),
            )
        }
        return Content(units, lessons, questions, corrections, meta, diagrams)
    }
}
