package com.maxbl4.maxlauncher.drawer

import com.maxbl4.maxlauncher.home.AppRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    private fun ref(name: String, serial: Long = 0) = AppRef("$name/.Main", serial)

    @Test
    fun mostOpenedFirstThenMostRecent() {
        val history = SearchHistory()
            .record(ref("a"), now = 1)
            .record(ref("b"), now = 2)
            .record(ref("b"), now = 3)
            .record(ref("c"), now = 4)
        assertEquals(listOf(ref("b"), ref("c"), ref("a")), history.ranked())
        assertEquals(2, history.hits.single { it.app == ref("b") }.count)
    }

    @Test
    fun keepsOnlyTheMostUsed() {
        var history = SearchHistory().record(ref("often"), 0).record(ref("often"), 1)
        repeat(SearchHistory.MAX_KEPT + 5) { history = history.record(ref("app$it"), 10L + it) }
        assertEquals(SearchHistory.MAX_KEPT, history.hits.size)
        assertEquals(ref("often"), history.ranked().first())
    }

    @Test
    fun uninstalledAppsAreForgotten() {
        val history = SearchHistory().record(ref("a"), 1).record(ref("a", serial = 10), 2).record(ref("b"), 3)
        assertEquals(listOf(ref("b"), ref("a", serial = 10)), history.removePackage("a", 0).ranked())
    }
}
