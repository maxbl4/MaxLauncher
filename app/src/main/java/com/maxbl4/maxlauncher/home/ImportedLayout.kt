package com.maxbl4.maxlauncher.home

import com.maxbl4.maxlauncher.drawer.DrawerFolder
import com.maxbl4.maxlauncher.drawer.DrawerLayout
import kotlinx.serialization.Serializable

/*
 * A home screen layout read from another launcher (One UI Home, by tools/import-oneui.ps1).
 * Apps are known only by their labels; they are matched to installed apps on import.
 */

@Serializable
data class ImportedLayout(
    val columns: Int,
    val rows: Int,
    /** Index of the home page in [pages]. */
    val homePage: Int = 0,
    val pages: List<ImportedPage>,
    /** Dock icons from left to right (apps or folders; x and y are ignored). */
    val dock: List<ImportedItem> = emptyList(),
    /** Folders of the apps screen. */
    val drawerFolders: List<ImportedFolder> = emptyList(),
)

@Serializable
data class ImportedFolder(val title: String, val apps: List<String>)

/** A shortcut found for an icon whose label matched no app. */
data class ShortcutTarget(val packageName: String, val shortcutId: String, val userSerial: Long, val label: String)

@Serializable
data class ImportedPage(val items: List<ImportedItem> = emptyList())

@Serializable
data class ImportedItem(
    /** "app", "folder" or "widget". */
    val type: String,
    /** App label, folder title or widget name. */
    val label: String,
    val x: Int = 0,
    val y: Int = 0,
    val spanX: Int = 1,
    val spanY: Int = 1,
    /** Folder content: app labels. */
    val apps: List<String> = emptyList(),
)

/** A widget to add after the layout is built (it needs binding by the widget host). */
data class PendingWidget(val pageId: Long, val label: String, val x: Int, val y: Int, val spanX: Int, val spanY: Int)

data class ImportResult(
    val layout: HomeLayout,
    val widgets: List<PendingWidget>,
    /** Labels that matched no installed app (left out). */
    val missing: List<String>,
)

/** All app labels the layout refers to (icons, folder content, dock, apps screen folders), without duplicates. */
fun ImportedLayout.appLabels(): List<String> =
    ((pages.flatMap { it.items } + dock)
        .flatMap { item ->
            when (item.type) {
                "app" -> listOf(item.label)
                "folder" -> item.apps
                else -> emptyList()
            }
        } + drawerFolders.flatMap { it.apps })
        .distinct()

/** Labels of standalone icons (home pages and dock): these may also be shortcuts. */
fun ImportedLayout.iconLabels(): List<String> =
    (pages.flatMap { it.items } + dock).filter { it.type == "app" }.map { it.label }.distinct()

/**
 * Apps screen folders. Apps that are not installed are left out; an app can be in one drawer
 * folder only (the first folder listing it keeps it); folders need at least two apps.
 */
fun ImportedLayout.toDrawerLayout(resolve: (String) -> AppRef?): DrawerLayout {
    val seen = HashSet<AppRef>()
    var nextId = 1L
    val folders = drawerFolders.mapNotNull { folder ->
        val apps = folder.apps.mapNotNull(resolve).distinct().filter { it !in seen }
        if (apps.size < 2) return@mapNotNull null
        seen += apps
        DrawerFolder(nextId++, folder.title.trim(), apps)
    }
    return DrawerLayout(folders, nextId)
}

/**
 * Builds a home layout. [resolve] maps an app label to the chosen installed app, or null when
 * there is none; standalone icons that are not apps are then looked up with [resolveShortcut].
 * Folders that end up with a single app become that app; empty ones are dropped.
 */
fun ImportedLayout.toHomeLayout(
    resolve: (String) -> AppRef?,
    resolveShortcut: (String) -> ShortcutTarget? = { null },
): ImportResult {
    val columns = columns.coerceIn(3, 7)
    val rows = rows.coerceIn(3, 8)
    var layout = HomeLayout(
        columns = columns,
        rows = rows,
        dockSlots = maxOf(columns, dock.size),
        pages = emptyList(),
        homePageId = 0,
        nextId = 1,
    )
    val missing = LinkedHashSet<String>()
    val widgets = mutableListOf<PendingWidget>()

    fun app(label: String): AppItem? {
        val ref = resolve(label) ?: run { missing += label; return null }
        val (id, next) = layout.allocateId()
        layout = next
        return AppItem(id, ref)
    }

    fun shortcutOrApp(label: String): HomeItem? {
        if (resolve(label) == null) {
            resolveShortcut(label)?.let { s ->
                val (id, next) = layout.allocateId()
                layout = next
                return ShortcutItem(id, s.packageName, s.shortcutId, s.userSerial, s.label)
            }
        }
        return app(label)
    }

    fun item(imported: ImportedItem): HomeItem? = when (imported.type) {
        "app" -> shortcutOrApp(imported.label)
        "folder" -> {
            val apps = imported.apps.mapNotNull(::app)
            when (apps.size) {
                0 -> null
                1 -> apps.single()
                else -> {
                    val (id, next) = layout.allocateId()
                    layout = next
                    FolderItem(id, imported.label.trim(), apps)
                }
            }
        }
        else -> null
    }

    val pageIds = pages.map {
        val (id, next) = layout.addPage(layout.pages.size)
        layout = next
        id
    }.ifEmpty {
        val (id, next) = layout.addPage(0)
        layout = next
        listOf(id)
    }
    layout = layout.copy(homePageId = pageIds[homePage.coerceIn(0, pageIds.lastIndex)])

    pages.forEachIndexed { index, page ->
        val container = Container.Page(pageIds[index])
        for (imported in page.items) {
            if (imported.type == "widget") {
                widgets += PendingWidget(pageIds[index], imported.label, imported.x, imported.y, imported.spanX, imported.spanY)
                continue
            }
            val homeItem = item(imported) ?: continue
            val x = imported.x.coerceIn(0, columns - 1)
            val y = imported.y.coerceIn(0, rows - 1)
            val spot = if (layout.isAreaFree(container, x, y, 1, 1)) x to y else layout.nearestFreeArea(container, x, y) ?: continue
            layout = layout.withItems(container, layout.items(container) + Placed(homeItem, spot.first, spot.second))
        }
    }
    dock.forEachIndexed { index, imported ->
        val homeItem = item(imported) ?: return@forEachIndexed
        layout = layout.withItems(Container.Dock, layout.dock + Placed(homeItem, index, 0))
    }
    return ImportResult(layout, widgets, missing.toList())
}
