package com.maxbl4.maxlauncher.drawer

import com.maxbl4.maxlauncher.home.AppRef
import kotlinx.serialization.Serializable

@Serializable
data class SearchHit(val app: AppRef, val count: Int, val lastUsed: Long)

/**
 * Apps opened from the drawer search, shown (most opened first) when the empty search field
 * gets the focus.
 */
@Serializable
data class SearchHistory(val hits: List<SearchHit> = emptyList()) {

    /** Counts one more launch of [app]; only the [MAX_KEPT] most used apps are remembered. */
    fun record(app: AppRef, now: Long): SearchHistory {
        val old = hits.firstOrNull { it.app == app }
        val hit = SearchHit(app, (old?.count ?: 0) + 1, now)
        return SearchHistory((hits.filter { it.app != app } + hit).sortedWith(ORDER).take(MAX_KEPT))
    }

    /** Apps by how often they were opened from search; the more recent one first on a tie. */
    fun ranked(): List<AppRef> = hits.sortedWith(ORDER).map { it.app }

    fun removePackage(packageName: String, userSerial: Long): SearchHistory =
        copy(hits = hits.filterNot { it.app.userSerial == userSerial && it.app.component.substringBefore('/') == packageName })

    companion object {
        const val MAX_KEPT = 50
        private val ORDER = compareByDescending<SearchHit> { it.count }.thenByDescending { it.lastUsed }
    }
}
