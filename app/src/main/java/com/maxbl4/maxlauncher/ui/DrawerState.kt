package com.maxbl4.maxlauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Open/close state of the app drawer. [progress] goes from 0 (closed) to 1 (open) and follows
 * the finger during a vertical swipe in either direction (docs/FEATURES.md, "Жесты"); the swipe
 * itself is detected in [launcherGestures].
 */
@Stable
class DrawerState(private val scope: CoroutineScope) {

    val progress = Animatable(0f)

    /** The drawer is open or is settling open. */
    var isOpen by mutableStateOf(false)
        private set

    /**
     * Where the drawer content sits when hidden: +1 below the screen, -1 above. Set from each
     * swipe so the content always moves with the finger: a swipe up brings it in from below and
     * takes it away upwards; a swipe down the other way round.
     */
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

    /**
     * Settles open or closed, moving one way only and slowing down at the end (no spring, so no
     * overshoot). After a swipe the motion starts at full speed, as if the finger let go of it;
     * Back / Home ease in and out. The time is proportional to the distance left.
     */
    private fun animateTo(open: Boolean, afterSwipe: Boolean = false) {
        isOpen = open
        scope.launch {
            val target = if (open) 1f else 0f
            val duration = (FULL_DURATION_MS * abs(target - progress.value)).roundToInt().coerceAtLeast(MIN_DURATION_MS)
            progress.animateTo(
                targetValue = target,
                animationSpec = tween(duration, easing = if (afterSwipe) LinearOutSlowInEasing else FastOutSlowInEasing),
            )
        }
    }

    // --- Swipe tracking ---

    private var swipeStartedOpen = false
    private var swipeSign = 0f
    private var swiped = 0f
    // Tracked here because progress.snapTo() runs asynchronously and may lag behind the finger.
    private var swipeProgress = 0f

    /** [sign] is the sign of the initial vertical movement (-1 up, +1 down). */
    fun beginSwipe(sign: Float) {
        swipeStartedOpen = isOpen
        swipeSign = sign
        swiped = 0f
        swipeProgress = if (isOpen) 1f else 0f
        // A swipe up brings the content in from below when opening and takes it up when closing.
        val swipeUp = sign < 0
        enterDirection = if (swipeUp != swipeStartedOpen) 1 else -1
    }

    /** [distance] is the finger travel for a full open/close. */
    fun swipeBy(dy: Float, distance: Float) {
        // Distance along the initial direction; reversing past the start does nothing.
        swiped = (swiped + dy * swipeSign).coerceAtLeast(0f)
        val fraction = (swiped / distance).coerceIn(0f, 1f)
        val target = if (swipeStartedOpen) 1f - fraction else fraction
        swipeProgress = target
        scope.launch { progress.snapTo(target) }
    }

    /** [velocityY] in px/s; a fling faster than [flingVelocity] completes the gesture. */
    fun endSwipe(velocityY: Float, distance: Float, flingVelocity: Float) {
        val along = velocityY * swipeSign
        val p = swipeProgress
        val shouldOpen = if (swipeStartedOpen) {
            !(along > flingVelocity || p < 1f - SETTLE_FRACTION)
        } else {
            along > flingVelocity || p > SETTLE_FRACTION
        }
        animateTo(shouldOpen, afterSwipe = true)
    }

    private companion object {
        /** Releasing past this fraction of the travel completes the gesture. */
        const val SETTLE_FRACTION = 0.4f
        /** Time for a full open or close; shorter when part of the way is already done. */
        const val FULL_DURATION_MS = 320f
        const val MIN_DURATION_MS = 120
    }
}
