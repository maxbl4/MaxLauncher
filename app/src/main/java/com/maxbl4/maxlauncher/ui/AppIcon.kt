package com.maxbl4.maxlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.maxbl4.maxlauncher.apps.AppEntry

val ICON_SIZE = 56.dp

/** How icon labels look: dark text on light wallpapers / the light drawer, white with a shadow otherwise. */
data class LabelStyle(val color: Color, val shadow: Boolean)

/**
 * Icon with a one-line label. Taps go to [onClick] with the icon bounds (for the launch
 * animation); long press and drag are handled at the root by [launcherGestures].
 */
@Composable
fun LauncherIcon(
    label: String,
    labelStyle: LabelStyle,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = ICON_SIZE,
    icon: @Composable (Modifier) -> Unit,
) {
    // Read only from callbacks, so updating it on layout does not recompose.
    var iconBounds by remember { mutableStateOf(Rect.Zero) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(iconBounds) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        icon(
            Modifier
                .size(iconSize)
                .onGloballyPositioned { iconBounds = it.boundsInRoot() },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = labelStyle.color,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 14.sp,
                shadow = if (labelStyle.shadow) LABEL_SHADOW else null,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun AppIcon(entry: AppEntry, actions: AppActions, labelStyle: LabelStyle, modifier: Modifier = Modifier) {
    LauncherIcon(entry.label, labelStyle, onClick = { actions.launch(entry, it) }, modifier = modifier) { iconModifier ->
        Image(bitmap = entry.icon, contentDescription = null, modifier = iconModifier)
    }
}

/** Folder icon: a translucent rounded square with previews of the first apps (2×2, or 3×3 for more than four). */
@Composable
fun FolderIcon(apps: List<ImageBitmap>, modifier: Modifier = Modifier, dark: Boolean = false) {
    val grid = if (apps.size <= 4) 2 else 3
    val shown = apps.take(grid * grid)
    Box(
        modifier
            .clip(RoundedCornerShape(30))
            .background(if (dark) Color(0x66303030) else Color(0x99F2F2F2))
            .padding(if (grid == 2) 9.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (row in 0 until grid) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (column in 0 until grid) {
                        val bitmap = shown.getOrNull(row * grid + column)
                        val cell = Modifier.size(if (grid == 2) 17.dp else 12.dp)
                        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = cell) else Spacer(cell)
                    }
                }
            }
        }
    }
}

/** Registers the element as a long-press / drag source while it is composed. */
@Composable
fun Modifier.registerSource(state: LauncherState, layer: Layer, source: DragSource): Modifier {
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { state.unregisterSource(token) } }
    return dragSource(state, layer, source, token)
}

private val LABEL_SHADOW = Shadow(color = Color(0x99000000), offset = Offset(0f, 1.5f), blurRadius = 4f)
