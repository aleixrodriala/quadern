package io.github.aleixrodriala.quadern.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * API keys and OAuth tokens, encrypted with an AES-GCM key that never leaves the Android Keystore.
 * One small file, rewritten atomically, so a crash mid-write can never lose a rotated refresh token.
 */
class SecretStore(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("secrets.bin"))
    private val mutex = Mutex()
    private var cache: Map<String, String>? = null
    private val _version = MutableStateFlow(0)

    /** Bumps on every write so UI can re-read "is a key set?" state. */
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun get(name: String): String? = mutex.withLock { load()[name] }

    suspend fun put(name: String, value: String?) = mutex.withLock {
        val next = load().toMutableMap()
        if (value.isNullOrEmpty()) next.remove(name) else next[name] = value
        save(next)
        _version.value++
    }

    private suspend fun load(): Map<String, String> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) return@withContext emptyMap()
            runCatching {
                val bytes = file.readFully()
                val iv = bytes.copyOfRange(1, 1 + bytes[0])
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                val plain = cipher.doFinal(bytes, 1 + iv.size, bytes.size - 1 - iv.size)
                (Json.parseToJsonElement(plain.decodeToString()) as JsonObject)
                    .mapValues { it.value.jsonPrimitive.content }
            }.getOrElse {
                // A restored backup or a reset keystore makes old ciphertext unreadable. Start clean
                // rather than crash; the user re-enters keys or signs in again.
                Log.w("SecretStore", "Could not read secrets, starting empty", it)
                emptyMap()
            }
        }
        cache = loaded
        return loaded
    }

    private suspend fun save(values: Map<String, String>) = withContext(Dispatchers.IO) {
        val plain = JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString().encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        val out = file.startWrite()
        try {
            out.write(byteArrayOf(iv.size.toByte()))
            out.write(iv)
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: Throwable) {
            file.failWrite(out)
            throw e
        }
        cache = values
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "quadern-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
