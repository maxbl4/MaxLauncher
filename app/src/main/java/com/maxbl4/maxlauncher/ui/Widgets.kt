package com.maxbl4.maxlauncher.ui

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.maxbl4.maxlauncher.R
import com.maxbl4.maxlauncher.apps.AppSearch
import com.maxbl4.maxlauncher.apps.AppSorting
import com.maxbl4.maxlauncher.home.Placed
import com.maxbl4.maxlauncher.home.WidgetItem
import com.maxbl4.maxlauncher.widgets.WidgetHostManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/** An app widget hosted on the home screen. */
@Composable
fun WidgetView(item: WidgetItem, widgets: WidgetHostManager, state: LauncherState, modifier: Modifier = Modifier) {
    val info = remember(item.appWidgetId) { widgets.info(item.appWidgetId) }
    if (info == null) {
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x33888888)), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.widget_unavailable), color = Color.White, fontSize = 13.sp)
        }
        return
    }
    BoxWithConstraints(modifier) {
        val size = SizeF(maxWidth.value, maxHeight.value)
        AndroidView(
            factory = { context -> widgets.createView(context, item.appWidgetId, info).also { state.registerWidgetView(item.id, it) } },
            onRelease = { state.unregisterWidgetView(item.id, it) },
            update = { view ->
                if (view.tag != size) {
                    view.tag = size
                    view.updateAppWidgetSize(Bundle(), listOf(size))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Frame with handles around a widget being resized. Handles snap to cells on release; the
 * provider's minimum size and resize directions are respected.
 */
@Composable
fun BoxScope.WidgetResizeFrame(
    placed: Placed,
    columns: Int,
    rows: Int,
    cellWidth: Dp,
    cellHeight: Dp,
    content: HomeContent,
    onResize: (x: Int, y: Int, spanX: Int, spanY: Int) -> Unit,
) {
    val item = placed.item as? WidgetItem ?: return
    val info = remember(item.appWidgetId) { content.widgets.info(item.appWidgetId) }
    val density = LocalDensity.current
    val cw = with(density) { cellWidth.toPx() }
    val ch = with(density) { cellHeight.toPx() }
    val mode = info?.resizeMode ?: AppWidgetProviderInfo.RESIZE_NONE
    val minW = info?.let { ceil(it.minResizeWidth / cw).toInt() }?.coerceIn(1, columns) ?: 1
    val minH = info?.let { ceil(it.minResizeHeight / ch).toInt() }?.coerceIn(1, rows) ?: 1

    var dLeft by remember(placed) { mutableFloatStateOf(0f) }
    var dRight by remember(placed) { mutableFloatStateOf(0f) }
    var dTop by remember(placed) { mutableFloatStateOf(0f) }
    var dBottom by remember(placed) { mutableFloatStateOf(0f) }

    fun commit() {
        var left = ((placed.x * cw + dLeft) / cw).roundToInt().coerceIn(0, columns - 1)
        var right = (((placed.x + placed.spanX) * cw + dRight) / cw).roundToInt().coerceIn(1, columns)
        var top = ((placed.y * ch + dTop) / ch).roundToInt().coerceIn(0, rows - 1)
        var bottom = (((placed.y + placed.spanY) * ch + dBottom) / ch).roundToInt().coerceIn(1, rows)
        if (right - left < minW) if (dLeft != 0f) left = right - minW else right = left + minW
        if (bottom - top < minH) if (dTop != 0f) top = bottom - minH else bottom = top + minH
        left = left.coerceAtLeast(0); top = top.coerceAtLeast(0)
        right = right.coerceAtMost(columns); bottom = bottom.coerceAtMost(rows)
        dLeft = 0f; dRight = 0f; dTop = 0f; dBottom = 0f
        onResize(left, top, right - left, bottom - top)
    }

    val left = placed.x * cw + dLeft
    val top = placed.y * ch + dTop
    val width = (placed.spanX * cw + dRight - dLeft).coerceAtLeast(cw * 0.5f)
    val height = (placed.spanY * ch + dBottom - dTop).coerceAtLeast(ch * 0.5f)
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            .border(2.dp, Color.White, RoundedCornerShape(14.dp)),
    ) {
        val horizontal = mode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = mode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        if (horizontal) {
            Handle(Modifier.align(Alignment.CenterStart).offset(x = -HANDLE_SIZE / 2), onDrag = { dLeft += it.x }, onEnd = ::commit)
            Handle(Modifier.align(Alignment.CenterEnd).offset(x = HANDLE_SIZE / 2), onDrag = { dRight += it.x }, onEnd = ::commit)
        }
        if (vertical) {
            Handle(Modifier.align(Alignment.TopCenter).offset(y = -HANDLE_SIZE / 2), onDrag = { dTop += it.y }, onEnd = ::commit)
            Handle(Modifier.align(Alignment.BottomCenter).offset(y = HANDLE_SIZE / 2), onDrag = { dBottom += it.y }, onEnd = ::commit)
        }
    }
}

@Composable
private fun Handle(modifier: Modifier, onDrag: (androidx.compose.ui.geometry.Offset) -> Unit, onEnd: () -> Unit) {
    Box(
        modifier
            .size(HANDLE_SIZE)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, amount ->
                    change.consume()
                    onDrag(amount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White).border(2.dp, Color(0xFF1565C0), CircleShape))
    }
}

/** A widget offered in the picker, with the size it will take on the home grid. */
class WidgetOption(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val label: String,
    val preview: ImageBitmap?,
    val spanX: Int,
    val spanY: Int,
)

/** Cells a widget takes by default: the provider's target size, or enough cells for its minimum size. */
fun widgetSpans(info: AppWidgetProviderInfo, cell: Size, columns: Int, rows: Int): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
        return info.targetCellWidth.coerceIn(1, columns) to info.targetCellHeight.coerceIn(1, rows)
    }
    if (cell.width <= 0f || cell.height <= 0f) return 2 to 2
    return ceil(info.minWidth / cell.width).toInt().coerceIn(1, columns) to ceil(info.minHeight / cell.height).toInt().coerceIn(1, rows)
}

private fun loadOptions(context: Context, widgets: WidgetHostManager, cell: Size, columns: Int, rows: Int): List<WidgetOption> {
    val pm = context.packageManager
    val density = context.resources.displayMetrics.densityDpi
    val maxPreview = (160 * context.resources.displayMetrics.density).roundToInt()
    return widgets.providers().map { info ->
        val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
            .getOrDefault(info.provider.packageName)
        val drawable = runCatching { info.loadPreviewImage(context, density) ?: info.loadIcon(context, density) }.getOrNull()
        val preview = drawable?.let {
            val w = it.intrinsicWidth.takeIf { v -> v > 0 } ?: maxPreview
            val h = it.intrinsicHeight.takeIf { v -> v > 0 } ?: maxPreview
            val scale = minOf(1f, maxPreview.toFloat() / maxOf(w, h))
            it.toBitmap((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1)).asImageBitmap()
        }
        val (spanX, spanY) = widgetSpans(info, cell, columns, rows)
        WidgetOption(info, appLabel, info.loadLabel(pm), preview, spanX, spanY)
    }.sortedWith(AppSorting.comparator<WidgetOption> { it.appLabel }.thenBy { it.label })
}

/**
 * Full-screen list of widgets grouped by app, with search. Tapping one adds it to the current
 * page; a long press picks it up to drop it where it should go.
 */
@Composable
fun WidgetPicker(
    state: LauncherState,
    widgets: WidgetHostManager,
    cell: Size,
    columns: Int,
    rows: Int,
    dark: Boolean,
    onPick: (WidgetOption) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val options by produceState<List<WidgetOption>?>(null) {
        value = withContext(Dispatchers.IO) { loadOptions(context, widgets, cell, columns, rows) }
    }
    val background = if (dark) Color(0xFF181818) else Color(0xFFF7F7F7)
    val textColor = if (dark) Color(0xFFF2F2F2) else Color(0xFF1A1A1A)
    Column(
        Modifier
            .fillMaxSize()
            .background(background)
            .tapToDismiss {}
            .windowInsetsPadding(WindowInsets.launcherBars)
            .imePadding(),
    ) {
        Text(
            stringResource(R.string.widgets_title),
            color = textColor,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
        var query by remember { mutableStateOf("") }
        SearchField(
            query = query,
            onQueryChange = { query = it },
            contentColor = textColor,
            onGo = {},
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        val all = options ?: return@Column
        // Search by widget name and by app name ("часы", "погода", "chrome").
        val list = remember(all, query) {
            if (query.isBlank()) all else AppSearch.filter(all, query) { "${it.appLabel} ${it.label}" }
        }
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.search_nothing_found),
                color = textColor.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        // Each app's name is shown above its first widget.
        val withHeaders = list.mapIndexed { i, option -> option to (i == 0 || list[i - 1].appLabel != option.appLabel) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(withHeaders, key = { (it) -> it.info.provider.flattenToString() + it.info.profile.hashCode() }) { (option, header) ->
                Column(Modifier.fillMaxWidth()) {
                    if (header) {
                        Text(
                            option.appLabel,
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .registerSource(state, Layer.WIDGET_PICKER, DragSource.NewWidget(option))
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    ) {
                        Box(
                            Modifier
                                .size(width = 120.dp, height = 96.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(textColor.copy(alpha = 0.06f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            option.preview?.let { Image(it, contentDescription = null, modifier = Modifier.heightIn(max = 88.dp).padding(4.dp)) }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.label, color = textColor, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${option.spanX} × ${option.spanY}", color = textColor.copy(alpha = 0.6f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

private val HANDLE_SIZE = 36.dp
