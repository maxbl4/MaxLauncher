package com.maxbl4.maxlauncher.home

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedLayoutTest {

    private val installed = setOf("Gmail", "Chrome", "Камера", "СберБанк", "ВТБ", "Телефон")
    private fun resolve(label: String) = if (label in installed) AppRef("pkg.$label/.Main", 0) else null

    private val sample = ImportedLayout(
        columns = 4,
        rows = 6,
        homePage = 1,
        pages = listOf(
            ImportedPage(listOf(ImportedItem("app", "Gmail", 0, 4), ImportedItem("widget", "Gismeteo", 0, 0, 4, 3))),
            ImportedPage(
                listOf(
                    ImportedItem("folder", "Банки", 2, 5, apps = listOf("СберБанк", "ВТБ", "Raiffeisen")),
                    ImportedItem("folder", "Одна", 3, 5, apps = listOf("Chrome", "Missing")),
                    ImportedItem("app", "Gmail", 2, 5), // same cell as the folder: moved nearby
                ),
            ),
        ),
        dock = listOf(ImportedItem("app", "Телефон"), ImportedItem("app", "Камера")),
    )

    @Test
    fun buildsPagesHomeDockAndFolders() {
        val result = sample.toHomeLayout(::resolve)
        val layout = result.layout
        assertEquals(2, layout.pages.size)
        assertEquals(layout.pages[1].id, layout.homePageId)
        assertEquals(4 to 6, layout.columns to layout.rows)

        val first = Container.Page(layout.pages[0].id)
        assertEquals(resolve("Gmail"), (layout.itemAt(first, 0, 4)?.item as AppItem).app)

        val second = Container.Page(layout.pages[1].id)
        val banks = layout.itemAt(second, 2, 5)?.item as FolderItem
        assertEquals("Банки", banks.title)
        assertEquals(2, banks.apps.size)
        // A folder with one installed app becomes that app.
        assertEquals(resolve("Chrome"), (layout.itemAt(second, 3, 5)?.item as AppItem).app)
        // The colliding icon went to a free cell.
        assertEquals(3, layout.items(second).size)

        assertEquals(listOf(resolve("Телефон"), resolve("Камера")), layout.dock.map { (it.item as AppItem).app })
        assertEquals(listOf("Raiffeisen", "Missing"), result.missing)
    }

    @Test
    fun widgetsAreReturnedForBinding() {
        val result = sample.toHomeLayout(::resolve)
        assertEquals(listOf(PendingWidget(result.layout.pages[0].id, "Gismeteo", 0, 0, 4, 3)), result.widgets)
    }

    @Test
    fun idsAreUniqueAndNextIdIsPastThem() {
        val layout = sample.toHomeLayout(::resolve).layout
        val ids = layout.allPlaced().flatMap { p ->
            listOf(p.item.id) + ((p.item as? FolderItem)?.apps?.map { it.id } ?: emptyList())
        } + layout.pages.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it < layout.nextId })
    }

    @Test
    fun appLabelsCoverIconsFoldersAndDock() {
        assertEquals(
            listOf("Gmail", "СберБанк", "ВТБ", "Raiffeisen", "Chrome", "Missing", "Телефон", "Камера"),
            sample.appLabels(),
        )
    }

    @Test
    fun parsesScriptJson() {
        val json = """{"columns":4,"rows":6,"homePage":0,"pages":[{"items":[{"type":"app","label":"Gmail","x":1,"y":2}]}],"dock":[]}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<ImportedLayout>(json)
        assertEquals(ImportedItem("app", "Gmail", 1, 2), parsed.pages.single().items.single())
    }
}
