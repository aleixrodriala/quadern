package io.github.aleixrodriala.noteai.ui.record

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aleixrodriala.noteai.ui.LocalContainer
import io.github.aleixrodriala.noteai.ui.components.CircleIconButton
import io.github.aleixrodriala.noteai.ui.components.Dot
import io.github.aleixrodriala.noteai.ui.components.LiveWaveform
import io.github.aleixrodriala.noteai.ui.theme.LocalExtraColors
import io.github.aleixrodriala.noteai.ui.theme.TabularNumbers
import io.github.aleixrodriala.noteai.util.formatDuration

/**
 * Full-screen recorder. Leaving this screen doesn't stop anything; the recording lives in the
 * foreground service and shows as a pill on the home screen and in the notification.
 */
@Composable
fun RecordScreen(onMinimize: () -> Unit, onFinished: (String) -> Unit) {
    val c = LocalContainer.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val live by c.recording.live.collectAsState()
    val starting by c.recording.starting.collectAsState()
    val extra = LocalExtraColors.current
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        c.recording.events.collect { e ->
            when (e) {
                is io.github.aleixrodriala.noteai.recording.RecordingController.Event.Finished -> onFinished(e.noteId)
                is io.github.aleixrodriala.noteai.recording.RecordingController.Event.Failed ->
                    if (e.noteId != null) onFinished(e.noteId) else onMinimize()
            }
        }
    }
    // Nothing is recording (stopped from the notification, or discarded): leave.
    LaunchedEffect(live == null, starting) {
        if (live == null && !starting) {
            kotlinx.coroutines.delay(400)
            if (c.recording.live.value == null && !c.recording.starting.value) onMinimize()
        }
    }

    val state = live
    val paused = state?.paused == true
    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            CircleIconButton(Icons.Rounded.KeyboardArrowDown, "Minimize", onMinimize)
        }
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(
                if (paused) MaterialTheme.colorScheme.outline else extra.record,
                modifier = Modifier.alpha(if (paused) 1f else dotAlpha),
                size = 10.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    state == null -> "Starting…"
                    state.silenced -> "Microphone in use by another app"
                    paused -> "Paused"
                    else -> "Recording"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (state?.silenced == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            formatDuration(state?.elapsedMs ?: 0),
            style = MaterialTheme.typography.displayLarge.merge(TabularNumbers).copy(fontSize = 72.sp, fontWeight = FontWeight.Light),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
        val waveColor by animateColorAsState(
            if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            tween(300),
            label = "wave color",
        )
        LiveWaveform(
            levels = state?.levels.orEmpty(),
            levelCount = state?.levelCount ?: 0,
            color = waveColor,
            active = state != null && !paused,
            // Edge to edge, past the screen padding: bars flow in from the edge and out of it.
            modifier = Modifier.fillMaxWidth().height(200.dp).layout { m, c ->
                val bleed = 24.dp.roundToPx() * 2
                val pl = m.measure(c.copy(minWidth = c.maxWidth + bleed, maxWidth = c.maxWidth + bleed))
                layout(c.maxWidth, pl.height) { pl.place(-bleed / 2, 0) }
            },
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Keeps recording with the screen off or the app closed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                Icons.Rounded.DeleteOutline, "Discard", { confirmDiscard = true },
                size = 60.dp, iconSize = 26.dp, enabled = state != null,
            )
            CircleIconButton(
                if (paused) Icons.Rounded.Mic else Icons.Rounded.Pause,
                if (paused) "Resume" else "Pause",
                {
                    haptics.performHapticFeedback(
                        if (paused) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                        else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                    )
                    if (paused) c.recording.resume() else c.recording.pause()
                },
                size = 76.dp, iconSize = 32.dp, enabled = state != null,
            )
            CircleIconButton(
                Icons.Rounded.Check, "Stop and save", {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                    c.recording.stop()
                },
                size = 60.dp, iconSize = 28.dp, enabled = state != null,
                container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard recording?") },
            text = { Text("This recording will be deleted and can't be recovered.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                    c.recording.discard()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep recording") } },
        )
    }
}
