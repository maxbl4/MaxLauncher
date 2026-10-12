package com.maxbl4.maxlauncher.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxbl4.maxlauncher.apps.AppRepository
import com.maxbl4.maxlauncher.apps.AppShortcut
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeLayout
import com.maxbl4.maxlauncher.home.HomeRepository
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.drop
import com.maxbl4.maxlauncher.home.move
import com.maxbl4.maxlauncher.home.remove
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

@Composable
fun LauncherScreen(appRepository: AppRepository, homeRepository: HomeRepository, homePresses: Flow<Unit>) {
    val apps by appRepository.apps.collectAsStateWithLifecycle()
    val layout by homeRepository.layout.collectAsStateWithLifecycle()
    // Work profile apps will get their own tab (docs/FEATURES.md, item 24).
    val personalApps = remember(apps) { apps.filter { !it.isWork } }
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
    val content = HomeContent(state, actions, appsByRef, shortcuts, homeLabels, darkTheme)

    SideEffect {
        state.onDrop = { source, target -> homeRepository.update { it.applyDrop(source, target) } }
    }
    LaunchedEffect(homePresses) { homePresses.collect { state.closeTopmost() } }
    // Coming back to the launcher from an app always lands on the plain home screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.reset() }
    BackHandler(enabled = state.menu != null || state.openFolderId != null || state.drawer.isOpen || state.editMode) {
        state.closeTopmost()
    }
    SystemBarsAppearance(darkIcons = if (state.drawer.isOpen || state.openFolderId != null) !darkTheme else wallpaperDarkText)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { state.rootSize = it.toSize() }
            .pointerInput(state) { launcherGestures(state, haptics) },
    ) {
        val currentLayout = layout
        if (currentLayout != null) {
            HomeScreen(
                layout = currentLayout,
                home = homeRepository,
                content = content,
                modifier = Modifier.graphicsLayer {
                    val p = state.drawer.progress.value
                    alpha = 1f - p
                    scaleX = 1f - HOME_SHRINK * p
                    scaleY = 1f - HOME_SHRINK * p
                },
            )
        }
        if (state.drawer.isVisible) {
            AppDrawer(personalApps, state, actions)
        }
        val folder = state.openFolderId?.let { currentLayout?.findFolder(it) }
        if (folder != null) {
            FolderOverlay(folder, personalApps, content, homeRepository)
        } else if (state.openFolderId != null && currentLayout != null) {
            // The folder disappeared (e.g. its last apps were removed).
            LaunchedEffect(Unit) { state.openFolderId = null }
        }
        state.menu?.let { request ->
            ItemMenu(request, actions, homeRepository, appsByRef, onDismiss = { state.menu = null })
        }
        state.drag?.let { drag -> DragOverlay(drag, content) }
    }
}

/** Applies a finished drag to the layout. */
private fun HomeLayout.applyDrop(source: DragSource, target: DropTarget): HomeLayout = when (target) {
    DropTarget.Remove -> when (source) {
        is DragSource.HomeEntry -> remove(source.item.id)
        is DragSource.FolderEntry -> remove(source.item.id)
        is DragSource.DrawerApp -> this
    }
    is DropTarget.Cell -> when (source) {
        is DragSource.DrawerApp -> {
            val (id, layout) = allocateId()
            layout.drop(AppItem(id, source.entry.ref), target.container, target.x, target.y) ?: this
        }
        is DragSource.HomeEntry -> move(source.item.id, target.container, target.x, target.y)
        is DragSource.FolderEntry -> move(source.item.id, target.container, target.x, target.y)
    }
}

private fun HomeLayout.shortcutItems(): List<ShortcutItem> =
    (pages.flatMap { it.items } + dock).mapNotNull { it.item as? ShortcutItem }

/** The icon following the finger during a drag. */
@Composable
private fun DragOverlay(drag: DragState, content: HomeContent) {
    val density = LocalDensity.current
    val topLeft = drag.position - drag.grabOffset
    Box(
        Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(with(density) { drag.size.width.toDp() }, with(density) { drag.size.height.toDp() })
            .graphicsLayer {
                scaleX = DRAG_SCALE
                scaleY = DRAG_SCALE
            },
        contentAlignment = Alignment.Center,
    ) {
        val iconModifier = Modifier.size(ICON_SIZE)
        when (val source = drag.source) {
            is DragSource.DrawerApp -> Image(source.entry.icon, null, iconModifier)
            is DragSource.FolderEntry -> content.appsByRef[source.item.app]?.let { Image(it.icon, null, iconModifier) }
            is DragSource.HomeEntry -> when (val item = source.item) {
                is AppItem -> content.appsByRef[item.app]?.let { Image(it.icon, null, iconModifier) }
                is FolderItem -> FolderIcon(folderIcons(item, content.appsByRef), iconModifier, content.darkTheme)
                is ShortcutItem -> content.shortcuts[item.id]?.icon?.let { Image(it, null, iconModifier) }
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
private const val DRAG_SCALE = 1.1f
