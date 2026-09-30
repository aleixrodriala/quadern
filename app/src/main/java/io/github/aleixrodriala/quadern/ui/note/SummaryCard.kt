package io.github.aleixrodriala.quadern.ui.note

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.github.aleixrodriala.quadern.data.InsightsStatus
import io.github.aleixrodriala.quadern.data.NoteWithProgress
import io.github.aleixrodriala.quadern.ui.components.Motion
import io.github.aleixrodriala.quadern.ui.components.SkeletonLines
import io.github.aleixrodriala.quadern.ui.components.appearStaggered
import io.github.aleixrodriala.quadern.ui.components.pressScale
import io.github.aleixrodriala.quadern.ui.components.shimmer

/** What the summary card shows; each state fades through to the next. */
private sealed interface SummaryUi {
    /** [short]: the note is too short for a summary, so only its tags are coming. */
    data class Writing(val short: Boolean) : SummaryUi
    data object Retrying : SummaryUi
    data class Content(val summary: String, val tags: List<String>) : SummaryUi
    data object Failed : SummaryUi
    data object NeedsSetup : SummaryUi
}

private fun summaryUiOf(n: NoteWithProgress): SummaryUi? = when (n.insightsStatus) {
    InsightsStatus.PENDING -> if (n.insightsAttempts > 0) SummaryUi.Retrying
        else SummaryUi.Writing(short = !io.github.aleixrodriala.quadern.insights.InsightsPrompt.wantsSummary(n.transcript))
    InsightsStatus.DONE -> if (n.summary.isBlank() && n.tagList.isEmpty()) null else SummaryUi.Content(n.summary, n.tagList)
    InsightsStatus.FAILED -> SummaryUi.Failed
    InsightsStatus.BLOCKED -> SummaryUi.NeedsSetup
    InsightsStatus.NONE -> null
}

/**
 * The note at a glance: a short summary and its tags, in a soft card under the title. While the
 * summary is being written the card holds its place with shimmering lines, then the text fades in
 * and the card grows to fit.
 */
@Composable
fun SummaryCard(
    note: NoteWithProgress,
    editing: Boolean,
    onTag: (String) -> Unit,
    onRetry: () -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = if (editing) null else summaryUiOf(note)
    // Keep the last state while the card collapses, so it doesn't go blank mid-animation.
    val last = remember { arrayOfNulls<SummaryUi>(2) }
    if (ui != null) last[0] = ui
    if (ui != null && ui != SummaryUi.NeedsSetup) last[1] = ui
    AnimatedVisibility(
        visible = ui != null,
        enter = fadeIn(tween(240, delayMillis = 120)) + expandVertically(Motion.resize(), expandFrom = Alignment.Top),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(300, easing = Motion.Standard), shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        val shown = ui ?: last[0] ?: return@AnimatedVisibility
        AnimatedContent(
            targetState = shown == SummaryUi.NeedsSetup,
            transitionSpec = { fadeIn(tween(220, delayMillis = 80)) togetherWith fadeOut(tween(110)) },
            label = "summary-setup",
        ) { needsSetup ->
            val cardState = (if (shown == SummaryUi.NeedsSetup) last[1] else shown) ?: SummaryUi.Writing(short = false)
            // A very short note has tags but no summary: show just the chips, no card around them,
            // and while they're being written, just the chips' outlines.
            val bare = (cardState is SummaryUi.Content && cardState.summary.isBlank()) || (cardState is SummaryUi.Writing && cardState.short)
            val cardColor by animateColorAsState(
                if (bare) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0f) else MaterialTheme.colorScheme.surfaceContainer,
                tween(300), label = "card-color",
            )
            val padH by animateDpAsState(if (bare) 0.dp else 20.dp, Motion.resize(), label = "card-pad-h")
            val padV by animateDpAsState(if (bare) 0.dp else 18.dp, Motion.resize(), label = "card-pad-v")
            if (needsSetup) SetUpHint(onSetUp) else Surface(
                color = cardColor,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = cardState,
                    contentKey = { it::class },
                    // The placeholder clears, the card eases to its new height, then the text fades in.
                    transitionSpec = {
                        (fadeIn(tween(280, delayMillis = 170, easing = LinearEasing)) togetherWith fadeOut(tween(120, easing = LinearEasing)))
                            .using(SizeTransform(clip = false) { _, _ -> Motion.resize() })
                    },
                    label = "summary",
                ) { state ->
                    Column(Modifier.padding(horizontal = padH, vertical = padV)) {
                        when (state) {
                            is SummaryUi.Writing -> if (state.short) WritingTags() else Writing()
                            is SummaryUi.Content -> Content(state, onTag, onCard = !bare)
                            SummaryUi.Retrying -> Problem("Couldn't reach the service. It will try again on its own.", "Try now", onRetry)
                            SummaryUi.Failed -> Problem("Couldn't write a summary.", "Try again", onRetry)
                            SummaryUi.NeedsSetup -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Writing() {
    val pulse = rememberInfiniteTransition(label = "writing")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "sparkle")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.AutoAwesome, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp).alpha(a),
        )
        Spacer(Modifier.width(8.dp))
        Text("Writing summary", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    SkeletonLines()
    Spacer(Modifier.height(6.dp))
}

/** Placeholders shaped like the one or two tags on their way. */
@Composable
private fun WritingTags() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in listOf(92.dp, 74.dp)) {
            Box(Modifier.width(w).height(34.dp).shimmer(RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun Content(state: SummaryUi.Content, onTag: (String) -> Unit, onCard: Boolean) {
    if (state.summary.isNotBlank()) {
        SelectionContainer {
            Text(state.summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (state.tags.isNotEmpty()) {
        if (state.summary.isNotBlank()) Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tags.forEachIndexed { i, tag ->
                TagChip(tag, onClick = { onTag(tag) }, modifier = Modifier.appearStaggered(i + 2), onCard = onCard)
            }
        }
    }
}

@Composable
private fun Problem(message: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SetUpHint(onSetUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Summaries need a service that can write them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSetUp) { Text("Set up") }
    }
}

/** A topic tag; tapping it lists every note with the same tag. */
@Composable
fun TagChip(tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, onCard: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shape = RoundedCornerShape(50),
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            onCard -> MaterialTheme.colorScheme.background // stands out on the grey card
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction),
    ) {
        Row(
            Modifier.animateContentSize(Motion.resize()).padding(start = 14.dp, end = if (selected) 10.dp else 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tag.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
            }
        }
    }
}
