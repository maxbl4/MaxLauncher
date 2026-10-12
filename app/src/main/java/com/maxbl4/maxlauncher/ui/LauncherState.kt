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
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.Container
import com.maxbl4.maxlauncher.home.HomeItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which surface is on top and receives long presses. */
enum class Layer { HOME, DRAWER, FOLDER }

/** Something that can be long-pressed (menu) and dragged. */
sealed interface DragSource {
    val key: String

    data class DrawerApp(val entry: AppEntry) : DragSource {
        override val key = "drawer:${entry.key.id}"
    }

    /** A top-level item on a home page or in the dock. */
    data class HomeEntry(val item: HomeItem) : DragSource {
        override val key = "home:${item.id}"
    }

    data class FolderEntry(val folderId: Long, val item: AppItem) : DragSource {
        override val key = "folder:${item.id}"
    }
}

sealed interface DropTarget {
    data class Cell(val container: Container, val x: Int, val y: Int) : DropTarget
    data object Remove : DropTarget
}

data class MenuRequest(val source: DragSource, val bounds: Rect)

data class DragState(val source: DragSource, val position: Offset, val grabOffset: Offset, val size: Size)

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
    var menu by mutableStateOf<MenuRequest?>(null)
    var drag by mutableStateOf<DragState?>(null)
        private set
    var hover by mutableStateOf<DropTarget?>(null)
        private set

    /** Set by the screen: applies a finished drag. */
    var onDrop: (DragSource, DropTarget) -> Unit = { _, _ -> }

    /** Set by the home screen: scrolls the pages by +1/-1 while an item is held at an edge. */
    var onEdgeScroll: suspend (Int) -> Unit = {}

    /** Set by the screen: called once a drag ends, to clean up pages added while dragging. */
    var onDragFinished: () -> Unit = {}

    var rootSize = Size.Zero

    val activeLayer: Layer
        get() = when {
            openFolderId != null -> Layer.FOLDER
            drawer.isVisible -> Layer.DRAWER
            else -> Layer.HOME
        }

    /** Drawer swipes only make sense on the plain home screen or in the drawer. */
    val canSwipeDrawer: Boolean
        get() = !editMode && openFolderId == null && drag == null && drawer.swipesEnabled

    // --- Hit registry (plain maps: written on layout, read by gestures; no recomposition) ---

    private class Registered(val layer: Layer, val source: DragSource, var bounds: Rect)
    private class GridArea(val container: Container, val columns: Int, val rows: Int, var bounds: Rect)

    private val sources = HashMap<Any, Registered>()
    private val grids = HashMap<Container, GridArea>()
    private var removeZone: Rect? = null

    fun registerSource(token: Any, layer: Layer, source: DragSource, bounds: Rect) {
        sources[token]?.takeIf { it.source == source && it.layer == layer }?.let { it.bounds = bounds; return }
        sources[token] = Registered(layer, source, bounds)
    }

    fun unregisterSource(token: Any) {
        sources.remove(token)
    }

    fun registerGrid(container: Container, columns: Int, rows: Int, bounds: Rect) {
        grids[container] = GridArea(container, columns, rows, bounds)
    }

    fun unregisterGrid(container: Container) {
        grids.remove(container)
    }

    fun setRemoveZone(bounds: Rect?) {
        removeZone = bounds
    }

    fun hitTest(position: Offset): Pair<DragSource, Rect>? {
        val layer = activeLayer
        return sources.values
            .filter { it.layer == layer && it.bounds.contains(position) }
            .minByOrNull { it.bounds.width * it.bounds.height }
            ?.let { it.source to it.bounds }
    }

    /** True when a long press at [position] landed on the home screen background. */
    fun isHomeBackground(position: Offset): Boolean =
        activeLayer == Layer.HOME && grids.values.any { it.container is Container.Page && it.bounds.contains(position) }

    private fun dropTargetAt(position: Offset, source: DragSource): DropTarget? {
        if (source !is DragSource.DrawerApp && removeZone?.contains(position) == true) return DropTarget.Remove
        val area = grids.values.firstOrNull { it.bounds.contains(position) } ?: return null
        val cellWidth = area.bounds.width / area.columns
        val cellHeight = area.bounds.height / area.rows
        val x = ((position.x - area.bounds.left) / cellWidth).toInt().coerceIn(0, area.columns - 1)
        val y = ((position.y - area.bounds.top) / cellHeight).toInt().coerceIn(0, area.rows - 1)
        return DropTarget.Cell(area.container, x, y)
    }

    // --- Long press, drag and drop ---

    fun showMenu(source: DragSource, bounds: Rect) {
        menu = MenuRequest(source, bounds)
    }

    fun startDrag(source: DragSource, position: Offset, bounds: Rect) {
        menu = null
        when (source) {
            is DragSource.DrawerApp -> drawer.close()
            is DragSource.FolderEntry -> openFolderId = null
            is DragSource.HomeEntry -> Unit
        }
        drag = DragState(source, position, position - bounds.topLeft, bounds.size)
        hover = dropTargetAt(position, source)
    }

    private var edgeJob: Job? = null

    fun dragTo(position: Offset) {
        val current = drag ?: return
        drag = current.copy(position = position)
        hover = dropTargetAt(position, current.source)

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
                    onEdgeScroll(direction)
                    delay(EDGE_REPEAT_MS)
                }
            }
        }
    }

    fun endDrag(position: Offset) {
        val current = drag ?: return
        edgeJob?.cancel()
        edgeJob = null
        val target = dropTargetAt(position, current.source)
        drag = null
        hover = null
        if (target != null) onDrop(current.source, target)
        onDragFinished()
    }

    fun cancelDrag() {
        edgeJob?.cancel()
        edgeJob = null
        drag = null
        hover = null
        onDragFinished()
    }

    fun isDragged(source: DragSource): Boolean = drag?.source?.key == source.key

    /** Back / Home: close the topmost thing. Returns false when there was nothing to close. */
    fun closeTopmost(): Boolean = when {
        menu != null -> { menu = null; true }
        openFolderId != null -> { openFolderId = null; true }
        drawer.isOpen -> { drawer.close(); true }
        editMode -> { editMode = false; true }
        else -> false
    }

    /** The launcher went to the background: reset to the plain home screen. */
    fun reset() {
        menu = null
        openFolderId = null
        editMode = false
        if (drag != null) cancelDrag()
        drawer.snapClosed()
    }

    private companion object {
        const val EDGE_FRACTION = 0.06f
        const val EDGE_DELAY_MS = 450L
        const val EDGE_REPEAT_MS = 900L
    }
}

/** Registers this element as a long-press / drag source in [state]'s hit registry. */
fun Modifier.dragSource(state: LauncherState, layer: Layer, source: DragSource, token: Any): Modifier =
    onGloballyPositioned { state.registerSource(token, layer, source, it.boundsInRoot()) }
