package io.github.aleixrodriala.quadern.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * What the swipeable rows of one list share. The hairline between two rows steps aside while
 * either of them is lifted, and the list holds off its own item animations while a row opens or
 * closes its space, so the rows below follow it exactly instead of trailing behind.
 */
@Stable
class SwipeRows {
    /** A row as its neighbours and its successor see it. */
    internal class Row(
        /** How far off the list it is, 0 to 1: the hairlines by it fade by this much. */
        val raised: () -> Float,
        /** How much of its height it takes up, 0 to 1. */
        val space: () -> Float,
        /** The card's shift, in px. */
        val offset: () -> Float,
    )

    private val rows = mutableStateMapOf<Any, Row>()
    private var resizes by mutableIntStateOf(0)

    /** A row is opening or closing its space: the list's items shouldn't start placement animations. */
    val resizing: Boolean get() = resizes > 0

    internal fun raisedOf(key: Any?): Float = key?.let { rows[it] }?.raised?.invoke() ?: 0f
    internal fun rowOf(key: Any): Row? = rows[key]
    internal fun register(key: Any, row: Row) { rows[key] = row }
    internal fun unregister(key: Any, row: Row) { if (rows[key] === row) rows.remove(key) }
    internal fun resizeStarted() { resizes++ }
    internal fun resizeEnded() { resizes-- }
}

/**
 * A list row, with the hairline under it, that can be swiped away to either side.
 *
 * Dragged, the row lifts off the list as a rounded card and a track opens in the space it leaves:
 * a dot, then a circle with a bin, then a pill as long as the gap. The card trails the finger a
 * little, as if held; at the threshold it snaps free with a tick, the track turns red and the bin
 * moves up to the card's edge. Let go there (or flick the card) and it flies off, [onDelete] runs
 * with the side it left by (-1 left, 1 right), and the space closes; [onGone] runs once it has, and
 * the list should drop the row then. Short of the threshold the card springs back and lies flat.
 *
 * [content] gets a `dismiss` that does the same from code: give it to a "Delete" custom action for
 * accessibility services. [enterFrom] brings a row in from that side as it appears, opening its
 * space first: the way back for one that was undone. [key] and [nextKey] (the row below) let the
 * hairlines between lifted rows step aside.
 */
@Composable
fun SwipeToDelete(
    key: Any,
    rows: SwipeRows,
    onDelete: (side: Int) -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    nextKey: Any? = null,
    enabled: Boolean = true,
    enterFrom: Int = 0,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestOnDelete by rememberUpdatedState(onDelete)
    val latestOnGone by rememberUpdatedState(onGone)
    val scheme = MaterialTheme.colorScheme
    val page = scheme.background
    val card = scheme.surfaceContainer
    val track = scheme.surfaceContainerHighest
    val alert = scheme.error
    val icon = scheme.onSurfaceVariant
    val onAlert = scheme.onError
    val hairline = scheme.outlineVariant
    val outlineBin = rememberVectorPainter(Icons.Rounded.DeleteOutline)
    val filledBin = rememberVectorPainter(Icons.Rounded.Delete)

    val held = remember { Held() }
    // Brought back while the row taking it away is still on screen: carry on from where that one is.
    val from = remember { if (enterFrom != 0) rows.rowOf(key)?.let { it.space() to it.offset() } else null }
    var width by remember { mutableIntStateOf(0) }
    // Horizontal shift of the card, in px. Far out of the way until an entering row knows its width.
    var offset by remember { mutableFloatStateOf(from?.second ?: if (enterFrom != 0) enterFrom * 100_000f else 0f) }
    // 0 flat in the list, 1 a rounded card off it.
    val lift = remember { Animatable(if (enterFrom != 0) 1f else 0f) }
    // How much of its height the row takes up: 0 while its space is closed.
    val space = remember { Animatable(if (enterFrom != 0) from?.first ?: 0f else 1f) }
    val bump = remember { Animatable(1f) }
    var armed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(enterFrom != 0) }
    val armedness by animateFloatAsState(if (armed) 1f else 0f, tween(140), label = "armed")
    val follow by animateFloatAsState(if (armed) 1f else 0f, spring(dampingRatio = 0.72f, stiffness = 520f), label = "follow")
    // Read through state: the gesture handler outlives the composition that created it.
    val threshold by remember(density) { derivedStateOf { minOf(width * 0.35f, with(density) { 160.dp.toPx() }).coerceAtLeast(1f) } }
    val flickVelocity by rememberUpdatedState(with(density) { 900.dp.toPx() })
    val hysteresis = with(density) { 12.dp.toPx() }
    val landing = with(density) { 12.dp.toPx() }
    val catchable = with(density) { 4.dp.toPx() }

    // Where the card sits for a finger at r: trailing a little more the nearer it gets to the
    // threshold, like something held back, until it snaps free there.
    fun pull(r: Float): Float {
        val t = (abs(r) / threshold).coerceAtMost(1f)
        return r * (1f - TENSION * t * t)
    }

    // The finger position that puts the card at x, so catching a moving card doesn't move it.
    fun unpull(x: Float): Float {
        var r = x
        repeat(4) {
            val t = (abs(r) / threshold).coerceAtMost(1f)
            val slope = if (t < 1f) 1f - 3f * TENSION * t * t else 1f - TENSION
            r -= (pull(r) - x) / slope
        }
        return r
    }

    fun place() {
        offset = lerp(pull(held.raw), held.raw, held.snap)
    }

    fun arm(on: Boolean, feel: Boolean = true) {
        if (on == armed) return
        armed = on
        if (feel) haptics.performHapticFeedback(if (on) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.SegmentFrequentTick)
        if (on) scope.launch { bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 800f), initialVelocity = 5f) }
        held.snapJob?.cancel()
        if (held.dragging) {
            held.snapJob = scope.launch {
                animate(held.snap, if (on) 1f else 0f, animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f)) { v, _ ->
                    held.snap = v
                    if (held.dragging) place()
                }
            }
        }
    }

    fun drag(delta: Float) {
        held.raw += delta
        val r = abs(held.raw)
        if (r >= threshold) arm(true) else if (r < threshold - hysteresis) arm(false)
        place()
    }

    fun grab() {
        held.settle?.cancel()
        held.snapJob?.cancel()
        held.dragging = true
        // Only a card on its way back can be caught, and those are never armed.
        held.snap = 0f
        held.raw = unpull(offset)
        held.liftJob?.cancel()
        held.liftJob = scope.launch { lift.animateTo(1f, spring(stiffness = 900f)) }
    }

    suspend fun exit(side: Float, velocity: Float, w: Float) {
        val remaining = w - abs(offset)
        if (remaining < 1f) {
            offset = side * w
            return
        }
        // Leaves at the finger's speed and speeds up from there: no stall after a flick, and no
        // slow tail once the card's text is already gone.
        val speed = if (sign(velocity) == side) abs(velocity) else 0f
        val seconds = if (speed * 0.2f >= remaining) remaining / speed else 0.2f
        val start = (0.4f * speed * seconds / remaining).coerceAtMost(0.4f)
        val easing = if (seconds < 0.2f) LinearEasing else CubicBezierEasing(0.4f, start, 1f, 1f)
        animate(offset, side * w, animationSpec = tween((seconds * 1000).toInt().coerceAtLeast(60), easing = easing)) { v, _ -> offset = v }
    }

    fun leave(side: Float, velocity: Float, feel: Boolean = true) {
        if (leaving) return
        leaving = true
        held.dragging = false
        held.snapJob?.cancel()
        arm(true, feel)
        held.settle?.cancel()
        held.settle = scope.launch {
            var told = false
            var resizing = false
            fun tell() {
                if (!told) {
                    told = true
                    latestOnDelete(side.toInt())
                }
            }
            try {
                if (lift.value < 1f && held.liftJob?.isActive != true) launch { lift.animateTo(1f, spring(stiffness = 900f)) }
                val w = width.toFloat()
                if (w > 0f) {
                    coroutineScope {
                        launch { exit(side, velocity, w) }
                        // The space starts closing while the card is still on its way: its text is
                        // off the screen well before its edge is.
                        snapshotFlow { abs(offset) >= w * 0.7f }.first { it }
                        tell()
                        rows.resizeStarted()
                        resizing = true
                        space.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
                    }
                }
            } finally {
                // Let go past the threshold means delete, even if the row is disposed on the way.
                tell()
                if (resizing) rows.resizeEnded()
                latestOnGone()
            }
        }
    }

    /** The finger let go; [cancelled] when something else took the gesture, which never deletes. */
    fun release(velocity: Float, cancelled: Boolean = false) {
        held.dragging = false
        held.snapJob?.cancel()
        val side = sign(offset)
        val flick = abs(velocity) > flickVelocity
        val out = !cancelled && side != 0f && if (flick) sign(velocity) == side else armed
        if (out) {
            leave(side, velocity)
            return
        }
        arm(false, feel = false)
        held.settle = scope.launch {
            held.settling = true
            try {
                coroutineScope {
                    launch {
                        // Lies flat again as it lands.
                        snapshotFlow { abs(offset) < landing }.first { it }
                        lift.animateTo(0f, spring(stiffness = 500f))
                    }
                    animate(offset, 0f, velocity, spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = 0.5f)) { v, _ -> offset = v }
                }
            } finally {
                held.settling = false
            }
        }
    }

    val dismiss: () -> Unit = {
        // One on its way back goes once it's there.
        if (entering) held.dismissQueued = true else leave(-1f, 0f, feel = false)
    }

    if (entering) {
        LaunchedEffect(Unit) {
            val w = snapshotFlow { width }.first { it > 0 }
            if (from == null) offset = enterFrom * w.toFloat()
            rows.resizeStarted()
            var resizing = true
            try {
                coroutineScope {
                    launch {
                        val open = space.value
                        space.animateTo(1f, tween((340 * (1f - open)).roundToInt().coerceAtLeast(120), easing = FastOutSlowInEasing))
                        rows.resizeEnded()
                        resizing = false
                    }
                    // Into the space as it opens, from the side it left by; straight back if it
                    // never quite left.
                    if (from == null) delay(110)
                    launch {
                        snapshotFlow { abs(offset) < landing }.first { it }
                        lift.animateTo(0f, spring(stiffness = 500f))
                    }
                    animate(offset, 0f, 0f, spring(dampingRatio = 0.86f, stiffness = 380f, visibilityThreshold = 0.5f)) { v, _ -> offset = v }
                }
            } finally {
                if (resizing) rows.resizeEnded()
                entering = false
            }
            if (held.dismissQueued) leave(-1f, 0f, feel = false)
        }
    }

    val row = remember {
        SwipeRows.Row(
            raised = { min(lift.value, space.value) },
            space = { space.value },
            offset = { offset },
        )
    }
    DisposableEffect(rows, key) {
        rows.register(key, row)
        onDispose { rows.unregister(key, row) }
    }

    val canDrag = enabled && !leaving && !entering
    Box(
        modifier
            .onSizeChanged { width = it.width }
            .graphicsLayer { clip = space.value < 1f }
            .drawBehind {
                // The track, in the space the card leaves.
                val o = offset
                if (entering || o == 0f) return@drawBehind
                val w = size.width
                val h = size.height
                // Closing in from both ends as the space goes.
                val squeeze = if (leaving) (1f - space.value) * 16.dp.toPx() else 0f
                val outer = EdgeInset.toPx() + squeeze
                val inner = min(abs(o) - Gap.toPx(), w - EdgeInset.toPx()) - squeeze
                val tw = inner - outer
                if (tw <= 0f) return@drawBehind
                // A circle until it's as tall as the card, then a pill.
                val th = min(h - 2f * CardInset.toPx() * lift.value, tw)
                if (th <= 0f) return@drawBehind
                val right = o < 0f
                val left = if (right) w - inner else outer
                drawRoundRect(
                    lerp(track, alert, armedness),
                    topLeft = Offset(left, (h - th) / 2f),
                    size = Size(tw, th),
                    cornerRadius = CornerRadius(min(th / 2f, CardRadius.toPx())),
                    alpha = (th / 12.dp.toPx()).coerceAtMost(1f),
                )
                // The bin, once there's room for it: near the edge, or by the card while armed,
                // never past the middle.
                val fit = ((th - 16.dp.toPx()) / 32.dp.toPx()).coerceIn(0f, 1f)
                if (fit > 0f) {
                    val rest = min(IconAnchor.toPx(), outer + tw / 2f)
                    val at = min(lerp(rest, max(rest, inner - IconFollow.toPx()), follow), w / 2f)
                    val s = IconSize.toPx()
                    translate(left = (if (right) w - at else at) - s / 2f, top = (h - s) / 2f) {
                        scale((0.6f + 0.4f * fit) * bump.value, pivot = Offset(s / 2f, s / 2f)) {
                            with(if (armed) filledBin else outlineBin) {
                                draw(Size(s, s), alpha = fit, colorFilter = ColorFilter.tint(lerp(icon, onAlert, armedness)))
                            }
                        }
                    }
                }
            }
            .drawWithContent {
                drawContent()
                // The hairline under the row, out of the way while this row or the next is lifted.
                val hidden = max(if (leaving) 1f else lift.value, rows.raisedOf(nextKey))
                if (hidden < 1f) {
                    val t = 1.dp.toPx()
                    val inset = 24.dp.toPx()
                    drawRect(hairline, Offset(inset, size.height - t), Size(size.width - 2f * inset, t), alpha = 1f - hidden)
                }
            }
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, (p.height * space.value).roundToInt()) { p.place(0, 0) }
            }
            // On the row's still frame, not the moving card, so positions and speed are the finger's.
            .pointerInput(canDrag) {
                if (!canDrag) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var dragging = false
                    try {
                        val start = if (held.settling && abs(offset) > catchable) {
                            // Catching a card on its way back takes it straight away.
                            grab()
                            down
                        } else {
                            awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                                change.consume()
                                grab()
                                drag(over)
                            }
                        } ?: return@awaitEachGesture
                        dragging = true
                        val ended = horizontalDrag(start.id) { change ->
                            tracker.addPointerInputChange(change)
                            drag(change.positionChange().x)
                            change.consume()
                        }
                        dragging = false
                        release(tracker.calculateVelocity().x, cancelled = !ended)
                    } catch (e: CancellationException) {
                        // The system took the touch (a back gesture, say): put the card back.
                        if (dragging) release(0f, cancelled = true)
                        throw e
                    }
                }
            }
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset
                    val l = lift.value
                    clip = l > 0f
                    shape = if (l > 0f) Lifted(l * CardRadius.toPx(), l * CardInset.toPx()) else RectangleShape
                }
                .drawBehind { drawRect(lerp(page, card, lift.value)) }
        ) {
            content(dismiss)
        }
    }
}

/** Gesture bookkeeping that nothing draws from. */
private class Held {
    var raw = 0f // where the finger has taken the card, before the pull
    var snap = 0f // 0 held back, 1 snapped free
    var dragging = false
    var settling = false
    var dismissQueued = false
    var settle: Job? = null
    var snapJob: Job? = null
    var liftJob: Job? = null
}

/** The lifted card: rounded, and a little shorter than its row so it reads as off the list. */
private class Lifted(private val radius: Float, private val inset: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(0f, inset, size.width, size.height - inset, CornerRadius(radius)))
}

private const val TENSION = 0.12f
private val CardRadius = 24.dp
private val CardInset = 3.dp // top and bottom, once lifted
private val EdgeInset = 6.dp // between the track and the screen's edge
private val Gap = 6.dp // between the track and the card
private val IconSize = 24.dp
private val IconAnchor = 40.dp // the bin's centre from the edge, once the track is long enough
private val IconFollow = 34.dp // the bin's centre from the track's inner end, while armed
