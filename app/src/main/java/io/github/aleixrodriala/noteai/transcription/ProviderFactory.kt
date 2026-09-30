package io.github.aleixrodriala.noteai.transcription

import io.github.aleixrodriala.noteai.auth.ChatGptAuth
import io.github.aleixrodriala.noteai.data.AppSettings
import io.github.aleixrodriala.noteai.data.SecretStore
import io.github.aleixrodriala.noteai.transcription.providers.AssemblyAiProvider
import io.github.aleixrodriala.noteai.transcription.providers.ChatGptProvider
import io.github.aleixrodriala.noteai.transcription.providers.DeepgramProvider
import io.github.aleixrodriala.noteai.transcription.providers.ElevenLabsProvider
import io.github.aleixrodriala.noteai.transcription.providers.GeminiProvider
import io.github.aleixrodriala.noteai.transcription.providers.OpenAiCompatibleProvider

class ProviderFactory(
    private val auth: ChatGptAuth,
    private val secrets: SecretStore,
    private val local: LocalProviderSource,
) {
    /** Builds [id] from current settings, or throws [SttException.NotConfigured] saying what's missing. */
    suspend fun create(id: ProviderId, settings: AppSettings): SttProvider {
        val model = settings.modelFor(id)
        suspend fun key(): String = apiKey(id)
            ?: throw SttException.NotConfigured("Add your ${id.label} API key in Settings")
        return when (val kind = id.kind) {
            ProviderId.Kind.ChatGpt -> {
                if (!auth.isSignedIn()) throw SttException.NotConfigured("Sign in with ChatGPT to transcribe")
                ChatGptProvider(auth)
            }
            is ProviderId.Kind.OpenAiCompatible -> {
                val base = if (id == ProviderId.CUSTOM) settings.customBaseUrl else kind.baseUrl
                if (base.isBlank()) throw SttException.NotConfigured("Set the server URL for ${id.label} in Settings")
                val k = if (id == ProviderId.CUSTOM) apiKey(id) else key()
                OpenAiCompatibleProvider(id, base, k, model)
            }
            ProviderId.Kind.Deepgram -> DeepgramProvider(key(), model)
            ProviderId.Kind.ElevenLabs -> ElevenLabsProvider(key(), model)
            ProviderId.Kind.Gemini -> GeminiProvider(key(), model)
            ProviderId.Kind.AssemblyAi -> AssemblyAiProvider(key(), model)
            ProviderId.Kind.Local -> local.create(settings)
        }
    }

    suspend fun apiKey(id: ProviderId): String? = secrets.get(keyName(id))?.takeIf { it.isNotBlank() }

    suspend fun setApiKey(id: ProviderId, key: String?) = secrets.put(keyName(id), key?.trim())

    /** Quick readiness check for the settings screen. */
    suspend fun isReady(id: ProviderId, settings: AppSettings): Boolean =
        runCatching { create(id, settings) }.isSuccess

    private fun keyName(id: ProviderId) = "apikey_${id.name}"
}

/** Indirection so the on-device engine (and its model files) is only touched when it's selected. */
fun interface LocalProviderSource {
    suspend fun create(settings: AppSettings): SttProvider
}
