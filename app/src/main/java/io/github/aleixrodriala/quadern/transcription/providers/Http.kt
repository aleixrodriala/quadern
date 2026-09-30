package io.github.aleixrodriala.quadern.transcription.providers

import io.github.aleixrodriala.quadern.BuildConfig
import io.github.aleixrodriala.quadern.transcription.SttException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response

object Http {
    val userAgent = "Quadern/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(4, TimeUnit.MINUTES)
        .callTimeout(6, TimeUnit.MINUTES)
        // Never follow a redirect with an audio upload and a bearer token attached.
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", userAgent).build() else req)
        }
        .build()

    val json = Json { ignoreUnknownKeys = true }

    val M4A = "audio/mp4".toMediaType()
    val JSON = "application/json".toMediaType()

    fun filePart(builder: MultipartBody.Builder, audio: File, field: String = "file", mime: okhttp3.MediaType = M4A) =
        builder.addFormDataPart(field, audio.name, audio.asRequestBody(mime))

    /** Executes on IO and maps transport failures to [SttException.Transient]. */
    suspend fun <T> execute(request: Request, handle: (Response) -> T): T = withContext(Dispatchers.IO) {
        val call: Call = client.newCall(request)
        try {
            call.execute().use(handle)
        } catch (e: SttException) {
            throw e
        } catch (e: IOException) {
            throw SttException.Transient("Network error: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /**
     * Maps a non-2xx response to the right [SttException]. [provider] names the service in messages.
     * Response bodies can carry account details, so only a short, sanitized snippet is kept.
     */
    fun failure(provider: String, response: Response): SttException {
        val code = response.code
        val snippet = runCatching { response.peekBody(2048).string() }.getOrDefault("")
            .let { body -> extractMessage(body) ?: body.takeIf { !it.trimStart().startsWith("<") } }
            ?.replace(Regex("\\s+"), " ")?.take(200)
        val detail = if (snippet.isNullOrBlank()) "" else ": $snippet"
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        return when {
            code == 401 -> SttException.Auth("$provider rejected the credentials (401)$detail")
            code == 403 && response.header("Content-Type").orEmpty().contains("html") ->
                SttException.Transient("$provider blocked the request (403, likely Cloudflare)")
            code == 403 -> SttException.Auth("$provider refused access (403)$detail")
            code == 408 || code == 425 || code == 429 -> SttException.Transient("$provider is busy ($code)$detail", retryAfterSec = retryAfter)
            code in 500..599 -> SttException.Transient("$provider server error ($code)$detail", retryAfterSec = retryAfter)
            code in 300..399 -> SttException.Transient("$provider redirected the upload ($code)")
            else -> SttException.Permanent("$provider refused the audio ($code)$detail")
        }
    }

    private fun extractMessage(body: String): String? = runCatching {
        val el = json.parseToJsonElement(body)
        findMessage(el)
    }.getOrNull()

    private fun findMessage(el: JsonElement): String? {
        if (el !is JsonObject) return null
        for (key in listOf("message", "detail", "error_description", "error")) {
            val v = el[key] ?: continue
            if (v is JsonObject) findMessage(v)?.let { return it }
            else runCatching { v.toString().trim('"') }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun parseJson(provider: String, body: String): JsonElement = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw SttException.Transient("$provider returned an unreadable response", e)
    }
}
