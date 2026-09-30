package io.github.aleixrodriala.quadern.transcription

/**
 * Every speech-to-text backend the app knows. The ChatGPT subscription is the default; the rest
 * exist because that endpoint is private and may change, and because some people prefer an API key
 * or fully offline transcription.
 */
enum class ProviderId(
    val label: String,
    val summary: String,
    val kind: Kind,
    /** Default model id; empty when the provider has no model choice. */
    val defaultModel: String = "",
    /** Suggestions shown in the model picker; free text is always allowed. */
    val models: List<String> = emptyList(),
    /** Where to get an API key, for providers that need one. */
    val keyUrl: String? = null,
    /** Text model that writes titles and summaries; null when the service can't. Empty = the user must pick one. */
    val chatModel: String? = null,
    val chatModels: List<String> = emptyList(),
) {
    CHATGPT(
        label = "ChatGPT",
        summary = "Your ChatGPT subscription, same voice-to-text as the ChatGPT app",
        kind = Kind.ChatGpt,
        chatModel = "gpt-6-luna",
        chatModels = listOf("gpt-6-luna", "gpt-6-sol"),
    ),
    OPENAI(
        label = "OpenAI API",
        summary = "Pay-per-use with an OpenAI API key",
        kind = Kind.OpenAiCompatible("https://api.openai.com/v1"),
        defaultModel = "gpt-transcribe",
        models = listOf("gpt-transcribe", "gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1"),
        keyUrl = "https://platform.openai.com/api-keys",
        chatModel = "gpt-6-luna",
        chatModels = listOf("gpt-6-luna", "gpt-5.6-luna", "gpt-6-sol"),
    ),
    GROQ(
        label = "Groq",
        summary = "Very fast Whisper large-v3, generous free tier",
        kind = Kind.OpenAiCompatible("https://api.groq.com/openai/v1"),
        defaultModel = "whisper-large-v3-turbo",
        models = listOf("whisper-large-v3-turbo", "whisper-large-v3"),
        keyUrl = "https://console.groq.com/keys",
        chatModel = "openai/gpt-oss-120b",
        chatModels = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b"),
    ),
    MISTRAL(
        label = "Mistral",
        summary = "Voxtral transcription",
        kind = Kind.OpenAiCompatible("https://api.mistral.ai/v1"),
        defaultModel = "voxtral-mini-latest",
        models = listOf("voxtral-mini-latest"),
        keyUrl = "https://console.mistral.ai/api-keys",
        chatModel = "mistral-small-latest",
        chatModels = listOf("mistral-small-latest", "ministral-8b-latest"),
    ),
    DEEPGRAM(
        label = "Deepgram",
        summary = "Nova speech-to-text",
        kind = Kind.Deepgram,
        defaultModel = "nova-3",
        models = listOf("nova-3", "nova-2"),
        keyUrl = "https://console.deepgram.com/",
    ),
    ELEVENLABS(
        label = "ElevenLabs",
        summary = "Scribe speech-to-text",
        kind = Kind.ElevenLabs,
        defaultModel = "scribe_v2",
        models = listOf("scribe_v2"),
        keyUrl = "https://elevenlabs.io/app/settings/api-keys",
    ),
    GEMINI(
        label = "Google Gemini",
        summary = "Gemini listens and writes the transcript",
        kind = Kind.Gemini,
        defaultModel = "gemini-3.5-flash-lite",
        models = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"),
        keyUrl = "https://aistudio.google.com/apikey",
        chatModel = "gemini-3.5-flash-lite",
        chatModels = listOf("gemini-3.5-flash-lite", "gemini-3.8-flash"),
    ),
    ASSEMBLYAI(
        label = "AssemblyAI",
        summary = "Universal speech-to-text",
        kind = Kind.AssemblyAi,
        defaultModel = "universal-3-5-pro",
        models = listOf("universal-3-5-pro", "universal-2"),
        keyUrl = "https://www.assemblyai.com/app/api-keys",
    ),
    CUSTOM(
        label = "Custom server",
        summary = "Any OpenAI-compatible /audio/transcriptions endpoint",
        kind = Kind.OpenAiCompatible(""),
        defaultModel = "whisper-1",
        chatModel = "",
    ),
    LOCAL(
        label = "On this device",
        summary = "Whisper runs on your phone. Private and offline, slower",
        kind = Kind.Local,
    );

    val needsApiKey: Boolean get() = kind !is Kind.ChatGpt && kind !is Kind.Local && this != CUSTOM
    val needsNetwork: Boolean get() = kind !is Kind.Local
    val canSummarize: Boolean get() = chatModel != null

    sealed interface Kind {
        data object ChatGpt : Kind
        data class OpenAiCompatible(val baseUrl: String) : Kind
        data object Deepgram : Kind
        data object ElevenLabs : Kind
        data object Gemini : Kind
        data object AssemblyAi : Kind
        data object Local : Kind
    }

    companion object {
        fun fromKey(key: String?): ProviderId? = entries.firstOrNull { it.name == key }
    }
}
