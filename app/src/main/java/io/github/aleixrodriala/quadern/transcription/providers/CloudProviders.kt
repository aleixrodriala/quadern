package io.github.aleixrodriala.quadern.transcription.providers

import android.util.Base64
import io.github.aleixrodriala.quadern.auth.ChatGptAuth
import io.github.aleixrodriala.quadern.transcription.ProviderId
import io.github.aleixrodriala.quadern.transcription.SttException
import io.github.aleixrodriala.quadern.transcription.SttProvider
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

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

/**
 * ChatGPT's own voice-to-text: the endpoint behind the microphone button in the ChatGPT apps.
 * Private and undocumented, so failures are classified conservatively.
 */
class ChatGptProvider(private val auth: ChatGptAuth) : SttProvider {
    override val id = ProviderId.CHATGPT

    override suspend fun transcribe(audio: File, language: String?): String {
        var creds = auth.credentials()
        repeat(2) { attempt ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM).also { b ->
                Http.filePart(b, audio)
                if (language != null) b.addFormDataPart("language", language)
            }.build()
            val request = Request.Builder()
                .url(URL)
                .header("Authorization", "Bearer ${creds.accessToken}")
                .header("ChatGPT-Account-Id", creds.accountId)
                .header("Accept", "application/json")
                .post(body)
                .build()
            val result = Http.execute(request) { r ->
                when {
                    r.isSuccessful -> Http.parseJson("ChatGPT", r.body.string()).at("text").text()?.trim()
                        ?: throw SttException.Transient("ChatGPT returned no text")
                    r.code == 401 && attempt == 0 -> null // token revoked early: refresh once and retry
                    else -> throw Http.failure("ChatGPT", r)
                }
            }
            if (result != null) return result
            creds = auth.credentials(forceRefresh = true)
        }
        throw SttException.Auth("ChatGPT rejected the session")
    }

    private companion object {
        const val URL = "https://chatgpt.com/backend-api/transcribe"
    }
}

/** OpenAI's /audio/transcriptions and the many services that copy it (Groq, Mistral, self-hosted...). */
class OpenAiCompatibleProvider(
    override val id: ProviderId,
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
) : SttProvider {
    override suspend fun transcribe(audio: File, language: String?): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).also { b ->
            Http.filePart(b, audio)
            b.addFormDataPart("model", model)
            b.addFormDataPart("response_format", "json")
            // gpt-transcribe takes `languages` instead; auto-detect is excellent there, so skip the hint.
            if (language != null && model != "gpt-transcribe") b.addFormDataPart("language", language)
        }.build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/audio/transcriptions")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body)
            .build()
        return Http.execute(request) { r ->
            if (!r.isSuccessful) throw Http.failure(id.label, r)
            val raw = r.body.string()
            // A few self-hosted servers answer plain text even when asked for JSON.
            runCatching { Http.json.parseToJsonElement(raw).at("text").text() }.getOrNull()?.trim()
                ?: raw.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
                ?: throw SttException.Transient("${id.label} returned no text")
        }
    }
}

class DeepgramProvider(private val apiKey: String, private val model: String) : SttProvider {
    override val id = ProviderId.DEEPGRAM

    override suspend fun transcribe(audio: File, language: String?): String {
        val url = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", model)
            .addQueryParameter("smart_format", "true")
            .apply {
                if (language != null) addQueryParameter("language", language)
                else addQueryParameter("detect_language", "true")
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .post(audio.asRequestBody(Http.M4A))
            .build()
        return Http.execute(request) { r ->
            if (!r.isSuccessful) throw Http.failure("Deepgram", r)
            val alt = Http.parseJson("Deepgram", r.body.string()).at("results", "channels", 0, "alternatives", 0)
            // Paragraph-formatted text reads better than the flat transcript when available.
            (alt.at("paragraphs", "transcript").text() ?: alt.at("transcript").text())?.trim()
                ?: throw SttException.Transient("Deepgram returned no text")
        }
    }
}

class ElevenLabsProvider(private val apiKey: String, private val model: String) : SttProvider {
    override val id = ProviderId.ELEVENLABS

    override suspend fun transcribe(audio: File, language: String?): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).also { b ->
            Http.filePart(b, audio)
            b.addFormDataPart("model_id", model)
            b.addFormDataPart("tag_audio_events", "false")
            if (language != null) b.addFormDataPart("language_code", language)
        }.build()
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(body)
            .build()
        return Http.execute(request) { r ->
            if (!r.isSuccessful) throw Http.failure("ElevenLabs", r)
            Http.parseJson("ElevenLabs", r.body.string()).at("text").text()?.trim()
                ?: throw SttException.Transient("ElevenLabs returned no text")
        }
    }
}

class GeminiProvider(private val apiKey: String, private val model: String) : SttProvider {
    override val id = ProviderId.GEMINI

    override suspend fun transcribe(audio: File, language: String?): String {
        val prompt = buildString {
            append("Transcribe this audio verbatim. Output only the transcript text, with punctuation and ")
            append("paragraph breaks where the speaker changes topic. No timestamps, no speaker labels, no commentary.")
            if (language != null) append(" The audio is in language code '$language'.")
            append(" If there is no speech, output nothing.")
        }
        val data = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "audio/m4a")
                                put("data", data)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject { put("temperature", 0) })
        }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(Http.JSON))
            .build()
        return Http.execute(request) { r ->
            if (!r.isSuccessful) throw Http.failure("Gemini", r)
            val json = Http.parseJson("Gemini", r.body.string())
            json.at("promptFeedback", "blockReason").text()?.let { throw SttException.Permanent("Gemini refused the audio ($it)") }
            val candidate = json.at("candidates", 0) ?: throw SttException.Transient("Gemini returned no answer")
            val finish = candidate.at("finishReason").text()
            if (finish != null && finish !in setOf("STOP", "MAX_TOKENS")) throw SttException.Permanent("Gemini stopped early ($finish)")
            // No parts with STOP means Gemini heard no speech, which the prompt allows.
            val parts = candidate.at("content", "parts") as? JsonArray ?: return@execute ""
            parts.mapNotNull { it.at("text").text() }.joinToString("").trim()
        }
    }
}

class AssemblyAiProvider(private val apiKey: String, private val model: String) : SttProvider {
    override val id = ProviderId.ASSEMBLYAI

    override suspend fun transcribe(audio: File, language: String?): String {
        val uploadUrl = Http.execute(
            Request.Builder().url("$BASE/v2/upload").header("authorization", apiKey)
                .post(audio.asRequestBody("application/octet-stream".toMediaTypeCompat())).build()
        ) { r ->
            if (!r.isSuccessful) throw Http.failure("AssemblyAI", r)
            Http.parseJson("AssemblyAI", r.body.string()).at("upload_url").text()
                ?: throw SttException.Transient("AssemblyAI upload failed")
        }
        val job = buildJsonObject {
            put("audio_url", uploadUrl)
            put("speech_models", buildJsonArray {
                add(JsonPrimitive(model))
                if (model != "universal-2") add(JsonPrimitive("universal-2"))
            })
            if (language != null) put("language_code", language) else put("language_detection", true)
        }
        val id = Http.execute(
            Request.Builder().url("$BASE/v2/transcript").header("authorization", apiKey)
                .post(job.toString().toRequestBody(Http.JSON)).build()
        ) { r ->
            if (!r.isSuccessful) throw Http.failure("AssemblyAI", r)
            Http.parseJson("AssemblyAI", r.body.string()).at("id").text()
                ?: throw SttException.Transient("AssemblyAI didn't start the job")
        }
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val (status, text, error) = Http.execute(
                Request.Builder().url("$BASE/v2/transcript/$id").header("authorization", apiKey).get().build()
            ) { r ->
                if (!r.isSuccessful) throw Http.failure("AssemblyAI", r)
                val j = Http.parseJson("AssemblyAI", r.body.string())
                Triple(j.at("status").text(), j.at("text").text(), j.at("error").text())
            }
            when (status) {
                "completed" -> return text?.trim().orEmpty()
                "error" -> throw SttException.Permanent("AssemblyAI: ${error ?: "transcription failed"}")
            }
        }
        throw SttException.Transient("AssemblyAI took too long")
    }

    private fun String.toMediaTypeCompat() = okhttp3.MediaType.Companion.run { toMediaType() }

    private companion object {
        const val BASE = "https://api.assemblyai.com"
    }
}
