package io.github.aleixrodriala.quadern.transcription

import java.io.File

/** One speech-to-text backend. Implementations must be safe to call concurrently. */
interface SttProvider {
    val id: ProviderId

    /**
     * Transcribes one chunk (an .m4a of at most a few minutes). [language] is an ISO-639-1 hint or
     * null for auto-detect. Throws [SttException] for every failure.
     */
    suspend fun transcribe(audio: File, language: String?): String
}

/**
 * Why a transcription failed, classified by what should happen next. The worker never
 * string-matches messages; it only looks at the type.
 */
sealed class SttException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Network trouble, timeouts, 5xx, 429: try the same request again later. */
    class Transient(message: String, cause: Throwable? = null, val retryAfterSec: Long? = null) : SttException(message, cause)

    /** Credentials are gone or rejected; the user has to sign in again or fix the key. */
    class Auth(message: String) : SttException(message)

    /** The provider isn't set up (no key, not signed in, model not downloaded). */
    class NotConfigured(message: String) : SttException(message)

    /** The provider refused this audio for good (bad format, too large...). */
    class Permanent(message: String) : SttException(message)
}
