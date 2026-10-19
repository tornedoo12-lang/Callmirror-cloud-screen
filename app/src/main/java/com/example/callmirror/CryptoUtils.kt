package com.example.callmirror

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for every message (local Wi-Fi/Hotspot AND cloud).
 *
 *  - Key: PBKDF2-HMAC-SHA256 (150k iterations) derived from the pairing code.
 *  - Cipher: AES-256-GCM with a fresh random 96-bit IV for every message
 *    (GCM also authenticates the message, so forged/modified data is rejected).
 *  - Replay protection: timestamp window + duplicate-IV rejection.
 *  - The cloud topic name is derived separately, so the code itself is never sent anywhere.
 *  - Messages that are not encrypted/authenticated are always rejected.
 */
object CryptoUtils {

    private const val TAG = "CryptoUtils"
    private const val PREFIX = "ENC2:"
    private const val ITERATIONS = 150_000
    private const val SALT = "callmirror.v2.pairing.salt"
    private val AAD = "callmirror-v2".toByteArray(Charsets.UTF_8)
    private const val IV_LEN = 12
    private const val MAX_AGE_MS = 10 * 60 * 1000L
    const val MIN_CODE_LENGTH = 12

    private class Keys(val aesKey: SecretKeySpec, val topic: String, val screenTopic: String)

    private val keyCache = ConcurrentHashMap<String, Keys>()
    private val secureRandom = SecureRandom()

    // ---- Replay guard: remembers recently seen IVs ----
    private val seenIvs = object : LinkedHashMap<String, Long>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 512
    }

    private fun isReplay(ivB64: String): Boolean = synchronized(seenIvs) {
        if (seenIvs.containsKey(ivB64)) true else {
            seenIvs[ivB64] = System.currentTimeMillis()
            false
        }
    }

    /** Normalizes user input so both phones always derive the same key. */
    fun normalizeCode(code: String): String =
        code.trim().filter { it.isLetterOrDigit() }.lowercase()

    fun isStrongCode(code: String): Boolean = normalizeCode(code).length >= MIN_CODE_LENGTH

    /** 20 random characters (~100 bits), formatted XXXXX-XXXXX-XXXXX-XXXXX. */
    fun generateStrongCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no look-alike chars
        val raw = (1..20).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
        return raw.chunked(5).joinToString("-")
    }

    private fun keysFor(code: String): Keys {
        val clean = normalizeCode(code)
        return keyCache.getOrPut(clean) {
            val spec = PBEKeySpec(clean.toCharArray(), SALT.toByteArray(Charsets.UTF_8), ITERATIONS, 512)
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val aes = SecretKeySpec(derived.copyOfRange(0, 32), "AES")
            val topicBytes = derived.copyOfRange(32, 48)
            val topic = topicBytes.joinToString("") { "%02x".format(it) }
            val screen = MessageDigest.getInstance("SHA-256")
                .digest("screen".toByteArray(Charsets.UTF_8) + topicBytes)
                .copyOfRange(0, 16)
                .joinToString("") { "%02x".format(it) }
            Keys(aes, "cm2_$topic", "cs2_$screen")
        }
    }

    /** Cloud topic name (derived one-way; does not reveal the pairing code). */
    fun topicFor(code: String): String = keysFor(code).topic

    /** Separate cloud topic used only for screen snapshots. */
    fun screenTopicFor(code: String): String = keysFor(code).screenTopic

    /** Returns null on failure — callers must NOT send anything unencrypted. */
    fun encrypt(plainText: String, code: String): String? {
        return try {
            val keys = keysFor(code)
            val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keys.aesKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            val ct = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed")
            null
        }
    }

    private val FRAME_AAD = "callmirror-v2-frame".toByteArray(Charsets.UTF_8)

    /** Binary AES-256-GCM (used for screen frames). Output = IV(12) + ciphertext+tag. */
    fun encryptBytes(data: ByteArray, code: String): ByteArray? {
        return try {
            val keys = keysFor(code)
            val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keys.aesKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(FRAME_AAD)
            iv + cipher.doFinal(data)
        } catch (e: Exception) {
            null
        }
    }

    fun decryptBytes(data: ByteArray, code: String): ByteArray? {
        return try {
            if (data.size <= IV_LEN + 16) return null
            val iv = data.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keysFor(code).aesKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(FRAME_AAD)
            cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
        } catch (e: Exception) {
            null
        }
    }

    /** Returns null when the message is not encrypted, tampered, wrong key, replayed or too old. */
    fun decrypt(message: String, code: String): String? {
        if (!message.startsWith(PREFIX)) return null // reject unencrypted messages
        return try {
            val data = Base64.decode(message.substring(PREFIX.length), Base64.NO_WRAP)
            if (data.size <= IV_LEN + 16) return null
            val iv = data.copyOfRange(0, IV_LEN)
            val ivKey = Base64.encodeToString(iv, Base64.NO_WRAP)

            val keys = keysFor(code)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys.aesKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            val plain = String(cipher.doFinal(data, IV_LEN, data.size - IV_LEN), Charsets.UTF_8)

            // Authentic. Now check freshness / replay.
            val ts = org.json.JSONObject(plain).optLong("timestamp", 0L)
            if (Math.abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) {
                Log.w(TAG, "Rejected stale message")
                return null
            }
            if (isReplay(ivKey)) {
                Log.w(TAG, "Rejected replayed message")
                return null
            }
            plain
        } catch (e: Exception) {
            Log.w(TAG, "Rejected invalid message")
            null
        }
    }
}
