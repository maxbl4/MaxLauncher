package com.maxbl4.maxlauncher.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.SettingsActivity
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppSearch
import com.maxbl4.maxlauncher.drawer.DrawerEntry

/** What the drawer shows; prepared by the screen from apps, drawer folders and settings. */
class DrawerContent(
    /** Personal tab: folders first, then apps in no folder. */
    val entries: List<DrawerEntry<AppEntry>>,
    /** All visible personal apps (including those in folders), for search. */
    val searchable: List<AppEntry>,
    /** Work profile apps; the tab is shown when [hasWorkProfile]. */
    val workApps: List<AppEntry>,
    val hasWorkProfile: Boolean,
    val workPaused: Boolean,
    val onUnpauseWork: () -> Unit,
    val columns: Int,
    val rows: Int,
    /** Apps opened from search, most opened first; shown when the empty search field is focused. */
    val searchHistory: List<AppEntry>,
    /** An app was opened from the search. */
    val onSearchLaunch: (AppEntry) -> Unit,
)

/**
 * Full list of apps, paged horizontally, with tabs (when there is a work profile) and the search
 * field at the bottom. It stays composed (but is not placed) while hidden, so opening it is
 * instant; the page, the tab and the query reset every time it closes.
 */
@Composable
fun AppDrawer(drawerContent: DrawerContent, content: HomeContent, modifier: Modifier = Modifier) {
    val state = content.state
    val drawer = state.drawer
    val dark = isSystemInDarkTheme()
    // Dark: the wallpaper stays visible under a light gradient, as on One UI (black from 8% at
    // the top to 20% at the bottom).
    val scrim = if (dark) {
        Brush.verticalGradient(listOf(Color(0x14000000), Color(0x33000000)))
    } else {
        SolidColor(Color(0xFFF2F2F2).copy(alpha = 0.82f))
    }
    val contentColor = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    val labels = LabelStyle(contentColor, shadow = false)
    val enterOffsetPx = with(LocalDensity.current) { 96.dp.toPx() }

    var workTab by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    // As One UI: search covers personal and work apps from either tab, personal ones first
    // (the search keeps the order of equally good matches).
    val searchable = remember(drawerContent) { drawerContent.searchable + drawerContent.workApps }
    val results = remember(searchable, query) { AppSearch.filter(searchable, query) { it.label } }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(drawer.isOpen) {
        if (!drawer.isOpen) focusManager.clearFocus()
    }
    val visible = drawer.isVisible
    LaunchedEffect(visible) {
        if (!visible) {
            query = ""
            workTab = false
        }
    }
    // Search results scroll vertically, so swipes must not close the drawer meanwhile.
    val searching = query.isNotBlank()
    // Tapping the empty search field shows the apps most often opened from search.
    val showHistory = !searching && searchFocused && drawerContent.searchHistory.isNotEmpty()
    val launchFromSearch: (AppEntry, Rect?) -> Unit = { entry, bounds ->
        drawerContent.onSearchLaunch(entry)
        content.actions.launch(entry, bounds)
    }
    DisposableEffect(searching) {
        drawer.swipesEnabled = !searching
        onDispose { drawer.swipesEnabled = true }
    }

    Box(
        modifier
            .then(if (visible) Modifier else Modifier.notPlaced())
            .fillMaxSize()
            .drawBehind { drawRect(scrim, alpha = drawer.progress.value) }
            // Swallow touches so they never reach the home screen underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawer.progress.value
                    alpha = p * (1f - state.drawerFolderProgress)
                    translationY = drawer.enterDirection * (1f - p) * enterOffsetPx
                }
                .windowInsetsPadding(WindowInsets.launcherBars)
                .padding(horizontal = 8.dp),
        ) {
            // The search field rides on top of the keyboard; the pages stay put under it, only
            // search results make room for it.
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (searching || showHistory) Modifier.imePadding() else Modifier)
                    .padding(bottom = SEARCH_AREA_HEIGHT),
            ) {
                TopBar(drawerContent.hasWorkProfile, workTab, onSelect = { workTab = it; query = "" }, contentColor)
                when {
                    searching -> SearchResults(results, drawerContent.columns, content, labels, launchFromSearch, Modifier.weight(1f))
                    showHistory -> SearchResults(drawerContent.searchHistory, drawerContent.columns, content, labels, launchFromSearch, Modifier.weight(1f))
                    workTab && drawerContent.workPaused -> WorkPaused(drawerContent.onUnpauseWork, contentColor, Modifier.weight(1f))
                    workTab -> PagedEntries(drawerContent.workApps.map { DrawerEntry.App(it) }, drawerContent, content, labels, Modifier.weight(1f))
                    else -> PagedEntries(drawerContent.entries, drawerContent, content, labels, Modifier.weight(1f))
                }
            }
            // While an app is dragged, the search field turns into the "to home screen" drop zone.
            val dragSource = state.drag?.source
            val showToHome = state.dragInDrawer && !state.layoutLocked && dragSource != null &&
                dragSource !is DragSource.DrawerFolderEntry
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
            ) {
                if (showToHome) {
                    ToHomeZone(state, Modifier.fillMaxWidth())
                } else {
                    DrawerSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        dark = dark,
                        onGo = { results.firstOrNull()?.let { launchFromSearch(it, null) } },
                        onFocusChange = { searchFocused = it },
                    )
                }
            }
        }
    }
}

/** As One UI: wide Personal / Work tabs on the left (with a work profile) and a menu on the right. */
@Composable
private fun TopBar(hasWorkProfile: Boolean, workTab: Boolean, onSelect: (Boolean) -> Unit, color: Color) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 8.dp).height(48.dp),
    ) {
        if (hasWorkProfile) {
            listOf(false to R.string.tab_personal, true to R.string.tab_work).forEach { (work, label) ->
                val selected = work == workTab
                Box(
                    Modifier
                        .width(145.dp)
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(if (selected) color.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onSelect(work) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        color = color.copy(alpha = if (selected) 1f else 0.6f),
                        fontSize = 17.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.drawer_more), tint = color)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_settings)) },
                    onClick = {
                        menuOpen = false
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    },
                )
            }
        }
    }
}

/** One UI's search: a short pill with a microphone at the bottom, as wide as the screen while in use. */
@Composable
private fun DrawerSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    dark: Boolean,
    onGo: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val expanded by animateFloatAsState(if (focused || query.isNotEmpty()) 1f else 0f, label = "search width")
    val background = if (dark) Color(0xFF3C3D3B) else Color(0xFFE4E4E4)
    val color = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    }
    val canSpeak = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onQueryChange)
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = color, fontSize = 17.sp),
            cursorBrush = SolidColor(color),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier
                .width(lerp(SEARCH_SHORT_WIDTH.coerceAtMost(maxWidth), maxWidth, expanded))
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                },
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(CircleShape)
                        .background(background)
                        .padding(start = 18.dp, end = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = color.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.search_hint), color = color.copy(alpha = 0.6f), fontSize = 17.sp, maxLines = 1)
                        }
                        innerTextField()
                    }
                    val button = Modifier.size(40.dp).clip(CircleShape)
                    when {
                        query.isNotEmpty() -> Box(button.clickable { onQueryChange("") }, contentAlignment = Alignment.Center) {
                            Text("✕", color = color.copy(alpha = 0.7f), fontSize = 17.sp)
                        }
                        canSpeak -> Box(button.clickable { voice.launch(voiceIntent) }, contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_mic),
                                contentDescription = stringResource(R.string.search_voice),
                                tint = color.copy(alpha = 0.8f),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

/** Width of the idle search pill (One UI). */
private val SEARCH_SHORT_WIDTH = 244.dp

/** Height under the pages kept for the search pill (52dp and its padding). */
private val SEARCH_AREA_HEIGHT = 66.dp

@Composable
private fun WorkPaused(onUnpause: () -> Unit, color: Color, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.work_paused), color = color.copy(alpha = 0.7f), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onUnpause) { Text(stringResource(R.string.work_unpause)) }
    }
}

@Composable
private fun PagedEntries(
    entries: List<DrawerEntry<AppEntry>>,
    drawerContent: DrawerContent,
    content: HomeContent,
    labels: LabelStyle,
    modifier: Modifier,
) {
    val pageSize = drawerContent.columns * drawerContent.rows
    val pageCount = maxOf(1, (entries.size + pageSize - 1) / pageSize)
    val pagerState = rememberPagerState { pageCount }
    val visible = content.state.drawer.isVisible
    LaunchedEffect(visible) {
        if (!visible) pagerState.scrollToPage(0)
    }
    SideEffect {
        content.state.onDrawerEdgeScroll = { direction ->
            val target = pagerState.currentPage + direction
            if (target in 0 until pagerState.pageCount) pagerState.animateScrollToPage(target)
        }
    }
    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f),
        ) { page ->
            val from = page * pageSize
            val pageEntries = if (from < entries.size) entries.subList(from, minOf(from + pageSize, entries.size)) else emptyList()
            EntryPage(pageEntries, drawerContent.columns, drawerContent.rows, content, labels)
        }
        PageDots(pageCount, pagerState.currentPage, labels.color)
    }
}

@Composable
private fun EntryPage(entries: List<DrawerEntry<AppEntry>>, columns: Int, rows: Int, content: HomeContent, labels: LabelStyle) {
    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
        repeat(rows) { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(columns) { column ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        entries.getOrNull(row * columns + column)?.let { DrawerEntryView(it, content, labels) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerEntryView(entry: DrawerEntry<AppEntry>, content: HomeContent, labels: LabelStyle) {
    val state = content.state
    val (source, hovered) = when (entry) {
        is DrawerEntry.App -> DragSource.DrawerApp(entry.app) to ((state.hover as? DropTarget.DrawerOnApp)?.entry?.ref == entry.app.ref)
        is DrawerEntry.Folder -> DragSource.DrawerFolderEntry(entry.folder) to ((state.hover as? DropTarget.DrawerOnFolder)?.folderId == entry.folder.id)
    }
    val modifier = Modifier
        .registerSource(state, Layer.DRAWER, source)
        .alpha(if (state.isDragged(source)) 0f else 1f)
        .then(if (hovered) Modifier.clip(RoundedCornerShape(18.dp)).background(labels.color.copy(alpha = 0.14f)) else Modifier)
    when (entry) {
        is DrawerEntry.App -> AppIcon(entry.app, content.actions, labels, modifier)
        is DrawerEntry.Folder -> LauncherIcon(entry.folder.title, labels, onClick = { bounds -> state.folderOrigin = bounds.center; state.openDrawerFolderId = entry.folder.id }, modifier = modifier) {
            FolderIcon(entry.apps.map { app -> app.icon }, it, drawerFolderBackground(content.darkTheme))
        }
    }
}

@Composable
private fun SearchResults(
    results: List<AppEntry>,
    columns: Int,
    content: HomeContent,
    labels: LabelStyle,
    onLaunch: (AppEntry, Rect?) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxWidth()) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.search_nothing_found),
                color = labels.color.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.key.id }) { entry ->
                    Box(Modifier.height(104.dp), contentAlignment = Alignment.Center) {
                        LauncherIcon(
                            entry.label,
                            labels,
                            onClick = { bounds -> onLaunch(entry, bounds) },
                            modifier = Modifier.registerSource(content.state, Layer.DRAWER, DragSource.DrawerApp(entry)),
                        ) { iconModifier -> Image(entry.icon, contentDescription = null, modifier = iconModifier) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToHomeZone(state: LauncherState, modifier: Modifier) {
    DisposableEffect(Unit) { onDispose { state.setToHomeZone(null) } }
    Box(
        modifier
            .height(48.dp)
            .onGloballyPositioned { state.setToHomeZone(it.boundsInRoot()) }
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xCC1565C0)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_home), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drop_to_home), color = Color.White, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    contentColor: Color,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = contentColor, fontSize = 16.sp),
        cursorBrush = SolidColor(contentColor),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(contentColor.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.search_hint), color = contentColor.copy(alpha = 0.5f), fontSize = 16.sp)
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Text(
                        text = "✕",
                        color = contentColor.copy(alpha = 0.6f),
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onQueryChange("") }
                            .padding(8.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun PageDots(pageCount: Int, currentPage: Int, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(28.dp),
    ) {
        if (pageCount > 1) {
            repeat(pageCount) { page ->
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = if (page == currentPage) 0.9f else 0.3f)),
                )
            }
        }
    }
}

/** Measured (so it stays ready) but neither drawn nor hit-tested. */
private fun Modifier.notPlaced(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {}
}
