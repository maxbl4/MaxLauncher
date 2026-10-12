package com.maxbl4.maxlauncher.ui

import android.app.ActivityOptions
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppRepository
import com.maxbl4.maxlauncher.apps.AppShortcut
import kotlin.math.roundToInt
import android.graphics.Rect as AndroidRect

/**
 * App actions triggered from the UI. Bounds are the icon's bounds in the Compose root and are
 * used for the "app grows out of its icon" launch animation.
 */
class AppActions(private val repository: AppRepository, private val view: View) {

    fun launch(entry: AppEntry, iconBounds: Rect?) {
        val (screenBounds, options) = animation(iconBounds)
        repository.launch(entry.key, screenBounds, options)
    }

    fun launchShortcut(shortcut: AppShortcut, iconBounds: Rect?) {
        val (screenBounds, options) = animation(iconBounds)
        repository.launchShortcut(shortcut, screenBounds, options)
    }

    fun openAppInfo(entry: AppEntry, iconBounds: Rect?) {
        repository.openAppInfo(entry.key, animation(iconBounds).first)
    }

    fun uninstall(entry: AppEntry) = repository.uninstall(entry.key)

    suspend fun shortcuts(entry: AppEntry): List<AppShortcut> = repository.shortcuts(entry.key)

    private fun animation(bounds: Rect?): Pair<AndroidRect?, Bundle?> {
        if (bounds == null || bounds.isEmpty) return null to null
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val width = bounds.width.roundToInt()
        val height = bounds.height.roundToInt()
        val options = ActivityOptions.makeScaleUpAnimation(view, left, top, width, height).toBundle()
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val screenBounds = AndroidRect(left, top, left + width, top + height).apply { offset(origin[0], origin[1]) }
        return screenBounds to options
    }
}

@Composable
fun rememberAppActions(repository: AppRepository): AppActions {
    val view = LocalView.current
    return remember(repository, view) { AppActions(repository, view) }
}
