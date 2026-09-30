package io.github.aleixrodriala.quadern.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Motion tokens, so every screen moves the same way. */
object Motion {
    /** Things arriving: fast start, long gentle settle. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Things leaving: quick and out of the way. */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Size changes that move the rest of the screen: smooth at both ends, no jump at the start. */
    fun <T> resize() = tween<T>(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)

    /**
     * Fade-through for text that changes in place (a title arriving, a status moving on): the old
     * text is fully gone before the new one rises a few pixels into place, so they never overlap,
     * while the size follows smoothly.
     */
    fun <S> AnimatedContentTransitionScope<S>.textSwap(rise: Boolean = true): ContentTransform {
        val fadeInSpec = fadeIn(tween(240, delayMillis = 110, easing = LinearEasing))
        val enter = if (rise) fadeInSpec + slideInVertically(tween(420, delayMillis = 110, easing = EmphasizedDecelerate)) { it / 5 } else fadeInSpec
        return (enter togetherWith fadeOut(tween(100, easing = LinearEasing)))
            .using(SizeTransform(clip = false) { _, _ -> resize() })
    }
}

/** A soft highlight sweeping across a placeholder, while something is being written. */
@Composable
fun Modifier.shimmer(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    // Read in the draw phase only: the sweep redraws without recomposing anything.
    return clip(shape).drawBehind {
        drawRect(base)
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(highlight.copy(alpha = 0f), highlight, highlight.copy(alpha = 0f)),
                start = Offset(w * x - w * 0.35f, 0f),
                end = Offset(w * x + w * 0.35f, 0f),
            )
        )
    }
}

/** Placeholder lines shaped like a paragraph of body text. */
@Composable
fun SkeletonLines(widths: List<Float> = listOf(1f, 0.93f, 0.58f), lineHeight: Dp = 12.dp, gap: Dp = 14.dp) {
    val shape = MaterialTheme.shapes.small
    Column {
        widths.forEachIndexed { i, w ->
            if (i > 0) Spacer(Modifier.height(gap))
            Box(Modifier.fillMaxWidth(w).height(lineHeight).shimmer(shape))
        }
    }
}

/** Presses sink in a little, like a real button. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.94f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Fades and lifts an item in when it first appears, [index] steps later than the first one. */
@Composable
fun Modifier.appearStaggered(index: Int, stepMillis: Int = 45): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(320, delayMillis = index * stepMillis, easing = Motion.EmphasizedDecelerate))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 6.dp.toPx()
    }
}
