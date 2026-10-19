package com.example.callmirror

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Cloud relay over ntfy.sh. Everything that goes through the relay is AES-256-GCM encrypted
 * and authenticated (see CryptoUtils). The relay topic is derived one-way from the pairing code,
 * so the relay never sees the code. Unencrypted / forged messages are dropped.
 */
class CloudMirrorManager(
    private val context: Context,
    private val onMessageReceived: ((CallPayload) -> Unit)? = null,
    private val onConnectionStateChanged: ((Boolean) -> Unit)? = null
) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var currentPairingCode: String? = null
    private var shouldKeepConnected = false
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "CloudMirrorManager"
        private const val NTFY_WS_BASE = "wss://ntfy.sh"
        private const val NTFY_HTTP_BASE = "https://ntfy.sh"

        private val postClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Encrypts and publishes one message (blocking; call from a background thread). */
        fun publishOnce(pairingCode: String, payload: CallPayload): Boolean {
            if (!CryptoUtils.isStrongCode(pairingCode)) {
                Log.w(TAG, "Not sending to cloud: pairing code missing or too short")
                return false
            }
            val secure = CryptoUtils.encrypt(payload.toJson(), pairingCode) ?: return false
            return try {
                val request = Request.Builder()
                    .url("$NTFY_HTTP_BASE/${CryptoUtils.topicFor(pairingCode)}")
                    .post(secure.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                postClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.e(TAG, "Cloud publish error")
                false
            }
        }
    }

    fun connect(pairingCode: String) {
        if (!CryptoUtils.isStrongCode(pairingCode)) {
            Log.w(TAG, "Pairing code missing or too short, cloud disabled")
            return
        }

        currentPairingCode = pairingCode
        disconnect()
        shouldKeepConnected = true

        val topic = CryptoUtils.topicFor(pairingCode)
        val request = Request.Builder().url("$NTFY_WS_BASE/$topic/ws").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onConnectionStateChanged?.invoke(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    if (json.optString("event") != "message") return
                    val code = currentPairingCode ?: return
                    // Only authenticated + encrypted messages are accepted.
                    val plain = CryptoUtils.decrypt(json.optString("message"), code) ?: return
                    val payload = CallPayload.fromJson(plain) ?: return
                    onMessageReceived?.invoke(payload)
                } catch (e: Exception) {
                    // ignore malformed relay frames
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onConnectionStateChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                reconnectIfNeeded()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                reconnectIfNeeded()
            }
        })
    }

    private fun reconnectIfNeeded() {
        if (shouldKeepConnected && !currentPairingCode.isNullOrBlank()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (shouldKeepConnected && !isConnected) {
                    currentPairingCode?.let { connect(it) }
                }
            }, 4000)
        }
    }

    /** Fire-and-forget publish using the code given to connect(). */
    fun sendPayload(payload: CallPayload): Boolean {
        val code = currentPairingCode ?: return false
        if (!CryptoUtils.isStrongCode(code)) return false
        scope.launch { publishOnce(code, payload) }
        return true
    }

    fun disconnect() {
        shouldKeepConnected = false
        try {
            webSocket?.close(1000, "App disconnect")
        } catch (ignored: Exception) {}
        webSocket = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)
    }
}
