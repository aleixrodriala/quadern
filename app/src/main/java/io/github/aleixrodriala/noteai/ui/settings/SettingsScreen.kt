package io.github.aleixrodriala.noteai.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import io.github.aleixrodriala.noteai.ui.components.Motion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.aleixrodriala.noteai.BuildConfig
import io.github.aleixrodriala.noteai.data.AppSettings
import io.github.aleixrodriala.noteai.data.ThemeMode
import io.github.aleixrodriala.noteai.transcription.ProviderId
import io.github.aleixrodriala.noteai.transcription.local.WhisperModels
import io.github.aleixrodriala.noteai.ui.LocalContainer
import io.github.aleixrodriala.noteai.ui.components.CircleIconButton
import io.github.aleixrodriala.noteai.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LANGUAGES = listOf(
    null to "Detect automatically",
    "en" to "English", "es" to "Spanish", "ca" to "Catalan", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish", "sv" to "Swedish",
    "ja" to "Japanese", "zh" to "Chinese", "ko" to "Korean", "hi" to "Hindi", "ar" to "Arabic",
    "ru" to "Russian", "uk" to "Ukrainian", "tr" to "Turkish",
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onSignIn: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsState(initial = null)
    val account by c.auth.account.collectAsState()
    val secretsVersion by c.secrets.version.collectAsState()
    val settings = s ?: return

    var pickService by remember { mutableStateOf(false) }
    var pickFallback by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf<ProviderId?>(null) }
    var editModel by remember { mutableStateOf<ProviderId?>(null) }
    var editChatModel by remember { mutableStateOf<ProviderId?>(null) }
    var pickSummarizer by remember { mutableStateOf(false) }
    var editUrl by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    // Whatever was fixed here (key, sign-in, model, provider), give blocked notes another go on the way out.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { c.appScope.launch { c.repository.retryBlocked() } }
    }

    suspend fun choose(p: ProviderId) {
        c.settings.setProvider(p)
        if (settings.fallback == p) c.settings.setFallback(null)
        c.repository.retryBlocked()
    }

    /** The service to switch to: one that's already set up, else OpenAI. */
    suspend fun defaultService(): ProviderId =
        ProviderId.entries.filter { it.mode == Mode.Service && it.needsApiKey }.firstOrNull { c.providers.apiKey(it) != null }
            ?: ProviderId.CUSTOM.takeIf { settings.customBaseUrl.isNotBlank() }
            ?: ProviderId.OPENAI

    val hasKey by produceState(false, settings.provider, secretsVersion) { value = c.providers.apiKey(settings.provider) != null }
    val summarizer = settings.summarizer
    val summarizerHasKey by produceState(false, summarizer, secretsVersion) { value = summarizer?.let { c.providers.apiKey(it) } != null }
    val batteryExempt = rememberBatteryExempt()
    val storage by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { formatBytes(c.files.totalBytes()) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(16.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("Transcription")
            ModeChooser(
                selected = settings.provider.mode,
                chatGptDetail = if (account != null) {
                    listOfNotNull(account?.email, account?.plan?.let { planLabel(it) }).joinToString(" · ").ifBlank { "Signed in" }
                } else {
                    null
                },
            ) { mode ->
                if (mode != settings.provider.mode) scope.launch {
                    val p = when (mode) {
                        Mode.ChatGpt -> ProviderId.CHATGPT
                        Mode.Device -> ProviderId.LOCAL
                        Mode.Service -> defaultService()
                    }
                    choose(p)
                    // Nothing to set up yet: open the list of services straight away.
                    if (mode == Mode.Service && p.needsApiKey && c.providers.apiKey(p) == null && p != ProviderId.CUSTOM) pickService = true
                }
            }

            // What the chosen way needs, fading through as the section resizes, so nothing jumps.
            AnimatedContent(
                targetState = settings.provider,
                contentKey = { it.mode },
                transitionSpec = {
                    fadeIn(tween(220, delayMillis = 120)) togetherWith fadeOut(tween(100)) using
                        SizeTransform(clip = true) { _, _ -> Motion.resize() }
                },
                label = "transcription mode",
            ) { provider ->
                Column(Modifier.fillMaxWidth()) {
                    when (provider.mode) {
                        Mode.ChatGpt -> if (account != null) {
                            Item("ChatGPT account", listOfNotNull(account?.email, account?.plan?.let { planLabel(it) }).joinToString(" · ").ifBlank { "Signed in" }) {
                                confirmSignOut = true
                            }
                        } else {
                            Item("ChatGPT account", "Not signed in · Tap to sign in", onClick = onSignIn, highlight = true)
                        }
                        Mode.Device -> WhisperModelList(c.whisperModels, settings) { id -> scope.launch { c.settings.setWhisperModel(id); c.repository.retryBlocked() } }
                        Mode.Service -> {
                            Item("Service", provider.label) { pickService = true }
                            if (provider == ProviderId.CUSTOM) {
                                Item("Server URL", settings.customBaseUrl.ifBlank { "Not set · e.g. https://host/v1" }, highlight = settings.customBaseUrl.isBlank()) { editUrl = true }
                            }
                            Item(
                                "API key",
                                if (hasKey) "Saved on this device, encrypted" else if (provider.needsApiKey) "Required · Tap to add" else "Optional",
                                highlight = !hasKey && provider.needsApiKey,
                            ) { editKey = provider }
                            Item("Model", settings.modelFor(provider)) { editModel = provider }
                        }
                    }
                }
            }
            Item("Language", LANGUAGES.firstOrNull { it.first == settings.language }?.second ?: settings.language ?: "") { pickLanguage = true }
            Item(
                "Backup provider",
                settings.fallback?.label?.let { "$it, used when ${settings.provider.label} fails" } ?: "None",
            ) { pickFallback = true }
            Toggle("Transcribe automatically", "Start as soon as a recording is saved", settings.autoTranscribe) {
                scope.launch { c.settings.setAutoTranscribe(it) }
            }
            Toggle("Only on Wi-Fi", "Wait for Wi-Fi before uploading audio", settings.wifiOnly) {
                scope.launch { c.settings.setWifiOnly(it) }
            }

            Section("Summaries")
            Toggle("Titles and summaries", "A title, a short summary and tags for every note", settings.summarize) {
                scope.launch {
                    c.settings.setSummarize(it)
                    c.repository.onSummarizeChanged(it)
                }
            }
            AnimatedVisibility(
                visible = settings.summarize,
                enter = fadeIn(tween(200, delayMillis = 60)) + expandVertically(tween(280)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(240)),
            ) {
                Column {
                    Item(
                        "Written by",
                        when {
                            summarizer == null -> "${settings.provider.label} can't write summaries · Choose a service"
                            settings.summaryProvider == null -> "${summarizer.label}, same as transcription"
                            else -> summarizer.label + if (summarizer == ProviderId.CHATGPT) " subscription" else ""
                        },
                        highlight = summarizer == null,
                    ) { pickSummarizer = true }
                    // A service other than the transcription one may still need its own setup.
                    if (summarizer != null && summarizer != settings.provider) {
                        when {
                            summarizer == ProviderId.CHATGPT && account == null ->
                                Item("ChatGPT account", "Not signed in · Tap to sign in", onClick = onSignIn, highlight = true)
                            summarizer == ProviderId.CUSTOM && settings.customBaseUrl.isBlank() ->
                                Item("Server URL", "Not set · e.g. https://host/v1", highlight = true) { editUrl = true }
                            summarizer.needsApiKey && !summarizerHasKey ->
                                Item("${summarizer.label} API key", "Required · Tap to add", highlight = true) { editKey = summarizer }
                        }
                    }
                    if (summarizer != null) {
                        val model = settings.chatModelFor(summarizer)
                        Item("Summary model", model.ifBlank { "Not set · Tap to choose" }, highlight = model.isBlank()) { editChatModel = summarizer }
                    }
                }
            }

            Section("Recording")
            if (batteryExempt) {
                Item("Background recording", "Unrestricted. Android won't pause NoteAI to save battery.")
            } else {
                Item(
                    "Background recording",
                    "Allow NoteAI to run unrestricted so long recordings are never cut short by battery saving.",
                    highlight = true,
                ) { requestBatteryExemption(context) }
            }
            Item("Why this matters", "Some phone makers stop background apps aggressively. See dontkillmyapp.com") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/")))
            }

            Section("Appearance")
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { scope.launch { c.settings.setTheme(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Toggle("Wallpaper colors", "Use Material You colors instead of black and white", settings.dynamicColor) {
                    scope.launch { c.settings.setDynamicColor(it) }
                }
            }

            Section("About")
            Item("Storage", storage?.let { "$it of recordings on this device" } ?: "…")
            Item("NoteAI ${BuildConfig.VERSION_NAME}", "Open source. Source code and issues on GitHub") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aleixrodriala/noteai")))
            }
            Text(
                "With a ChatGPT subscription, transcripts use the same private endpoint as the ChatGPT apps and " +
                    "summaries the one Codex uses. Neither is an official API: OpenAI may change them at any time. " +
                    "Audio is sent only to the transcription service you choose, and transcripts only to the one " +
                    "that writes summaries; recordings otherwise stay on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    if (pickService) {
        ProviderSheet(
            title = "Transcription service",
            subtitle = "Pay-per-use with your own API key, or your own server",
            options = ProviderId.entries.filter { it.mode == Mode.Service },
            selected = settings.provider,
            onDismiss = { pickService = false },
        ) { p ->
            pickService = false
            scope.launch { choose(p!!) }
        }
    }
    if (pickFallback) {
        ProviderSheet(
            title = "Backup provider",
            options = listOf(null) + ProviderId.entries.filter { it != settings.provider },
            selected = settings.fallback,
            onDismiss = { pickFallback = false },
        ) { p ->
            pickFallback = false
            scope.launch { c.settings.setFallback(p) }
        }
    }
    if (pickSummarizer) {
        ProviderSheet(
            title = "Summaries written by",
            options = listOf(null) + ProviderId.entries.filter { it.canSummarize },
            selected = settings.summaryProvider,
            noneLabel = "Same as transcription",
            noneSummary = if (settings.provider.canSummarize) "Uses ${settings.provider.label}" else "${settings.provider.label} can't write summaries",
            summaryOf = { chatSummary(it) },
            onDismiss = { pickSummarizer = false },
        ) { p ->
            pickSummarizer = false
            scope.launch {
                c.settings.setSummaryProvider(p)
                c.repository.retryBlocked()
            }
        }
    }
    editChatModel?.let { p ->
        ModelDialog(p.chatModels, p.chatModel.orEmpty(), settings.chatModelFor(p), onDismiss = { editChatModel = null }) { value ->
            editChatModel = null
            scope.launch {
                c.settings.setChatModel(p, value)
                c.repository.retryBlocked()
            }
        }
    }
    if (pickLanguage) {
        ChoiceDialog(
            title = "Language",
            options = LANGUAGES.map { it.second },
            selected = LANGUAGES.indexOfFirst { it.first == settings.language }.coerceAtLeast(0),
            onDismiss = { pickLanguage = false },
        ) { i ->
            pickLanguage = false
            scope.launch { c.settings.setLanguage(LANGUAGES[i].first) }
        }
    }
    editKey?.let { p ->
        TextInputDialog(
            title = "${p.label} API key",
            initial = "",
            placeholder = if (hasKey) "Leave empty to remove the key" else "Paste your key",
            password = true,
            helpUrl = p.keyUrl,
            onDismiss = { editKey = null },
        ) { value ->
            editKey = null
            scope.launch {
                c.providers.setApiKey(p, value.ifBlank { null })
                c.repository.retryBlocked()
            }
        }
    }
    editModel?.let { p ->
        ModelDialog(p.models, p.defaultModel, settings.modelFor(p), onDismiss = { editModel = null }) { value ->
            editModel = null
            scope.launch { c.settings.setModel(p, value) }
        }
    }
    if (editUrl) {
        TextInputDialog(
            title = "Server URL",
            initial = settings.customBaseUrl,
            placeholder = "https://example.com/v1",
            password = false,
            helpUrl = null,
            onDismiss = { editUrl = false },
        ) { value ->
            editUrl = false
            scope.launch {
                c.settings.setCustomBaseUrl(value)
                c.repository.retryBlocked()
            }
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of ChatGPT?") },
            text = { Text("New recordings won't be transcribed until you sign in again or pick another provider.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; scope.launch { c.auth.signOut() } }) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

private fun chatSummary(p: ProviderId) = when (p) {
    ProviderId.CHATGPT -> "Your ChatGPT subscription"
    ProviderId.CUSTOM -> "Any OpenAI-compatible /chat/completions server"
    else -> "Pay-per-use, with your ${p.label.removeSuffix(" API")} API key"
}

private fun planLabel(plan: String) = when (plan.lowercase()) {
    "plus" -> "Plus"
    "pro" -> "Pro"
    "prolite" -> "Pro"
    "team" -> "Business"
    "business" -> "Business"
    "enterprise" -> "Enterprise"
    "edu" -> "Edu"
    "free" -> "Free"
    else -> plan.replaceFirstChar { it.uppercase() }
}

/** The three ways to transcribe; API services and custom servers share one. */
private enum class Mode { ChatGpt, Device, Service }

private val ProviderId.mode: Mode
    get() = when (kind) {
        ProviderId.Kind.ChatGpt -> Mode.ChatGpt
        ProviderId.Kind.Local -> Mode.Device
        else -> Mode.Service
    }

/** ChatGPT first and recommended; the other two ways right beside it, so they're easy to find. */
@Composable
private fun ModeChooser(selected: Mode, chatGptDetail: String?, onSelect: (Mode) -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.selectableGroup()) {
            ModeRow(
                "ChatGPT",
                chatGptDetail?.let { "Included with your plan · $it" } ?: "Included with your ChatGPT plan. No extra cost",
                selected == Mode.ChatGpt,
                badge = "Recommended",
            ) { onSelect(Mode.ChatGpt) }
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ModeRow("On this device", "Private and offline, using a Whisper model you download. Slower", selected == Mode.Device) { onSelect(Mode.Device) }
            HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ModeRow("Another service", "OpenAI API, Groq, Deepgram and more, with your own API key", selected == Mode.Service) { onSelect(Mode.Service) }
        }
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, badge: String? = null, onClick: () -> Unit) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        tween(220),
        label = "mode tint",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 8.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 6.dp),
    )
}

@Composable
private fun Item(title: String, subtitle: String? = null, highlight: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(
    title: String,
    subtitle: String? = null,
    options: List<ProviderId?>,
    selected: ProviderId?,
    noneLabel: String = "None",
    noneSummary: String = "Only use the main provider",
    summaryOf: (ProviderId) -> String = { it.summary },
    onDismiss: () -> Unit,
    onPick: (ProviderId?) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Let the sheet slide away first, then apply the choice, so the screen behind never changes under it.
    val pick = { p: ProviderId? -> scope.launch { sheet.hide() }.invokeOnCompletion { onPick(p) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp, bottom = if (subtitle == null) 8.dp else 2.dp))
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(options, key = { it?.name ?: "none" }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = p == selected, role = Role.RadioButton) { pick(p) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = p == selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p?.label ?: noneLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            p?.let(summaryOf) ?: noneSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options.size) { i ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = i == selected, role = Role.RadioButton) { onPick(i) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(options[i], style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    password: Boolean,
    helpUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text(placeholder) },
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (helpUrl != null) {
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl))) }) { Text("Get a key") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelDialog(models: List<String>, default: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model") },
        text = {
            Column {
                models.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = value == m, role = Role.RadioButton) { value = m }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == m, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(m + if (m == default) "  (default)" else "", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Model id") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WhisperModelList(models: WhisperModels, settings: AppSettings, onSelect: (String) -> Unit) {
    val installed by models.installed.collectAsState()
    val downloads by models.downloads.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (settings.whisperModel !in installed) {
            Text(
                "Download the selected model to start transcribing on this device. It stays on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        models.catalog.forEach { m ->
            val selected = settings.whisperModel == m.id
            val dl = downloads[m.id]
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                onClick = { onSelect(m.id) },
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { onSelect(m.id) })
                        Column(Modifier.weight(1f)) {
                            Text("${m.label} · ${formatBytes(m.bytes)}", style = MaterialTheme.typography.titleMedium)
                            Text(m.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            m.id in installed -> TextButton(onClick = { models.delete(m.id) }) { Text("Delete") }
                            dl is WhisperModels.Download.Running -> TextButton(onClick = { models.cancel(m.id) }) { Text("Cancel") }
                            else -> TextButton(onClick = { models.download(m.id) }) { Text(if (dl is WhisperModels.Download.Failed) "Retry" else "Download") }
                        }
                    }
                    if (dl is WhisperModels.Download.Running) {
                        LinearProgressIndicator(progress = { dl.done.toFloat() / dl.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    if (dl is WhisperModels.Download.Failed) {
                        Text(dl.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberBatteryExempt(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { tick++ }
    }
    return remember(tick) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }
}

@SuppressLint("BatteryLife") // a recorder is exactly the kind of app this exemption exists for
private fun requestBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(direct) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
