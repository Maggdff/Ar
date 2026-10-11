package com.maggd.arabicskills.model

enum class QType(val key: String, val label: String) {
    TRUE_FALSE("true_false", "صح / خطأ"),
    MCQ("mcq", "اختيار من متعدد"),
    APPLIED("applied", "تطبيقي"),
    PRECISE("precise", "دقيق");

    /** الأسئلة التي يمكن تصحيحها تلقائيًا في الاختبارات */
    val autoGraded: Boolean get() = this == TRUE_FALSE || this == MCQ

    companion object {
        fun fromKey(k: String): QType = entries.firstOrNull { it.key == k } ?: APPLIED
    }
}

data class Example(val text: String, val note: String)

data class BookUnit(val id: String, val title: String, val lessonIds: List<String>)

data class Lesson(
    val id: String,
    val unitId: String,
    val title: String,
    val position: String,
    val ruleText: String,
    val conditions: List<String>,
    val examples: List<Example>,
    val summary: String,
    val differences: String,
    val commonErrors: String,
    val verificationStatus: String,
    val verificationNotes: String,
    val excerpt: String,
    val extractionNote: String,
)

data class Question(
    val id: String,
    val text: String,
    val type: QType,
    val options: List<String>,
    val answer: String,
    val explanation: String,
    val correctionIfFalse: String,
    val unitIds: List<String>,
    val lessonIds: List<String>,
    val difficulty: String,
    val sourceType: String,
    val verification: String,
    val appNote: String,
)

data class Correction(
    val id: String,
    val category: String,
    val lessonRef: String,
    val lessonIds: List<String>,
    val original: String,
    val suggested: String,
    val reason: String,
    val confidence: String,
    val status: String,
    val notes: String,
)

data class BookMeta(
    val title: String,
    val isCompleteBook: Boolean,
    val completenessNote: String,
    val extractionStatus: String,
)

/** عنصر في رسم تفاعلي (شريحة، بطاقة، مرحلة…). */
data class DiagramItem(val label: String, val body: String, val example: String, val weight: Float)

/** عقدة في رسم "شجرة القرار": سؤال بنعم/لا، أو نتيجة نهائية إن كان answer غير فارغ. */
data class DiagramNode(val id: String, val text: String, val yes: String, val no: String, val answer: String)

/** رسم تفاعلي لدرس: kind = chips | reveal | flow | timeline */
data class Diagram(
    val id: String,
    val lessonId: String,
    val kind: String,
    val title: String,
    val hint: String,
    val items: List<DiagramItem>,
    val nodes: List<DiagramNode>,
)

data class Content(
    val units: List<BookUnit>,
    val lessons: List<Lesson>,
    val questions: List<Question>,
    val corrections: List<Correction>,
    val meta: BookMeta,
    val diagrams: List<Diagram> = emptyList(),
) {
    private val lessonMap = lessons.associateBy { it.id }
    private val unitMap = units.associateBy { it.id }
    fun lesson(id: String): Lesson? = lessonMap[id]
    fun unit(id: String): BookUnit? = unitMap[id]
    fun lessonsOf(unitId: String): List<Lesson> = lessons.filter { it.unitId == unitId }
    fun questionsOfLesson(lessonId: String): List<Question> = questions.filter { lessonId in it.lessonIds }
    fun questionsOfUnit(unitId: String): List<Question> = questions.filter { unitId in it.unitIds }
    fun diagramsOfLesson(lessonId: String): List<Diagram> = diagrams.filter { it.lessonId == lessonId }
    fun correctionsOfLesson(lessonId: String): List<Correction> = corrections.filter { lessonId in it.lessonIds }
}

/** نتيجة سؤال واحد داخل اختبار */
data class AnswerResult(val questionId: String, val selected: String, val correct: Boolean)

/** ملخص جلسة اختبار محفوظة */
data class SessionRecord(
    val timestamp: Long,
    val label: String,
    val total: Int,
    val correct: Int,
)

/** حالة تقدم المستخدم كاملة (غير قابلة للتغيير) */
data class Progress(
    val attempts: Map<String, Int> = emptyMap(),
    val correctCounts: Map<String, Int> = emptyMap(),
    val lastCorrect: Map<String, Boolean> = emptyMap(),
    val wrongSet: Set<String> = emptySet(),
    val boxes: Map<String, Int> = emptyMap(),
    val lessonsRead: Set<String> = emptySet(),
    val sessions: List<SessionRecord> = emptyList(),
)
