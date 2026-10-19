package com.example.callmirror

import android.util.Log
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Encrypted + authenticated local (Wi-Fi / Hotspot) transport. */
object SecureTransport {

    private const val TAG = "SecureTransport"
    private const val MAX_LINE = 16 * 1024
    private const val READ_TIMEOUT_MS = 5000

    /** Sends one encrypted line. Returns false (and sends nothing) if the code is missing/weak. */
    fun send(ip: String, port: Int, payload: CallPayload, code: String, connectTimeoutMs: Int = 2500): Boolean {
        if (!CryptoUtils.isStrongCode(code)) {
            Log.w(TAG, "Not sending: pairing code missing or too short")
            return false
        }
        val encrypted = CryptoUtils.encrypt(payload.toJson(), code) ?: return false
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, port), connectTimeoutMs)
            val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)
            writer.println(encrypted)
            writer.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Local send failed")
            false
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
        }
    }

    /** Reads one bounded line from the socket and returns the decrypted payload, or null if invalid. */
    fun receive(socket: Socket, code: String): CallPayload? {
        if (!CryptoUtils.isStrongCode(code)) return null
        return try {
            socket.soTimeout = READ_TIMEOUT_MS
            val line = readBoundedLine(socket.getInputStream()) ?: return null
            val plain = CryptoUtils.decrypt(line.trim(), code) ?: return null
            CallPayload.fromJson(plain)
        } catch (e: Exception) {
            null
        }
    }

    private fun readBoundedLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return if (sb.isEmpty() || sb.length >= MAX_LINE) null else sb.toString()
    }
}
