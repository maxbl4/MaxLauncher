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
 * Drops [item] (not yet in the layout) onto a cell.
 *
 * With [merge], an app dropped onto another app creates a folder and onto a folder joins it.
 * Otherwise an icon dropped onto an occupied cell takes it and pushes the following icons aside
 * (in reading order, skipping widgets); widgets take the nearest free area.
 *
 * In the dock (an ordered row) [x] is an index: with [merge] the icon at that index, otherwise
 * the position to insert at.
 *
 * Returns null when the container has no room.
 */
fun HomeLayout.drop(
    item: HomeItem,
    container: Container,
    x: Int,
    y: Int,
    spanX: Int = 1,
    spanY: Int = 1,
    merge: Boolean = true,
): HomeLayout? {
    if (!exists(container)) return null
    // Widgets live on pages only.
    if (item is WidgetItem && container !is Container.Page) return null
    val (columns, rows) = size(container)
    if (spanX > columns || spanY > rows) return null
    val cx = x.coerceIn(0, columns - spanX)
    val cy = y.coerceIn(0, rows - spanY)

    if (merge && spanX == 1 && spanY == 1 && item is AppItem) {
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
    if (container == Container.Dock) return insertIntoDock(item, x)
    if (isAreaFree(container, cx, cy, spanX, spanY)) {
        return withItems(container, items(container) + Placed(item, cx, cy, spanX, spanY))
    }
    if (spanX == 1 && spanY == 1) insertPushing(item, container, cx, cy)?.let { return it }
    val (fx, fy) = nearestFreeArea(container, cx, cy, spanX, spanY) ?: return null
    return withItems(container, items(container) + Placed(item, fx, fy, spanX, spanY))
}

/** Moves an existing item (top-level or from a folder) to a cell; unchanged when it does not fit. */
fun HomeLayout.move(itemId: Long, container: Container, x: Int, y: Int, merge: Boolean = true): HomeLayout {
    val item = findItem(itemId) ?: return this
    val source = findPlaced(itemId)
    val spanX = source?.second?.spanX ?: 1
    val spanY = source?.second?.spanY ?: 1
    val occupant = itemAt(container, x, y)?.item
    // Dropping an icon onto itself, or an app back onto its own folder, changes nothing.
    if (spanX == 1 && spanY == 1 && occupant?.id == itemId) return this
    if (occupant is FolderItem && occupant.apps.any { it.id == itemId }) return this
    // Within the dock, indexes after the moved icon shift left once it is taken out.
    val target = if (container == Container.Dock && source?.first == Container.Dock && source.second.x < x) x - 1 else x
    return remove(itemId).drop(item, container, target, y, spanX, spanY, merge) ?: this
}

/** Inserts an icon into the dock at [index], shifting the following ones; null when the dock is full. */
private fun HomeLayout.insertIntoDock(item: HomeItem, index: Int): HomeLayout? {
    val row = dock.sortedBy { it.x }
    if (row.size >= dockSlots) return null
    val at = index.coerceIn(0, row.size)
    val inserted = row.take(at) + Placed(item, at, 0) + row.drop(at)
    return withItems(Container.Dock, inserted.mapIndexed { i, p -> p.copy(x = i) })
}

/**
 * Puts a one-cell [item] at (x, y) and shifts the icons from there on by one cell in reading
 * order until a free cell absorbs them; when there is none after (x, y), the icons before it
 * shift back instead. Cells under widgets are skipped and widgets never move. Null when full.
 */
private fun HomeLayout.insertPushing(item: HomeItem, container: Container, x: Int, y: Int): HomeLayout? {
    val (columns, rows) = size(container)
    val list = items(container)
    val widgets = list.filter { it.spanX > 1 || it.spanY > 1 }
    val cells = (0 until rows).flatMap { r -> (0 until columns).map { c -> c to r } }
        .filter { (c, r) -> widgets.none { it.covers(c, r) } }
    val start = cells.indexOf(x to y)
    if (start < 0) return null
    val iconAt = list.filter { it.spanX == 1 && it.spanY == 1 }.associateBy { it.x to it.y }
    fun free(i: Int) = iconAt[cells[i]] == null

    val moves = HashMap<Long, Pair<Int, Int>>()
    val forward = (start + 1 until cells.size).firstOrNull(::free)
    if (forward != null) {
        for (i in start until forward) moves[iconAt.getValue(cells[i]).item.id] = cells[i + 1]
    } else {
        val backward = (start - 1 downTo 0).firstOrNull(::free) ?: return null
        for (i in backward + 1..start) moves[iconAt.getValue(cells[i]).item.id] = cells[i - 1]
    }
    val shifted = list.map { p -> moves[p.item.id]?.let { (nx, ny) -> p.copy(x = nx, y = ny) } ?: p }
    return withItems(container, shifted + Placed(item, x, y))
}

/** Removes pages without items, except the home page; there is always at least one page. */
fun HomeLayout.removeEmptyPages(): HomeLayout {
    val kept = pages.filter { it.items.isNotEmpty() || it.id == homePageId }
    return if (kept.size == pages.size || kept.isEmpty()) this else copy(pages = kept)
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
    cell: Pair<Int, Int>? = null,
): HomeLayout {
    val (id, layout) = allocateId()
    val w = spanX.coerceIn(1, columns)
    val h = spanY.coerceIn(1, rows)
    val item = WidgetItem(id, appWidgetId, provider, userSerial)
    // Dropped at a spot: there, or as close as it fits on that page.
    if (cell != null) layout.drop(item, Container.Page(preferredPageId), cell.first, cell.second, w, h, merge = false)?.let { return it }
    return layout.placeAnywhere(item, preferredPageId, w, h)
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
