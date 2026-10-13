package com.maxbl4.maxlauncher.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxbl4.maxlauncher.LauncherApplication
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppShortcut
import com.maxbl4.maxlauncher.drawer.DrawerLayout
import com.maxbl4.maxlauncher.drawer.buildDrawerEntries
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.Container
import com.maxbl4.maxlauncher.home.removeEmptyPages
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.WidgetItem
import com.maxbl4.maxlauncher.home.drop
import com.maxbl4.maxlauncher.home.move
import com.maxbl4.maxlauncher.home.remove
import com.maxbl4.maxlauncher.home.renameFolder
import com.maxbl4.maxlauncher.home.setFolderApps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun LauncherScreen(
    app: LauncherApplication,
    homePresses: Flow<Unit>,
    onAddWidget: (WidgetOption, pageId: Long, cell: Pair<Int, Int>?) -> Unit,
    onLockScreen: () -> Unit,
) {
    val appRepository = app.appRepository
    val homeRepository = app.homeRepository
    val apps by appRepository.apps.collectAsStateWithLifecycle()
    val workPaused by appRepository.workPaused.collectAsStateWithLifecycle()
    val layout by homeRepository.layout.collectAsStateWithLifecycle()
    val settings by app.settings.value.collectAsStateWithLifecycle()
    val drawerLayout by app.drawer.value.collectAsStateWithLifecycle()

    val appsByRef = remember(apps) { apps.associateBy { it.ref } }
    val shortcutItems = remember(layout) { layout?.shortcutItems().orEmpty() }
    val shortcuts by produceState(emptyMap<Long, AppShortcut>(), shortcutItems) {
        value = appRepository.pinnedShortcuts(shortcutItems)
    }

    val scope = rememberCoroutineScope()
    val state = remember { LauncherState(scope) }
    val actions = rememberAppActions(appRepository)
    val haptics = LocalHapticFeedback.current
    val darkTheme = isSystemInDarkTheme()
    val wallpaperDarkText = rememberWallpaperSupportsDarkText()
    val homeLabels = if (wallpaperDarkText) LabelStyle(Color(0xFF1A1A1A), shadow = false) else LabelStyle(Color.White, shadow = true)
    val currentLayout = layout
    // While an icon is held over a cell for a moment, the others move aside to show where it will go.
    val dragSource = state.drag?.source
    val preview = state.previewHover as? DropTarget.Cell
    val shownLayout = remember(currentLayout, dragSource, preview) {
        if (currentLayout == null || dragSource == null || preview == null) currentLayout
        else currentLayout.withDrop(dragSource, preview)
    }
    // An app dragged in from the drawer gets the next id in the preview; it is drawn by the drag overlay.
    val previewHiddenId = if (dragSource is DragSource.DrawerApp || dragSource is DragSource.DrawerFolderApp) currentLayout?.nextId else null
    // Kept across recompositions: a new instance would recompose every icon.
    val content = remember(state, actions, appsByRef, shortcuts, homeLabels, darkTheme, previewHiddenId) {
        HomeContent(state, actions, appsByRef, shortcuts, homeLabels, darkTheme, app.widgets, previewHiddenId)
    }

    val drawerContent = remember(apps, drawerLayout, settings, workPaused) {
        val hidden = settings.hiddenApps
        val personal = apps.filter { !it.isWork }
        val work = apps.filter { it.isWork && it.ref !in hidden }
        DrawerContent(
            entries = buildDrawerEntries(personal, drawerLayout, hidden, { it.ref }, { it.label }, { it.title }),
            searchable = personal.filter { it.ref !in hidden },
            workApps = work,
            hasWorkProfile = appRepository.hasWorkProfile(),
            workPaused = workPaused,
            onUnpauseWork = appRepository::unpauseWork,
            columns = settings.drawerColumns,
            rows = settings.drawerRows,
        )
    }

    SideEffect {
        state.layoutLocked = settings.lockLayout
        state.doubleTapToLock = settings.doubleTapToLock
        state.onDoubleTap = onLockScreen
        state.onDrop = { source, target, preview ->
            if (source is DragSource.NewWidget && target is DropTarget.Cell && target.container is Container.Page) {
                onAddWidget(source.option, target.container.pageId, target.x to target.y)
            } else {
                applyDrop(app, source, target, preview as? DropTarget.Cell)
            }
        }
    }
    // Pages left empty disappear once editing and dragging are over (the home page stays).
    val idle = !state.editMode && state.drag == null && state.pageDrag == null
    LaunchedEffect(currentLayout, idle) {
        if (idle) homeRepository.update { it.removeEmptyPages() }
    }
    LaunchedEffect(homePresses) { homePresses.collect { state.closeTopmost() } }
    // Coming back to the launcher from an app always lands on the plain home screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.reset() }
    val unlockReveal = rememberUnlockReveal()
    BackHandler(
        enabled = state.menu != null || state.openFolderId != null || state.openDrawerFolderId != null ||
            state.drawer.isOpen || state.editMode || state.resizingWidgetId != null || state.widgetPickerOpen,
    ) {
        state.closeTopmost()
    }
    val overlayOpen = state.drawer.isOpen || state.openFolderId != null || state.widgetPickerOpen
    SystemBarsAppearance(darkIcons = if (overlayOpen) !darkTheme else wallpaperDarkText)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { state.rootSize = it.toSize() }
            .pointerInput(state) { launcherGestures(state, haptics) },
    ) {
        if (settings.dimWallpaperInDark && darkTheme) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = WALLPAPER_DIM)))
        }
        if (shownLayout != null && currentLayout != null) {
            HomeScreen(
                layout = shownLayout,
                baseDockSize = currentLayout.dock.size,
                home = homeRepository,
                content = content,
                modifier = Modifier.graphicsLayer {
                    val p = state.drawer.progress.value
                    val reveal = unlockReveal.value
                    val zoom = 1f + UNLOCK_ZOOM * (1f - reveal)
                    alpha = (1f - p) * (1f - state.homeFolderProgress) * reveal
                    scaleX = (1f - HOME_SHRINK * p) * zoom
                    scaleY = (1f - HOME_SHRINK * p) * zoom
                },
            )
        }
        AppDrawer(drawerContent, content)

        HomeFolder(state, currentLayout, content, app, drawerContent)
        DrawerFolder(state, drawerLayout, content, app, drawerContent)

        if (state.widgetPickerOpen && currentLayout != null) {
            WidgetPicker(
                state = state,
                widgets = app.widgets,
                cell = state.homeCellSize() ?: Size.Zero,
                columns = currentLayout.columns,
                rows = currentLayout.rows,
                dark = darkTheme,
                onPick = { option ->
                    state.widgetPickerOpen = false
                    state.editMode = false
                    onAddWidget(option, state.currentHomePageId ?: currentLayout.homePageId, null)
                },
                onClose = { state.widgetPickerOpen = false },
            )
        }
        state.menu?.let { request ->
            ItemMenu(request, content, homeRepository, app.drawer, onDismiss = { state.menu = null })
        }
        state.drag?.let { drag -> DragOverlay(drag, state, content) }
    }
}

/**
 * Keeps showing the last opened folder while it plays its closing animation after
 * [openId] became null. Returns the id to show, or null when nothing is shown.
 */
@Composable
private fun rememberShownFolder(openId: Long?): Pair<Long?, () -> Unit> {
    var closingId by remember { mutableStateOf<Long?>(null) }
    SideEffect { if (openId != null) closingId = openId }
    return (openId ?: closingId) to { closingId = null }
}

@Composable
private fun HomeFolder(state: LauncherState, layout: HomeLayout?, content: HomeContent, app: LauncherApplication, drawerContent: DrawerContent) {
    val (id, onClosed) = rememberShownFolder(state.openFolderId)
    if (id == null) return
    val folder = layout?.findFolder(id)
    if (folder == null) {
        // The folder disappeared (e.g. its last apps were removed).
        if (layout != null) LaunchedEffect(Unit) { state.openFolderId = null; onClosed() }
        return
    }
    val apps = folder.apps.mapNotNull { item -> content.appsByRef[item.app]?.let { DragSource.FolderEntry(folder.id, item) to it } }
    FolderPanel(
        folderId = folder.id,
        title = folder.title,
        apps = apps,
        pickerApps = drawerContent.searchable,
        content = content,
        visible = state.openFolderId == folder.id,
        onRename = { title -> app.homeRepository.update { it.renameFolder(folder.id, title) } },
        onPicked = { refs -> app.homeRepository.update { it.setFolderApps(folder.id, refs) } },
        onDismiss = { state.openFolderId = null },
        onClosed = onClosed,
        onProgress = { state.homeFolderProgress = it },
    )
}

@Composable
private fun DrawerFolder(state: LauncherState, layout: DrawerLayout, content: HomeContent, app: LauncherApplication, drawerContent: DrawerContent) {
    val (id, onClosed) = rememberShownFolder(state.openDrawerFolderId)
    if (id == null) return
    val folder = layout.folder(id)
    if (folder == null) {
        LaunchedEffect(Unit) { state.openDrawerFolderId = null; onClosed() }
        return
    }
    val visible = drawerContent.searchable.associateBy { it.ref }
    val apps = folder.apps.mapNotNull { ref -> visible[ref]?.let { DragSource.DrawerFolderApp(folder.id, it) to it } }
    FolderPanel(
        folderId = folder.id,
        title = folder.title,
        apps = apps,
        pickerApps = drawerContent.searchable,
        content = content,
        visible = state.openDrawerFolderId == folder.id,
        onRename = { title -> app.drawer.update { it.rename(folder.id, title) } },
        onPicked = { refs -> app.drawer.update { it.setApps(folder.id, refs) } },
        onDismiss = { state.openDrawerFolderId = null },
        onClosed = onClosed,
        onProgress = { state.drawerFolderProgress = it },
    )
}

/** Applies a finished drag: drawer folder edits or home layout edits. */
private fun applyDrop(app: LauncherApplication, source: DragSource, target: DropTarget, preview: DropTarget.Cell?) {
    val ref = source.appRef
    when (target) {
        is DropTarget.DrawerOnApp -> if (ref != null) app.drawer.update { it.merge(ref, target.entry.ref) }
        is DropTarget.DrawerOnFolder -> if (ref != null) app.drawer.update { it.addToFolder(target.folderId, ref) }
        DropTarget.DrawerEmpty -> if (source is DragSource.DrawerFolderApp) app.drawer.update { it.removeApp(source.entry.ref) }
        DropTarget.Remove -> when (source) {
            is DragSource.HomeEntry -> app.homeRepository.update { it.remove(source.item.id) }
            is DragSource.FolderEntry -> app.homeRepository.update { it.remove(source.item.id) }
            else -> Unit
        }
        is DropTarget.Cell -> app.homeRepository.update { it.withDrop(source, target, preview) }
    }
}

private val DragSource.appRef: AppRef?
    get() = when (this) {
        is DragSource.DrawerApp -> entry.ref
        is DragSource.DrawerFolderApp -> entry.ref
        else -> null
    }

/**
 * The drop onto [target] with the icons pushed aside by [preview] kept where they are shown: a
 * merge goes into the icon under the finger at its shown spot.
 */
private fun HomeLayout.withDrop(source: DragSource, target: DropTarget.Cell, preview: DropTarget.Cell?): HomeLayout {
    val targetId = target.targetId
    if (!target.merge || targetId == null || preview == null || preview.merge || preview.container != target.container) {
        return withDrop(source, target)
    }
    val movedId = when (source) {
        is DragSource.HomeEntry -> source.item.id
        is DragSource.FolderEntry -> source.item.id
        is DragSource.DrawerApp, is DragSource.DrawerFolderApp -> nextId
        else -> return withDrop(source, target)
    }
    val shown = withDrop(source, preview)
    val (container, placed) = shown.findPlaced(targetId) ?: return withDrop(source, target)
    return shown.move(movedId, container, placed.x, placed.y, merge = true)
}

/** The home layout after dropping [source] onto [target]; also used to preview the drop. */
private fun HomeLayout.withDrop(source: DragSource, cell: DropTarget.Cell): HomeLayout {
    // A merge goes into the item under the finger, wherever it is now.
    val target = cell.targetId?.let { id -> findPlaced(id)?.let { (container, placed) -> cell.copy(container = container, x = placed.x, y = placed.y) } } ?: cell
    return withDropAt(source, target)
}

private fun HomeLayout.withDropAt(source: DragSource, target: DropTarget.Cell): HomeLayout = when (source) {
    is DragSource.DrawerApp, is DragSource.DrawerFolderApp -> {
        val (id, next) = allocateId()
        next.drop(AppItem(id, source.appRef!!), target.container, target.x, target.y, merge = target.merge) ?: this
    }
    is DragSource.HomeEntry -> move(source.item.id, target.container, target.x, target.y, target.merge)
    is DragSource.FolderEntry -> move(source.item.id, target.container, target.x, target.y, target.merge)
    is DragSource.DrawerFolderEntry, is DragSource.NewWidget -> this
}

private fun HomeLayout.shortcutItems(): List<ShortcutItem> =
    (pages.flatMap { it.items } + dock).mapNotNull { it.item as? ShortcutItem }

/** The item following the finger during a drag. */
@Composable
private fun DragOverlay(drag: DragState, state: LauncherState, content: HomeContent) {
    val density = LocalDensity.current
    Box(
        Modifier
            // Read while placing, so following the finger does not recompose anything.
            .offset {
                val topLeft = state.dragPosition - drag.grabOffset
                IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt())
            }
            .size(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
            .graphicsLayer {
                scaleX = DRAG_SCALE
                scaleY = DRAG_SCALE
            },
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier = Modifier.size(ICON_SIZE)
        fun icon(entry: AppEntry?): @Composable () -> Unit = { entry?.let { Image(it.icon, null, iconModifier) } }
        when (val source = drag.source) {
            is DragSource.DrawerApp -> icon(source.entry)()
            is DragSource.DrawerFolderApp -> icon(source.entry)()
            is DragSource.DrawerFolderEntry -> FolderIcon(source.folder.apps.mapNotNull { content.appsByRef[it]?.icon }, iconModifier, drawerFolderBackground(content.darkTheme))
            is DragSource.NewWidget -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(16.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                source.option.preview?.let { Image(it, contentDescription = null) }
            }
            is DragSource.FolderEntry -> icon(content.appsByRef[source.item.app])()
            is DragSource.HomeEntry -> when (val item = source.item) {
                is AppItem -> icon(content.appsByRef[item.app])()
                is FolderItem -> FolderIcon(folderIcons(item, content.appsByRef), iconModifier, homeFolderBackground(content.darkTheme))
                is ShortcutItem -> content.shortcuts[item.id]?.icon?.let { Image(it, null, iconModifier) }
                is WidgetItem -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}

/** Dark status/navigation bar icons over a light wallpaper or the light drawer. */
@Composable
private fun SystemBarsAppearance(darkIcons: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

/**
 * How far home is shown after the phone was unlocked (0 hidden .. 1 shown). One UI zooms home in
 * over the wallpaper when coming back from an app, but nothing animates it after unlocking; this
 * does the same then. Only a stop with the screen off hides home: coming back from apps keeps the
 * system animation.
 */
@Composable
private fun rememberUnlockReveal(): Animatable<Float, AnimationVector1D> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reveal = remember { Animatable(1f) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) scope.launch { reveal.snapTo(0f) }
    }
    // Started is too early: the launcher starts behind the lock screen as the screen wakes. It gets
    // the focus once the lock screen is gone.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused && reveal.value < 1f) reveal.animateTo(1f, tween(UNLOCK_REVEAL_MS, easing = LinearOutSlowInEasing))
        }
    }
    return reveal
}

@Composable
private fun rememberWallpaperSupportsDarkText(): Boolean {
    val context = LocalContext.current
    var supportsDarkText by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val manager = WallpaperManager.getInstance(context)
        fun update(colors: WallpaperColors?) {
            supportsDarkText = colors != null && colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        }
        update(manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM))
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) update(colors)
        }
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.removeOnColorsChangedListener(listener) }
    }
    return supportsDarkText
}

private const val HOME_SHRINK = 0.06f
/** After unlocking, home zooms in from this much larger (as One UI does coming back from an app). */
private const val UNLOCK_ZOOM = 0.2f
private const val UNLOCK_REVEAL_MS = 340
private const val DRAG_SCALE = 1.1f
private const val WALLPAPER_DIM = 0.35f
