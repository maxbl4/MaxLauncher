package com.maxbl4.maxlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.apps.AppEntry
import com.maxbl4.maxlauncher.apps.AppSearch

private const val DRAWER_COLUMNS = 5
private const val DRAWER_ROWS = 6
private const val PAGE_SIZE = DRAWER_COLUMNS * DRAWER_ROWS

/**
 * Full list of apps, paged horizontally, with a search field on top. Composed only while the
 * drawer is visible, so the page and the query reset every time it is reopened.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    drawer: DrawerState,
    actions: AppActions,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val scrim = if (dark) Color(0xFF101010).copy(alpha = 0.75f) else Color(0xFFF2F2F2).copy(alpha = 0.9f)
    val contentColor = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    val enterOffsetPx = with(LocalDensity.current) { 96.dp.toPx() }

    var query by remember { mutableStateOf("") }
    val results = remember(apps, query) { AppSearch.filter(apps, query) { it.label } }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(drawer.isOpen) {
        if (!drawer.isOpen) focusManager.clearFocus()
    }
    // Search results scroll vertically, so swipes must not close the drawer meanwhile.
    val searching = query.isNotBlank()
    DisposableEffect(searching) {
        drawer.swipesEnabled = !searching
        onDispose { drawer.swipesEnabled = true }
    }

    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(scrim, alpha = drawer.progress.value) }
            // Swallow touches so they never reach the home screen underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = drawer.progress.value
                    alpha = p
                    translationY = drawer.enterDirection * (1f - p) * enterOffsetPx
                }
                .windowInsetsPadding(WindowInsets.systemBars)
                // The search field sits at the bottom and rides on top of the keyboard.
                .imePadding()
                .padding(horizontal = 8.dp),
        ) {
            if (query.isBlank()) {
                PagedApps(apps, actions, contentColor, Modifier.weight(1f))
            } else {
                SearchResults(results, actions, contentColor, Modifier.weight(1f))
            }
            SearchField(
                query = query,
                onQueryChange = { query = it },
                contentColor = contentColor,
                onGo = { results.firstOrNull()?.let { actions.launch(it, null) } },
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun PagedApps(apps: List<AppEntry>, actions: AppActions, contentColor: Color, modifier: Modifier) {
    val pageCount = maxOf(1, (apps.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val pagerState = rememberPagerState { pageCount }
    Column(modifier) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.weight(1f),
        ) { page ->
            val from = page * PAGE_SIZE
            val pageApps = if (from < apps.size) apps.subList(from, minOf(from + PAGE_SIZE, apps.size)) else emptyList()
            AppPage(pageApps, actions, contentColor)
        }
        PageIndicator(pageCount, pagerState.currentPage, contentColor)
    }
}

@Composable
private fun AppPage(apps: List<AppEntry>, actions: AppActions, contentColor: Color) {
    Column(Modifier.fillMaxSize()) {
        repeat(DRAWER_ROWS) { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(DRAWER_COLUMNS) { column ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        apps.getOrNull(row * DRAWER_COLUMNS + column)?.let { entry ->
                            AppIcon(entry, actions, contentColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(results: List<AppEntry>, actions: AppActions, contentColor: Color, modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.search_nothing_found),
                color = contentColor.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(DRAWER_COLUMNS),
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.key.id }) { entry ->
                    Box(Modifier.height(104.dp), contentAlignment = Alignment.Center) {
                        AppIcon(entry, actions, contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
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
private fun PageIndicator(pageCount: Int, currentPage: Int, color: Color) {
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
