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
 * folder; onto a folder it joins the folder. Otherwise the item takes the cell, or the nearest
 * free one when the cell is taken. Returns null when the container has no room.
 */
fun HomeLayout.drop(item: HomeItem, container: Container, x: Int, y: Int): HomeLayout? {
    if (!exists(container)) return null
    val (columns, rows) = size(container)
    val cx = x.coerceIn(0, columns - 1)
    val cy = y.coerceIn(0, rows - 1)
    val occupant = itemAt(container, cx, cy)
        ?: return withItems(container, items(container) + Placed(item, cx, cy))

    if (item is AppItem) {
        when (val target = occupant.item) {
            is AppItem -> {
                val (folderId, layout) = allocateId()
                val folder = FolderItem(folderId, apps = listOf(target, item))
                return layout.withItems(container, items(container).replace(occupant, occupant.copy(item = folder)))
            }
            is FolderItem -> {
                val apps = if (target.apps.any { it.app == item.app }) target.apps else target.apps + item
                return withItems(container, items(container).replace(occupant, occupant.copy(item = target.copy(apps = apps))))
            }
            is ShortcutItem -> Unit
        }
    }
    val (fx, fy) = nearestFreeCell(container, cx, cy) ?: return null
    return withItems(container, items(container) + Placed(item, fx, fy))
}

/** Moves an existing item (top-level or from a folder) to a cell; unchanged when it does not fit. */
fun HomeLayout.move(itemId: Long, container: Container, x: Int, y: Int): HomeLayout {
    val item = findItem(itemId) ?: return this
    val occupant = itemAt(container, x, y)?.item
    // Dropping an item onto itself, or an app back onto its own folder, changes nothing.
    if (occupant?.id == itemId) return this
    if (occupant is FolderItem && occupant.apps.any { it.id == itemId }) return this
    return remove(itemId).drop(item, container, x, y) ?: this
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
            layout.nearestFreeCell(container, placed.x, placed.y)
                ?.let { (x, y) -> layout.withItems(container, layout.items(container) + Placed(app, x, y)) }
                ?: layout.placeAnywhere(app, preferredPage)
        }
    }
    return layout
}

/** Removes every occurrence of an app (e.g. after it was uninstalled). */
fun HomeLayout.removeApp(app: AppRef): HomeLayout {
    val ids = containers.flatMap { items(it) }.flatMap { p ->
        when (val item = p.item) {
            is AppItem -> listOfNotNull(item.id.takeIf { item.app == app })
            is FolderItem -> item.apps.filter { it.app == app }.map { it.id }
            is ShortcutItem -> emptyList()
        }
    }
    return ids.fold(this) { layout, id -> layout.remove(id) }
}

fun HomeLayout.removeShortcutsOf(packageName: String, userSerial: Long): HomeLayout {
    val ids = containers.flatMap { items(it) }.mapNotNull { p ->
        (p.item as? ShortcutItem)?.takeIf { it.packageName == packageName && it.userSerial == userSerial }?.id
    }
    return ids.fold(this) { layout, id -> layout.remove(id) }
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

fun HomeLayout.nearestFreeCell(container: Container, x: Int, y: Int): Pair<Int, Int>? {
    val (columns, rows) = size(container)
    val taken = items(container).map { it.x to it.y }.toSet()
    return (0 until rows).flatMap { cy -> (0 until columns).map { cx -> cx to cy } }
        .filter { it !in taken }
        .minByOrNull { (cx, cy) -> (cx - x) * (cx - x) + (cy - y) * (cy - y) }
}

private fun HomeLayout.firstFreeCell(container: Container): Pair<Int, Int>? {
    val (columns, rows) = size(container)
    val taken = items(container).map { it.x to it.y }.toSet()
    for (cy in 0 until rows) for (cx in 0 until columns) if ((cx to cy) !in taken) return cx to cy
    return null
}

private fun HomeLayout.placeAnywhere(item: HomeItem, preferredPageId: Long): HomeLayout {
    val ordered = pages.sortedBy { if (it.id == preferredPageId) 0 else 1 }
    for (page in ordered) {
        val container = Container.Page(page.id)
        firstFreeCell(container)?.let { (x, y) -> return withItems(container, items(container) + Placed(item, x, y)) }
    }
    val (pageId, layout) = addPage(pages.size)
    return layout.withItems(Container.Page(pageId), listOf(Placed(item, 0, 0)))
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
