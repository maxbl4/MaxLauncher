package com.maxbl4.maxlauncher.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A launchable activity in a user profile, in a form that survives reboots (user serial number). */
@Serializable
data class AppRef(val component: String, val userSerial: Long)

@Serializable
sealed interface HomeItem {
    val id: Long
}

@Serializable
@SerialName("app")
data class AppItem(override val id: Long, val app: AppRef) : HomeItem

/** Apps inside a folder are shown in alphabetical order, so [apps] order does not matter. */
@Serializable
@SerialName("folder")
data class FolderItem(override val id: Long, val title: String = "", val apps: List<AppItem>) : HomeItem

/** A pinned shortcut (e.g. a contact or a browser bookmark) placed by another app. */
@Serializable
@SerialName("shortcut")
data class ShortcutItem(
    override val id: Long,
    val packageName: String,
    val shortcutId: String,
    val userSerial: Long,
    val label: String,
) : HomeItem

/** An app widget; [appWidgetId] is allocated from the launcher's AppWidgetHost. */
@Serializable
@SerialName("widget")
data class WidgetItem(
    override val id: Long,
    val appWidgetId: Int,
    val provider: String,
    val userSerial: Long,
) : HomeItem

/** An item on a grid. Icons take one cell; widgets span [spanX] × [spanY] cells from (x, y). */
@Serializable
data class Placed(val item: HomeItem, val x: Int, val y: Int, val spanX: Int = 1, val spanY: Int = 1) {
    fun covers(cx: Int, cy: Int): Boolean = cx in x until x + spanX && cy in y until y + spanY

    fun overlaps(ox: Int, oy: Int, w: Int, h: Int): Boolean =
        ox < x + spanX && x < ox + w && oy < y + spanY && y < oy + h
}

@Serializable
data class HomePage(val id: Long, val items: List<Placed> = emptyList())

/** Where top-level items live: a home page grid or the dock (a single row). */
sealed interface Container {
    data class Page(val pageId: Long) : Container
    data object Dock : Container
}

/** The whole home screen: pages left to right, the home page, the dock and the grid size. */
@Serializable
data class HomeLayout(
    val columns: Int = 4,
    val rows: Int = 6,
    val dockSlots: Int = 4,
    val pages: List<HomePage>,
    val homePageId: Long,
    val dock: List<Placed> = emptyList(),
    val nextId: Long,
) {
    val homePageIndex: Int get() = pages.indexOfFirst { it.id == homePageId }.coerceAtLeast(0)

    val containers: List<Container> get() = pages.map { Container.Page(it.id) } + Container.Dock

    fun size(container: Container): Pair<Int, Int> = when (container) {
        is Container.Page -> columns to rows
        Container.Dock -> dockSlots to 1
    }

    fun exists(container: Container): Boolean = when (container) {
        is Container.Page -> pages.any { it.id == container.pageId }
        Container.Dock -> true
    }

    fun items(container: Container): List<Placed> = when (container) {
        is Container.Page -> pages.firstOrNull { it.id == container.pageId }?.items.orEmpty()
        Container.Dock -> dock
    }

    fun withItems(container: Container, items: List<Placed>): HomeLayout = when (container) {
        is Container.Page -> copy(pages = pages.map { if (it.id == container.pageId) it.copy(items = items) else it })
        // The dock is an ordered row: x is the position, kept contiguous.
        Container.Dock -> copy(dock = items.sortedBy { it.x }.mapIndexed { i, p -> p.copy(x = i, y = 0) })
    }

    fun itemAt(container: Container, x: Int, y: Int): Placed? = items(container).firstOrNull { it.covers(x, y) }

    /** Whether a [w] × [h] area at (x, y) is inside the grid and free, ignoring item [ignoreId]. */
    fun isAreaFree(container: Container, x: Int, y: Int, w: Int, h: Int, ignoreId: Long? = null): Boolean {
        val (columns, rows) = size(container)
        if (x < 0 || y < 0 || x + w > columns || y + h > rows) return false
        return items(container).none { it.item.id != ignoreId && it.overlaps(x, y, w, h) }
    }

    /** A top-level item (not inside a folder) and its container. */
    fun findPlaced(itemId: Long): Pair<Container, Placed>? {
        for (container in containers) {
            items(container).firstOrNull { it.item.id == itemId }?.let { return container to it }
        }
        return null
    }

    /** Any item by id, including apps inside folders. */
    fun findItem(itemId: Long): HomeItem? {
        for (container in containers) {
            for (placed in items(container)) {
                if (placed.item.id == itemId) return placed.item
                (placed.item as? FolderItem)?.apps?.firstOrNull { it.id == itemId }?.let { return it }
            }
        }
        return null
    }

    fun findFolder(folderId: Long): FolderItem? = findItem(folderId) as? FolderItem

    fun allocateId(): Pair<Long, HomeLayout> = nextId to copy(nextId = nextId + 1)

    companion object {
        fun initial(): HomeLayout = HomeLayout(pages = listOf(HomePage(1)), homePageId = 1, nextId = 2)
    }
}
