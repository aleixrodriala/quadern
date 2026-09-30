package io.github.aleixrodriala.noteai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.aleixrodriala.noteai.data.AppSettings
import io.github.aleixrodriala.noteai.data.ThemeMode
import io.github.aleixrodriala.noteai.ui.LocalContainer
import io.github.aleixrodriala.noteai.ui.home.HomeScreen
import io.github.aleixrodriala.noteai.ui.note.NoteScreen
import io.github.aleixrodriala.noteai.ui.record.RecordScreen
import io.github.aleixrodriala.noteai.ui.settings.SettingsScreen
import io.github.aleixrodriala.noteai.ui.settings.SignInScreen
import io.github.aleixrodriala.noteai.ui.theme.NoteTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        val container = (application as NoteApp).container

        setContent {
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The app theme can differ from the system's, and uiMode changes don't recreate this
            // activity: restyle the system bars whenever the resolved theme flips.
            androidx.compose.runtime.DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            NoteTheme(settings.theme, settings.dynamicColor) {
                CompositionLocalProvider(LocalContainer provides container) {
                    val nav = rememberNavController()
                    val route by pendingRoute.collectAsState()
                    LaunchedEffect(route) {
                        route?.let {
                            pendingRoute.value = null
                            nav.navigateSingle(it)
                        }
                    }
                    // Surface sets the default content color (onBackground) for all text and icons.
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                        AppNav(nav)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // noteai://auth/... just brings us back after browser sign-in; the sign-in screen is still open.
        intent?.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)) ?: return
            val container = (application as NoteApp).container
            // The note opens right away and shows the import, transcription and summary as they happen.
            pendingRoute.value = noteRoute(container.startImport(uri))
        }
    }

    companion object {
        /** Behind the three-button navigation bar: the app's own background, slightly see-through. */
        private val LIGHT_SCRIM = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0xE6, 0x0B, 0x0B, 0x0C)

        const val EXTRA_ROUTE = "route"
        const val ROUTE_HOME = "home"
        const val ROUTE_RECORD = "record"
        const val ROUTE_SETTINGS = "settings"
        const val ROUTE_SIGN_IN = "signin"
        fun noteRoute(id: String) = "note/$id"
    }
}

private const val KEY_SEARCH = "search"

private fun NavHostController.navigateSingle(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) { launchSingleTop = true }
}

@androidx.compose.runtime.Composable
private fun AppNav(nav: NavHostController) {
    val c = LocalContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var micDenied by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) {
            c.recording.start()
            nav.navigateSingle(MainActivity.ROUTE_RECORD)
        } else {
            micDenied = true
        }
    }
    val startRecording = {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val micGranted = Manifest.permission.RECORD_AUDIO !in needed
        if (micGranted && needed.isEmpty()) {
            c.recording.start()
            nav.navigateSingle(MainActivity.ROUTE_RECORD)
        } else {
            permissions.launch(needed.toTypedArray())
        }
    }

    val importFailure by c.importFailure.collectAsState()
    LaunchedEffect(importFailure) {
        importFailure?.let { msg ->
            android.widget.Toast.makeText(context, "Couldn't import that file. $msg", android.widget.Toast.LENGTH_LONG).show()
            c.importFailure.value = null
        }
    }

    NavHost(
        navController = nav,
        startDestination = MainActivity.ROUTE_HOME,
        enterTransition = { slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(220)) },
    ) {
        composable(MainActivity.ROUTE_HOME) { entry ->
            val searchRequest by entry.savedStateHandle.getStateFlow<String?>(KEY_SEARCH, null).collectAsState()
            HomeScreen(
                searchRequest = searchRequest,
                onSearchRequestHandled = { entry.savedStateHandle[KEY_SEARCH] = null },
                onOpenNote = { nav.navigateSingle(MainActivity.noteRoute(it)) },
                onRecord = startRecording,
                onOpenRecorder = { nav.navigateSingle(MainActivity.ROUTE_RECORD) },
                onOpenSettings = { nav.navigateSingle(MainActivity.ROUTE_SETTINGS) },
                onSignIn = { nav.navigateSingle(MainActivity.ROUTE_SIGN_IN) },
            )
        }
        composable(
            MainActivity.ROUTE_RECORD,
            enterTransition = { slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)) },
            popExitTransition = { slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(260)) },
        ) {
            RecordScreen(
                onMinimize = { if (!nav.popBackStack(MainActivity.ROUTE_HOME, false)) nav.navigateSingle(MainActivity.ROUTE_HOME) },
                onFinished = { id ->
                    nav.navigate(MainActivity.noteRoute(id)) {
                        popUpTo(MainActivity.ROUTE_HOME)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable("note/{id}") { entry ->
            NoteScreen(
                noteId = entry.arguments?.getString("id")!!,
                onBack = { if (!nav.popBackStack()) nav.navigateSingle(MainActivity.ROUTE_HOME) },
                onSignIn = { nav.navigateSingle(MainActivity.ROUTE_SIGN_IN) },
                onOpenSettings = { nav.navigateSingle(MainActivity.ROUTE_SETTINGS) },
                onTag = { tag ->
                    // Back to the list, filtered to this tag.
                    runCatching { nav.getBackStackEntry(MainActivity.ROUTE_HOME) }.getOrNull()?.savedStateHandle?.set(KEY_SEARCH, tag)
                    if (!nav.popBackStack(MainActivity.ROUTE_HOME, false)) nav.navigateSingle(MainActivity.ROUTE_HOME)
                },
            )
        }
        composable(MainActivity.ROUTE_SETTINGS) {
            SettingsScreen(
                onBack = { if (!nav.popBackStack()) nav.navigateSingle(MainActivity.ROUTE_HOME) },
                onSignIn = { nav.navigateSingle(MainActivity.ROUTE_SIGN_IN) },
            )
        }
        composable(MainActivity.ROUTE_SIGN_IN) {
            SignInScreen(onDone = { if (!nav.popBackStack()) nav.navigateSingle(MainActivity.ROUTE_HOME) })
        }
    }

    if (micDenied) {
        AlertDialog(
            onDismissRequest = { micDenied = false },
            title = { Text("Microphone access needed") },
            text = { Text("NoteAI needs the microphone to record. You can allow it in the app's settings.") },
            confirmButton = {
                TextButton(onClick = {
                    micDenied = false
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { micDenied = false }) { Text("Not now") } },
        )
    }
}
