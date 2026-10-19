package com.example.callmirror

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * Wire format for screen sharing (live over Wi-Fi, snapshots over the cloud):
 *   encrypted blob = AES-GCM( int64 timestamp | int32 sequence | JPEG bytes )
 * Local live stream: [int32 length][blob] repeated. Cloud: one blob per uploaded file.
 * Frames that fail authentication, are stale, or are out of order are rejected.
 */
object ScreenProtocol {
    const val PORT = 8090
    private const val MAX_FRAME = 3 * 1024 * 1024
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    class Frame(val seq: Int, val jpeg: ByteArray)

    fun encodeFrame(seq: Int, jpeg: ByteArray, code: String): ByteArray? {
        val buf = ByteBuffer.allocate(12 + jpeg.size)
        buf.putLong(System.currentTimeMillis())
        buf.putInt(seq)
        buf.put(jpeg)
        return CryptoUtils.encryptBytes(buf.array(), code)
    }

    /** Returns null for an invalid / stale / replayed frame. */
    fun decodeFrame(enc: ByteArray, code: String, lastSeq: Int): Frame? {
        val plain = CryptoUtils.decryptBytes(enc, code) ?: return null
        if (plain.size <= 12) return null
        val bb = ByteBuffer.wrap(plain)
        val ts = bb.long
        val seq = bb.int
        if (Math.abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) return null
        if (seq <= lastSeq) return null
        return Frame(seq, plain.copyOfRange(12, plain.size))
    }

    @Throws(IOException::class)
    fun writeFrame(out: DataOutputStream, seq: Int, jpeg: ByteArray, code: String) {
        val enc = encodeFrame(seq, jpeg, code) ?: throw IOException("encrypt failed")
        out.writeInt(enc.size)
        out.write(enc)
        out.flush()
    }

    @Throws(IOException::class)
    fun readFrame(input: DataInputStream, code: String, lastSeq: Int): Frame? {
        val len = input.readInt()
        if (len <= 28 || len > MAX_FRAME) return null
        val enc = ByteArray(len)
        input.readFully(enc)
        return decodeFrame(enc, code, lastSeq)
    }
}

/** Cloud relay for slow screen snapshots (ntfy.sh attachments, always encrypted). */
object CloudScreen {
    private const val BASE = "https://ntfy.sh"
    const val MAX_DOWNLOAD = 3 * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Uploads one encrypted snapshot. Returns the HTTP status, or -1 on failure. */
    fun upload(code: String, jpeg: ByteArray, seq: Int): Int {
        val enc = ScreenProtocol.encodeFrame(seq, jpeg, code) ?: return -1
        return try {
            val request = Request.Builder()
                .url("$BASE/${CryptoUtils.screenTopicFor(code)}")
                .header("Filename", "s.bin")
                .put(enc.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.code }
        } catch (e: Exception) {
            -1
        }
    }

    /** Downloads an attachment. Only https://ntfy.sh/... URLs are ever fetched. */
    fun download(url: String): ByteArray? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != "ntfy.sh") return null
        return try {
            client.newCall(Request.Builder().url(parsed).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                if (body.contentLength() > MAX_DOWNLOAD) return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                val input = body.byteStream()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DOWNLOAD) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }
}
