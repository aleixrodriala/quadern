package io.github.aleixrodriala.quadern.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aleixrodriala.quadern.audio.AudioSpec

/** The round, softly filled icon button used across the app (Voicenotes-style). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        contentColor = content,
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction, pressed = 0.9f).size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * Live waveform, the centrepiece of the recorder. [levels] are 0..255, the last one being number
 * [levelCount] - 1. Bars enter at the right edge and spring up to their level as they flow left,
 * fading out toward the left. In silence the baseline breathes with a slow ripple; when not
 * [active] (paused) everything holds still.
 *
 * Levels arrive every 100 ms, often in bursts, so nothing jumps when one arrives: every frame the
 * scroll advances at real-time pace and eases toward the newest level.
 */
@Composable
fun LiveWaveform(
    levels: List<Int>,
    levelCount: Int,
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
    barWidth: Dp = 4.dp,
    gap: Dp = 4.dp,
) {
    val latestLevels by rememberUpdatedState(levels)
    val latestCount by rememberUpdatedState(levelCount)
    val isActive by rememberUpdatedState(active)
    // Scroll position in levels: bar i appears at the right edge once head passes i.
    val head = remember { mutableFloatStateOf(levelCount.toFloat()) }
    // Seconds of breathing so far; stops while paused so the ripple freezes with the bars.
    val breath = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000f).coerceAtMost(100f)
                last = now
                val target = latestCount.toFloat()
                var p = head.floatValue + dt / AudioSpec.LEVEL_INTERVAL_MS
                // Behind after a burst (or a stall): catch up smoothly instead of jumping.
                val lag = target - p
                if (lag > 1f) p += (lag - 1f) * (dt / 150f).coerceAtMost(1f)
                // Never ahead of the newest level: paused or late levels just hold still.
                head.floatValue = p.coerceAtMost(target)
                if (isActive) breath.floatValue += dt / 1000f
            }
        }
    }
    Canvas(modifier) {
        // State is read only here, so each frame is just a redraw.
        val p = head.floatValue
        val t = breath.floatValue
        val bw = barWidth.toPx()
        val step = bw + gap.toPx()
        val minH = bw
        // Headroom for the spring's overshoot.
        val maxH = size.height * 0.86f
        val ripple = bw * 2f
        val fadeWidth = size.width * 0.45f
        val list = latestLevels
        val count = latestCount
        val first = count - list.size
        var i = kotlin.math.ceil(p).toInt() - 1
        while (true) {
            val x = size.width - (p - i) * step
            if (x + bw < 0f) break
            val alpha = (x / fadeWidth).coerceIn(0f, 1f).let { it * it }
            // Two slow waves drifting across the screen: what silence looks like while recording.
            val sx = x / step
            val wave = 0.5f + 0.3f * kotlin.math.sin(sx * 0.28f + t * 2.2f) + 0.2f * kotlin.math.sin(sx * 0.11f - t * 1.3f)
            val calm = minH + ripple * wave
            val h = if (i < first || i >= count) {
                calm
            } else {
                // Springs up over ~300 ms as it flows in, overshooting a touch before settling.
                val grow = springIn(((p - i) / 3f).coerceIn(0f, 1f))
                val level = minH + (maxH - minH) * shape(list[i - first] / 255f)
                maxOf(calm, level * grow).coerceAtMost(size.height)
            }
            drawRoundRect(color.copy(alpha = color.alpha * alpha), Offset(x, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2))
            i--
        }
    }
}

/** Ease-out with a small overshoot: 0 → ~1.08 → 1. */
private fun springIn(x: Float): Float {
    val c = 1.2f
    val u = x - 1f
    return 1f + (c + 1f) * u * u * u + c * u * u
}

/**
 * Static waveform of a whole note with a played/unplayed split; tap or drag to seek.
 * [progress] and the seek callback are 0..1.
 */
@Composable
fun WaveformScrubber(
    levels: ByteArray,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    played: Color = MaterialTheme.colorScheme.primary,
    unplayed: Color = MaterialTheme.colorScheme.outline,
) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownProgress = dragging ?: progress
    Canvas(
        modifier
            .clip(MaterialTheme.shapes.small)
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging?.let(onSeek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { change, _ -> dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        width = size.width
        val gap = 2.dp.toPx()
        val barW = 3.dp.toPx()
        val count = ((size.width + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val minH = 2.dp.toPx()
        for (i in 0 until count) {
            // Peak of the slice of levels under this bar.
            val from = (i.toLong() * levels.size / count).toInt()
            val to = (((i + 1).toLong() * levels.size) / count).toInt().coerceAtLeast(from + 1).coerceAtMost(levels.size)
            var peak = 0
            for (j in from until to) peak = maxOf(peak, levels[j].toInt() and 0xFF)
            val norm = if (levels.isEmpty()) 0f else peak / 255f
            val h = (minH + (size.height - minH) * shape(norm)).coerceAtMost(size.height)
            val x = i * (barW + gap)
            val color = if (x / size.width <= shownProgress) played else unplayed
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

/** Level (0..1 over -60..0 dBFS) to bar height: room noise stays flat, speech fills the bar. */
private fun shape(norm: Float): Float = (((norm - 0.2f) / 0.65f).coerceIn(0f, 1f)).let { it * kotlin.math.sqrt(it) }

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
