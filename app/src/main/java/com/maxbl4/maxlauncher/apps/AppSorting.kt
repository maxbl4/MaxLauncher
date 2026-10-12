package com.maxbl4.maxlauncher.apps

import java.text.Collator
import java.util.Locale

/**
 * Alphabetical order used across the launcher (docs/FEATURES.md, "Правила сортировки"):
 * digits and symbols first, then Cyrillic, then Latin, then any other script.
 * Comparison ignores case; "ё" sorts together with "е".
 */
object AppSorting {

    /** Script group of a label, by its first non-whitespace character. */
    internal fun scriptGroup(label: String): Int {
        val ch = label.firstOrNull { !it.isWhitespace() } ?: return GROUP_SYMBOLS
        if (!ch.isLetter()) return GROUP_SYMBOLS
        return when (Character.UnicodeScript.of(ch.code)) {
            Character.UnicodeScript.CYRILLIC -> GROUP_CYRILLIC
            Character.UnicodeScript.LATIN -> GROUP_LATIN
            else -> GROUP_OTHER
        }
    }

    /**
     * Builds a comparator over items with labels. Collators are not thread-safe, so every call
     * creates its own instances; use the returned comparator from one thread at a time.
     */
    fun <T> comparator(label: (T) -> String): Comparator<T> {
        val ru = collator(Locale.forLanguageTag("ru"))
        val en = collator(Locale.ENGLISH)
        val root = collator(Locale.ROOT)
        return Comparator { a, b ->
            val la = label(a)
            val lb = label(b)
            val ga = scriptGroup(la)
            val gb = scriptGroup(lb)
            if (ga != gb) return@Comparator ga.compareTo(gb)
            val byCollator = when (ga) {
                GROUP_LATIN -> en.compare(la, lb)
                GROUP_OTHER -> root.compare(la, lb)
                else -> ru.compare(la, lb)
            }
            if (byCollator != 0) byCollator else la.compareTo(lb)
        }
    }

    private fun collator(locale: Locale): Collator =
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    internal const val GROUP_SYMBOLS = 0
    internal const val GROUP_CYRILLIC = 1
    internal const val GROUP_LATIN = 2
    internal const val GROUP_OTHER = 3
}
