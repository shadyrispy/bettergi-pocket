package com.bettergi.pocket.recognition

object OcrText {
    fun removeAllSpace(text: String): String {
        if (text.isEmpty()) return text
        return text.replace(" ", "").replace("\t", "")
    }

    fun applyReplacements(
        text: String,
        replacements: Map<String, List<String>>,
    ): String {
        var result = text
        for ((canonical, wrongs) in replacements) {
            for (wrong in wrongs) {
                result = result.replace(wrong, canonical)
            }
        }
        return result
    }

    fun normalize(
        text: String,
        replacements: Map<String, List<String>> = emptyMap(),
    ): String = applyReplacements(removeAllSpace(text), replacements)

    fun matches(
        text: String,
        allContain: List<String>,
        oneContain: List<String>,
        regex: List<String>,
    ): Boolean {
        val allOk = allContain.all { text.contains(it) }
        val regexOk = regex.all { Regex(it).containsMatchIn(text) }
        val oneOk = oneContain.isEmpty() || oneContain.any { text.contains(it) }
        return allOk && regexOk && oneOk
    }
}
