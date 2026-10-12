package com.maxbl4.maxlauncher.settings

import com.maxbl4.maxlauncher.home.AppRef
import kotlinx.serialization.Serializable

@Serializable
data class LauncherSettings(
    val drawerColumns: Int = 5,
    val drawerRows: Int = 6,
    /** Home items cannot be moved or removed (docs/FEATURES.md, item 9). */
    val lockLayout: Boolean = false,
    /** Apps left out of the app drawer and its search (item 21). */
    val hiddenApps: Set<AppRef> = emptySet(),
    /** Double tap on an empty spot of the home screen turns the screen off (item 45). */
    val doubleTapToLock: Boolean = false,
    /** Darken the wallpaper while the system is in dark mode (item 48). */
    val dimWallpaperInDark: Boolean = false,
)

/** Grid sizes offered in the settings: columns × rows. */
val HOME_GRIDS = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6)
val DRAWER_GRIDS = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6, 5 to 7)
