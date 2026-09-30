package io.github.aleixrodriala.quadern.ui.settings

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aleixrodriala.quadern.auth.SignInController
import io.github.aleixrodriala.quadern.ui.LocalContainer
import io.github.aleixrodriala.quadern.ui.components.CircleIconButton

@Composable
fun SignInScreen(onDone: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val state by c.signIn.state.collectAsState()
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state == SignInController.State.Done) {
            kotlinx.coroutines.delay(900)
            c.signIn.reset()
            onDone()
        }
    }
    DisposableEffect(Unit) { onDispose { if (c.signIn.state.value !is SignInController.State.Done) c.signIn.cancel() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            CircleIconButton(Icons.Rounded.Close, "Close", { c.signIn.reset(); onDone() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("Sign in with ChatGPT", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Quadern transcribes with your ChatGPT subscription, the same voice-to-text the ChatGPT app uses. " +
                    "You sign in on OpenAI's own page; Quadern never sees your password.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            AnimatedContent(state, label = "signin", contentKey = { it::class }) { st ->
                Column {
                    when (st) {
                        SignInController.State.Idle, is SignInController.State.Failed -> {
                            if (st is SignInController.State.Failed) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                                    Text(st.message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
                                }
                                Spacer(Modifier.height(20.dp))
                            }
                            Button(onClick = { c.signIn.startBrowser(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("Continue in browser")
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { c.signIn.startDeviceCode() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign in with a code instead")
                            }
                        }
                        SignInController.State.WaitingForBrowser -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(12.dp))
                                Text("Finish signing in in the browser…", style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(20.dp))
                            OutlinedButton(onClick = { c.signIn.startBrowser(context) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Open the sign-in page again")
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showPaste = !showPaste }, modifier = Modifier.fillMaxWidth()) {
                                Text("Browser says it can't connect?")
                            }
                            if (showPaste) {
                                Text(
                                    "Copy the full address from the browser's address bar (it starts with http://localhost) and paste it here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(value = pasted, onValueChange = { pasted = it }, placeholder = { Text("http://localhost:1455/auth/callback?code=…") }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { c.signIn.submitPastedLink(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                    Text("Finish sign-in")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { c.signIn.startDeviceCode() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Sign in with a code instead")
                            }
                        }
                        SignInController.State.Finishing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(12.dp))
                                Text("Finishing sign-in…", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        is SignInController.State.DeviceCode -> {
                            Text("1. Open this page and sign in", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.code.verificationUrl))) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(st.code.verificationUrl.removePrefix("https://")) }
                            Spacer(Modifier.height(20.dp))
                            Text("2. Enter this code", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                onClick = {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Code", st.code.userCode))
                                },
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(st.code.userCode, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Tap to copy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Waiting for approval…", style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "If OpenAI says codes are disabled, turn on \"device code sign-in\" in ChatGPT → Settings → Security, then try again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SignInController.State.Done -> {
                            Text("You're signed in.", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
