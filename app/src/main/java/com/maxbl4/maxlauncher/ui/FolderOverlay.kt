package com.maxbl4.maxlauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppSorting
import com.maxbl4.maxlauncher.home.AppRef

/**
 * An open folder (on the home screen or in the drawer): editable name, its apps in alphabetical
 * order and an "Add" tile that opens the app picker. Tapping outside closes it; long press and
 * drag of the apps are handled at the root, like everywhere else.
 *
 * [apps] are the folder's apps with the drag source each one registers as.
 */
@Composable
fun FolderPanel(
    folderId: Long,
    title: String,
    apps: List<Pair<DragSource, AppEntry>>,
    pickerApps: List<AppEntry>,
    content: HomeContent,
    visible: Boolean,
    onRename: (String) -> Unit,
    onPicked: (List<AppRef>) -> Unit,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
) {
    val state = content.state
    val dark = content.darkTheme
    val cardColor = if (dark) Color(0xFF262626) else Color(0xFFF7F7F7)
    val textColor = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    val labels = LabelStyle(textColor, shadow = false)
    val sorted = remember(apps) { apps.sortedWith(AppSorting.comparator { it.second.label }) }
    var pickerOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // The card grows out of the tapped folder icon and shrinks back into it when closed.
    val origin = remember(folderId) { state.folderOrigin }
    val progress = remember(folderId) { Animatable(0f) }
    // Closing (tap outside, Back, Home, dragging an app out) clears the open folder; the panel
    // stays until it has shrunk back, then reports [onClosed].
    LaunchedEffect(folderId, visible) {
        if (visible) {
            progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        } else {
            focusManager.clearFocus()
            progress.animateTo(0f, tween(durationMillis = 180))
            onClosed()
        }
    }
    val close: () -> Unit = onDismiss
    var cardBounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color(0x66000000), alpha = progress.value.coerceIn(0f, 1f)) }
            .tapToDismiss(close)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                // Measured before the layer below, so the bounds are not affected by its scale.
                .onGloballyPositioned { cardBounds = it.boundsInRoot() }
                .graphicsLayer {
                    val p = progress.value
                    val scale = FOLDER_START_SCALE + (1f - FOLDER_START_SCALE) * p
                    scaleX = scale
                    scaleY = scale
                    alpha = p.coerceIn(0f, 1f)
                    transformOrigin = if (origin != null && cardBounds.width > 0f) {
                        TransformOrigin(
                            ((origin.x - cardBounds.left) / cardBounds.width).coerceIn(0f, 1f),
                            ((origin.y - cardBounds.top) / cardBounds.height).coerceIn(0f, 1f),
                        )
                    } else {
                        TransformOrigin.Center
                    }
                }
                .clip(RoundedCornerShape(28.dp))
                .background(cardColor)
                .tapToDismiss { focusManager.clearFocus() }
                .padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            var name by remember(folderId) { mutableStateOf(title) }
            BasicTextField(
                value = name,
                onValueChange = {
                    name = it
                    onRename(it)
                },
                singleLine = true,
                textStyle = TextStyle(color = textColor, fontSize = 20.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (name.isEmpty()) {
                            Text(stringResource(R.string.folder_name_hint), color = textColor.copy(alpha = 0.45f), fontSize = 20.sp)
                        }
                        inner()
                    }
                },
            )
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.heightIn(max = 440.dp)) {
                items(sorted, key = { it.first.key }) { (source, entry) ->
                    Box(Modifier.height(100.dp), contentAlignment = Alignment.Center) {
                        AppIcon(entry, content.actions, labels, Modifier.registerSource(state, Layer.FOLDER, source))
                    }
                }
                item(key = "add") {
                    Box(Modifier.height(100.dp), contentAlignment = Alignment.Center) {
                        LauncherIcon(stringResource(R.string.folder_add_apps), labels, onClick = { pickerOpen = true }) { iconModifier ->
                            Box(
                                iconModifier
                                    .clip(RoundedCornerShape(30))
                                    .border(1.5.dp, textColor.copy(alpha = 0.3f), RoundedCornerShape(30)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = textColor.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        AppChecklist(
            title = stringResource(R.string.picker_title),
            apps = pickerApps,
            selected = apps.map { it.second.ref },
            dark = dark,
            onDone = { refs ->
                pickerOpen = false
                onPicked(refs)
            },
            onCancel = { pickerOpen = false },
        )
    }
}

/** Folder card scale at the start of the opening animation (relative to its full size). */
private const val FOLDER_START_SCALE = 0.15f

/** Full-screen list of apps with checkboxes (folder content, hidden apps). */
@Composable
fun AppChecklist(
    title: String,
    apps: List<AppEntry>,
    selected: Collection<AppRef>,
    dark: Boolean,
    onDone: (List<AppRef>) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val chosen = remember { mutableStateListOf<AppRef>().apply { addAll(selected) } }
    val background = if (dark) Color(0xFF181818) else Color(0xFFF7F7F7)
    val textColor = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .tapToDismiss {}
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Text(title, color = textColor, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.key.id }) { entry ->
                val checked = entry.ref in chosen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (checked) chosen.remove(entry.ref) else chosen.add(entry.ref) }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    Image(entry.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        entry.label,
                        color = textColor,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Checkbox(checked = checked, onCheckedChange = null)
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.picker_cancel)) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onDone(chosen.toList()) }) { Text(stringResource(R.string.picker_done)) }
        }
    }
}

/** Handles taps on this element itself, so they do not reach what is underneath. */
@Composable
fun Modifier.tapToDismiss(onTap: () -> Unit): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    return pointerInput(Unit) { detectTapGestures { currentOnTap() } }
}
