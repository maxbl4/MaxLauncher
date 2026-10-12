package com.maxbl4.maxlauncher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Follows the system light/dark mode and the wallpaper-based dynamic colors. */
@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Space taken by the status and navigation bars, also while they are hidden. The lock screen
 * hides the navigation bar; following that would lay out and redraw everything on unlock.
 */
@OptIn(ExperimentalLayoutApi::class)
val WindowInsets.Companion.launcherBars: WindowInsets
    @Composable get() = systemBarsIgnoringVisibility
