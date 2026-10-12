package com.maxbl4.maxlauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
        if (!swipeStartedOpen) enterDirection = if (sign < 0) 1 else -1
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
        // Finger velocity in progress units per second.
        val progressVelocity = along / distance * if (swipeStartedOpen) -1f else 1f
        animateTo(shouldOpen, progressVelocity)
    }

    private companion object {
        /** Releasing past this fraction of the travel completes the gesture. */
        const val SETTLE_FRACTION = 0.4f
    }
}
