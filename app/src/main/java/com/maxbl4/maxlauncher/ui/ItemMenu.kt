package com.maxbl4.maxlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppShortcut
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeRepository
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.addApp
import com.maxbl4.maxlauncher.home.dissolveFolder
import com.maxbl4.maxlauncher.home.remove
import kotlin.math.roundToInt

/** Long-press menu, anchored to the pressed item. Its entries depend on what was pressed. */
@Composable
fun ItemMenu(
    request: MenuRequest,
    actions: AppActions,
    home: HomeRepository,
    appsByRef: Map<AppRef, AppEntry>,
    onDismiss: () -> Unit,
) {
    val bounds = request.bounds
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
    ) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
            when (val source = request.source) {
                is DragSource.DrawerApp -> AppEntries(
                    entry = source.entry,
                    actions = actions,
                    onDismiss = onDismiss,
                    extra = {
                        MenuItem(stringResource(R.string.menu_add_to_home)) {
                            onDismiss()
                            home.update { it.addApp(source.entry.ref) }
                        }
                    },
                )
                is DragSource.HomeEntry -> when (val homeItem = source.item) {
                    is AppItem -> {
                        val entry = appsByRef[homeItem.app]
                        if (entry != null) {
                            AppEntries(entry, actions, onDismiss, extra = {
                                MenuItem(stringResource(R.string.menu_remove_from_home)) {
                                    onDismiss()
                                    home.update { it.remove(homeItem.id) }
                                }
                            })
                        } else {
                            MenuItem(stringResource(R.string.menu_remove_from_home)) {
                                onDismiss()
                                home.update { it.remove(homeItem.id) }
                            }
                        }
                    }
                    is FolderItem -> MenuItem(stringResource(R.string.menu_delete_folder)) {
                        onDismiss()
                        home.update { it.dissolveFolder(homeItem.id) }
                    }
                    is ShortcutItem -> MenuItem(stringResource(R.string.menu_remove_from_home)) {
                        onDismiss()
                        home.update { it.remove(homeItem.id) }
                    }
                }
                is DragSource.FolderEntry -> {
                    val removeItem: @Composable () -> Unit = {
                        MenuItem(stringResource(R.string.menu_remove_from_folder)) {
                            onDismiss()
                            home.update { it.remove(source.item.id) }
                        }
                    }
                    val entry = appsByRef[source.item.app]
                    if (entry != null) AppEntries(entry, actions, onDismiss, extra = removeItem) else removeItem()
                }
            }
        }
    }
}

/** App shortcuts, then [extra], "App info" and "Uninstall". */
@Composable
private fun AppEntries(
    entry: AppEntry,
    actions: AppActions,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit,
) {
    val shortcuts by produceState(emptyList<AppShortcut>(), entry) { value = actions.shortcuts(entry) }
    shortcuts.forEach { shortcut ->
        DropdownMenuItem(
            text = { Text(shortcut.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = shortcut.icon?.let { icon ->
                { Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
            },
            onClick = {
                onDismiss()
                actions.launchShortcut(shortcut, null)
            },
        )
    }
    if (shortcuts.isNotEmpty()) HorizontalDivider()
    extra()
    MenuItem(stringResource(R.string.menu_app_info)) {
        onDismiss()
        actions.openAppInfo(entry, null)
    }
    if (!entry.isSystem) {
        MenuItem(stringResource(R.string.menu_uninstall)) {
            onDismiss()
            actions.uninstall(entry)
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick)
}
