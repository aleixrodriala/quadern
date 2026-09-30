package io.github.aleixrodriala.quadern.data

import android.content.Context
import android.util.Log
import io.github.aleixrodriala.quadern.transcription.providers.Http
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

/**
 * A switch the project can flip to pause the ChatGPT route for every install, for example if
 * OpenAI asks. It's one small JSON file on the project's website, read at most twice a day and
 * only while ChatGPT is in use. Nothing is sent but the request itself. Anything unreadable, or
 * no connection, keeps the last known answer, and the first answer is "go ahead".
 *
 * `{"chatgpt": {"transcription": true, "summaries": true, "message": ""}}`
 */
class ServiceStatus(context: Context, private val url: String = URL) {
    data class ChatGpt(val transcription: Boolean = true, val summaries: Boolean = true, val message: String = "")

    private val prefs = context.getSharedPreferences("service_status", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    /**
     * The last known status, without waiting: callers hold locks, and a slow network must never
     * delay saving a recording. When it's more than [MAX_AGE_MS] old a refresh starts in the background.
     */
    fun chatGpt(): ChatGpt {
        if (System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) > MAX_AGE_MS && refreshing.compareAndSet(false, true)) {
            scope.launch { try { refresh() } finally { refreshing.set(false) } }
        }
        return ChatGpt(
            transcription = prefs.getBoolean(KEY_TRANSCRIPTION, true),
            summaries = prefs.getBoolean(KEY_SUMMARIES, true),
            message = prefs.getString(KEY_MESSAGE, "").orEmpty(),
        )
    }

    private fun refresh() {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        val status = runCatching {
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val obj = Http.json.parseToJsonElement(r.body.string()) as? JsonObject
                obj?.get("chatgpt") as? JsonObject
            }
        }.onFailure { Log.i(TAG, "Status check failed: ${it.message}") }.getOrNull()
        // No answer: keep the last one and ask again in an hour rather than in twelve.
        val now = System.currentTimeMillis()
        val edit = prefs.edit().putLong(KEY_CHECKED, if (status != null) now else now - MAX_AGE_MS + RETRY_MS)
        if (status != null) {
            fun bool(key: String) = (status[key] as? JsonPrimitive)?.booleanOrNull ?: true
            edit.putBoolean(KEY_TRANSCRIPTION, bool("transcription"))
                .putBoolean(KEY_SUMMARIES, bool("summaries"))
                .putString(KEY_MESSAGE, (status["message"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(300))
        }
        edit.apply()
    }

    companion object {
        const val URL = "https://aleixrodriala.github.io/quadern/status.json"
        private const val TAG = "ServiceStatus"
        private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
        private const val RETRY_MS = 60 * 60 * 1000L
        private const val KEY_CHECKED = "checked"
        private const val KEY_TRANSCRIPTION = "chatgpt_transcription"
        private const val KEY_SUMMARIES = "chatgpt_summaries"
        private const val KEY_MESSAGE = "chatgpt_message"

        /** What a note says when the route is paused. */
        fun pausedMessage(s: ChatGpt, what: String) =
            s.message.ifBlank { "ChatGPT $what is paused for Quadern right now" } + ". Choose another way in Settings"
    }
}
