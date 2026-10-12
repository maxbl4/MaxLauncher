package com.maxbl4.maxlauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSearchTest {

    private val apps = listOf("Авито", "Почта", "Сбербанк", "Яндекс Go", "Google Maps", "Telegram", "Yandex Music")

    private fun search(query: String) = AppSearch.filter(apps, query) { it }

    @Test
    fun emptyQueryReturnsEverything() {
        assertEquals(apps, search("  "))
    }

    @Test
    fun prefixBeforeWordStartBeforeSubstring() {
        val list = listOf("Mapster", "Google Maps", "Snapmaps")
        assertEquals(listOf("Mapster", "Google Maps", "Snapmaps"), AppSearch.filter(list, "map") { it })
    }

    @Test
    fun caseAndYoInsensitive() {
        assertEquals(listOf("Сбербанк"), search("СБЕР"))
        assertEquals(listOf("Ёжик"), AppSearch.filter(listOf("Ёжик", "Жук"), "еж") { it })
    }

    @Test
    fun wrongKeyboardLayout() {
        assertEquals(listOf("Telegram"), search("еуду"))
        assertEquals(listOf("Сбербанк"), search("c,th"))
    }

    @Test
    fun wrongLayoutAndOtherAlphabet() {
        // "телегр" typed on the English layout, while the label is Latin.
        assertEquals(listOf("Telegram"), search("ntktuh"))
        // "yandex" typed on the Russian layout.
        assertEquals(listOf("Яндекс Go", "Yandex Music"), search("нфтвуч"))
    }

    @Test
    fun transliteration() {
        assertEquals(listOf("Telegram"), search("телеграм"))
        assertEquals(listOf("Яндекс Go", "Yandex Music"), search("яндекс"))
        assertEquals(listOf("Yandex Music", "Яндекс Go"), search("yandex"))
    }

    @Test
    fun convertedQueriesDoNotMatchMidWord() {
        // "ш" switches layout to "i", which must not match the "i" inside "Telegram"/"Music".
        assertEquals(emptyList<String>(), search("ш"))
    }
}
