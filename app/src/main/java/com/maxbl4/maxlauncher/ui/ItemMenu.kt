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
import com.maxbl4.maxlauncher.drawer.DrawerLayout
import com.maxbl4.maxlauncher.home.AppItem
import com.maxbl4.maxlauncher.home.FolderItem
import com.maxbl4.maxlauncher.home.HomeRepository
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.WidgetItem
import com.maxbl4.maxlauncher.home.addApp
import com.maxbl4.maxlauncher.home.dissolveFolder
import com.maxbl4.maxlauncher.home.remove
import com.maxbl4.maxlauncher.settings.JsonFileStore
import kotlin.math.roundToInt

/** Long-press menu, anchored to the pressed item. Its entries depend on what was pressed. */
@Composable
fun ItemMenu(
    request: MenuRequest,
    content: HomeContent,
    home: HomeRepository,
    drawer: JsonFileStore<DrawerLayout>,
    onDismiss: () -> Unit,
) {
    val bounds = request.bounds
    val density = LocalDensity.current
    val locked = content.state.layoutLocked
    val actions = content.actions

    /** A menu entry that closes the menu and runs [action]. */
    @Composable
    fun entry(text: Int, action: () -> Unit) {
        DropdownMenuItem(text = { Text(stringResource(text)) }, onClick = { onDismiss(); action() })
    }

    @Composable
    fun lockedNote() {
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_layout_locked)) }, onClick = onDismiss, enabled = false)
    }

    val addToHome: (AppEntry) -> @Composable () -> Unit = { app ->
        { if (!locked) entry(R.string.menu_add_to_home) { home.update { it.addApp(app.ref) } } }
    }

    Box(
        Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() }),
    ) {
        DropdownMenu(expanded = true, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp)) {
            when (val source = request.source) {
                is DragSource.DrawerApp -> AppEntries(source.entry, actions, onDismiss, extra = addToHome(source.entry))
                is DragSource.DrawerFolderApp -> AppEntries(source.entry, actions, onDismiss, extra = {
                    entry(R.string.menu_remove_from_folder) { drawer.update { it.removeApp(source.entry.ref) } }
                    addToHome(source.entry)()
                })
                is DragSource.DrawerFolderEntry -> entry(R.string.menu_delete_folder) {
                    drawer.update { it.deleteFolder(source.folder.id) }
                }
                is DragSource.NewWidget -> Unit
                is DragSource.HomeEntry -> when (val item = source.item) {
                    is AppItem -> {
                        val removeEntry: @Composable () -> Unit = {
                            if (!locked) entry(R.string.menu_remove_from_home) { home.update { it.remove(item.id) } }
                        }
                        val app = content.appsByRef[item.app]
                        if (app != null) AppEntries(app, actions, onDismiss, extra = removeEntry) else removeEntry()
                    }
                    is FolderItem -> if (locked) lockedNote() else entry(R.string.menu_delete_folder) {
                        home.update { it.dissolveFolder(item.id) }
                    }
                    is ShortcutItem -> if (locked) lockedNote() else entry(R.string.menu_remove_from_home) {
                        home.update { it.remove(item.id) }
                    }
                    is WidgetItem -> if (locked) lockedNote() else {
                        entry(R.string.menu_resize) { content.state.resizingWidgetId = item.id }
                        entry(R.string.menu_remove_from_home) { home.update { it.remove(item.id) } }
                    }
                }
                is DragSource.FolderEntry -> {
                    val removeEntry: @Composable () -> Unit = {
                        if (!locked) entry(R.string.menu_remove_from_folder) { home.update { it.remove(source.item.id) } }
                    }
                    val app = content.appsByRef[source.item.app]
                    if (app != null) AppEntries(app, actions, onDismiss, extra = removeEntry) else removeEntry()
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
    DropdownMenuItem(text = { Text(stringResource(R.string.menu_app_info)) }, onClick = {
        onDismiss()
        actions.openAppInfo(entry, null)
    })
    if (!entry.isSystem) {
        DropdownMenuItem(text = { Text(stringResource(R.string.menu_uninstall)) }, onClick = {
            onDismiss()
            actions.uninstall(entry)
        })
    }
}
