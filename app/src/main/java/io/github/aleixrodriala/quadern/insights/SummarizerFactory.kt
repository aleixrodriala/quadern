package io.github.aleixrodriala.quadern.insights

import io.github.aleixrodriala.quadern.auth.ChatGptAuth
import io.github.aleixrodriala.quadern.data.AppSettings
import io.github.aleixrodriala.quadern.data.ServiceStatus
import io.github.aleixrodriala.quadern.transcription.ProviderFactory
import io.github.aleixrodriala.quadern.transcription.ProviderId
import io.github.aleixrodriala.quadern.transcription.SttException

class SummarizerFactory(private val auth: ChatGptAuth, private val providers: ProviderFactory, private val status: ServiceStatus) {
    /** Builds the summary writer from settings, or throws [SttException.NotConfigured] saying what's missing. */
    suspend fun create(settings: AppSettings): Summarizer {
        val id = settings.summarizer ?: throw SttException.NotConfigured(
            if (settings.summarize) "Choose who writes summaries in Settings" else "Summaries are off"
        )
        val model = settings.chatModelFor(id)
        if (model.isBlank()) throw SttException.NotConfigured("Choose a model for summaries in Settings")
        suspend fun key(): String = providers.apiKey(id)
            ?: throw SttException.NotConfigured("Add your ${id.label} API key in Settings")
        return when (val kind = id.kind) {
            ProviderId.Kind.ChatGpt -> {
                if (!auth.isSignedIn()) throw SttException.NotConfigured("Sign in with ChatGPT to get summaries")
                val s = status.chatGpt()
                if (!s.summaries) throw SttException.NotConfigured(ServiceStatus.pausedMessage(s, "summaries"))
                ChatGptSummarizer({ auth.credentials(it) }, model, modelIsExplicit = settings.chatModels[id]?.isNotBlank() == true)
            }
            is ProviderId.Kind.OpenAiCompatible -> {
                val base = if (id == ProviderId.CUSTOM) settings.customBaseUrl else kind.baseUrl
                if (base.isBlank()) throw SttException.NotConfigured("Set the server URL for ${id.label} in Settings")
                val k = if (id == ProviderId.CUSTOM) providers.apiKey(id) else key()
                ChatCompletionsSummarizer(id, base, k, model, reasoningEffortFor(id, model))
            }
            ProviderId.Kind.Gemini -> GeminiSummarizer(key(), model)
            else -> throw SttException.NotConfigured("${id.label} can't write summaries")
        }
    }

    suspend fun isReady(settings: AppSettings): Boolean = runCatching { create(settings) }.isSuccess

    companion object {
        /**
         * Reasoning models think less for a summary; other models reject the parameter. Mistral Small
         * answers in "thinking" chunks unless reasoning is off.
         */
        fun reasoningEffortFor(id: ProviderId, model: String): String? = when {
            id == ProviderId.OPENAI && Regex("^(gpt-[5-9]|o\\d)").containsMatchIn(model) -> "low"
            id == ProviderId.GROQ && model.startsWith("openai/gpt-oss") -> "low"
            id == ProviderId.MISTRAL && model.startsWith("mistral-small") -> "none"
            else -> null
        }
    }
}
