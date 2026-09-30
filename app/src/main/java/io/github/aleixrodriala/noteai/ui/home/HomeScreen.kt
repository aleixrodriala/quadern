package io.github.aleixrodriala.noteai.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aleixrodriala.noteai.data.NoteWithProgress
import io.github.aleixrodriala.noteai.data.TranscriptionStatus
import io.github.aleixrodriala.noteai.recording.RecordingController
import io.github.aleixrodriala.noteai.transcription.ProviderId
import io.github.aleixrodriala.noteai.ui.LocalContainer
import io.github.aleixrodriala.noteai.ui.StatusInfo
import io.github.aleixrodriala.noteai.ui.Tone
import io.github.aleixrodriala.noteai.ui.components.CircleIconButton
import io.github.aleixrodriala.noteai.ui.components.Dot
import io.github.aleixrodriala.noteai.ui.components.Motion
import io.github.aleixrodriala.noteai.ui.components.pressScale
import io.github.aleixrodriala.noteai.ui.components.Motion.textSwap
import io.github.aleixrodriala.noteai.ui.displayTitle
import io.github.aleixrodriala.noteai.ui.insightsStatusOf
import io.github.aleixrodriala.noteai.ui.note.TagChip
import io.github.aleixrodriala.noteai.ui.statusOf
import io.github.aleixrodriala.noteai.ui.theme.LocalExtraColors
import io.github.aleixrodriala.noteai.ui.theme.TabularNumbers
import io.github.aleixrodriala.noteai.util.formatDuration
import io.github.aleixrodriala.noteai.util.formatListDate
import io.github.aleixrodriala.noteai.util.formatShortDuration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun HomeScreen(
    onOpenNote: (String) -> Unit,
    onRecord: () -> Unit,
    onOpenRecorder: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignIn: () -> Unit,
    /** A tag to filter by, asked for from elsewhere (tapped on a note); consumed once shown. */
    searchRequest: String? = null,
    onSearchRequestHandled: () -> Unit = {},
) {
    val c = LocalContainer.current
    val query = remember { MutableStateFlow("") }
    val q by query.collectAsState()
    val tagFilter = remember { MutableStateFlow<String?>(null) }
    val tag by tagFilter.collectAsState()
    val notesFlow = remember { combine(query, tagFilter, ::Pair).flatMapLatest { (q, t) -> c.repository.observeNotes(q, t) } }
    val notes by notesFlow.collectAsState(initial = null)
    val live by c.recording.live.collectAsState()
    val importing by c.importing.collectAsState()
    val starting by c.recording.starting.collectAsState()
    val settings by c.settings.settings.collectAsState(initial = null)
    val account by c.auth.account.collectAsState()
    val authLoaded by c.auth.loaded.collectAsState()
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tags by remember { c.repository.observeTags() }.collectAsState(initial = emptyList())
    LaunchedEffect(searchRequest) {
        if (searchRequest != null) {
            searching = true
            query.value = ""
            tagFilter.value = searchRequest
            onSearchRequestHandled()
        }
    }

    val needsSignIn = settings?.provider == ProviderId.CHATGPT && authLoaded && account == null

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp),
        ) {
            item(key = "header") {
                Header(
                    searching = searching,
                    query = q,
                    tag = tag,
                    onQuery = { query.value = it },
                    onSearch = { searching = true },
                    onCloseSearch = { searching = false; query.value = ""; tagFilter.value = null },
                    onSettings = onOpenSettings,
                )
            }
            item(key = "tags") {
                // Full width, so the chips scroll from edge to edge.
                TagFilter(
                    tags = if (searching) tags else emptyList(),
                    selected = tag,
                    onSelect = { tagFilter.value = it },
                )
            }
            if (needsSignIn && !searching) {
                item(key = "signin") { SignInBanner(onSignIn) }
            }
            val list = notes
            if (list != null && list.isEmpty()) {
                item(key = "empty") { EmptyState(searching = q.isNotBlank() || tag != null) }
            }
            if (list != null) {
                items(list, key = { it.id }) { note ->
                    NoteRow(
                        note = note,
                        isLive = live?.noteId == note.id,
                        importProgress = importing[note.id],
                        onClick = { if (live?.noteId == note.id) onOpenRecorder() else onOpenNote(note.id) },
                        onStatusAction = {
                            when (note.transcriptionStatus) {
                                TranscriptionStatus.NEEDS_AUTH -> onSignIn()
                                TranscriptionStatus.NEEDS_SETUP -> scope.launch {
                                    val st = c.settings.current()
                                    if (c.providers.isReady(st.provider, st)) c.repository.transcribe(note.id) else onOpenSettings()
                                }
                                else -> scope.launch { c.repository.transcribe(note.id) }
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        // Soft fade so the list slides under the record button.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(170.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
        )
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(targetState = live != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "rec") { recording ->
                if (recording) {
                    live?.let { RecordingPill(it, onOpenRecorder) }
                } else {
                    RecordButton(enabled = !starting, onClick = onRecord)
                }
            }
        }
    }
}

@Composable
private fun Header(
    searching: Boolean,
    query: String,
    tag: String?,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.statusBarsPadding().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        AnimatedContent(searching, label = "search") { isSearching ->
            if (isSearching) {
                val focus = remember { FocusRequester() }
                // Coming from a tag: show the results rather than pop the keyboard over them.
                LaunchedEffect(Unit) { if (tag == null) focus.requestFocus() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = query,
                        onValueChange = onQuery,
                        placeholder = { Text(if (tag != null) "Search in ${tag.replaceFirstChar { it.uppercase() }}" else "Search notes", maxLines = 1) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = RoundedCornerShape(50),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(Icons.Rounded.Close, "Close search", onCloseSearch)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notes", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Rounded.Search, "Search", onSearch)
                    Spacer(Modifier.width(8.dp))
                    CircleIconButton(Icons.Rounded.Settings, "Settings", onSettings)
                }
            }
        }
    }
}

/** Tags in use, under the search field: one tap shows only the notes with that tag, another clears it. */
@Composable
private fun TagFilter(tags: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    // The picked tag leads, even if it's not among the most used.
    val shown = if (selected == null || tags.isEmpty()) tags else listOf(selected) + (tags - selected)
    AnimatedVisibility(
        visible = shown.isNotEmpty(),
        enter = androidx.compose.animation.fadeIn(tween(200, delayMillis = 120)) + expandVertically(tween(300, easing = Motion.EmphasizedDecelerate)),
        exit = androidx.compose.animation.fadeOut(tween(100)) + shrinkVertically(tween(220)),
    ) {
        val row = rememberLazyListState()
        // The picked tag moves to the front: follow it there.
        LaunchedEffect(selected) { if (selected != null) row.animateScrollToItem(0) }
        LazyRow(
            state = row,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        ) {
            items(shown, key = { it }) { tag ->
                val isSelected = tag == selected
                TagChip(
                    tag,
                    selected = isSelected,
                    onClick = { onSelect(if (isSelected) null else tag) },
                    modifier = Modifier
                        .animateItem()
                        .then(
                            if (isSelected) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                        ),
                )
            }
        }
    }
}

@Composable
private fun SignInBanner(onSignIn: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Get transcripts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sign in with ChatGPT to transcribe with your subscription.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSignIn) { Text("Sign in") }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (searching) Icons.Rounded.Search else Icons.Rounded.GraphicEq,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (searching) "No notes match" else "Your voice notes live here",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (searching) "Try other words." else "Tap the button below and start talking. Transcripts appear on their own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun NoteRow(
    note: NoteWithProgress,
    isLive: Boolean,
    importProgress: Float?,
    onClick: () -> Unit,
    onStatusAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isImporting = importProgress != null
    val status = statusOf(note, isLive, importProgress) ?: insightsStatusOf(note)
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                // Rows grow and shrink smoothly as the transcript, summary and status come and go.
                .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatListDate(note.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (note.durationMs > 0) {
                    Text(
                        formatShortDuration(note.durationMs),
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            AnimatedContent(
                targetState = displayTitle(note, isImporting) to note.title.isBlank(),
                contentKey = { it.first },
                transitionSpec = { textSwap() },
                label = "row-title",
            ) { (title, placeholder) ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // The summary says more than the first words of the transcript, once there is one.
            val snippet = note.summary.replace('\n', ' ').trim().ifEmpty {
                note.transcript.replace('\n', ' ').trim().ifEmpty {
                    if (note.transcriptionStatus == TranscriptionStatus.DONE &&
                        note.recordingState == io.github.aleixrodriala.noteai.data.RecordingState.RECORDED
                    ) "No speech detected" else ""
                }
            }
            if (snippet.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                AnimatedContent(
                    targetState = snippet,
                    // Transcripts grow chunk by chunk; only the switch to the summary deserves a fade.
                    contentKey = { note.summary.isNotBlank() },
                    transitionSpec = { textSwap(rise = false) },
                    label = "row-snippet",
                ) { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            AnimatedVisibility(
                visible = status != null,
                enter = androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)) + expandVertically(tween(260, easing = Motion.EmphasizedDecelerate)),
                exit = androidx.compose.animation.fadeOut(tween(120)) + shrinkVertically(tween(240, easing = Motion.Standard)),
            ) {
                var lastStatus by remember { mutableStateOf(status) }
                if (status != null) lastStatus = status
                lastStatus?.let { shown ->
                    AnimatedContent(targetState = shown, contentKey = { it.kind }, transitionSpec = { textSwap(rise = false) }, label = "row-status") { st ->
                        Column {
                            Spacer(Modifier.height(8.dp))
                            StatusLine(st, onClick = if (st.actionable) onStatusAction else null)
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun StatusLine(status: StatusInfo, onClick: (() -> Unit)? = null) {
    val extra = LocalExtraColors.current
    val color = when (status.tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Busy -> MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Recording -> extra.record
        Tone.Problem -> MaterialTheme.colorScheme.error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier,
    ) {
        when (status.tone) {
            Tone.Busy -> androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color,
            )
            else -> Dot(color, size = 7.dp)
        }
        Spacer(Modifier.width(8.dp))
        Text(status.text, style = MaterialTheme.typography.labelMedium.merge(TabularNumbers), color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecordButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn)
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        interactionSource = interaction,
        modifier = Modifier.pressScale(interaction, pressed = 0.9f).size(76.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, "Record", modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun RecordingPill(live: RecordingController.Live, onClick: () -> Unit) {
    val extra = LocalExtraColors.current
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        interactionSource = interaction,
        modifier = Modifier.pressScale(interaction),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (live.paused) MaterialTheme.colorScheme.outline else extra.record, size = 10.dp)
            Spacer(Modifier.width(12.dp))
            Text(formatDuration(live.elapsedMs), style = MaterialTheme.typography.titleMedium.merge(TabularNumbers))
            Spacer(Modifier.width(12.dp))
            Text(if (live.paused) "Paused" else "Recording", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
