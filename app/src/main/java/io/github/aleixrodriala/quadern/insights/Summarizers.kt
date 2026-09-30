package io.github.aleixrodriala.quadern.insights

import io.github.aleixrodriala.quadern.auth.ChatGptAuth
import io.github.aleixrodriala.quadern.transcription.ProviderId
import io.github.aleixrodriala.quadern.transcription.SttException
import io.github.aleixrodriala.quadern.transcription.providers.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Writes a note's title, summary and tags from its transcript. Throws [SttException] on failure. */
interface Summarizer {
    val id: ProviderId
    suspend fun summarize(transcript: String, existingTags: List<String>): NoteInsights
}

private fun JsonElement?.at(vararg path: Any): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) {
        cur = when (p) {
            is String -> (cur as? JsonObject)?.get(p)
            is Int -> (cur as? JsonArray)?.getOrNull(p)
            else -> null
        }
    }
    return cur
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun Response.bodySnippet(): String = runCatching { peekBody(4096).string() }.getOrDefault("")

/**
 * The ChatGPT subscription, through the Responses endpoint the Codex CLI uses with the same
 * sign-in. It only accepts streamed, unstored requests.
 */
class ChatGptSummarizer(
    private val credentials: suspend (forceRefresh: Boolean) -> ChatGptAuth.Credentials,
    private val model: String,
    /** The user typed a model: use exactly that one, no substitutes. */
    private val modelIsExplicit: Boolean,
    private val url: String = URL,
) : Summarizer {
    override val id = ProviderId.CHATGPT

    private class ModelUnavailable(message: String) : Exception(message)

    override suspend fun summarize(transcript: String, existingTags: List<String>): NoteInsights {
        val input = InsightsPrompt.input(transcript, existingTags)
        // Models get retired; walk down a short list before giving up on the default.
        val candidates = if (modelIsExplicit) listOf(model) else listOfNotNull(workingModel, model, *FALLBACK_MODELS).distinct()
        var unavailable: ModelUnavailable? = null
        for (m in candidates) {
            try {
                return InsightsPrompt.parse(request(m, input)).also { workingModel = m }
            } catch (e: ModelUnavailable) {
                unavailable = e
            }
        }
        throw SttException.Permanent(unavailable?.message ?: "No ChatGPT model is available for summaries")
    }

    private suspend fun request(model: String, input: String): String {
        val body = buildJsonObject {
            put("model", model)
            put("instructions", InsightsPrompt.instructions)
            putJsonArray("input") {
                addJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", input)
                        }
                    }
                }
            }
            put("store", false)
            put("stream", true)
            putJsonObject("reasoning") { put("effort", "low") }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", "note")
                    put("strict", true)
                    put("schema", InsightsPrompt.schema)
                }
            }
        }.toString()
        var creds = credentials(false)
        repeat(2) { attempt ->
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${creds.accessToken}")
                .header("ChatGPT-Account-Id", creds.accountId)
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(Http.JSON))
                .build()
            val result = Http.execute(request) { r ->
                when {
                    r.isSuccessful -> {
                        val source = r.body.source()
                        ResponsesStream.collect(generateSequence { source.readUtf8Line() }.iterator())
                    }
                    r.code == 401 && attempt == 0 -> null // token revoked early: refresh once and retry
                    r.code == 400 && r.bodySnippet().let { it.contains("model", true) && it.contains("not supported", true) } ->
                        throw ModelUnavailable("ChatGPT doesn't offer $model for summaries on this account")
                    else -> throw Http.failure("ChatGPT", r)
                }
            }
            if (result != null) return result
            creds = credentials(true)
        }
        throw SttException.Auth("ChatGPT rejected the session")
    }

    companion object {
        const val URL = "https://chatgpt.com/backend-api/codex/responses"
        private val FALLBACK_MODELS = arrayOf("gpt-6-luna", "gpt-5.6-luna", "gpt-6-sol")

        /** Remembered for the process so a retired default costs one failed request, not one per note. */
        @Volatile internal var workingModel: String? = null
    }
}

/**
 * OpenAI's /chat/completions and the services that copy it (Groq, Mistral, most self-hosted servers).
 * Strict JSON Schema output first; servers that don't support it get plain JSON mode, then nothing.
 */
class ChatCompletionsSummarizer(
    override val id: ProviderId,
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    /** Sent only to reasoning models that accept it; keeps summaries fast and cheap. */
    private val reasoningEffort: String?,
) : Summarizer {
    private class FormatUnsupported : Exception()

    override suspend fun summarize(transcript: String, existingTags: List<String>): NoteInsights {
        val input = InsightsPrompt.input(transcript, existingTags)
        val formats = listOf(
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "note")
                    put("strict", true)
                    put("schema", InsightsPrompt.schema)
                }
            },
            buildJsonObject { put("type", "json_object") },
            null,
        )
        for (format in formats) {
            try {
                return InsightsPrompt.parse(request(input, format))
            } catch (_: FormatUnsupported) {
                continue
            }
        }
        throw SttException.Permanent("${id.label} couldn't write a summary")
    }

    private suspend fun request(input: String, format: JsonObject?): String {
        val body = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", InsightsPrompt.instructions)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", input)
                }
            }
            if (format != null) put("response_format", format)
            if (reasoningEffort != null) put("reasoning_effort", reasoningEffort)
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return Http.execute(request) { r ->
            if (!r.isSuccessful) {
                val snippet = r.bodySnippet()
                if (format != null && (r.code == 400 || r.code == 422) &&
                    listOf("response_format", "json_schema", "json_object", "structured").any { snippet.contains(it, true) }
                ) throw FormatUnsupported()
                throw Http.failure(id.label, r)
            }
            val message = Http.parseJson(id.label, r.body.string()).at("choices", 0, "message")
            message.at("refusal").text()?.takeIf { it.isNotBlank() }?.let { throw SttException.Permanent("${id.label} declined: $it") }
            when (val content = message.at("content")) {
                is JsonPrimitive -> content.contentOrNull
                // Some reasoning models answer with a list of chunks; the text ones hold the answer.
                is JsonArray -> content.filter { it.at("type").text() == "text" }.mapNotNull { it.at("text").text() }.joinToString("")
                else -> null
            }?.takeIf { it.isNotBlank() } ?: throw SttException.Transient("${id.label} returned no summary")
        }
    }
}

class GeminiSummarizer(private val apiKey: String, private val model: String) : Summarizer {
    override val id = ProviderId.GEMINI

    override suspend fun summarize(transcript: String, existingTags: List<String>): NoteInsights {
        val payload = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", InsightsPrompt.instructions) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", InsightsPrompt.input(transcript, existingTags)) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", InsightsPrompt.schema)
            }
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(Http.JSON))
            .build()
        val raw = Http.execute(request) { r ->
            if (!r.isSuccessful) throw Http.failure("Gemini", r)
            val json = Http.parseJson("Gemini", r.body.string())
            json.at("promptFeedback", "blockReason").text()?.let { throw SttException.Permanent("Gemini refused the note ($it)") }
            val candidate = json.at("candidates", 0) ?: throw SttException.Transient("Gemini returned no answer")
            val finish = candidate.at("finishReason").text()
            if (finish != null && finish !in setOf("STOP", "MAX_TOKENS")) throw SttException.Permanent("Gemini stopped early ($finish)")
            (candidate.at("content", "parts") as? JsonArray)?.mapNotNull { it.at("text").text() }?.joinToString("")
                ?: throw SttException.Transient("Gemini returned no summary")
        }
        return InsightsPrompt.parse(raw)
    }
}
