package com.maxbl4.maxlauncher.home

/*
 * Pure edits of a HomeLayout. Every function returns a new layout; none of them touch storage.
 */

/**
 * Removes an item wherever it is: on a page, in the dock or inside a folder. A folder left with
 * a single app turns back into that app; an empty folder disappears.
 */
fun HomeLayout.remove(itemId: Long): HomeLayout {
    findPlaced(itemId)?.let { (container, placed) -> return withItems(container, items(container) - placed) }
    for (container in containers) {
        val list = items(container)
        val index = list.indexOfFirst { p -> (p.item as? FolderItem)?.apps?.any { it.id == itemId } == true }
        if (index < 0) continue
        val placed = list[index]
        val folder = placed.item as FolderItem
        val replacement = placed.withFolderApps(folder, folder.apps.filter { it.id != itemId })
        return withItems(container, list.replaceAt(index, replacement))
    }
    return this
}

/**
 * Drops [item] (not yet in the layout) onto a cell. An app dropped onto another app creates a
 * folder; onto a folder it joins the folder. Otherwise the item takes the cell (the area, for
 * widgets), or the nearest free one. Returns null when the container has no room.
 */
fun HomeLayout.drop(item: HomeItem, container: Container, x: Int, y: Int, spanX: Int = 1, spanY: Int = 1): HomeLayout? {
    if (!exists(container)) return null
    // Widgets live on pages only.
    if (item is WidgetItem && container !is Container.Page) return null
    val (columns, rows) = size(container)
    if (spanX > columns || spanY > rows) return null
    val cx = x.coerceIn(0, columns - spanX)
    val cy = y.coerceIn(0, rows - spanY)

    if (spanX == 1 && spanY == 1 && item is AppItem) {
        val occupant = itemAt(container, cx, cy)
        when (val target = occupant?.item) {
            is AppItem -> {
                val (folderId, layout) = allocateId()
                val folder = FolderItem(folderId, apps = listOf(target, item))
                return layout.withItems(container, items(container).replace(occupant, occupant.copy(item = folder)))
            }
            is FolderItem -> {
                val apps = if (target.apps.any { it.app == item.app }) target.apps else target.apps + item
                return withItems(container, items(container).replace(occupant, occupant.copy(item = target.copy(apps = apps))))
            }
            else -> Unit
        }
    }
    val (fx, fy) = if (isAreaFree(container, cx, cy, spanX, spanY)) cx to cy
    else nearestFreeArea(container, cx, cy, spanX, spanY) ?: return null
    return withItems(container, items(container) + Placed(item, fx, fy, spanX, spanY))
}

/** Moves an existing item (top-level or from a folder) to a cell; unchanged when it does not fit. */
fun HomeLayout.move(itemId: Long, container: Container, x: Int, y: Int): HomeLayout {
    val item = findItem(itemId) ?: return this
    val source = findPlaced(itemId)?.second
    val spanX = source?.spanX ?: 1
    val spanY = source?.spanY ?: 1
    val occupant = itemAt(container, x, y)?.item
    // Dropping an icon onto itself, or an app back onto its own folder, changes nothing.
    if (spanX == 1 && spanY == 1 && occupant?.id == itemId) return this
    if (occupant is FolderItem && occupant.apps.any { it.id == itemId }) return this
    return remove(itemId).drop(item, container, x, y, spanX, spanY) ?: this
}

/** Moves and/or resizes a widget; unchanged when the area is outside the grid or taken. */
fun HomeLayout.resizeWidget(itemId: Long, x: Int, y: Int, spanX: Int, spanY: Int): HomeLayout {
    val (container, placed) = findPlaced(itemId) ?: return this
    if (spanX < 1 || spanY < 1 || !isAreaFree(container, x, y, spanX, spanY, ignoreId = itemId)) return this
    return withItems(container, items(container).replace(placed, placed.copy(x = x, y = y, spanX = spanX, spanY = spanY)))
}

/** Adds a new app at the first free cell, starting from [preferredPageId]; appends a page if all are full. */
fun HomeLayout.addApp(app: AppRef, preferredPageId: Long = homePageId): HomeLayout {
    val (id, layout) = allocateId()
    return layout.placeAnywhere(AppItem(id, app), preferredPageId)
}

fun HomeLayout.addShortcut(packageName: String, shortcutId: String, userSerial: Long, label: String): HomeLayout {
    val (id, layout) = allocateId()
    return layout.placeAnywhere(ShortcutItem(id, packageName, shortcutId, userSerial, label), homePageId)
}

fun HomeLayout.addWidget(
    appWidgetId: Int,
    provider: String,
    userSerial: Long,
    spanX: Int,
    spanY: Int,
    preferredPageId: Long,
): HomeLayout {
    val (id, layout) = allocateId()
    val w = spanX.coerceIn(1, columns)
    val h = spanY.coerceIn(1, rows)
    return layout.placeAnywhere(WidgetItem(id, appWidgetId, provider, userSerial), preferredPageId, w, h)
}

/** Replaces the folder content with [apps], keeping ids of apps that stay. */
fun HomeLayout.setFolderApps(folderId: Long, apps: List<AppRef>): HomeLayout {
    val (container, placed) = findPlaced(folderId) ?: return this
    val folder = placed.item as? FolderItem ?: return this
    var layout = this
    val newApps = apps.distinct().map { ref ->
        folder.apps.firstOrNull { it.app == ref } ?: layout.allocateId().let { (id, l) -> layout = l; AppItem(id, ref) }
    }
    val list = layout.items(container)
    val replacement = placed.withFolderApps(folder, newApps)
    return layout.withItems(container, list.replaceAt(list.indexOf(placed), replacement))
}

fun HomeLayout.renameFolder(folderId: Long, title: String): HomeLayout {
    val (container, placed) = findPlaced(folderId) ?: return this
    val folder = placed.item as? FolderItem ?: return this
    return withItems(container, items(container).replace(placed, placed.copy(item = folder.copy(title = title.trim()))))
}

/** Deletes a folder, putting its apps back: the first in the folder's cell, the rest nearby. */
fun HomeLayout.dissolveFolder(folderId: Long): HomeLayout {
    val (container, placed) = findPlaced(folderId) ?: return this
    val folder = placed.item as? FolderItem ?: return this
    var layout = withItems(container, items(container) - placed)
    val preferredPage = (container as? Container.Page)?.pageId ?: homePageId
    folder.apps.forEachIndexed { i, app ->
        layout = if (i == 0) {
            layout.withItems(container, layout.items(container) + Placed(app, placed.x, placed.y))
        } else {
            layout.nearestFreeArea(container, placed.x, placed.y)
                ?.let { (x, y) -> layout.withItems(container, layout.items(container) + Placed(app, x, y)) }
                ?: layout.placeAnywhere(app, preferredPage)
        }
    }
    return layout
}

/** Removes every occurrence of an app (e.g. after it was uninstalled). */
fun HomeLayout.removeApp(app: AppRef): HomeLayout {
    val ids = allPlaced().flatMap { p ->
        when (val item = p.item) {
            is AppItem -> listOfNotNull(item.id.takeIf { item.app == app })
            is FolderItem -> item.apps.filter { it.app == app }.map { it.id }
            else -> emptyList()
        }
    }
    return ids.fold(this) { layout, id -> layout.remove(id) }
}

fun HomeLayout.removeShortcutsOf(packageName: String, userSerial: Long): HomeLayout {
    val ids = allPlaced().mapNotNull { p ->
        (p.item as? ShortcutItem)?.takeIf { it.packageName == packageName && it.userSerial == userSerial }?.id
    }
    return ids.fold(this) { layout, id -> layout.remove(id) }
}

fun HomeLayout.removeWidgetsOf(packageName: String, userSerial: Long): HomeLayout {
    val ids = allPlaced().mapNotNull { p ->
        (p.item as? WidgetItem)?.takeIf { it.provider.substringBefore('/') == packageName && it.userSerial == userSerial }?.id
    }
    return ids.fold(this) { layout, id -> layout.remove(id) }
}

fun HomeLayout.allPlaced(): List<Placed> = containers.flatMap { items(it) }

fun HomeLayout.widgets(): List<WidgetItem> = allPlaced().mapNotNull { it.item as? WidgetItem }

// --- Grid size ---

/**
 * Changes the grid (the dock follows the column count). Items that no longer fit move to the
 * nearest free spot on the same page, or to other pages; widgets shrink to fit the grid.
 */
fun HomeLayout.resizeGrid(newColumns: Int, newRows: Int): HomeLayout {
    if (newColumns == columns && newRows == rows) return this
    val displaced = mutableListOf<Pair<Long, Placed>>() // page id (or -1 for the dock) to item
    val pages = pages.map { page ->
        val (keep, move) = page.items
            .map { it.copy(spanX = it.spanX.coerceAtMost(newColumns), spanY = it.spanY.coerceAtMost(newRows)) }
            .partition { it.x + it.spanX <= newColumns && it.y + it.spanY <= newRows }
        displaced += move.map { page.id to it }
        page.copy(items = keep)
    }
    val (dockKeep, dockMove) = dock.partition { it.x < newColumns }
    displaced += dockMove.map { -1L to it }

    var layout = copy(columns = newColumns, rows = newRows, dockSlots = newColumns, pages = pages, dock = dockKeep)
    for ((pageId, placed) in displaced) {
        val container: Container = if (pageId == -1L) Container.Dock else Container.Page(pageId)
        val spot = layout.nearestFreeArea(container, placed.x, placed.y, placed.spanX, placed.spanY)
        layout = if (spot != null) {
            layout.withItems(container, layout.items(container) + placed.copy(x = spot.first, y = spot.second))
        } else {
            layout.placeAnywhere(placed.item, if (pageId == -1L) homePageId else pageId, placed.spanX, placed.spanY)
        }
    }
    return layout
}

// --- Pages ---

fun HomeLayout.addPage(index: Int): Pair<Long, HomeLayout> {
    val (id, layout) = allocateId()
    val pages = layout.pages.toMutableList().apply { add(index.coerceIn(0, size), HomePage(id)) }
    return id to layout.copy(pages = pages)
}

/** Removes a page with its content. The last remaining page cannot be removed. */
fun HomeLayout.removePage(pageId: Long): HomeLayout {
    if (pages.size <= 1) return this
    val index = pages.indexOfFirst { it.id == pageId }
    if (index < 0) return this
    val remaining = pages.filterNot { it.id == pageId }
    val home = if (homePageId == pageId) remaining[index.coerceAtMost(remaining.size - 1)].id else homePageId
    return copy(pages = remaining, homePageId = home)
}

fun HomeLayout.setHomePage(pageId: Long): HomeLayout =
    if (pages.any { it.id == pageId }) copy(homePageId = pageId) else this

fun HomeLayout.movePage(from: Int, to: Int): HomeLayout {
    if (from !in pages.indices || to !in pages.indices || from == to) return this
    val list = pages.toMutableList()
    list.add(to, list.removeAt(from))
    return copy(pages = list)
}

// --- Helpers ---

/** The free top-left position for a [spanX] × [spanY] area closest to (x, y). */
fun HomeLayout.nearestFreeArea(container: Container, x: Int, y: Int, spanX: Int = 1, spanY: Int = 1): Pair<Int, Int>? {
    val (columns, rows) = size(container)
    return (0..rows - spanY).flatMap { cy -> (0..columns - spanX).map { cx -> cx to cy } }
        .filter { (cx, cy) -> isAreaFree(container, cx, cy, spanX, spanY) }
        .minByOrNull { (cx, cy) -> (cx - x) * (cx - x) + (cy - y) * (cy - y) }
}

private fun HomeLayout.firstFreeArea(container: Container, spanX: Int, spanY: Int): Pair<Int, Int>? {
    val (columns, rows) = size(container)
    for (cy in 0..rows - spanY) for (cx in 0..columns - spanX) {
        if (isAreaFree(container, cx, cy, spanX, spanY)) return cx to cy
    }
    return null
}

private fun HomeLayout.placeAnywhere(item: HomeItem, preferredPageId: Long, spanX: Int = 1, spanY: Int = 1): HomeLayout {
    val ordered = pages.sortedBy { if (it.id == preferredPageId) 0 else 1 }
    for (page in ordered) {
        val container = Container.Page(page.id)
        firstFreeArea(container, spanX, spanY)?.let { (x, y) ->
            return withItems(container, items(container) + Placed(item, x, y, spanX, spanY))
        }
    }
    val (pageId, layout) = addPage(pages.size)
    return layout.withItems(Container.Page(pageId), listOf(Placed(item, 0, 0, spanX, spanY)))
}

/** The folder with new content; with one app left it becomes that app, with none it is gone. */
private fun Placed.withFolderApps(folder: FolderItem, apps: List<AppItem>): Placed? = when (apps.size) {
    0 -> null
    1 -> copy(item = apps.single())
    else -> copy(item = folder.copy(apps = apps))
}

private fun List<Placed>.replace(old: Placed, new: Placed): List<Placed> = map { if (it == old) new else it }

private fun List<Placed>.replaceAt(index: Int, new: Placed?): List<Placed> =
    toMutableList().apply { if (new == null) removeAt(index) else set(index, new) }
