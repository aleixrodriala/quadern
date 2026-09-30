package io.github.aleixrodriala.quadern.ui.welcome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aleixrodriala.quadern.transcription.ProviderId
import io.github.aleixrodriala.quadern.ui.LocalContainer
import io.github.aleixrodriala.quadern.ui.components.appearStaggered
import io.github.aleixrodriala.quadern.ui.theme.LocalExtraColors
import kotlinx.coroutines.launch

private enum class Way { ChatGpt, Device, Service }

/**
 * The first screen, shown once: what Quadern is, and the one real decision, who turns speech into
 * text. The three ways sit side by side with their honest trade-off; none is hidden behind another.
 */
@Composable
fun WelcomeScreen(onSignIn: () -> Unit, onOpenSettings: () -> Unit, onDone: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val account by c.auth.account.collectAsState()
    var way by rememberSaveable { mutableStateOf(Way.ChatGpt) }
    val settings by c.settings.settings.collectAsState(initial = null)

    // Back from a successful sign-in: that was the last step.
    LaunchedEffect(account, settings?.provider) {
        if (account != null && settings?.provider == ProviderId.CHATGPT) {
            c.settings.setOnboarded(true)
            onDone()
        }
    }

    fun go() = scope.launch {
        when (way) {
            Way.ChatGpt -> {
                c.settings.setProvider(ProviderId.CHATGPT)
                onSignIn()
            }
            Way.Device -> {
                c.settings.setProvider(ProviderId.LOCAL)
                val model = c.settings.current().whisperModel
                if (model !in c.whisperModels.installed.value) c.whisperModels.download(model)
                c.settings.setOnboarded(true)
                onDone()
            }
            Way.Service -> {
                c.settings.setProvider(ProviderId.OPENAI)
                c.settings.setOnboarded(true)
                onOpenSettings()
            }
        }
    }

    val modelSize = c.whisperModels.model(settings?.whisperModel ?: "base-q8_0")?.let { "${it.bytes / 1_000_000} MB" } ?: ""

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Mark(Modifier.appearStaggered(0))
            Spacer(Modifier.height(28.dp))
            Text("Quadern", style = MaterialTheme.typography.displaySmall, modifier = Modifier.appearStaggered(1))
            Spacer(Modifier.height(6.dp))
            Text(
                "Think out loud.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.appearStaggered(2),
            )
            Spacer(Modifier.height(44.dp))
            Text(
                "Who writes down what you say?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.appearStaggered(3),
            )
            Spacer(Modifier.height(14.dp))
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WayCard(
                    "ChatGPT", "Most accurate",
                    "The ChatGPT plan you already have, and the same voice-to-text as the ChatGPT app. Your audio goes to OpenAI.",
                    way == Way.ChatGpt, Modifier.appearStaggered(4),
                ) { way = Way.ChatGpt }
                WayCard(
                    "On this phone", "Most private",
                    "Whisper runs here. Nothing leaves your phone, and it works offline. Slower, and less accurate outside English. $modelSize download.",
                    way == Way.Device, Modifier.appearStaggered(5),
                ) { way = Way.Device }
                WayCard(
                    "Your own API key", null,
                    "OpenAI, Groq, Mistral, Deepgram and others. You pay them per use.",
                    way == Way.Service, Modifier.appearStaggered(6),
                ) { way = Way.Service }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Button(onClick = { go() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(
                    when (way) {
                        Way.ChatGpt -> "Continue with ChatGPT"
                        Way.Device -> "Download and start"
                        Way.Service -> "Add an API key"
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "You can change this any time in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** The app's mark: a page being written, the last line still growing toward the recording dot. */
@Composable
private fun Mark(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val red = LocalExtraColors.current.record
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, delayMillis = 350)) }
    val pulse = rememberInfiniteTransition(label = "mark")
    val dot by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "dot")
    Canvas(modifier.size(64.dp)) {
        val u = size.width / 108f
        drawRoundRect(ink, cornerRadius = CornerRadius(24 * u))
        val w = 6 * u
        drawLine(paper, Offset(35 * u, 42 * u), Offset(73 * u, 42 * u), w, StrokeCap.Round)
        drawLine(paper, Offset(35 * u, 54 * u), Offset(73 * u, 54 * u), w, StrokeCap.Round)
        drawLine(paper, Offset(35 * u, 66 * u), Offset((35 + 17 * grow.value) * u, 66 * u), w, StrokeCap.Round)
        drawCircle(red.copy(alpha = dot), 5 * u, Offset(65 * u, 66 * u))
    }
}

@Composable
private fun WayCard(title: String, badge: String?, body: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
        tween(200), label = "way border",
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border),
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
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
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
