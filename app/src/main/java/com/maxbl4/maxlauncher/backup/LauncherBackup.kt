package com.maxbl4.maxlauncher.backup

import com.maxbl4.maxlauncher.drawer.DrawerLayout
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.Container
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.PendingWidget
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.WidgetItem
import com.maxbl4.maxlauncher.home.allPlaced
import com.maxbl4.maxlauncher.home.remove
import com.maxbl4.maxlauncher.home.removeApp
import com.maxbl4.maxlauncher.settings.LauncherSettings
import kotlinx.serialization.Serializable

/** Everything the user set up: home layout, drawer folders and settings, saved to a file. */
@Serializable
data class LauncherBackup(
    val format: Int = FORMAT,
    val home: HomeLayout,
    val drawer: DrawerLayout,
    val settings: LauncherSettings,
) {
    companion object {
        const val FORMAT = 1
    }
}

data class RestorePlan(
    val home: HomeLayout,
    val drawer: DrawerLayout,
    val settings: LauncherSettings,
    /** Widgets that must be created again; they are not in [home] yet. Labels are provider names. */
    val widgets: List<PendingWidget>,
    /** Packages of apps and shortcuts that are not installed (left out). */
    val missingPackages: List<String>,
)

/**
 * Fits a backup to this phone: apps and shortcuts that are not installed are left out, and
 * widgets whose ids are no longer bound to the same provider (another phone, a reinstall) are
 * taken off to be created again at the same spot.
 */
fun LauncherBackup.toRestorePlan(
    isInstalled: (AppRef) -> Boolean,
    isPackageInstalled: (packageName: String, userSerial: Long) -> Boolean,
    isWidgetBound: (WidgetItem) -> Boolean,
): RestorePlan {
    val missing = LinkedHashSet<String>()
    fun installed(ref: AppRef) = isInstalled(ref).also { if (!it) missing += ref.component.substringBefore('/') }

    var layout = home
    val homeApps = home.allPlaced().flatMap { p ->
        when (val item = p.item) {
            is AppItem -> listOf(item.app)
            is FolderItem -> item.apps.map { it.app }
            else -> emptyList()
        }
    }.distinct()
    homeApps.filterNot(::installed).forEach { layout = layout.removeApp(it) }

    val widgets = mutableListOf<PendingWidget>()
    for (container in home.containers) {
        for (placed in home.items(container)) {
            when (val item = placed.item) {
                is ShortcutItem -> if (!isPackageInstalled(item.packageName, item.userSerial)) {
                    missing += item.packageName
                    layout = layout.remove(item.id)
                }
                is WidgetItem -> if (!isWidgetBound(item)) {
                    layout = layout.remove(item.id)
                    val pageId = (container as? Container.Page)?.pageId ?: home.homePageId
                    widgets += PendingWidget(pageId, item.provider, placed.x, placed.y, placed.spanX, placed.spanY)
                }
                else -> Unit
            }
        }
    }

    val drawerApps = drawer.folders.flatMap { it.apps }.distinct()
    val restoredDrawer = drawer.removeApps(drawerApps.filterNot(::installed))
    val restoredSettings = settings.copy(hiddenApps = settings.hiddenApps.filter(::installed).toSet())
    return RestorePlan(layout, restoredDrawer, restoredSettings, widgets, missing.toList())
}
