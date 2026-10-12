package com.maxbl4.maxlauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppShortcut

/** App icon with label; long press opens the app menu (shortcuts, app info, uninstall). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppIcon(
    entry: AppEntry,
    actions: AppActions,
    labelColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = ICON_SIZE,
) {
    val haptics = LocalHapticFeedback.current
    // Read only from callbacks, so updating it on layout does not recompose.
    var iconBounds by remember { mutableStateOf(Rect.Zero) }
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = { actions.launch(entry, iconBounds) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            Image(
                bitmap = entry.icon,
                contentDescription = null,
                modifier = Modifier
                    .size(iconSize)
                    .onGloballyPositioned { iconBounds = it.boundsInRoot() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.label,
                color = labelColor,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (menuOpen) {
            AppMenu(entry, actions, iconBounds, onDismiss = { menuOpen = false })
        }
    }
}

@Composable
private fun AppMenu(entry: AppEntry, actions: AppActions, iconBounds: Rect, onDismiss: () -> Unit) {
    val shortcuts by produceState(emptyList<AppShortcut>(), entry) { value = actions.shortcuts(entry) }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
        shortcuts.forEach { shortcut ->
            DropdownMenuItem(
                text = { Text(shortcut.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = shortcut.icon?.let { icon ->
                    { Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
                },
                onClick = {
                    onDismiss()
                    actions.launchShortcut(shortcut, iconBounds)
                },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_app_info)) },
            onClick = {
                onDismiss()
                actions.openAppInfo(entry, iconBounds)
            },
        )
        if (!entry.isSystem) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_uninstall)) },
                onClick = {
                    onDismiss()
                    actions.uninstall(entry)
                },
            )
        }
    }
}

val ICON_SIZE = 56.dp
