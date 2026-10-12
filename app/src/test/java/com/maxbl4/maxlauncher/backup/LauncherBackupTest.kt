package com.maxbl4.maxlauncher.backup

import com.maxbl4.maxlauncher.drawer.DrawerFolder
import com.maxbl4.maxlauncher.drawer.DrawerLayout
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.Container
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.PendingWidget
import com.maxbl4.maxlauncher.home.Placed
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.WidgetItem
import com.maxbl4.maxlauncher.settings.JsonFileStore
import com.maxbl4.maxlauncher.settings.LauncherSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherBackupTest {

    private val page = Container.Page(1)
    private fun ref(name: String) = AppRef("$name/.Main", 0)

    private val backup = LauncherBackup(
        home = HomeLayout.initial().copy(nextId = 100)
            .withItems(
                page,
                listOf(
                    Placed(AppItem(10, ref("a")), 0, 0),
                    Placed(AppItem(11, ref("gone")), 1, 0),
                    Placed(FolderItem(12, "F", listOf(AppItem(13, ref("a")), AppItem(14, ref("gone")))), 2, 0),
                    Placed(ShortcutItem(15, "gone", "s1", 0, "S"), 3, 0),
                    Placed(ShortcutItem(16, "a", "s2", 0, "S2"), 0, 1),
                    Placed(WidgetItem(17, 5, "w/.Kept", 0), 0, 2, 2, 2),
                    Placed(WidgetItem(18, 6, "w/.Lost", 0), 2, 2, 4, 2),
                ),
            )
            .withItems(Container.Dock, listOf(Placed(AppItem(19, ref("b")), 0, 0))),
        drawer = DrawerLayout(listOf(DrawerFolder(1, "D", listOf(ref("a"), ref("b"), ref("gone")))), nextId = 2),
        settings = LauncherSettings(drawerColumns = 4, hiddenApps = setOf(ref("b"), ref("gone"))),
    )

    private fun plan() = backup.toRestorePlan(
        isInstalled = { it.component.substringBefore('/') != "gone" },
        isPackageInstalled = { pkg, _ -> pkg != "gone" },
        isWidgetBound = { it.appWidgetId == 5 },
    )

    @Test
    fun missingAppsAndShortcutsAreLeftOut() {
        val plan = plan()
        val items = plan.home.items(page).associate { it.item.id to it.item }
        assertEquals(setOf(10L, 13L, 16L, 17L), items.keys)
        // The folder lost an app and became the other one.
        assertEquals(AppItem(13, ref("a")), plan.home.itemAt(page, 2, 0)?.item)
        assertEquals(listOf("gone"), plan.missingPackages)
        assertEquals(listOf(ref("b")), plan.home.dock.map { (it.item as AppItem).app })
    }

    @Test
    fun unboundWidgetsAreCreatedAgainAtTheirSpot() {
        assertEquals(listOf(PendingWidget(1, "w/.Lost", 2, 2, 4, 2)), plan().widgets)
    }

    @Test
    fun drawerAndSettingsKeepInstalledApps() {
        val plan = plan()
        assertEquals(listOf(ref("a"), ref("b")), plan.drawer.folders.single().apps)
        assertEquals(setOf(ref("b")), plan.settings.hiddenApps)
        assertEquals(4, plan.settings.drawerColumns)
    }

    @Test
    fun roundTripsThroughJson() {
        val text = JsonFileStore.JSON.encodeToString(LauncherBackup.serializer(), backup)
        assertEquals(backup, JsonFileStore.JSON.decodeFromString(LauncherBackup.serializer(), text))
    }
}
