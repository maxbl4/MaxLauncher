package com.maxbl4.maxlauncher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs
import kotlin.math.sign

/**
 * All launcher-level gestures, handled at the root like ViewGroup.onInterceptTouchEvent:
 * events are observed in the Initial pass, before children see them.
 *
 * - Vertical swipe (either direction): opens / closes the app drawer.
 * - Long press on an item: shows its menu; moving the finger then drags the item.
 * - Long press on the home screen background: edit mode.
 *
 * Once a gesture is claimed every following change is consumed, which cancels clicks and
 * scrolling underneath. Horizontal movement is left to the pagers; taps to the items.
 */
suspend fun PointerInputScope.launcherGestures(state: LauncherState, haptics: HapticFeedback) {
    val swipeDistance = size.height * DRAWER_SWIPE_FRACTION
    val flingVelocity = FLING_VELOCITY_DP * density

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        var total = Offset.Zero
        var last: PointerInputChange = down

        // Phase 1: wait for movement past the slop, release, or the long-press timeout.
        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            awaitSlopOrRelease(down.id) { change ->
                last = change
                total += change.positionChange()
                abs(total.x) > slop || abs(total.y) > slop
            }
        } ?: Outcome.LongPress

        when (outcome) {
            Outcome.Lost, Outcome.Released -> Unit
            Outcome.Moved -> {
                val vertical = abs(total.y) > abs(total.x)
                if (vertical && state.canSwipeDrawer) {
                    swipeDrawer(state, down.id, last, sign(total.y), swipeDistance, flingVelocity)
                }
            }
            Outcome.LongPress -> {
                val hit = state.hitTest(down.position)
                when {
                    hit != null -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.showMenu(hit.first, hit.second)
                        dragAfterLongPress(state, down.id, down.position, hit.first, hit.second)
                    }
                    !state.editMode && state.isHomeBackground(down.position) -> {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.editMode = true
                        consumeUntilUp(down.id)
                    }
                    else -> consumeUntilUp(down.id)
                }
            }
        }
    }
}

private enum class Outcome { Moved, Released, LongPress, Lost }

private suspend fun AwaitPointerEventScope.awaitChange(id: PointerId): PointerInputChange? =
    awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == id }

/** [pastSlop] sees every change and tells whether the finger has moved past the touch slop. */
private suspend fun AwaitPointerEventScope.awaitSlopOrRelease(
    id: PointerId,
    pastSlop: (PointerInputChange) -> Boolean,
): Outcome {
    while (true) {
        val change = awaitChange(id) ?: return Outcome.Lost
        if (!change.pressed) return Outcome.Released
        if (pastSlop(change)) return Outcome.Moved
    }
}

private suspend fun AwaitPointerEventScope.swipeDrawer(
    state: LauncherState,
    id: PointerId,
    first: PointerInputChange,
    sign: Float,
    distance: Float,
    flingVelocity: Float,
) {
    val drawer = state.drawer
    drawer.beginSwipe(sign)
    val tracker = VelocityTracker()
    tracker.addPointerInputChange(first)
    first.consume()
    var released = false
    while (!released) {
        val change = awaitChange(id) ?: break
        tracker.addPointerInputChange(change)
        released = !change.pressed
        drawer.swipeBy(change.positionChange().y, distance)
        change.consume()
    }
    val velocity = if (released) tracker.calculateVelocity().y else 0f
    drawer.endSwipe(velocity, distance, flingVelocity)
}

private suspend fun AwaitPointerEventScope.dragAfterLongPress(
    state: LauncherState,
    id: PointerId,
    start: Offset,
    source: DragSource,
    bounds: androidx.compose.ui.geometry.Rect,
) {
    val slop = viewConfiguration.touchSlop
    var dragging = false
    while (true) {
        val change = awaitChange(id)
        if (change == null) {
            if (dragging) state.cancelDrag()
            return
        }
        change.consume()
        if (!dragging && (change.position - start).getDistance() > slop) {
            dragging = true
            state.startDrag(source, change.position, bounds)
        }
        if (!change.pressed) {
            // Released without moving: the menu stays open.
            if (dragging) state.endDrag(change.position)
            return
        }
        if (dragging) state.dragTo(change.position)
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp(id: PointerId) {
    while (true) {
        val change = awaitChange(id) ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/** Finger travel, as a fraction of the screen height, for a full drawer open/close. */
private const val DRAWER_SWIPE_FRACTION = 0.3f
/** A fling faster than this (dp/s) completes the drawer gesture regardless of distance. */
private const val FLING_VELOCITY_DP = 400f
