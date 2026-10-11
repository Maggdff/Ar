package com.maggd.arabicskills.data

import android.content.Context
import com.maggd.arabicskills.model.Content
import com.maggd.arabicskills.model.Progress

object ContentLoader {
    private fun Context.asset(name: String) = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun load(ctx: Context): Content = ContentParser.parse(
        ctx.asset("lessons.json"), ctx.asset("questions.json"),
        ctx.asset("corrections.json"), ctx.asset("meta.json"),
    )
}

/** حفظ محلي في SharedPreferences (يعمل دون إنترنت). */
class ProgressStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("progress_store", Context.MODE_PRIVATE)
    fun load(): Progress = ProgressCodec.decode(prefs.getString("progress", null))
    fun save(p: Progress) { prefs.edit().putString("progress", ProgressCodec.encode(p)).apply() }
}
