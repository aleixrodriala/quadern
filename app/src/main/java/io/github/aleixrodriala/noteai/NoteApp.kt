package io.github.aleixrodriala.noteai

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.net.Uri
import io.github.aleixrodriala.noteai.audio.AudioImporter
import io.github.aleixrodriala.noteai.auth.ChatGptAuth
import io.github.aleixrodriala.noteai.auth.SignInController
import io.github.aleixrodriala.noteai.data.AppDatabase
import io.github.aleixrodriala.noteai.data.NoteFiles
import io.github.aleixrodriala.noteai.data.NotesRepository
import io.github.aleixrodriala.noteai.data.SecretStore
import io.github.aleixrodriala.noteai.data.SettingsRepository
import io.github.aleixrodriala.noteai.insights.InsightsScheduler
import io.github.aleixrodriala.noteai.insights.SummarizerFactory
import io.github.aleixrodriala.noteai.recording.RecordingController
import io.github.aleixrodriala.noteai.transcription.ProviderFactory
import io.github.aleixrodriala.noteai.transcription.TranscriptionScheduler
import io.github.aleixrodriala.noteai.transcription.local.WhisperModels
import io.github.aleixrodriala.noteai.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(context)
    val files = NoteFiles(context)
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val auth = ChatGptAuth(secrets)
    val scheduler = TranscriptionScheduler(context, settings)
    val insights = InsightsScheduler(context)
    val repository: NotesRepository = NotesRepository(db.notes(), files, settings, scheduler, insights) {
        summarizers.isReady(settings.current())
    }
    val recording = RecordingController(context)
    // Notes waiting for a model start transcribing as soon as it's downloaded.
    val whisperModels = WhisperModels(context) { appScope.launch { repository.retryBlocked() } }
    val providers = ProviderFactory(auth, secrets, whisperModels::createProvider)
    val summarizers: SummarizerFactory = SummarizerFactory(auth, providers)
    val signIn = SignInController(context, auth) { appScope.launch { repository.retryBlocked() } }

    /** Notes being converted from a shared audio file right now, with how far along (0..1). */
    val importing = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Why the last shared file couldn't be imported; kept until the UI has shown it (even on a cold start). */
    val importFailure = MutableStateFlow<String?>(null)

    /**
     * Starts importing a shared audio file and returns its note id at once, so the note can be shown
     * while it's copied, converted and queued like a recording.
     */
    fun startImport(uri: Uri): String {
        val id = UUID.randomUUID().toString()
        recording.own(id) // keep crash recovery away while it's being converted
        importing.update { it + (id to 0f) }
        appScope.launch(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "import-${System.nanoTime()}")
            try {
                repository.createRecording(id)
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("Couldn't open the shared file")
                AudioImporter.import(tmp, files.adts(id), files.levels(id)) { p ->
                    importing.update { if (id in it) it + (id to p) else it }
                }
                repository.finalizeRecording(id, interrupted = false)
            } catch (e: Exception) {
                android.util.Log.w("Import", "Couldn't import $uri", e)
                // Deleted while it was being converted: that's no failure to tell anyone about.
                val deleted = runCatching { repository.getNote(id) == null }.getOrDefault(false)
                runCatching { repository.delete(id) }
                if (!deleted) importFailure.value = e.message ?: "The file isn't audio NoteAI can read"
            } finally {
                tmp.delete()
                recording.release(id)
                importing.update { it - id }
            }
        }
        return id
    }
}

class NoteApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        val started = System.currentTimeMillis()
        container.appScope.launch {
            // Copies of shared files a previous process was importing when it died (not this one's).
            cacheDir.listFiles { f -> f.name.startsWith("import-") && f.lastModified() < started }?.forEach { it.delete() }
            // Audio whose note a power cut undid gets its note back, then anything left "recording"
            // by a previous process died with it: save it as a note.
            container.repository.adoptOrphans(started, container.recording::isLive)
            container.repository.recoverInterrupted(container.recording::isLive)
            container.repository.resumeTranscriptions()
        }
        // Opening the app is a good moment to retry anything waiting in backoff.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var first = true
            override fun onStart(owner: LifecycleOwner) {
                if (first) {
                    first = false
                    return
                }
                container.appScope.launch { container.repository.resumeTranscriptions() }
            }
        })
    }
}

/** After a reboot mid-recording, finalize that recording and queue it without waiting for the user. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // NoteApp.onCreate already runs recovery for this process start; just keep the process
        // alive long enough for it to finish.
        val pending = goAsync()
        val app = context.applicationContext as NoteApp
        app.container.appScope.launch {
            kotlinx.coroutines.delay(5_000)
            pending.finish()
        }
    }
}
