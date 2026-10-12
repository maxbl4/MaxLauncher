package com.maxbl4.maxlauncher.drawer

import com.maxbl4.maxlauncher.home.AppRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrawerLayoutTest {

    private fun ref(name: String) = AppRef("pkg.$name/.Main", 0)
    private val a = ref("a")
    private val b = ref("b")
    private val c = ref("c")

    @Test
    fun mergeCreatesFolderAndJoinsExisting() {
        val one = DrawerLayout().merge(a, b)
        assertEquals(listOf(DrawerFolder(1, apps = listOf(b, a))), one.folders)
        val two = one.merge(c, a)
        assertEquals(listOf(b, a, c), two.folders.single().apps)
    }

    @Test
    fun removingFromTwoAppFolderDissolvesIt() {
        val layout = DrawerLayout().merge(a, b).removeApp(a)
        assertEquals(emptyList<DrawerFolder>(), layout.folders)
    }

    @Test
    fun movingAppBetweenFolders() {
        val layout = DrawerLayout().merge(a, b).merge(c, ref("d")).merge(ref("e"), c)
        val target = layout.folders.first { b in it.apps }.id
        val moved = layout.addToFolder(target, c)
        assertEquals(listOf(b, a, c), moved.folder(target)!!.apps)
        assertEquals(listOf(ref("d"), ref("e")), moved.folders.first { it.id != target }.apps)
    }

    @Test
    fun setAppsBelowTwoDissolves() {
        val layout = DrawerLayout().merge(a, b)
        assertNull(layout.setApps(1, listOf(a)).folder(1))
        assertEquals(listOf(a, b, c), layout.setApps(1, listOf(a, b, c)).folder(1)!!.apps)
    }

    @Test
    fun removePackage() {
        val layout = DrawerLayout().merge(a, b).merge(c, a)
        assertEquals(listOf(b, c), layout.removePackage("pkg.a", 0).folders.single().apps)
    }

    @Test
    fun entriesFoldersFirstSortedHiddenExcluded() {
        val apps = listOf("Авито", "Банк", "Карты", "Chrome", "Gmail", "Yandex")
        fun r(label: String) = AppRef("$label/.M", 0)
        val layout = DrawerLayout(
            folders = listOf(
                DrawerFolder(1, "Работа", listOf(r("Gmail"), r("Chrome"))),
                DrawerFolder(2, "Банки", listOf(r("Банк"), r("Yandex"))),
            ),
        )
        val entries = buildDrawerEntries(apps, layout, hidden = setOf(r("Карты"), r("Yandex")), ref = ::r, label = { it }, folderTitle = { it.title })
        val shown = entries.map {
            when (it) {
                is DrawerEntry.Folder -> it.folder.title + it.apps
                is DrawerEntry.App -> it.app
            }
        }
        assertEquals(listOf("Банки[Банк]", "Работа[Chrome, Gmail]", "Авито"), shown)
    }
}
