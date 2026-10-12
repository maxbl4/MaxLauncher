package com.maxbl4.maxlauncher.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.drawer.DrawerFolder
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.Container
import com.maxbl4.maxlauncher.home.HomeItem
import com.maxbl4.maxlauncher.home.WidgetItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Which surface is on top and receives long presses. */
enum class Layer { HOME, DRAWER, FOLDER, WIDGET_PICKER }

/** Something that can be long-pressed (menu) and dragged. */
sealed interface DragSource {
    val key: String

    data class DrawerApp(val entry: AppEntry) : DragSource {
        override val key = "drawer:${entry.key.id}"
    }

    data class DrawerFolderEntry(val folder: DrawerFolder) : DragSource {
        override val key = "drawerFolder:${folder.id}"
    }

    data class DrawerFolderApp(val folderId: Long, val entry: AppEntry) : DragSource {
        override val key = "drawerFolderApp:${entry.key.id}"
    }

    /** A top-level item on a home page or in the dock. */
    data class HomeEntry(val item: HomeItem) : DragSource {
        override val key = "home:${item.id}"
    }

    data class FolderEntry(val folderId: Long, val item: AppItem) : DragSource {
        override val key = "folder:${item.id}"
    }

    /** A widget from the widget picker, not on the home screen yet. */
    data class NewWidget(val option: WidgetOption) : DragSource {
        override val key = "newWidget:${option.info.provider.flattenToString()}#${option.info.profile.hashCode()}"
    }
}

/** Sources that live in the app drawer; dragging them starts inside the drawer. */
val DragSource.isDrawerSource: Boolean
    get() = this is DragSource.DrawerApp || this is DragSource.DrawerFolderApp || this is DragSource.DrawerFolderEntry

sealed interface DropTarget {
    /**
     * A grid cell. [merge]: the finger is over the middle of an icon, so a dropped app makes a
     * folder with it; otherwise icons are pushed aside. In the dock [x] is an index.
     */
    data class Cell(val container: Container, val x: Int, val y: Int, val merge: Boolean = false) : DropTarget
    data object Remove : DropTarget

    // Inside the app drawer.
    data class DrawerOnApp(val entry: AppEntry) : DropTarget
    data class DrawerOnFolder(val folderId: Long) : DropTarget
    data object DrawerEmpty : DropTarget
}

data class MenuRequest(val source: DragSource, val bounds: Rect)

/** [widgetSpans]: cells the dragged widget takes (null for icons). */
data class DragState(
    val source: DragSource,
    val position: Offset,
    val grabOffset: Offset,
    val size: Size,
    val widgetSpans: Pair<Int, Int>? = null,
)

data class PageDrag(val pageId: Long, val position: Offset)

/**
 * UI state shared by the home screen, the app drawer and open folders: modes, the long-press
 * menu, drag and drop, and a registry of on-screen bounds used to hit-test gestures handled at
 * the root (see [launcherGestures]).
 */
@Stable
class LauncherState(private val scope: CoroutineScope) {

    val drawer = DrawerState(scope)

    var editMode by mutableStateOf(false)
    var openFolderId by mutableStateOf<Long?>(null)
    var openDrawerFolderId by mutableStateOf<Long?>(null)

    /** Centre of the folder icon that was tapped; the open folder grows out of it. */
    var folderOrigin: Offset? = null
    var resizingWidgetId by mutableStateOf<Long?>(null)
    var widgetPickerOpen by mutableStateOf(false)
    var menu by mutableStateOf<MenuRequest?>(null)
    var drag by mutableStateOf<DragState?>(null)
        private set
    /** The current drag happens inside the app drawer (folders), not on the home screen. */
    var dragInDrawer by mutableStateOf(false)
        private set
    var hover by mutableStateOf<DropTarget?>(null)
        private set
    var pageDrag by mutableStateOf<PageDrag?>(null)
        private set

    // From the settings.
    var layoutLocked by mutableStateOf(false)
    var doubleTapToLock by mutableStateOf(false)

    /** Set by the screen: applies a finished drag. */
    var onDrop: (DragSource, DropTarget) -> Unit = { _, _ -> }

    /** Set by the home screen: scrolls pages by +1/-1 while something is held at an edge. */
    var onEdgeScroll: suspend (direction: Int, mayAddPage: Boolean) -> Unit = { _, _ -> }

    /** Set by the drawer: scrolls its pages by +1/-1 while an app is held at an edge. */
    var onDrawerEdgeScroll: suspend (Int) -> Unit = {}

    /** Set by the screen: called once a drag ends, to clean up pages added while dragging. */
    var onDragFinished: () -> Unit = {}

    /** Set by the home screen: moves the dragged page to the page currently shown. */
    var onPageDrop: (Long) -> Unit = {}

    var onDoubleTap: () -> Unit = {}

    var rootSize = Size.Zero

    /** The home page currently shown (new widgets go there). */
    var currentHomePageId: Long? = null

    val activeLayer: Layer
        get() = when {
            widgetPickerOpen -> Layer.WIDGET_PICKER
            openFolderId != null || openDrawerFolderId != null -> Layer.FOLDER
            drawer.isVisible -> Layer.DRAWER
            else -> Layer.HOME
        }

    /** Drawer swipes only make sense on the plain home screen or in the drawer. */
    val canSwipeDrawer: Boolean
        get() = !editMode && openFolderId == null && openDrawerFolderId == null && drag == null &&
            resizingWidgetId == null && !widgetPickerOpen && drawer.swipesEnabled

    /** With a locked layout, home items can only be opened, not moved. */
    fun canDrag(source: DragSource): Boolean = when (source) {
        is DragSource.HomeEntry, is DragSource.FolderEntry -> !layoutLocked
        else -> true
    }

    // --- Hit registry (plain maps: written on layout, read by gestures; no recomposition) ---

    private class Registered(val layer: Layer, val source: DragSource, var bounds: Rect)
    private class GridArea(val container: Container, val columns: Int, val rows: Int, var bounds: Rect, val size: Size)

    private val sources = HashMap<Any, Registered>()
    private val grids = HashMap<Container, GridArea>()
    private var removeZone: Rect? = null
    private var toHomeZone: Rect? = null

    fun registerSource(token: Any, layer: Layer, source: DragSource, bounds: Rect) {
        sources[token]?.takeIf { it.source == source && it.layer == layer }?.let { it.bounds = bounds; return }
        sources[token] = Registered(layer, source, bounds)
    }

    fun unregisterSource(token: Any) {
        sources.remove(token)
    }

    fun registerGrid(container: Container, columns: Int, rows: Int, bounds: Rect, size: Size) {
        grids[container] = GridArea(container, columns, rows, bounds, size)
    }

    fun unregisterGrid(container: Container) {
        grids.remove(container)
    }

    fun setRemoveZone(bounds: Rect?) {
        removeZone = bounds
    }

    fun setToHomeZone(bounds: Rect?) {
        toHomeZone = bounds
    }

    fun hitTest(position: Offset, layer: Layer = activeLayer, exclude: DragSource? = null): Pair<DragSource, Rect>? =
        sources.values
            .filter { it.layer == layer && it.bounds.contains(position) && it.source.key != exclude?.key }
            .minByOrNull { it.bounds.width * it.bounds.height }
            ?.let { it.source to it.bounds }

    /** The home page whose background is at [position], if the home screen is on top. */
    fun homePageAt(position: Offset): Long? {
        if (activeLayer != Layer.HOME) return null
        return grids.values.firstOrNull { it.container is Container.Page && it.bounds.contains(position) }
            ?.let { (it.container as Container.Page).pageId }
    }

    /** Size of one home grid cell in px (unscaled, also in edit mode), or null before layout. */
    fun homeCellSize(): Size? = grids.values.firstOrNull { it.container is Container.Page }
        ?.let { Size(it.size.width / it.columns, it.size.height / it.rows) }

    private fun dropTargetAt(position: Offset, drag: DragState): DropTarget? {
        val source = drag.source
        if (dragInDrawer) {
            val hit = hitTest(position, Layer.DRAWER, exclude = source)?.first
            return when {
                hit is DragSource.DrawerApp && source !is DragSource.DrawerFolderEntry -> DropTarget.DrawerOnApp(hit.entry)
                hit is DragSource.DrawerFolderEntry && source !is DragSource.DrawerFolderEntry -> DropTarget.DrawerOnFolder(hit.folder.id)
                else -> DropTarget.DrawerEmpty
            }
        }
        if ((source is DragSource.HomeEntry || source is DragSource.FolderEntry) && removeZone?.contains(position) == true) {
            return DropTarget.Remove
        }
        // Widgets are placed by their top-left corner, icons by the finger.
        val isWidget = source.isWidget
        val point = if (isWidget) {
            val cell = homeCellSize() ?: Size.Zero
            position - drag.grabOffset + Offset(cell.width / 2, cell.height / 2)
        } else {
            position
        }
        val area = grids.values.firstOrNull { it.bounds.contains(point) } ?: return null
        val cellWidth = area.bounds.width / area.columns
        val cellHeight = area.bounds.height / area.rows
        val fx = (point.x - area.bounds.left) / cellWidth
        val fy = (point.y - area.bounds.top) / cellHeight
        val inMiddleX = abs(fx - floor(fx) - 0.5f) < MERGE_ZONE
        val inMiddleY = abs(fy - floor(fy) - 0.5f) < MERGE_ZONE
        if (area.container == Container.Dock) {
            if (isWidget) return null
            // A row of icons: over the middle of one merges with it, elsewhere inserts between.
            return if (inMiddleX && fx.toInt() < area.columns) {
                DropTarget.Cell(Container.Dock, fx.toInt(), 0, merge = true)
            } else {
                DropTarget.Cell(Container.Dock, fx.roundToInt().coerceIn(0, area.columns), 0)
            }
        }
        val x = fx.toInt().coerceIn(0, area.columns - 1)
        val y = fy.toInt().coerceIn(0, area.rows - 1)
        return DropTarget.Cell(area.container, x, y, merge = !isWidget && inMiddleX && inMiddleY)
    }

    private val DragSource.isWidget: Boolean
        get() = this is DragSource.NewWidget || (this as? DragSource.HomeEntry)?.item is WidgetItem

    // --- Long press, drag and drop ---

    fun showMenu(source: DragSource, bounds: Rect) {
        // Widgets in the picker have no menu; a long press only starts dragging them.
        if (source !is DragSource.NewWidget) menu = MenuRequest(source, bounds)
    }

    fun startDrag(source: DragSource, position: Offset, bounds: Rect) {
        menu = null
        dragInDrawer = source.isDrawerSource
        val state = when (source) {
            is DragSource.NewWidget -> {
                // Leave the picker and carry a widget-sized frame, held by its middle.
                widgetPickerOpen = false
                editMode = false
                val cell = homeCellSize() ?: Size(bounds.width / 2, bounds.height / 2)
                val size = Size(cell.width * source.option.spanX, cell.height * source.option.spanY)
                DragState(source, position, Offset(size.width / 2, size.height / 2), size, source.option.spanX to source.option.spanY)
            }
            else -> {
                when (source) {
                    is DragSource.DrawerFolderApp -> openDrawerFolderId = null
                    is DragSource.FolderEntry -> openFolderId = null
                    else -> Unit
                }
                val spans = if (source.isWidget) {
                    homeCellSize()?.let { cell -> (bounds.width / cell.width).roundToInt().coerceAtLeast(1) to (bounds.height / cell.height).roundToInt().coerceAtLeast(1) }
                } else {
                    null
                }
                DragState(source, position, position - bounds.topLeft, bounds.size, spans)
            }
        }
        drag = state
        hover = dropTargetAt(position, state)
    }

    private var edgeJob: Job? = null

    fun dragTo(position: Offset) {
        val current = drag?.copy(position = position) ?: return
        drag = current
        // Apps dragged to the top of the drawer continue on the home screen.
        if (dragInDrawer && !layoutLocked && current.source !is DragSource.DrawerFolderEntry &&
            toHomeZone?.contains(position) == true
        ) {
            dragInDrawer = false
            drawer.close()
        }
        hover = dropTargetAt(position, current)
        val inDrawer = dragInDrawer
        handleEdge(position) { direction ->
            if (inDrawer) onDrawerEdgeScroll(direction) else onEdgeScroll(direction, true)
        }
    }

    private fun handleEdge(position: Offset, scroll: suspend (Int) -> Unit) {
        val edge = rootSize.width * EDGE_FRACTION
        val direction = when {
            position.x < edge -> -1
            position.x > rootSize.width - edge -> 1
            else -> 0
        }
        if (direction == 0) {
            edgeJob?.cancel()
            edgeJob = null
        } else if (edgeJob == null) {
            edgeJob = scope.launch {
                delay(EDGE_DELAY_MS)
                while (true) {
                    scroll(direction)
                    delay(EDGE_REPEAT_MS)
                }
            }
        }
    }

    private fun stopEdge() {
        edgeJob?.cancel()
        edgeJob = null
    }

    fun endDrag(position: Offset) {
        val current = drag ?: return
        stopEdge()
        val target = dropTargetAt(position, current)
        drag = null
        hover = null
        dragInDrawer = false
        if (target != null) onDrop(current.source, target)
        onDragFinished()
    }

    fun cancelDrag() {
        stopEdge()
        drag = null
        hover = null
        dragInDrawer = false
        onDragFinished()
    }

    fun isDragged(source: DragSource): Boolean = drag?.source?.key == source.key

    val draggedWidgetSpans: Pair<Int, Int>? get() = drag?.widgetSpans

    // --- Page reordering (edit mode) ---

    fun startPageDrag(pageId: Long, position: Offset) {
        pageDrag = PageDrag(pageId, position)
    }

    fun pageDragTo(position: Offset) {
        val current = pageDrag ?: return
        pageDrag = current.copy(position = position)
        handleEdge(position) { direction -> onEdgeScroll(direction, false) }
    }

    fun endPageDrag() {
        val current = pageDrag ?: return
        stopEdge()
        pageDrag = null
        onPageDrop(current.pageId)
    }

    /** Back / Home: close the topmost thing. Returns false when there was nothing to close. */
    fun closeTopmost(): Boolean = when {
        menu != null -> { menu = null; true }
        widgetPickerOpen -> { widgetPickerOpen = false; true }
        resizingWidgetId != null -> { resizingWidgetId = null; true }
        openFolderId != null -> { openFolderId = null; true }
        openDrawerFolderId != null -> { openDrawerFolderId = null; true }
        drawer.isOpen -> { drawer.close(); true }
        editMode -> { editMode = false; true }
        else -> false
    }

    /** The launcher went to the background: reset to the plain home screen. */
    fun reset() {
        menu = null
        openFolderId = null
        openDrawerFolderId = null
        resizingWidgetId = null
        widgetPickerOpen = false
        editMode = false
        if (drag != null) cancelDrag()
        pageDrag = null
        drawer.snapClosed()
    }

    private companion object {
        const val EDGE_FRACTION = 0.06f
        const val EDGE_DELAY_MS = 450L
        const val EDGE_REPEAT_MS = 900L
        /** Half-size of the middle part of a cell (as a fraction) where a dropped app makes a folder. */
        const val MERGE_ZONE = 0.25f
    }
}

/** Registers this element as a long-press / drag source in [state]'s hit registry. */
fun Modifier.dragSource(state: LauncherState, layer: Layer, source: DragSource, token: Any): Modifier =
    onGloballyPositioned { state.registerSource(token, layer, source, it.boundsInRoot()) }
