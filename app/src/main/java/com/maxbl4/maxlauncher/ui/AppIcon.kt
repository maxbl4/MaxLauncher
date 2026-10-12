package com.maxbl4.maxlauncher.ui

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.maxbl4.maxlauncher.apps.AppEntry

/** Matches One UI Home icons on a 4-column grid. */
val ICON_SIZE = 63.dp

/** The system icon shape (a squircle on One UI), so folders look like the app icons next to them. */
val IconShape: Shape = object : Shape {
    // config_icon_mask in a 100×100 viewport (the drawable has no bounds).
    private val mask = AdaptiveIconDrawable(ColorDrawable(), ColorDrawable()).iconMask

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = android.graphics.Path(mask)
        path.transform(android.graphics.Matrix().apply { setScale(size.width / 100f, size.height / 100f) })
        return Outline.Generic(path.asComposePath())
    }
}

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
        Spacer(Modifier.height(4.dp))
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

/** Folder icon: a translucent [IconShape] with previews of the first nine apps in a 3×3 grid, as One UI. */
@Composable
fun FolderIcon(apps: List<ImageBitmap>, modifier: Modifier = Modifier, background: Color = HOME_FOLDER_LIGHT) {
    val grid = 3
    val shown = apps.take(grid * grid)
    // Previews scale with the icon: the folder is always ICON_SIZE.
    val padding = ICON_SIZE * 0.125f
    val gap = 3.dp
    val cellSize = (ICON_SIZE - padding * 2 - gap * (grid - 1)) / grid
    Box(
        modifier
            .clip(IconShape)
            .background(background)
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until grid) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (column in 0 until grid) {
                        val bitmap = shown.getOrNull(row * grid + column)
                        val cell = Modifier.size(cellSize)
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

/** Folder backgrounds: translucent over the wallpaper (dark: black at 59%, as One UI), tinted over the light drawer. */
val HOME_FOLDER_LIGHT = Color(0x99F2F2F2)
val HOME_FOLDER_DARK = Color(0x96000000)
val DRAWER_FOLDER_LIGHT = Color(0x1F000000)
val DRAWER_FOLDER_DARK = HOME_FOLDER_DARK

fun homeFolderBackground(dark: Boolean) = if (dark) HOME_FOLDER_DARK else HOME_FOLDER_LIGHT
fun drawerFolderBackground(dark: Boolean) = if (dark) DRAWER_FOLDER_DARK else DRAWER_FOLDER_LIGHT
