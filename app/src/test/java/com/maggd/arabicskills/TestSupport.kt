package com.maggd.arabicskills

import com.maggd.arabicskills.data.ContentParser
import com.maggd.arabicskills.model.Content
import java.io.File

object TestSupport {
    private fun assetsDir(): File = listOf("src/main/assets", "app/src/main/assets").map { File(it) }.first { it.exists() }
    val content: Content by lazy {
        val d = assetsDir()
        fun r(n: String) = File(d, n).readText(Charsets.UTF_8)
        ContentParser.parse(r("lessons.json"), r("questions.json"), r("corrections.json"), r("meta.json"), r("diagrams.json"))
    }
}
