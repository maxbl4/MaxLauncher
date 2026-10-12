package com.maxbl4.maxlauncher.ui

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxbl4.maxlauncher.apps.AppRepository
import kotlinx.coroutines.flow.Flow

@Composable
fun LauncherScreen(repository: AppRepository, homePresses: Flow<Unit>) {
    val apps by repository.apps.collectAsStateWithLifecycle()
    // Work profile apps will get their own tab (docs/FEATURES.md, item 24).
    val drawerApps = remember(apps) { apps.filter { !it.isWork } }
    val scope = rememberCoroutineScope()
    val drawer = remember { DrawerState(scope) }
    val actions = rememberAppActions(repository)

    LaunchedEffect(homePresses) { homePresses.collect { drawer.close() } }
    // Coming back to the launcher from an app always lands on the home screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { drawer.snapClosed() }
    BackHandler(enabled = drawer.isOpen) { drawer.close() }
    SystemBarsAppearance(drawerOpen = drawer.isOpen)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(drawer) { with(drawer) { handleVerticalSwipes() } },
    ) {
        HomeScreen(
            Modifier.graphicsLayer {
                val p = drawer.progress.value
                alpha = 1f - p
                scaleX = 1f - HOME_SHRINK * p
                scaleY = 1f - HOME_SHRINK * p
            },
        )
        if (drawer.isVisible) {
            AppDrawer(drawerApps, drawer, actions)
        }
    }
}

/** Home screen pages. Placeholder until pages, folders and the dock are implemented. */
@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize())
}

/** Dark status/navigation bar icons over a light wallpaper or the light drawer. */
@Composable
private fun SystemBarsAppearance(drawerOpen: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    val wallpaperDarkText = rememberWallpaperSupportsDarkText()
    val darkIcons = if (drawerOpen) !darkTheme else wallpaperDarkText
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
