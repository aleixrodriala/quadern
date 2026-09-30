package io.github.aleixrodriala.quadern.auth

import android.util.Base64
import io.github.aleixrodriala.quadern.data.SecretStore
import io.github.aleixrodriala.quadern.transcription.SttException
import io.github.aleixrodriala.quadern.transcription.providers.Http
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * "Sign in with ChatGPT", the same OAuth client the Codex CLI uses. The resulting token is what the
 * ChatGPT apps send to their own voice-to-text endpoint, so transcription runs on the user's
 * subscription instead of a paid API key.
 *
 * Refresh tokens rotate on every use, so refreshing is serialized and the new pair is persisted
 * before the new access token is handed out: a crash can never strand the only valid refresh token
 * in memory.
 */
class ChatGptAuth(private val secrets: SecretStore) {
    @Serializable
    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val accountId: String,
        val email: String? = null,
        val plan: String? = null,
        val lastRefresh: Long = System.currentTimeMillis(),
    )

    data class Account(val email: String?, val plan: String?)
    data class Credentials(val accessToken: String, val accountId: String)

    /** A browser sign-in in progress; kept until the redirect comes back. */
    data class PkceAttempt(val verifier: String, val state: String, val authorizeUrl: String)

    data class DeviceCode(val deviceAuthId: String, val userCode: String, val intervalSec: Long, val verificationUrl: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _account = MutableStateFlow<Account?>(null)
    private val _loaded = MutableStateFlow(false)

    /** Signed-in account, or null. */
    val account: StateFlow<Account?> = _account.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            val t = load()
            _account.value = t?.let { Account(it.email, it.plan) }
            _loaded.value = true
        }
    }

    suspend fun isSignedIn(): Boolean = load() != null

    /**
     * A valid access token, refreshed first if it expires within five minutes or [forceRefresh].
     * Throws [SttException.NotConfigured] when signed out, [SttException.Auth] when the session is
     * dead, [SttException.Transient] when the refresh couldn't reach OpenAI.
     */
    suspend fun credentials(forceRefresh: Boolean = false): Credentials = mutex.withLock {
        val tokens = load() ?: throw SttException.NotConfigured("Sign in with ChatGPT to transcribe")
        val expiresAt = jwtExpiry(tokens.accessToken)
        val stale = expiresAt == null || expiresAt - System.currentTimeMillis() < 5 * 60_000 ||
            System.currentTimeMillis() - tokens.lastRefresh > 8L * 24 * 3600_000
        val fresh = if (forceRefresh || stale) refreshLocked(tokens) else tokens
        Credentials(fresh.accessToken, fresh.accountId)
    }

    private suspend fun refreshLocked(tokens: Tokens): Tokens {
        val body = buildJsonObject {
            put("client_id", JsonPrimitive(CLIENT_ID))
            put("grant_type", JsonPrimitive("refresh_token"))
            put("refresh_token", JsonPrimitive(tokens.refreshToken))
            put("scope", JsonPrimitive("openid profile email"))
        }.toString().toRequestBody(Http.JSON)
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        val json = Http.execute(request) { response ->
            val text = response.body.string()
            when {
                response.isSuccessful -> Http.parseJson("ChatGPT", text).jsonObject
                // Invalid, expired, reused or revoked refresh token: only these end the session.
                (response.code == 400 || response.code == 401) && !text.trimStart().startsWith("<") -> null
                else -> throw SttException.Transient("ChatGPT sign-in is unavailable right now (${response.code})")
            }
        }
        if (json == null) {
            signOutLocked()
            throw SttException.Auth("Your ChatGPT session expired. Sign in again to transcribe.")
        }
        val access = json.str("access_token") ?: throw SttException.Transient("ChatGPT sign-in returned no token")
        val next = tokens.copy(
            accessToken = access,
            refreshToken = json.str("refresh_token") ?: tokens.refreshToken,
            idToken = json.str("id_token") ?: tokens.idToken,
            lastRefresh = System.currentTimeMillis(),
        ).withClaims()
        save(next)
        return next
    }

    // --- Browser sign-in (PKCE + loopback redirect) ---

    fun newPkceAttempt(): PkceAttempt {
        val verifier = randomToken(64)
        val state = randomToken(32)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", "openid profile email offline_access api.connectors.read api.connectors.invoke")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .build()
        return PkceAttempt(verifier, state, url.toString())
    }

    /**
     * Pulls the authorization code out of a redirect URL (or a pasted copy of it) and checks it
     * belongs to [attempt].
     */
    fun codeFromRedirect(attempt: PkceAttempt, redirect: String): String {
        val text = redirect.trim().trim('"')
        val query = text.substringAfter('?', text)
        val params = query.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        params["error"]?.let { throw IOException(params["error_description"] ?: it) }
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: throw IOException("That link has no sign-in code")
        if (params["state"] != attempt.state) throw IOException("That link belongs to a different sign-in attempt")
        return code
    }

    suspend fun completePkce(attempt: PkceAttempt, code: String) =
        exchange(code, attempt.verifier, REDIRECT_URI)

    // --- Device code sign-in (for when the browser redirect can't reach the app) ---

    suspend fun requestDeviceCode(): DeviceCode {
        val body = buildJsonObject { put("client_id", JsonPrimitive(CLIENT_ID)) }.toString().toRequestBody(Http.JSON)
        val json = Http.execute(Request.Builder().url("$ISSUER/api/accounts/deviceauth/usercode").post(body).build()) { r ->
            val text = r.body.string()
            if (r.code == 404) throw IOException("Device code sign-in isn't available right now")
            if (!r.isSuccessful) throw IOException("Couldn't start device sign-in (${r.code})")
            Http.parseJson("ChatGPT", text).jsonObject
        }
        return DeviceCode(
            deviceAuthId = json.str("device_auth_id") ?: throw IOException("Bad device code response"),
            userCode = json.str("user_code") ?: json.str("usercode") ?: throw IOException("Bad device code response"),
            intervalSec = json.str("interval")?.trim()?.toLongOrNull() ?: 5,
            verificationUrl = "$ISSUER/codex/device",
        )
    }

    /** Polls once. Returns true when signed in, false while the user hasn't approved yet. */
    suspend fun pollDeviceCode(code: DeviceCode): Boolean {
        val body = buildJsonObject {
            put("device_auth_id", JsonPrimitive(code.deviceAuthId))
            put("user_code", JsonPrimitive(code.userCode))
        }.toString().toRequestBody(Http.JSON)
        val json = Http.execute(Request.Builder().url("$ISSUER/api/accounts/deviceauth/token").post(body).build()) { r ->
            val text = r.body.string()
            when {
                r.isSuccessful -> Http.parseJson("ChatGPT", text).jsonObject
                r.code == 403 || r.code == 404 -> null // still pending
                else -> throw IOException("Device sign-in failed (${r.code})")
            }
        } ?: return false
        val authCode = json.str("authorization_code") ?: throw IOException("Bad device sign-in response")
        val verifier = json.str("code_verifier") ?: throw IOException("Bad device sign-in response")
        exchange(authCode, verifier, "$ISSUER/deviceauth/callback")
        return true
    }

    private suspend fun exchange(code: String, verifier: String, redirectUri: String) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val json = Http.execute(Request.Builder().url(TOKEN_URL).post(form).build()) { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw IOException("Sign-in failed (${r.code})")
            Http.parseJson("ChatGPT", text).jsonObject
        }
        val tokens = Tokens(
            accessToken = json.str("access_token") ?: throw IOException("Sign-in returned no access token"),
            refreshToken = json.str("refresh_token") ?: throw IOException("Sign-in returned no refresh token"),
            idToken = json.str("id_token") ?: "",
            accountId = "",
        ).withClaims()
        if (tokens.accountId.isBlank()) throw IOException("This account has no ChatGPT workspace")
        mutex.withLock { save(tokens) }
    }

    suspend fun signOut() = mutex.withLock { signOutLocked() }

    private suspend fun signOutLocked() {
        secrets.put(KEY, null)
        cached = null
        _account.value = null
    }

    // --- storage ---

    private var cached: Tokens? = null

    private suspend fun load(): Tokens? {
        cached?.let { return it }
        val raw = secrets.get(KEY) ?: return null
        return runCatching { Http.json.decodeFromString<Tokens>(raw) }.getOrNull()?.also { cached = it }
    }

    private suspend fun save(tokens: Tokens) {
        secrets.put(KEY, Http.json.encodeToString(Tokens.serializer(), tokens))
        cached = tokens
        _account.value = Account(tokens.email, tokens.plan)
    }

    private fun Tokens.withClaims(): Tokens {
        val id = jwtClaims(idToken)
        val access = jwtClaims(accessToken)
        fun authClaim(claims: JsonObject?, key: String): String? =
            (claims?.get("https://api.openai.com/auth") as? JsonObject)?.str(key)
                ?: claims?.str("https://api.openai.com/auth.$key")
        val profile = access?.get("https://api.openai.com/profile") as? JsonObject
        return copy(
            accountId = authClaim(id, "chatgpt_account_id") ?: authClaim(access, "chatgpt_account_id") ?: accountId,
            email = id?.str("email") ?: profile?.str("email") ?: email,
            plan = authClaim(id, "chatgpt_plan_type") ?: authClaim(access, "chatgpt_plan_type") ?: plan,
        )
    }

    companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ISSUER = "https://auth.openai.com"
        const val AUTHORIZE_URL = "$ISSUER/oauth/authorize"
        const val TOKEN_URL = "$ISSUER/oauth/token"
        const val LOOPBACK_PORT = 1455
        const val REDIRECT_URI = "http://localhost:$LOOPBACK_PORT/auth/callback"
        private const val KEY = "chatgpt_tokens"

        private val random = SecureRandom()

        private fun randomToken(bytes: Int) = base64Url(ByteArray(bytes).also(random::nextBytes))
        private fun base64Url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        fun jwtClaims(token: String): JsonObject? = runCatching {
            val payload = token.split('.')[1]
            Http.json.parseToJsonElement(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).jsonObject
        }.getOrNull()

        fun jwtExpiry(token: String): Long? = jwtClaims(token)?.str("exp")?.toLongOrNull()?.times(1000)

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
