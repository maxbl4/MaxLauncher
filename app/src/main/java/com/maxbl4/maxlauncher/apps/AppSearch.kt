package com.maxbl4.maxlauncher.apps

import java.util.Locale

/**
 * Search over app labels. Besides the literal query it tries the query typed in the wrong
 * keyboard layout ("ntktuhfv" -> "телеграм") and transliterated in both directions
 * ("телеграм" -> "telegram", "yandex" -> "яндекс").
 */
object AppSearch {

    /**
     * Returns the items matching [query], best matches first; items with equal rank keep the
     * input order (the input is expected to be sorted already).
     */
    fun <T> filter(items: List<T>, query: String, label: (T) -> String): List<T> {
        val literal = normalize(query).trim()
        if (literal.isEmpty()) return items
        val alternatives = variants(literal) - literal
        return items
            .mapNotNull { item ->
                val text = normalize(label(item))
                // At equal rank the literal query wins over its converted variants.
                val literalScore = rank(text, literal, allowSubstring = true)?.let { it * 2 }
                // Converted queries are noisy for short input, so they must match a word start.
                val altScore = alternatives.mapNotNull { rank(text, it, allowSubstring = false) }
                    .minOrNull()?.let { it * 2 + 1 }
                val best = listOfNotNull(literalScore, altScore).minOrNull() ?: return@mapNotNull null
                best to item
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    internal fun normalize(s: String): String = s.lowercase(Locale.ROOT).replace('ё', 'е')

    internal fun variants(normalizedQuery: String): Set<String> {
        val latin = toLatin(normalizedQuery)
        return linkedSetOf(
            normalizedQuery,
            normalize(switchLayout(normalizedQuery)),
            latin,
            latin.replace("ks", "x"),
            normalize(toCyrillic(normalizedQuery)),
        ).filterTo(LinkedHashSet()) { it.isNotBlank() }
    }

    /** 0 = label starts with the query, 1 = a word starts with it, 2 = substring, null = no match. */
    private fun rank(text: String, query: String, allowSubstring: Boolean): Int? = when {
        text.startsWith(query) -> 0
        wordStarts(text).any { text.startsWith(query, it) } -> 1
        allowSubstring && text.contains(query) -> 2
        else -> null
    }

    private fun wordStarts(text: String): List<Int> =
        text.indices.filter { i -> i > 0 && text[i].isLetterOrDigit() && !text[i - 1].isLetterOrDigit() }

    // --- Keyboard layout (ЙЦУКЕН <-> QWERTY) ---

    private const val LAYOUT_EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val LAYOUT_RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    internal fun switchLayout(s: String): String = buildString(s.length) {
        for (ch in s) {
            val en = LAYOUT_EN.indexOf(ch)
            val ru = LAYOUT_RU.indexOf(ch)
            append(
                when {
                    en >= 0 -> LAYOUT_RU[en]
                    ru >= 0 -> LAYOUT_EN[ru]
                    else -> ch
                }
            )
        }
    }

    // --- Transliteration ---

    private val ruToLat = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    // Longest sequences first so that greedy matching prefers "shch" over "sh".
    private val latToRu = listOf(
        "shch" to "щ", "sch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч",
        "sh" to "ш", "yu" to "ю", "ya" to "я", "yo" to "е",
        "a" to "а", "b" to "б", "c" to "к", "d" to "д", "e" to "е", "f" to "ф", "g" to "г",
        "h" to "х", "i" to "и", "j" to "дж", "k" to "к", "l" to "л", "m" to "м", "n" to "н",
        "o" to "о", "p" to "п", "q" to "к", "r" to "р", "s" to "с", "t" to "т", "u" to "у",
        "v" to "в", "w" to "в", "x" to "кс", "y" to "и", "z" to "з",
    )

    internal fun toLatin(s: String): String = buildString(s.length) {
        for (ch in s) append(ruToLat[ch] ?: ch.toString())
    }

    internal fun toCyrillic(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val match = latToRu.firstOrNull { (lat, _) -> s.startsWith(lat, i) }
            if (match != null) {
                append(match.second)
                i += match.first.length
            } else {
                append(s[i])
                i++
            }
        }
    }
}
