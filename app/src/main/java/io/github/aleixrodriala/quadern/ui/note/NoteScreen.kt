package io.github.aleixrodriala.quadern.ui.note

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.aleixrodriala.quadern.data.InsightsStatus
import io.github.aleixrodriala.quadern.data.NoteWithProgress
import io.github.aleixrodriala.quadern.data.RecordingState
import io.github.aleixrodriala.quadern.data.TranscriptionStatus
import io.github.aleixrodriala.quadern.ui.LocalContainer
import io.github.aleixrodriala.quadern.ui.components.CircleIconButton
import io.github.aleixrodriala.quadern.ui.components.Motion
import io.github.aleixrodriala.quadern.ui.components.Motion.textSwap
import io.github.aleixrodriala.quadern.ui.components.SkeletonLines
import io.github.aleixrodriala.quadern.ui.components.WaveformScrubber
import io.github.aleixrodriala.quadern.ui.displayTitle
import io.github.aleixrodriala.quadern.ui.home.StatusLine
import io.github.aleixrodriala.quadern.ui.statusOf
import io.github.aleixrodriala.quadern.ui.theme.TabularNumbers
import io.github.aleixrodriala.quadern.util.formatDuration
import io.github.aleixrodriala.quadern.util.formatListDate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NoteScreen(
    noteId: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onTag: (String) -> Unit,
) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noteState by remember(noteId) { c.repository.observeNote(noteId) }.collectAsState(initial = null)
    val live by c.recording.live.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val importingIds by c.importing.collectAsState()
    val importing = noteId in importingIds
    val importProgress = importingIds[noteId]
    val player = rememberNotePlayer()

    var loadedOnce by remember { mutableStateOf(false) }
    val note = noteState
    LaunchedEffect(note == null, importing) {
        if (note != null) loadedOnce = true
        else if (loadedOnce) onBack() // deleted
        else if (!importing) {
            // Never showed up (a shared file that failed to import, a stale link): don't sit blank.
            kotlinx.coroutines.delay(800)
            if (c.repository.getNote(noteId) == null) onBack()
        }
    }
    if (note == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val levels by produceState(ByteArray(0), noteId, note.recordingState) {
        value = withContext(Dispatchers.IO) { c.files.readLevels(noteId) }
    }
    val audio = c.files.playable(noteId)
    LaunchedEffect(audio?.path) { audio?.let(player::load) }

    val scroll = rememberScrollState()
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val showBarTitle by remember { derivedStateOf { scroll.value > titleBottom } }
    var playerBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    val playerHidden by remember { derivedStateOf { scroll.value > playerBottom } }
    val showMiniPlayer = playerHidden && (player.playing || player.positionMs > 0)
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRetranscribe by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(14.dp))
            // Once the big title scrolls away, a small one takes its place up here.
            Box(Modifier.weight(1f)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBarTitle && !editing,
                    enter = fadeIn(tween(180)) + slideInVertically(tween(260, easing = Motion.EmphasizedDecelerate)) { it / 2 },
                    exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 2 },
                ) {
                    Text(
                        displayTitle(note, importing),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (editing) {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
                Button(onClick = {
                    editing = false
                    scope.launch { c.repository.editTranscript(noteId, draft.trim()) }
                }) { Text("Save") }
            } else {
                // Listening while reading further down: the controls follow you up here.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showMiniPlayer,
                    enter = fadeIn(tween(160)) + scaleIn(tween(220, easing = Motion.EmphasizedDecelerate), initialScale = 0.6f),
                    exit = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.6f),
                ) {
                    Row {
                        CircleIconButton(
                            if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (player.playing) "Pause" else "Play",
                            { player.toggle() },
                            container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                CircleIconButton(Icons.Rounded.IosShare, "Share", { shareText(context, note) }, enabled = note.transcript.isNotBlank())
                Spacer(Modifier.width(8.dp))
                Box {
                    CircleIconButton(Icons.Rounded.MoreHoriz, "More", { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Copy transcript") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            enabled = note.transcript.isNotBlank(),
                            onClick = { menu = false; copy(context, note.transcript) },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit transcript") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; draft = note.transcript; editing = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.TextFields, null) },
                            onClick = { menu = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Summarize again") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                            enabled = note.transcript.isNotBlank() && note.recordingState == RecordingState.RECORDED &&
                                !(note.insightsStatus == InsightsStatus.PENDING && note.insightsAttempts == 0),
                            onClick = { menu = false; scope.launch { c.repository.summarizeAgain(noteId) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Transcribe again") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            enabled = note.recordingState == RecordingState.RECORDED,
                            onClick = { menu = false; confirmRetranscribe = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Share audio") }, leadingIcon = { Icon(Icons.Rounded.GraphicEq, null) },
                            enabled = audio != null,
                            onClick = { menu = false; audio?.let { shareAudio(context, it, displayTitle(note)) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                }
            }
        }

        val dividerAlpha by animateFloatAsState(if (scroll.value > 0) 1f else 0f, tween(200), label = "divider")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha))
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(formatListDate(note.createdAt))
                    if (note.interrupted) append(" · Saved after interruption")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            // A muted placeholder until the written title arrives, then a quiet fade-through.
            AnimatedContent(
                targetState = displayTitle(note, importing) to note.title.isBlank(),
                contentKey = { it.first },
                transitionSpec = { textSwap() },
                label = "title",
                modifier = Modifier.onGloballyPositioned { titleBottom = it.positionInParent().y + it.size.height },
            ) { (title, placeholder) ->
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !editing) { renaming = true },
                )
            }
            Spacer(Modifier.height(16.dp))
            SummaryCard(
                note = note,
                editing = editing,
                onTag = onTag,
                onRetry = { scope.launch { c.repository.summarizeAgain(noteId) } },
                onSetUp = onOpenSettings,
                modifier = Modifier.padding(bottom = 20.dp),
            )

            AnimatedVisibility(
                visible = audio != null,
                enter = fadeIn(tween(240, delayMillis = 100)) + expandVertically(Motion.resize()),
                exit = fadeOut(tween(120)) + shrinkVertically(Motion.resize()),
                // Measured on the wrapper, a direct child of the scrolling column: its position is in
                // scroll-content coordinates.
                modifier = Modifier.onGloballyPositioned { playerBottom = it.positionInParent().y + it.size.height },
            ) {
                Column {
                    PlayerBar(player, levels, note.durationMs)
                    Spacer(Modifier.height(20.dp))
                }
            }

            val status = statusOf(note, live?.noteId == note.id, importProgress)
            AnimatedVisibility(
                visible = status != null,
                enter = fadeIn(tween(200, delayMillis = 80)) + expandVertically(Motion.resize()),
                exit = fadeOut(tween(120)) + shrinkVertically(Motion.resize()),
            ) {
                var lastStatus by remember { mutableStateOf(status) }
                if (status != null) lastStatus = status
                lastStatus?.let { shown ->
                    val action: (() -> Unit)? = if (!shown.actionable) null else when (note.transcriptionStatus) {
                        TranscriptionStatus.NEEDS_AUTH -> onSignIn
                        TranscriptionStatus.NEEDS_SETUP -> ({
                            scope.launch {
                                val st = c.settings.current()
                                if (c.providers.isReady(st.provider, st)) c.repository.transcribe(noteId) else onOpenSettings()
                            }
                        })
                        TranscriptionStatus.FAILED, TranscriptionStatus.IDLE -> ({ scope.launch { c.repository.transcribe(noteId) } })
                        else -> null
                    }
                    AnimatedContent(targetState = shown, contentKey = { it.kind }, transitionSpec = { textSwap(rise = false) }, label = "status") { st ->
                        Column {
                            StatusLine(st, onClick = action)
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }

            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Placeholder lines while the words are on their way, then the transcript fades in.
                val body = when {
                    note.transcript.isNotBlank() -> Body.Text
                    note.transcriptionStatus == TranscriptionStatus.DONE && note.recordingState == RecordingState.RECORDED -> Body.NoSpeech
                    importing || note.transcriptionStatus == TranscriptionStatus.QUEUED ||
                        note.transcriptionStatus == TranscriptionStatus.RUNNING -> Body.Waiting
                    else -> Body.Empty
                }
                AnimatedContent(
                    targetState = body,
                    transitionSpec = {
                        (fadeIn(tween(260, delayMillis = 120)) togetherWith fadeOut(tween(110)))
                            .using(SizeTransform(clip = false) { _, _ -> Motion.resize() })
                    },
                    label = "transcript",
                ) { state ->
                    when (state) {
                        Body.Text -> Column {
                            val hasCard = note.insightsStatus != InsightsStatus.NONE &&
                                !(note.insightsStatus == InsightsStatus.DONE && note.summary.isBlank() && note.tags.isBlank())
                            AnimatedVisibility(visible = hasCard, enter = fadeIn(), exit = fadeOut()) {
                                Text(
                                    "Transcript",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            SelectionContainer {
                                Text(note.transcript, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Body.NoSpeech -> Text(
                            "No speech detected.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Body.Waiting -> Column(Modifier.padding(top = 8.dp)) {
                            SkeletonLines(listOf(1f, 0.96f, 0.9f, 0.97f, 0.93f, 0.55f), lineHeight = 13.dp, gap = 15.dp)
                        }
                        Body.Empty -> Spacer(Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(note.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text("Title") },
                    supportingText = { Text("Leave empty for an automatic title") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    // Saving the automatic title untouched keeps it automatic.
                    if (!(note.titleIsAuto && title.trim() == note.title)) scope.launch { c.repository.rename(noteId, title) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete note?") },
            text = { Text("The recording and its transcript will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
                    player.exo.stop()
                    scope.launch { c.repository.delete(noteId); onBack() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmRetranscribe) {
        val settings by c.settings.settings.collectAsState(initial = null)
        AlertDialog(
            onDismissRequest = { confirmRetranscribe = false },
            title = { Text("Transcribe again?") },
            text = {
                Text(
                    buildString {
                        append("The transcript will be replaced with a new one from ${settings?.provider?.label ?: "the selected provider"}.")
                        if (note.transcriptEdited) append(" Your edits will be lost.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRetranscribe = false; scope.launch { c.repository.retranscribe(noteId) } }) { Text("Transcribe") }
            },
            dismissButton = { TextButton(onClick = { confirmRetranscribe = false }) { Text("Cancel") } },
        )
    }
}

private enum class Body { Text, NoSpeech, Waiting, Empty }

@Composable
private fun PlayerBar(player: NotePlayer, levels: ByteArray, noteDurationMs: Long) {
    val duration = player.durationMs.takeIf { it > 0 } ?: noteDurationMs
    val progress = if (duration > 0) (player.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(
            if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (player.playing) "Pause" else "Play",
            { player.toggle() },
            size = 52.dp, iconSize = 28.dp,
            container = MaterialTheme.colorScheme.primary, content = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            WaveformScrubber(levels, progress, onSeek = player::seekTo, modifier = Modifier.fillMaxWidth().height(40.dp))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(formatDuration(player.positionMs), style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall.merge(TabularNumbers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            onClick = { player.cycleSpeed() },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                "${if (player.speed % 1f == 0f) player.speed.toInt() else player.speed}×",
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, note: NoteWithProgress) {
    // Titles from the first words just repeat the transcript; named or written ones are worth sharing.
    val titled = note.title.isNotBlank() && (!note.titleIsAuto || note.insightsStatus == InsightsStatus.DONE)
    val text = if (titled) "${note.title}\n\n${note.transcript}" else note.transcript
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, displayTitle(note))
    context.startActivity(Intent.createChooser(send, null))
}

private fun shareAudio(context: Context, file: File, title: String) {
    val safe = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifBlank { "Voice note" }
    val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
    val copy = File(dir, "$safe.m4a")
    file.copyTo(copy, overwrite = true)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    val send = Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
