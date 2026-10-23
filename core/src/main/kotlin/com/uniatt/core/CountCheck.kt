package com.uniatt.core

/** مقارنة عدد المسجَّلين بعدد الدكتور الفعلي في القاعة (تنبيه فقط، لا منع). تطابق CountCheck في pwa/core.js. */
object CountCheck {
    enum class Level { NONE, OK, MORE, LESS }
    data class Result(val level: Level, val diff: Int, val text: String)

    /** [counted] < 0 تعني أن الدكتور لم يُدخل العدد بعد. diff = مسجَّل − معدود. */
    fun check(registered: Int, counted: Int): Result {
        if (counted < 0) return Result(Level.NONE, 0, "لم تُدخل عدد الحاضرين في القاعة بعد")
        val d = registered - counted
        return when {
            d == 0 -> Result(Level.OK, 0, "مسجَّل $registered = معدود $counted ✓ العدد مطابق")
            d > 0 -> Result(Level.MORE, d, "مسجَّل $registered • معدود $counted • زاد $d: راجع القائمة وألغِ من ليس في القاعة")
            else -> Result(Level.LESS, d, "مسجَّل $registered • معدود $counted • ناقص ${-d}: قد يكون بعض من في القاعة لم يُسجَّل (حضّره يدويًا)")
        }
    }
}
