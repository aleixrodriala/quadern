package io.github.aleixrodriala.quadern.ui.record

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HeadsetMic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aleixrodriala.quadern.audio.Microphones
import io.github.aleixrodriala.quadern.ui.LocalContainer
import io.github.aleixrodriala.quadern.ui.components.CircleIconButton
import io.github.aleixrodriala.quadern.ui.components.Dot
import io.github.aleixrodriala.quadern.ui.components.LiveWaveform
import io.github.aleixrodriala.quadern.ui.components.Motion.textSwap
import io.github.aleixrodriala.quadern.ui.components.pressScale
import io.github.aleixrodriala.quadern.ui.theme.LocalExtraColors
import io.github.aleixrodriala.quadern.ui.theme.TabularNumbers
import io.github.aleixrodriala.quadern.util.formatDuration
import kotlinx.coroutines.launch

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
    val finishing by c.recording.finishing.collectAsState()
    // After Stop, the screen holds its last frame while the note is saved, then leaves once.
    val lastLive = remember { arrayOfNulls<io.github.aleixrodriala.quadern.recording.RecordingController.Live>(1) }
    live?.let { lastLive[0] = it }
    var left by remember { mutableStateOf(false) }
    val extra = LocalExtraColors.current
    var confirmDiscard by remember { mutableStateOf(false) }
    val mics by remember { c.microphones.available }.collectAsState(initial = emptyList())
    var pickingMic by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        c.recording.events.collect { e ->
            if (left) return@collect
            left = true
            when (e) {
                is io.github.aleixrodriala.quadern.recording.RecordingController.Event.Finished -> onFinished(e.noteId)
                is io.github.aleixrodriala.quadern.recording.RecordingController.Event.Failed ->
                    if (e.noteId != null) onFinished(e.noteId) else onMinimize()
            }
        }
    }
    // Nothing is recording (stopped from the notification, or discarded): leave.
    LaunchedEffect(live == null, starting, finishing) {
        if (live == null && !starting && !finishing && !left) {
            kotlinx.coroutines.delay(400)
            val r = c.recording
            if (!left && r.live.value == null && !r.starting.value && !r.finishing.value) {
                left = true
                onMinimize()
            }
        }
    }

    val state = live ?: lastLive[0].takeIf { finishing || left }
    // Saving is instant for most notes; say so only when it isn't (a long recording being packed).
    var slowSave by remember { mutableStateOf(false) }
    LaunchedEffect(finishing) {
        if (finishing) {
            kotlinx.coroutines.delay(600)
            slowSave = true
        }
    }
    val saving = live == null && state != null
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
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.Rounded.KeyboardArrowDown, "Minimize", onMinimize)
            Spacer(Modifier.weight(1f))
            // Only when there's a choice to make: a headset or another microphone is connected.
            val mic = live?.let { it.micConnecting ?: it.mic }
            var lastMic by remember { mutableStateOf(mic) }
            if (mic != null) lastMic = mic
            AnimatedVisibility(
                visible = mic != null && (mics.size > 1 || mic.kind != Microphones.Kind.PHONE) && !finishing,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                lastMic?.let { MicChip(it, connecting = live?.micConnecting != null) { pickingMic = true } }
            }
        }
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(
                if (paused) MaterialTheme.colorScheme.outline else extra.record,
                modifier = Modifier.alpha(if (paused || saving) 1f else dotAlpha),
                size = 10.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    state == null -> "Starting…"
                    saving && slowSave -> "Saving…"
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
            active = live != null && !paused,
            // Edge to edge, past the screen padding: bars flow in from the edge and out of it.
            modifier = Modifier.fillMaxWidth().height(200.dp).layout { m, c ->
                val bleed = 24.dp.roundToPx() * 2
                val pl = m.measure(c.copy(minWidth = c.maxWidth + bleed, maxWidth = c.maxWidth + bleed))
                layout(c.maxWidth, pl.height) { pl.place(-bleed / 2, 0) }
            },
        )
        Spacer(Modifier.height(20.dp))
        // A picked microphone that couldn't be used: say what's recording instead.
        val unavailable = state?.let { st ->
            st.micUnavailable?.let { gone -> "${gone.name} isn't available. Recording with ${st.mic?.inSentence ?: "the phone"}." }
        }
        AnimatedContent(
            targetState = unavailable,
            transitionSpec = { textSwap(rise = false) },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            label = "mic notice",
        ) { notice ->
            Text(
                notice ?: "Keeps recording with the screen off or the app closed.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(
                Icons.Rounded.DeleteOutline, "Discard", { confirmDiscard = true },
                size = 60.dp, iconSize = 26.dp, enabled = live != null && !finishing,
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
                size = 76.dp, iconSize = 32.dp, enabled = live != null && !finishing,
            )
            CircleIconButton(
                Icons.Rounded.Check, "Stop and save", {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                    c.recording.stop()
                },
                size = 60.dp, iconSize = 28.dp, enabled = live != null && !finishing,
                container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    if (pickingMic) {
        MicSheet(
            mics = mics,
            current = live?.let { it.micConnecting ?: it.mic },
            onDismiss = { pickingMic = false },
            onPick = {
                pickingMic = false
                c.recording.selectMic(it.key)
            },
        )
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

/** Which microphone is recording; tap to pick another. */
@Composable
private fun MicChip(mic: Microphones.Mic, connecting: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        interactionSource = interaction,
        modifier = Modifier
            .pressScale(interaction)
            .height(44.dp)
            .semantics { if (connecting) stateDescription = "Connecting" },
    ) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                // A Bluetooth headset takes a moment; the phone records meanwhile.
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = LocalContentColor.current)
                } else {
                    Icon(mic.icon, "Microphone", Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                mic.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicSheet(mics: List<Microphones.Mic>, current: Microphones.Mic?, onDismiss: () -> Unit, onPick: (Microphones.Mic) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Let the sheet slide away first, then switch, so the screen behind never changes under it.
    val pick = { m: Microphones.Mic -> scope.launch { sheet.hide() }.invokeOnCompletion { onPick(m) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text("Record with", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 2.dp))
        Text(
            "Used again next time, whenever it's connected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        Column(Modifier.selectableGroup().padding(bottom = 32.dp)) {
            mics.forEach { mic ->
                val selected = mic.key == current?.key
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) { pick(mic) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(mic.name, style = MaterialTheme.typography.titleMedium)
                        Text(mic.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(mic.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val Microphones.Mic.icon: ImageVector
    get() = when (kind) {
        Microphones.Kind.PHONE -> Icons.Rounded.Smartphone
        Microphones.Kind.WIRED -> Icons.Rounded.HeadsetMic
        Microphones.Kind.USB -> Icons.Rounded.Usb
        Microphones.Kind.BLUETOOTH_LE, Microphones.Kind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    }

private val Microphones.Mic.detail: String
    get() = when (kind) {
        Microphones.Kind.PHONE -> "Built-in microphone"
        Microphones.Kind.WIRED -> "Plugged in"
        Microphones.Kind.USB -> "USB"
        Microphones.Kind.BLUETOOTH_LE -> "Bluetooth LE Audio"
        // The link phone calls use: narrower sound, and music pauses while it's on.
        Microphones.Kind.BLUETOOTH -> "Bluetooth · Sounds like a phone call"
    }
