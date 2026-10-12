package com.maxbl4.maxlauncher.drawer

import com.maxbl4.maxlauncher.apps.AppSorting
import com.maxbl4.maxlauncher.home.AppRef
import kotlinx.serialization.Serializable

/** A folder in the app drawer. Its apps are not shown in the drawer's main list. */
@Serializable
data class DrawerFolder(val id: Long, val title: String = "", val apps: List<AppRef>)

/** User-made folders of the app drawer. Everything else in the drawer is derived from installed apps. */
@Serializable
data class DrawerLayout(val folders: List<DrawerFolder> = emptyList(), val nextId: Long = 1) {

    fun folderOf(app: AppRef): DrawerFolder? = folders.firstOrNull { app in it.apps }

    fun folder(id: Long): DrawerFolder? = folders.firstOrNull { it.id == id }

    /** Drops [app] onto [target]: both end up in a new folder (or in the target's folder). */
    fun merge(app: AppRef, target: AppRef): DrawerLayout {
        if (app == target) return this
        folderOf(target)?.let { return addToFolder(it.id, app) }
        val base = removeApp(app)
        return base.copy(
            folders = base.folders + DrawerFolder(base.nextId, apps = listOf(target, app)),
            nextId = base.nextId + 1,
        )
    }

    fun addToFolder(folderId: Long, app: AppRef): DrawerLayout {
        if (folder(folderId)?.apps?.contains(app) == true) return this
        val base = removeApp(app)
        if (base.folder(folderId) == null) return this
        return base.copy(folders = base.folders.map { if (it.id == folderId) it.copy(apps = it.apps + app) else it })
    }

    /** Takes [app] out of its folder; a folder left with one app is dissolved. */
    fun removeApp(app: AppRef): DrawerLayout =
        copy(folders = folders.mapNotNull { f -> if (app in f.apps) f.copy(apps = f.apps - app).takeIf { it.apps.size > 1 } else f })

    fun removeApps(apps: Collection<AppRef>): DrawerLayout = apps.fold(this) { layout, app -> layout.removeApp(app) }

    fun rename(folderId: Long, title: String): DrawerLayout =
        copy(folders = folders.map { if (it.id == folderId) it.copy(title = title.trim()) else it })

    /** Replaces a folder's content (picked in the app picker); fewer than two apps dissolves it. */
    fun setApps(folderId: Long, apps: List<AppRef>): DrawerLayout {
        val folder = folder(folderId) ?: return this
        val chosen = apps.distinct()
        // Apps picked into this folder leave their old folders.
        val base = removeApps(chosen.filter { folderOf(it)?.id != folderId })
        val updated = folder.copy(apps = chosen)
        return base.copy(
            folders = base.folders.mapNotNull { if (it.id == folderId) updated.takeIf { u -> u.apps.size > 1 } else it },
        )
    }

    fun deleteFolder(folderId: Long): DrawerLayout = copy(folders = folders.filterNot { it.id == folderId })

    fun removePackage(packageName: String, userSerial: Long): DrawerLayout = removeApps(
        folders.flatMap { it.apps }.filter { it.userSerial == userSerial && it.component.substringBefore('/') == packageName },
    )
}

/** An entry of the drawer's main list: a folder, or an app that is in no folder. */
sealed interface DrawerEntry<A> {
    data class Folder<A>(val folder: DrawerFolder, val apps: List<A>) : DrawerEntry<A>
    data class App<A>(val app: A) : DrawerEntry<A>
}

/**
 * Builds the drawer list: folders first (sorted by name, apps inside sorted), then apps that
 * are in no folder. [apps] must already be sorted; hidden apps are left out everywhere.
 */
fun <A : Any> buildDrawerEntries(
    apps: List<A>,
    layout: DrawerLayout,
    hidden: Set<AppRef>,
    ref: (A) -> AppRef,
    label: (A) -> String,
    folderTitle: (DrawerFolder) -> String,
): List<DrawerEntry<A>> {
    val visible = apps.filter { ref(it) !in hidden }
    val byRef = visible.associateBy(ref)
    val inFolders = HashSet<AppRef>()
    val folders: List<DrawerEntry.Folder<A>> = layout.folders.mapNotNull { folder ->
        val folderApps = folder.apps.mapNotNull { byRef[it] }
        if (folderApps.isEmpty()) return@mapNotNull null
        inFolders += folder.apps
        DrawerEntry.Folder(folder, folderApps.sortedWith(AppSorting.comparator(label)))
    }.sortedWith(AppSorting.comparator { folderTitle(it.folder) })
    val loose: List<DrawerEntry<A>> = visible.filter { ref(it) !in inFolders }.map { DrawerEntry.App(it) }
    return folders + loose
}
