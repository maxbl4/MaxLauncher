package com.maxbl4.maxlauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSortingTest {

    private fun sort(vararg labels: String) = labels.sortedWith(AppSorting.comparator { it })

    @Test
    fun symbolsThenCyrillicThenLatin() {
        assertEquals(
            listOf("1Password", "2ГИС", "Авито", "Сбербанк", "Яндекс", "Chrome", "Telegram", "YouTube"),
            sort("Telegram", "Яндекс", "Chrome", "2ГИС", "Авито", "YouTube", "1Password", "Сбербанк"),
        )
    }

    @Test
    fun ignoresCase() {
        assertEquals(listOf("alpha", "Beta", "gamma"), sort("gamma", "Beta", "alpha"))
        assertEquals(listOf("арбуз", "Банк", "вкусвилл"), sort("вкусвилл", "Банк", "арбуз"))
    }

    @Test
    fun yoSortsWithYe() {
        assertEquals(listOf("Ежик", "Ёлка", "Жук"), sort("Жук", "Ёлка", "Ежик"))
    }

    @Test
    fun otherScriptsGoLast() {
        assertEquals(listOf("Почта", "Mail", "微信"), sort("微信", "Mail", "Почта"))
    }

    @Test
    fun leadingWhitespaceIsIgnoredForGrouping() {
        assertEquals(AppSorting.GROUP_CYRILLIC, AppSorting.scriptGroup("  Карты"))
        assertEquals(AppSorting.GROUP_SYMBOLS, AppSorting.scriptGroup(""))
    }
}
