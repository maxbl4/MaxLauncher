package com.maxbl4.maxlauncher.ui

import android.app.WallpaperManager
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.SettingsActivity
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppShortcut
import com.maxbl4.maxlauncher.apps.AppSorting
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.Container
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeItem
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.HomePage
import com.maxbl4.maxlauncher.home.HomeRepository
import com.maxbl4.maxlauncher.home.Placed
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.WidgetItem
import com.maxbl4.maxlauncher.home.addPage
import com.maxbl4.maxlauncher.home.movePage
import com.maxbl4.maxlauncher.home.removePage
import com.maxbl4.maxlauncher.home.resizeWidget
import com.maxbl4.maxlauncher.home.setHomePage
import com.maxbl4.maxlauncher.widgets.WidgetHostManager
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** Everything needed to draw home items. */
class HomeContent(
    val state: LauncherState,
    val actions: AppActions,
    val appsByRef: Map<AppRef, AppEntry>,
    val shortcuts: Map<Long, AppShortcut>,
    val labelStyle: LabelStyle,
    val darkTheme: Boolean,
    val widgets: WidgetHostManager,
)

/**
 * Home screen: pages scrolled horizontally, page indicator (the home page is a house) and the
 * dock. In edit mode pages shrink and get controls: make home, delete, add page left/right,
 * long press and drag to reorder; the dock is replaced by the edit bar.
 */
@Composable
fun HomeScreen(layout: HomeLayout, home: HomeRepository, content: HomeContent, modifier: Modifier = Modifier) {
    val state = content.state
    val pagerState = rememberPagerState(initialPage = layout.homePageIndex) { layout.pages.size }
    val currentLayout by rememberUpdatedState(layout)
    val editProgress by animateFloatAsState(if (state.editMode) 1f else 0f, label = "edit")
    // Pages added by dragging an item past the last page; removed again if left empty.
    val dragPages = remember { mutableSetOf<Long>() }
    var focusPageId by remember { mutableStateOf<Long?>(null) }

    SideEffect {
        state.currentHomePageId = layout.pages.getOrNull(pagerState.currentPage)?.id
        state.onEdgeScroll = edge@{ direction, mayAddPage ->
            val target = pagerState.currentPage + direction
            if (target < 0) return@edge
            if (target >= currentLayout.pages.size) {
                // Past the last page: add a new one, unless the last page is still empty.
                if (!mayAddPage || currentLayout.pages.last().items.isEmpty()) return@edge
                home.update { l -> l.addPage(l.pages.size).also { dragPages += it.first }.second }
                snapshotFlow { pagerState.pageCount }.first { it > target }
            }
            pagerState.animateScrollToPage(target)
        }
        state.onDragFinished = {
            val ids = dragPages.toSet()
            dragPages.clear()
            home.update { l ->
                ids.fold(l) { acc, id -> if (acc.pages.firstOrNull { it.id == id }?.items?.isEmpty() == true) acc.removePage(id) else acc }
            }
        }
        state.onPageDrop = { pageId ->
            home.update { l -> l.movePage(l.pages.indexOfFirst { it.id == pageId }, pagerState.currentPage) }
            focusPageId = pageId
        }
    }
    // After a page moved, the pager follows the page that was shown; switch to the moved one.
    LaunchedEffect(layout.pages, focusPageId) {
        val index = layout.pages.indexOfFirst { it.id == focusPageId }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            focusPageId = null
        }
    }
    WallpaperParallax(pagerState.let { { it.currentPage + it.currentPageOffsetFraction } }, layout.pages.size)

    var pageToDelete by remember { mutableStateOf<HomePage?>(null) }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state = pagerState,
                key = { layout.pages.getOrNull(it)?.id ?: it },
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val page = layout.pages.getOrNull(index) ?: return@HorizontalPager
                PageView(
                    page = page,
                    layout = layout,
                    home = home,
                    content = content,
                    editProgress = editProgress,
                    isFirst = index == 0,
                    isLast = index == layout.pages.lastIndex,
                    onSetHome = { home.update { it.setHomePage(page.id) } },
                    onDelete = {
                        if (page.items.isEmpty()) home.update { it.removePage(page.id) } else pageToDelete = page
                    },
                    onAddLeft = { home.update { it.addPage(0).second } },
                    onAddRight = { home.update { l -> l.addPage(l.pages.size).second } },
                )
            }
            val dragSource = state.drag?.source
            if (!state.dragInDrawer && (dragSource is DragSource.HomeEntry || dragSource is DragSource.FolderEntry)) {
                RemoveZone(state, Modifier.align(Alignment.TopCenter))
            }
        }
        PageIndicator(layout, pagerState.currentPage, content.labelStyle.color, visible = layout.pages.size > 1 || state.editMode)
        if (state.editMode) {
            EditBar(state, content.labelStyle.color)
        } else {
            ItemGrid(
                container = Container.Dock,
                items = layout.dock,
                columns = layout.dockSlots,
                rows = 1,
                home = home,
                content = content,
                modifier = Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 8.dp),
            )
        }
    }

    state.pageDrag?.let { drag -> PageDragOverlay(drag.position, content.labelStyle.color) }

    pageToDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = { Text(stringResource(R.string.delete_page_title)) },
            text = { Text(stringResource(R.string.delete_page_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pageToDelete = null
                    home.update { it.removePage(page.id) }
                }) { Text(stringResource(R.string.delete_page_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pageToDelete = null }) { Text(stringResource(R.string.picker_cancel)) }
            },
        )
    }
}

/** Moves the wallpaper with the pages, if the wallpaper is wider than the screen. */
@Composable
private fun WallpaperParallax(position: () -> Float, pageCount: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(pageCount) {
        val manager = WallpaperManager.getInstance(context)
        val step = if (pageCount > 1) 1f / (pageCount - 1) else 0f
        manager.setWallpaperOffsetSteps(step, 1f)
        snapshotFlow(position).collect { pos ->
            val token = view.windowToken ?: return@collect
            val x = if (pageCount > 1) (pos / (pageCount - 1)).coerceIn(0f, 1f) else 0.5f
            runCatching { manager.setWallpaperOffsets(token, x, 0.5f) }
        }
    }
}

@Composable
private fun PageView(
    page: HomePage,
    layout: HomeLayout,
    home: HomeRepository,
    content: HomeContent,
    editProgress: Float,
    isFirst: Boolean,
    isLast: Boolean,
    onSetHome: () -> Unit,
    onDelete: () -> Unit,
    onAddLeft: () -> Unit,
    onAddRight: () -> Unit,
) {
    val state = content.state
    val color = content.labelStyle.color
    val dragged = state.pageDrag?.pageId == page.id
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (dragged) 0.35f else 1f)
            .then(
                if (state.editMode) {
                    Modifier.pointerInput(Unit) { detectTapGestures { state.editMode = false } }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - EDIT_SHRINK * editProgress
                    scaleX = scale
                    scaleY = scale
                }
                .then(
                    if (editProgress > 0f) {
                        Modifier
                            .border(1.5.dp, color.copy(alpha = 0.5f * editProgress), RoundedCornerShape(24.dp))
                            .background(color.copy(alpha = 0.08f * editProgress), RoundedCornerShape(24.dp))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            ItemGrid(Container.Page(page.id), page.items, layout.columns, layout.rows, home, content, Modifier.fillMaxSize())
        }

        if (state.editMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.align(Alignment.TopCenter).alpha(editProgress),
            ) {
                val isHome = layout.homePageId == page.id
                RoundIconButton(
                    icon = if (isHome) R.drawable.ic_home else R.drawable.ic_home_outline,
                    description = stringResource(R.string.edit_home_page),
                    color = color,
                    onClick = onSetHome,
                )
                if (layout.pages.size > 1) {
                    RoundIconButton(R.drawable.ic_delete, stringResource(R.string.edit_delete_page), color, onDelete)
                }
            }
            if (isFirst) {
                RoundIconButton(
                    R.drawable.ic_add, stringResource(R.string.edit_add_page_left), color, onAddLeft,
                    Modifier.align(Alignment.CenterStart).alpha(editProgress),
                )
            }
            if (isLast) {
                RoundIconButton(
                    R.drawable.ic_add, stringResource(R.string.edit_add_page_right), color, onAddRight,
                    Modifier.align(Alignment.CenterEnd).alpha(editProgress),
                )
            }
        }
    }
}

/**
 * A grid of home items (a page or the dock). Registers its bounds so drops can be mapped to
 * cells, and highlights the cells under a dragged item.
 */
@Composable
private fun ItemGrid(
    container: Container,
    items: List<Placed>,
    columns: Int,
    rows: Int,
    home: HomeRepository,
    content: HomeContent,
    modifier: Modifier = Modifier,
) {
    val state = content.state
    DisposableEffect(container) { onDispose { state.unregisterGrid(container) } }
    BoxWithConstraints(
        modifier
            .onGloballyPositioned { state.registerGrid(container, columns, rows, it.boundsInRoot(), it.size.toSize()) }
            .then(
                if (state.resizingWidgetId != null) {
                    Modifier.pointerInput(Unit) { detectTapGestures { state.resizingWidgetId = null } }
                } else {
                    Modifier
                },
            ),
    ) {
        val cellWidth = maxWidth / columns
        val cellHeight = maxHeight / rows
        val hover = state.hover as? DropTarget.Cell
        if (hover != null && hover.container == container) {
            val draggedPlaced = (state.drag?.source as? DragSource.HomeEntry)?.item?.let { item -> items.firstOrNull { it.item.id == item.id } }
            val spanX = if (draggedPlaced?.item is WidgetItem) draggedPlaced.spanX else 1
            val spanY = if (draggedPlaced?.item is WidgetItem) draggedPlaced.spanY else 1
            val x = hover.x.coerceAtMost(columns - spanX)
            val y = hover.y.coerceAtMost(rows - spanY)
            Box(
                Modifier
                    .offset(cellWidth * x, cellHeight * y)
                    .size(cellWidth * spanX, cellHeight * spanY)
                    .padding(4.dp)
                    .background(content.labelStyle.color.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
            )
        }
        for (placed in items) {
            key(placed.item.id) {
                Box(
                    Modifier
                        .offset(cellWidth * placed.x, cellHeight * placed.y)
                        .size(cellWidth * placed.spanX, cellHeight * placed.spanY),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeItemView(placed.item, content)
                }
            }
        }
        val resizing = items.firstOrNull { it.item.id == state.resizingWidgetId }
        if (resizing != null) {
            WidgetResizeFrame(resizing, columns, rows, cellWidth, cellHeight, content, onResize = { x, y, w, h ->
                home.update { it.resizeWidget(resizing.item.id, x, y, w, h) }
            })
        }
    }
}

@Composable
private fun HomeItemView(item: HomeItem, content: HomeContent) {
    val state = content.state
    val source = DragSource.HomeEntry(item)
    val modifier = Modifier
        .registerSource(state, Layer.HOME, source)
        .alpha(if (state.isDragged(source)) 0f else 1f)
    when (item) {
        is AppItem -> {
            val entry = content.appsByRef[item.app] ?: return
            AppIcon(entry, content.actions, content.labelStyle, modifier)
        }
        is FolderItem -> {
            val icons = remember(item, content.appsByRef) { folderIcons(item, content.appsByRef) }
            LauncherIcon(item.title, content.labelStyle, onClick = { bounds -> state.folderOrigin = bounds.center; state.openFolderId = item.id }, modifier = modifier) {
                FolderIcon(icons, it, homeFolderBackground(content.darkTheme))
            }
        }
        is ShortcutItem -> {
            val shortcut = content.shortcuts[item.id]
            LauncherIcon(
                label = shortcut?.label ?: item.label,
                labelStyle = content.labelStyle,
                onClick = { bounds -> shortcut?.let { content.actions.launchShortcut(it, bounds) } },
                modifier = modifier,
            ) { iconModifier ->
                val icon = shortcut?.icon
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = iconModifier)
                } else {
                    Box(iconModifier.clip(CircleShape).background(Color(0x55888888)))
                }
            }
        }
        is WidgetItem -> WidgetView(item, content.widgets, modifier.fillMaxSize().padding(4.dp))
    }
}

/** Icons of the apps in a folder, in alphabetical order. */
fun folderIcons(folder: FolderItem, appsByRef: Map<AppRef, AppEntry>) =
    folder.apps.mapNotNull { appsByRef[it.app] }.sortedWith(AppSorting.comparator { it.label }).map { it.icon }

@Composable
private fun RemoveZone(state: LauncherState, modifier: Modifier) {
    DisposableEffect(Unit) { onDispose { state.setRemoveZone(null) } }
    val active = state.hover == DropTarget.Remove
    Box(
        modifier
            .onGloballyPositioned { state.setRemoveZone(it.boundsInRoot()) }
            .clip(RoundedCornerShape(24.dp))
            .background(if (active) Color(0xE6D32F2F) else Color(0x99000000))
            .padding(horizontal = 28.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drop_remove), color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
private fun PageIndicator(layout: HomeLayout, currentPage: Int, color: Color, visible: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(24.dp),
    ) {
        if (!visible) return@Row
        layout.pages.forEachIndexed { index, page ->
            val tint = color.copy(alpha = if (index == currentPage) 0.95f else 0.4f)
            if (page.id == layout.homePageId) {
                Icon(painterResource(R.drawable.ic_home), contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            }
        }
    }
}

@Composable
private fun EditBar(state: LauncherState, color: Color) {
    val context = LocalContext.current
    val wallpaperTitle = stringResource(R.string.edit_wallpaper)
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().height(104.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditBarButton(R.drawable.ic_wallpaper, wallpaperTitle, color) {
            val intent = Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), wallpaperTitle)
            runCatching { context.startActivity(intent) }
        }
        EditBarButton(R.drawable.ic_widgets, stringResource(R.string.edit_widgets), color) {
            state.widgetPickerOpen = true
        }
        EditBarButton(R.drawable.ic_settings, stringResource(R.string.edit_settings), color) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}

@Composable
private fun EditBarButton(icon: Int, label: String, color: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, fontSize = 13.sp)
    }
}

/** A page outline following the finger while pages are reordered. */
@Composable
private fun PageDragOverlay(position: androidx.compose.ui.geometry.Offset, color: Color) {
    val density = LocalDensity.current
    val width = 110.dp
    val height = 200.dp
    val half = with(density) { androidx.compose.ui.geometry.Offset(width.toPx() / 2, height.toPx() / 2) }
    Box(
        Modifier
            .offset { (position - half).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
            .size(width, height)
            .border(2.dp, color.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun RoundIconButton(icon: Int, description: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = color, modifier = Modifier.size(22.dp))
    }
}

private const val EDIT_SHRINK = 0.16f
