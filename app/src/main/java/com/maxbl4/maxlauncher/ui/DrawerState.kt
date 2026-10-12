package com.maxbl4.maxlauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Open/close state of the app drawer. [progress] goes from 0 (closed) to 1 (open) and follows
 * the finger during a vertical swipe in either direction (docs/FEATURES.md, "Жесты").
 */
@Stable
class DrawerState(private val scope: CoroutineScope) {

    val progress = Animatable(0f)

    /** The drawer is open or is settling open. */
    var isOpen by mutableStateOf(false)
        private set

    /** +1 when the drawer enters from below (swipe up), -1 from above (swipe down). */
    var enterDirection by mutableIntStateOf(1)
        private set

    val isVisible by derivedStateOf { progress.value > 0f || isOpen }

    /** Vertical swipes open/close the drawer; off while a vertically scrolling list is shown. */
    var swipesEnabled by mutableStateOf(true)

    fun open() = animateTo(open = true)

    fun close() = animateTo(open = false)

    fun snapClosed() {
        isOpen = false
        scope.launch { progress.snapTo(0f) }
    }

    private fun animateTo(open: Boolean, velocity: Float = 0f) {
        isOpen = open
        scope.launch {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                initialVelocity = velocity,
            )
        }
    }

    /**
     * Vertical drag handling for the whole launcher surface. A swipe up or down opens the
     * closed drawer and closes the open one.
     *
     * Events are observed in the Initial pass, before children see them: once the finger has
     * moved past the touch slop mostly vertically, the gesture is claimed and every following
     * change is consumed, which cancels clicks on icons underneath. Mostly horizontal movement
     * is left to the pagers. While [swipesEnabled] is false (e.g. a scrolling search list is
     * shown) nothing is intercepted.
     */
    suspend fun PointerInputScope.handleVerticalSwipes() {
        val distance = size.height * DRAG_DISTANCE_FRACTION
        val flingVelocity = FLING_VELOCITY_DP * density

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!swipesEnabled) return@awaitEachGesture

            // Slop phase: decide whether this is our vertical swipe.
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var change: PointerInputChange
            while (true) {
                change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                total += change.positionChange()
                if (abs(total.x) > slop && abs(total.x) >= abs(total.y)) return@awaitEachGesture
                if (abs(total.y) > slop) break
            }

            // Drag phase.
            val startedOpen = isOpen
            val dragSign = sign(total.y)
            if (!startedOpen) enterDirection = if (dragSign < 0) 1 else -1
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(change)
            change.consume()
            var dragged = 0f
            var released = false
            while (!released) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPointerInputChange(change)
                // Distance along the initial direction; reversing past the start does nothing.
                dragged = (dragged + change.positionChange().y * dragSign).coerceAtLeast(0f)
                released = !change.pressed
                change.consume()
                val fraction = (dragged / distance).coerceIn(0f, 1f)
                val target = if (startedOpen) 1f - fraction else fraction
                scope.launch { progress.snapTo(target) }
            }

            val velocityAlongDrag = if (released) tracker.calculateVelocity().y * dragSign else 0f
            val p = progress.value
            val shouldOpen = if (startedOpen) {
                !(velocityAlongDrag > flingVelocity || p < 1f - SETTLE_FRACTION)
            } else {
                velocityAlongDrag > flingVelocity || p > SETTLE_FRACTION
            }
            // Convert finger velocity into progress units per second.
            val progressVelocity = velocityAlongDrag / distance * if (startedOpen) -1f else 1f
            animateTo(shouldOpen, progressVelocity)
        }
    }

    private companion object {
        /** Finger travel, as a fraction of the screen height, for a full open/close. */
        const val DRAG_DISTANCE_FRACTION = 0.3f
        /** Releasing past this fraction of the travel completes the gesture. */
        const val SETTLE_FRACTION = 0.4f
        /** A fling faster than this (dp/s) completes the gesture regardless of distance. */
        const val FLING_VELOCITY_DP = 400f
    }
}
