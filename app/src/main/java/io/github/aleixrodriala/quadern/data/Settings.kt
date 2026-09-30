package io.github.aleixrodriala.quadern.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.aleixrodriala.quadern.transcription.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val provider: ProviderId = ProviderId.CHATGPT,
    /** Tried for a chunk when the main provider fails for a reason retrying won't fix. */
    val fallback: ProviderId? = null,
    /** ISO-639-1 code, or null to auto-detect. */
    val language: String? = null,
    val autoTranscribe: Boolean = true,
    val wifiOnly: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val models: Map<ProviderId, String> = emptyMap(),
    val customBaseUrl: String = "",
    val whisperModel: String = "base-q8_0",
    /** Write a title, summary and tags for every transcribed note. */
    val summarize: Boolean = true,
    /** Who writes them; null = the transcription service, when it can. */
    val summaryProvider: ProviderId? = null,
    val chatModels: Map<ProviderId, String> = emptyMap(),
    /** The first-run choice of how to transcribe has been made. */
    val onboarded: Boolean = false,
    /** Read and accepted what using the ChatGPT route means (unofficial, audio to OpenAI). */
    val chatGptConsent: Boolean = false,
) {
    fun modelFor(provider: ProviderId): String = models[provider]?.takeIf { it.isNotBlank() } ?: provider.defaultModel
    fun chatModelFor(provider: ProviderId): String = chatModels[provider]?.takeIf { it.isNotBlank() } ?: provider.chatModel.orEmpty()

    /** The service that writes summaries, or null when summaries are off or nothing suitable is chosen. */
    val summarizer: ProviderId?
        get() = if (!summarize) null else summaryProvider ?: provider.takeIf { it.canSummarize }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val provider = stringPreferencesKey("provider")
        val fallback = stringPreferencesKey("fallback")
        val language = stringPreferencesKey("language")
        val autoTranscribe = booleanPreferencesKey("auto_transcribe")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val customBaseUrl = stringPreferencesKey("custom_base_url")
        val whisperModel = stringPreferencesKey("whisper_model")
        val summarize = booleanPreferencesKey("summarize")
        val summaryProvider = stringPreferencesKey("summary_provider")
        val onboarded = booleanPreferencesKey("onboarded")
        val chatGptConsent = booleanPreferencesKey("chatgpt_consent")
        fun model(p: ProviderId) = stringPreferencesKey("model_${p.name}")
        fun chatModel(p: ProviderId) = stringPreferencesKey("chat_model_${p.name}")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        provider = ProviderId.fromKey(this[Keys.provider]) ?: ProviderId.CHATGPT,
        fallback = ProviderId.fromKey(this[Keys.fallback]),
        language = this[Keys.language]?.takeIf { it.isNotBlank() },
        autoTranscribe = this[Keys.autoTranscribe] ?: true,
        wifiOnly = this[Keys.wifiOnly] ?: false,
        theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[Keys.dynamicColor] ?: false,
        models = ProviderId.entries.mapNotNull { p -> this[Keys.model(p)]?.let { p to it } }.toMap(),
        customBaseUrl = this[Keys.customBaseUrl] ?: "",
        whisperModel = this[Keys.whisperModel] ?: "base-q8_0",
        summarize = this[Keys.summarize] ?: true,
        summaryProvider = ProviderId.fromKey(this[Keys.summaryProvider])?.takeIf { it.canSummarize },
        chatModels = ProviderId.entries.mapNotNull { p -> this[Keys.chatModel(p)]?.let { p to it } }.toMap(),
        onboarded = this[Keys.onboarded] ?: false,
        chatGptConsent = this[Keys.chatGptConsent] ?: false,
    )

    suspend fun setProvider(p: ProviderId) = context.dataStore.edit { it[Keys.provider] = p.name }
    suspend fun setFallback(p: ProviderId?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.fallback) else it[Keys.fallback] = p.name
    }
    suspend fun setLanguage(code: String?) = context.dataStore.edit {
        if (code == null) it.remove(Keys.language) else it[Keys.language] = code
    }
    suspend fun setAutoTranscribe(v: Boolean) = context.dataStore.edit { it[Keys.autoTranscribe] = v }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.wifiOnly] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[Keys.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = v }
    suspend fun setModel(p: ProviderId, model: String) = context.dataStore.edit {
        if (model.isBlank()) it.remove(Keys.model(p)) else it[Keys.model(p)] = model.trim()
    }
    suspend fun setCustomBaseUrl(url: String) = context.dataStore.edit { it[Keys.customBaseUrl] = url.trim() }
    suspend fun setWhisperModel(id: String) = context.dataStore.edit { it[Keys.whisperModel] = id }
    suspend fun setSummarize(v: Boolean) = context.dataStore.edit { it[Keys.summarize] = v }
    suspend fun setSummaryProvider(p: ProviderId?) = context.dataStore.edit {
        if (p == null) it.remove(Keys.summaryProvider) else it[Keys.summaryProvider] = p.name
    }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[Keys.onboarded] = v }
    suspend fun setChatGptConsent(v: Boolean) = context.dataStore.edit { it[Keys.chatGptConsent] = v }
    suspend fun setChatModel(p: ProviderId, model: String) = context.dataStore.edit {
        if (model.isBlank()) it.remove(Keys.chatModel(p)) else it[Keys.chatModel(p)] = model.trim()
    }
}
