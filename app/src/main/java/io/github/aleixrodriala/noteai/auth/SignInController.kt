package io.github.aleixrodriala.noteai.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Drives the sign-in UI: browser sign-in with a loopback redirect, pasted link, or device code. */
class SignInController(
    private val appContext: Context,
    private val auth: ChatGptAuth,
    private val onSignedIn: () -> Unit,
) {
    sealed interface State {
        data object Idle : State
        data object WaitingForBrowser : State
        data object Finishing : State
        data class DeviceCode(val code: ChatGptAuth.DeviceCode) : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var attempt: ChatGptAuth.PkceAttempt? = null
    private var server: LoopbackServer? = null
    private var deviceJob: Job? = null

    /** Opens the ChatGPT sign-in page in a Custom Tab. Call from a foreground activity. */
    fun startBrowser(activityContext: Context) {
        cancel()
        val a = auth.newPkceAttempt()
        attempt = a
        val srv = LoopbackServer(ChatGptAuth.LOOPBACK_PORT) { target -> onRedirect(a, target) }
        try {
            srv.start()
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Couldn't start sign-in")
            return
        }
        server = srv
        SignInService.start(appContext)
        _state.value = State.WaitingForBrowser
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activityContext, Uri.parse(a.authorizeUrl))
    }

    /** Called on the loopback thread; blocks until the token exchange finishes so the page can say how it went. */
    private fun onRedirect(a: ChatGptAuth.PkceAttempt, target: String): Boolean = runBlocking {
        val result = withTimeoutOrNull(45_000) { runCatching { finish(a, "http://localhost$target") } }
        val ok = result?.isSuccess == true
        if (!ok) _state.value = State.Failed(result?.exceptionOrNull()?.message ?: "Sign-in timed out")
        ok
    }

    /** For when the browser shows "can't connect": the user pastes the address bar URL. */
    fun submitPastedLink(link: String) {
        val a = attempt ?: run {
            _state.value = State.Failed("Start the sign-in again first")
            return
        }
        scope.launch { runCatching { finish(a, link) }.onFailure { _state.value = State.Failed(it.message ?: "Sign-in failed") } }
    }

    private suspend fun finish(a: ChatGptAuth.PkceAttempt, redirect: String) {
        if (_state.value == State.Done) return
        val code = auth.codeFromRedirect(a, redirect)
        _state.value = State.Finishing
        auth.completePkce(a, code)
        succeed()
    }

    fun startDeviceCode() {
        cancel()
        deviceJob = scope.launch {
            try {
                val code = auth.requestDeviceCode()
                _state.value = State.DeviceCode(code)
                val deadline = System.currentTimeMillis() + 15 * 60_000
                while (System.currentTimeMillis() < deadline) {
                    delay(code.intervalSec.coerceAtLeast(3) * 1000)
                    if (auth.pollDeviceCode(code)) {
                        succeed()
                        return@launch
                    }
                }
                _state.value = State.Failed("The code expired. Try again.")
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _state.value = State.Failed(e.message ?: "Sign-in failed")
            }
        }
    }

    private fun succeed() {
        _state.value = State.Done
        stopListening()
        onSignedIn()
    }

    private fun stopListening() {
        val srv = server ?: return
        srv.close()
        server = null
        SignInService.stop(appContext)
    }

    fun cancel() {
        deviceJob?.cancel()
        deviceJob = null
        stopListening()
        if (_state.value != State.Done) _state.value = State.Idle
    }

    fun reset() {
        cancel()
        attempt = null
        _state.value = State.Idle
    }
}
