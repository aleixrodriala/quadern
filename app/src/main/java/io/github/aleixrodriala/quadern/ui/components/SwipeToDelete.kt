package io.github.aleixrodriala.quadern.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * A list row that can be swiped away to either side. A bin shows in the space it uncovers, growing
 * as the row goes; past the threshold it turns red with a tick, and letting go there (or flicking
 * the row) sends it off the screen, then [onDelete] runs with the side it left by (-1 left,
 * 1 right) so the list can close the gap. Short of that it springs back, and crossing back over the
 * threshold before letting go changes nothing.
 *
 * For accessibility services, give the content a "Delete" custom action on its clickable node.
 *
 * [enterFrom] slides a row in from that side as it appears, the way back for one that was undone.
 */
@Composable
fun SwipeToDelete(
    onDelete: (side: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    enterFrom: Int = 0,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestOnDelete by rememberUpdatedState(onDelete)
    var width by remember { mutableIntStateOf(0) }
    // Horizontal shift of the row, in px. Far out of the way until an entering row knows its width.
    var offset by remember { mutableFloatStateOf(if (enterFrom != 0) enterFrom * 100_000f else 0f) }
    var armed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(enterFrom != 0) }
    var settle by remember { mutableStateOf<Job?>(null) }
    var settling by remember { mutableStateOf(false) }
    // Read through state: the gesture handler outlives the composition that created it.
    val threshold by remember(density) { derivedStateOf { minOf(width * 0.35f, with(density) { 160.dp.toPx() }).coerceAtLeast(1f) } }
    val flickVelocity by rememberUpdatedState(with(density) { 900.dp.toPx() })

    fun arm(on: Boolean) {
        if (on == armed) return
        armed = on
        haptics.performHapticFeedback(if (on) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick)
    }

    fun drag(delta: Float) {
        offset += delta
        arm(abs(offset) >= threshold)
    }

    /** The finger let go; [cancelled] when something else took the gesture, which never deletes. */
    fun release(velocity: Float, cancelled: Boolean = false) {
        val side = sign(offset)
        val flick = abs(velocity) > flickVelocity
        val out = !cancelled && side != 0f && if (flick) sign(velocity) == side else armed
        settle = scope.launch {
            settling = true
            try {
                if (out) {
                    leaving = true
                    arm(true)
                    // Leaving accelerates, starting at the finger's speed: no stall after a flick,
                    // and no slow tail once the row's text is already gone.
                    val remaining = (width - abs(offset)).coerceAtLeast(0f)
                    val speed = if (sign(velocity) == side) abs(velocity) else 0f
                    val duration = if (speed * 0.2f >= remaining) remaining / speed else 0.2f
                    val start = (0.4f * speed * duration / remaining.coerceAtLeast(1f)).coerceAtMost(0.4f)
                    val easing = if (duration < 0.2f) LinearEasing else CubicBezierEasing(0.4f, start, 1f, 1f)
                    // The gap starts closing while the row is on its way out (its text is off the
                    // screen well before its edge is); it keeps going as the list fades it.
                    var handedOver = false
                    fun handOver() {
                        if (handedOver) return
                        handedOver = true
                        latestOnDelete(side.toInt())
                    }
                    try {
                        animate(offset, side * width, animationSpec = tween((duration * 1000).toInt().coerceAtLeast(60), easing = easing)) { v, _ ->
                            offset = v
                            if (abs(v) >= width * 0.7f) handOver()
                        }
                    } finally {
                        // Let go past the threshold means delete, even if the row is disposed on the way.
                        handOver()
                    }
                } else {
                    armed = false
                    animate(offset, 0f, velocity, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f)) { v, _ -> offset = v }
                }
            } finally {
                settling = false
            }
        }
    }

    if (entering) {
        LaunchedEffect(Unit) {
            val w = snapshotFlow { width }.first { it > 0 }
            offset = enterFrom * w.toFloat()
            // Let the gap start opening before the row slides into it.
            delay(90)
            animate(offset, 0f, 0f, spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = 0.5f)) { v, _ -> offset = v }
            entering = false
        }
    }

    val side by remember { derivedStateOf { sign(offset).toInt() } }
    val canDrag = enabled && !leaving && !entering
    Box(
        modifier
            .onSizeChanged { width = it.width }
            // On the row's still frame, not the moving one, so positions and speed are the finger's.
            .pointerInput(canDrag) {
                if (!canDrag) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var dragging = false
                    try {
                        val start = if (settling) {
                            // Catching a row that's still springing back takes it straight away.
                            settle?.cancel()
                            down
                        } else {
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                settle?.cancel()
                                change.consume()
                                drag(over)
                            }
                        } ?: return@awaitEachGesture
                        dragging = true
                        val lifted = horizontalDrag(start.id) { change ->
                            tracker.addPointerInputChange(change)
                            drag(change.positionChange().x)
                            change.consume()
                        }
                        dragging = false
                        release(tracker.calculateVelocity().x, cancelled = !lifted)
                    } catch (e: CancellationException) {
                        // The system took the touch (a back gesture, say): put the row back.
                        if (dragging) release(0f, cancelled = true)
                        throw e
                    }
                }
            }
    ) {
        if (side != 0 && !entering) {
            Bin(
                armed = armed,
                progress = { (abs(offset) / threshold).coerceIn(0f, 1f) },
                modifier = Modifier
                    .align(if (side < 0) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
                    .padding(horizontal = 24.dp),
            )
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset }
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}

/** The bin behind a row: grey and small while it's uncovered, red once letting go would delete. */
@Composable
private fun Bin(armed: Boolean, progress: () -> Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (armed) scheme.error else scheme.surfaceContainer, tween(140), label = "bin")
    val tint by animateColorAsState(if (armed) scheme.onError else scheme.onSurfaceVariant, tween(140), label = "bin-icon")
    val pop by animateFloatAsState(
        if (armed) 1.1f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "bin-pop",
    )
    Box(
        modifier
            .size(44.dp)
            .graphicsLayer {
                val p = progress()
                val s = (0.55f + 0.45f * p) * pop
                scaleX = s
                scaleY = s
                alpha = (p * 1.6f).coerceAtMost(1f)
            }
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DeleteOutline, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
