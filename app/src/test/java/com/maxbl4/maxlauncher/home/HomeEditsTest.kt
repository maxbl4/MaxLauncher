package com.maxbl4.maxlauncher.home

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEditsTest {

    private val page = Container.Page(1)
    private fun ref(name: String) = AppRef("pkg/$name", 0)

    /** Layout with apps "a" (id 10) at (0,0) and "b" (id 11) at (1,0) on page 1. */
    private fun twoApps() = HomeLayout.initial().copy(nextId = 100).withItems(
        page,
        listOf(Placed(AppItem(10, ref("a")), 0, 0), Placed(AppItem(11, ref("b")), 1, 0)),
    )

    @Test
    fun moveToEmptyCell() {
        val layout = twoApps().move(10, page, 3, 5)
        assertEquals(AppItem(10, ref("a")), layout.itemAt(page, 3, 5)?.item)
        assertNull(layout.itemAt(page, 0, 0))
    }

    @Test
    fun appOnAppCreatesFolder() {
        val layout = twoApps().move(10, page, 1, 0)
        val folder = layout.itemAt(page, 1, 0)?.item as FolderItem
        assertEquals(setOf(10L, 11L), folder.apps.map { it.id }.toSet())
        assertEquals(1, layout.items(page).size)
    }

    @Test
    fun appOnFolderJoinsIt() {
        val withFolder = twoApps().move(10, page, 1, 0)
        val layout = withFolder.drop(AppItem(500, ref("c")), page, 1, 0)!!
        assertEquals(3, (layout.itemAt(page, 1, 0)?.item as FolderItem).apps.size)
    }

    @Test
    fun draggingOutOfTwoAppFolderDissolvesIt() {
        val withFolder = twoApps().move(10, page, 1, 0)
        val layout = withFolder.move(10, page, 2, 2)
        assertEquals(AppItem(11, ref("b")), layout.itemAt(page, 1, 0)?.item)
        assertEquals(AppItem(10, ref("a")), layout.itemAt(page, 2, 2)?.item)
    }

    @Test
    fun droppingAppBackOnItsFolderChangesNothing() {
        val withFolder = twoApps().move(10, page, 1, 0)
        assertEquals(withFolder, withFolder.move(10, page, 1, 0))
    }

    @Test
    fun shortcutOnOccupiedCellGoesToNearestFreeCell() {
        val layout = twoApps().drop(ShortcutItem(7, "p", "s", 0, "S"), page, 0, 0)!!
        assertEquals(7L, layout.itemAt(page, 0, 1)?.item?.id)
    }

    @Test
    fun fullDockRejectsNonMergingDrop() {
        var layout = HomeLayout.initial().copy(nextId = 100)
        repeat(layout.dockSlots) { i -> layout = layout.drop(ShortcutItem(i.toLong(), "p", "$i", 0, ""), Container.Dock, i, 0)!! }
        assertNull(layout.drop(ShortcutItem(99, "p", "x", 0, ""), Container.Dock, 0, 0))
        // A failed move keeps the item where it was.
        val withApp = layout.withItems(page, listOf(Placed(ShortcutItem(50, "p", "y", 0, ""), 0, 0)))
        assertEquals(withApp, withApp.move(50, Container.Dock, 0, 0))
    }

    @Test
    fun addAppAppendsPageWhenAllFull() {
        var layout = HomeLayout.initial().copy(columns = 1, rows = 1)
        layout = layout.addApp(ref("a")).addApp(ref("b"))
        assertEquals(2, layout.pages.size)
        assertEquals(1, layout.pages[1].items.size)
    }

    @Test
    fun setFolderAppsKeepsIdsAndDissolvesToSingleApp() {
        val withFolder = twoApps().move(10, page, 1, 0)
        val folderId = withFolder.itemAt(page, 1, 0)!!.item.id
        val three = withFolder.setFolderApps(folderId, listOf(ref("a"), ref("b"), ref("c")))
        val apps = (three.findFolder(folderId)!!).apps
        assertTrue(apps.any { it.id == 10L } && apps.any { it.id == 11L } && apps.size == 3)
        val one = three.setFolderApps(folderId, listOf(ref("c")))
        assertEquals(ref("c"), (one.itemAt(page, 1, 0)?.item as AppItem).app)
    }

    @Test
    fun dissolveFolderPutsAppsBackNearby() {
        val withFolder = twoApps().move(10, page, 1, 0).drop(AppItem(500, ref("c")), page, 1, 0)!!
        val folderId = withFolder.itemAt(page, 1, 0)!!.item.id
        val layout = withFolder.dissolveFolder(folderId)
        assertEquals(3, layout.items(page).size)
        assertTrue(layout.items(page).all { it.item is AppItem })
    }

    @Test
    fun removeAppEverywhere() {
        val layout = twoApps().move(10, page, 1, 0).drop(AppItem(500, ref("a")), page, 3, 3)!!.removeApp(ref("a"))
        assertEquals(listOf(AppItem(11, ref("b"))), layout.items(page).map { it.item })
    }

    @Test
    fun removingHomePageMovesHomeToNeighbour() {
        val (second, layout) = HomeLayout.initial().addPage(1)
        val removed = layout.setHomePage(second).removePage(second)
        assertEquals(1L, removed.homePageId)
        assertEquals(removed, removed.removePage(1)) // the last page stays
    }

    @Test
    fun addPageOnTheLeft() {
        val (id, layout) = HomeLayout.initial().addPage(0)
        assertEquals(listOf(id, 1L), layout.pages.map { it.id })
        assertEquals(1, layout.homePageIndex)
    }

    @Test
    fun widgetTakesFreeAreaAndBlocksCells() {
        val layout = twoApps().drop(WidgetItem(20, 5, "p/.W", 0), page, 0, 0, 2, 2)!!
        // (0,0) and (1,0) are taken, so the 2x2 widget goes to the nearest free 2x2 area.
        val widget = layout.findPlaced(20)!!.second
        assertEquals(listOf(0, 1, 2, 2), listOf(widget.x, widget.y, widget.spanX, widget.spanY))
        assertEquals(20L, layout.itemAt(page, 1, 2)?.item?.id)
        // An app dropped onto the widget does not merge; it goes to a free cell.
        val withApp = layout.drop(AppItem(30, ref("c")), page, 1, 2)!!
        assertTrue(withApp.findPlaced(30)!!.second.let { !(it.x in 0..1 && it.y in 1..2) })
    }

    @Test
    fun widgetsAreNotAllowedInDock() {
        assertNull(HomeLayout.initial().drop(WidgetItem(20, 5, "p/.W", 0), Container.Dock, 0, 0, 2, 1))
    }

    @Test
    fun resizeWidgetRespectsNeighbours() {
        val layout = twoApps().drop(WidgetItem(20, 5, "p/.W", 0), page, 0, 1, 2, 2)!!
        assertEquals(layout, layout.resizeWidget(20, 0, 0, 2, 3)) // would cover the apps in row 0
        val bigger = layout.resizeWidget(20, 0, 1, 4, 3)
        assertEquals(4 to 3, bigger.findPlaced(20)!!.second.let { it.spanX to it.spanY })
    }

    @Test
    fun movingWidgetKeepsItsSize() {
        val layout = twoApps().drop(WidgetItem(20, 5, "p/.W", 0), page, 0, 1, 2, 2)!!.move(20, page, 2, 4)
        val widget = layout.findPlaced(20)!!.second
        assertEquals(listOf(2, 4, 2, 2), listOf(widget.x, widget.y, widget.spanX, widget.spanY))
    }

    @Test
    fun shrinkingGridMovesItemsThatNoLongerFit() {
        val layout = HomeLayout.initial().copy(nextId = 100).withItems(
            page,
            listOf(Placed(AppItem(10, ref("a")), 4, 5), Placed(WidgetItem(11, 1, "p/.W", 0), 0, 0, 5, 2)),
        ).copy(columns = 5, rows = 6, dockSlots = 5).withItems(Container.Dock, listOf(Placed(AppItem(12, ref("d")), 4, 0)))
        val small = layout.resizeGrid(4, 5)
        val all = small.allPlaced()
        assertEquals(3, all.size)
        assertTrue(all.all { it.x + it.spanX <= 4 })
        assertEquals(4, small.findPlaced(11)!!.second.spanX)
        assertEquals(4, small.dockSlots)
    }

    @Test
    fun jsonRoundTrip() {
        val layout = twoApps().move(10, page, 1, 0).addShortcut("p", "s", 0, "Label")
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(layout, json.decodeFromString<HomeLayout>(json.encodeToString(layout)))
    }
}
