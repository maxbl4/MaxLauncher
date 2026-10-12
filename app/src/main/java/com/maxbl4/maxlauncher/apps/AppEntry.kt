package com.maxbl4.maxlauncher.apps

import android.content.ComponentName
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.maxbl4.maxlauncher.home.AppRef

/** Identifies a launchable activity in a given user profile. */
@Immutable
data class AppKey(val component: ComponentName, val user: UserHandle) {
    val packageName: String get() = component.packageName

    /** Stable string form, usable as a Compose list key or a database id. */
    val id: String = "${component.flattenToShortString()}#${user.hashCode()}"
}

@Immutable
data class AppEntry(
    val key: AppKey,
    /** Persistent reference used by the home screen layout. */
    val ref: AppRef,
    val label: String,
    val icon: ImageBitmap,
    val isSystem: Boolean,
    val isWork: Boolean,
)

data class PackageRemoval(val packageName: String, val userSerial: Long)

/** An app shortcut: shown in the long-press menu, or pinned to the home screen. */
@Immutable
data class AppShortcut(
    val info: ShortcutInfo,
    val label: String,
    val icon: ImageBitmap?,
)
