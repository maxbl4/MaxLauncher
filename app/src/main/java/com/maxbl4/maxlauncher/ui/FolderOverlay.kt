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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * An open folder (on the home screen or in the drawer), laid out as in One UI: an editable name,
 * a button that opens the app picker and the apps in alphabetical order, 3 × 4 per page. Tapping outside closes it; long press and
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
    /** How far the folder is shown, every frame (0 once it is gone). */
    onProgress: (Float) -> Unit,
) {
    val state = content.state
    val dark = content.darkTheme
    // Dark: a see-through card over a lightly dimmed screen, as One UI (black at 30% and 10%).
    val cardColor = if (dark) Color(0x4D000000) else Color(0xFFF7F7F7)
    val scrimColor = if (dark) Color(0x1A000000) else Color(0x66000000)
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
    LaunchedEffect(progress) { snapshotFlow { progress.value.coerceIn(0f, 1f) }.collect(onProgress) }
    DisposableEffect(progress) { onDispose { onProgress(0f) } }
    val close: () -> Unit = onDismiss
    var cardBounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(scrimColor, alpha = progress.value.coerceIn(0f, 1f)) }
            .tapToDismiss(close)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // As One UI: a large name above the card, the add button over its right edge and a card
        // of a fixed size with 3 × 4 apps per page.
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
            var name by remember(folderId) { mutableStateOf(title) }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onRename(it)
                    },
                    singleLine = true,
                    textStyle = TITLE_STYLE,
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (name.isEmpty()) {
                                Text(stringResource(R.string.folder_name_hint), style = TITLE_STYLE.copy(color = Color.White.copy(alpha = 0.5f)))
                            }
                            inner()
                        }
                    },
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 21.dp, bottom = 8.dp)
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { pickerOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(25.dp).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.folder_add_apps),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            val pages = remember(sorted) { sorted.chunked(FOLDER_COLUMNS * FOLDER_ROWS).ifEmpty { listOf(emptyList()) } }
            val pagerState = rememberPagerState { pages.size }
            Column(
                Modifier
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
                    .clip(RoundedCornerShape(34.dp))
                    .background(cardColor)
                    .tapToDismiss { focusManager.clearFocus() }
                    .padding(start = 12.dp, end = 12.dp, top = 16.dp),
            ) {
                HorizontalPager(pagerState, Modifier.fillMaxWidth().height(FOLDER_CELL_HEIGHT * FOLDER_ROWS)) { page ->
                    Column(Modifier.fillMaxSize()) {
                        pages[page].chunked(FOLDER_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(FOLDER_CELL_HEIGHT)) {
                                row.forEach { (source, entry) ->
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        AppIcon(entry, content.actions, labels, Modifier.registerSource(state, Layer.FOLDER, source))
                                    }
                                }
                                repeat(FOLDER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(21.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pages.size > 1) {
                        repeat(pages.size) { page ->
                            Box(
                                Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(textColor.copy(alpha = if (page == pagerState.currentPage) 0.9f else 0.3f)),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(50.dp))
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

// One UI folder grid: 3 × 4 apps per page in cells of about 94 × 99dp.
private const val FOLDER_COLUMNS = 3
private const val FOLDER_ROWS = 4
private val FOLDER_CELL_HEIGHT = 99.dp
private val TITLE_STYLE = TextStyle(
    color = Color.White,
    fontSize = 32.sp,
    fontWeight = FontWeight.SemiBold,
    textAlign = TextAlign.Center,
    shadow = Shadow(color = Color(0x66000000), offset = Offset(0f, 2f), blurRadius = 6f),
)

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
