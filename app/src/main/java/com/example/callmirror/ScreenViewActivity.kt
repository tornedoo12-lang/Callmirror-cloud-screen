package com.example.callmirror

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Receiver side of screen sharing. Listens while this screen is open and shows the frames
 * sent by the Transmitter. Only authenticated (encrypted with our pairing code) frames are shown.
 */
class ScreenViewActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var statusView: TextView
    private var code = ""
    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var lastBitmap: Bitmap? = null
    private val pending = AtomicInteger(0)
    private lateinit var modeLabel: TextView

    // Cloud snapshot listener (works over the internet, slow updates)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private var cloudSocket: WebSocket? = null
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastCloudSeq = -1
    @Volatile private var reconnectPending = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        code = prefs.getString("receiver_cloud_code", "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("cloud_pairing_code", "") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.black))
        }

        val frame = FrameLayout(this)
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        frame.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        statusView = TextView(this).apply {
            text = "Waiting for the other phone to share its screen…\n(Wi-Fi: live • Cloud: a snapshot every ~10 seconds)"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        frame.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        modeLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.white))
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        frame.addView(modeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(color(R.color.bg_dark))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val save = Button(this).apply {
            text = "📸 Save screenshot"
            isAllCaps = false
            setTextColor(color(R.color.white))
            backgroundTintList = ContextCompat.getColorStateList(this@ScreenViewActivity, R.color.purple_500)
            setOnClickListener { saveScreenshot() }
        }
        val close = Button(this).apply {
            text = "Close"
            isAllCaps = false
            setTextColor(color(R.color.text_primary))
            backgroundTintList = ContextCompat.getColorStateList(this@ScreenViewActivity, R.color.card_border)
            setOnClickListener { finish() }
        }
        bar.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, dp(6), 0) })
        bar.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        root.addView(bar)

        setContentView(root)

        if (!CryptoUtils.isStrongCode(code)) {
            Toast.makeText(this, "Set a Security Code first (at least ${CryptoUtils.MIN_CODE_LENGTH} characters)", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (CryptoUtils.isStrongCode(code)) startServer()
    }

    override fun onStop() {
        super.onStop()
        stopServer()
    }

    private fun startServer() {
        if (running) return
        running = true
        startCloudListener()
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(ScreenProtocol.PORT))
                serverSocket = ss
                val ip = NetworkUtils.getLocalIpAddress(this) ?: "this phone's IP"
                runOnUiThread { statusView.text = "Waiting for the other phone to share its screen…\n\nReceiver IP: $ip" }
                while (running) {
                    val s = ss.accept()
                    if (pending.get() >= 4) {
                        try { s.close() } catch (ignored: Exception) {}
                        continue
                    }
                    pending.incrementAndGet()
                    Thread { handleConnection(s) }.start()
                }
            } catch (e: Exception) {
                if (running) runOnUiThread { statusView.text = "Could not start listening (port ${ScreenProtocol.PORT} busy?)" }
            }
        }.start()
    }

    private fun stopServer() {
        running = false
        try { cloudSocket?.close(1000, null) } catch (ignored: Exception) {}
        cloudSocket = null
        try { serverSocket?.close() } catch (ignored: Exception) {}
        try { activeSocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
        activeSocket = null
    }

    private fun handleConnection(s: Socket) {
        try {
            s.soTimeout = 10_000
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
            var lastSeq = -1
            var authed = false
            while (running) {
                val f = ScreenProtocol.readFrame(input, code, lastSeq) ?: break
                lastSeq = f.seq
                if (!authed) {
                    // First valid frame proves the sender knows the code -> it takes over the stream.
                    authed = true
                    val old = activeSocket
                    activeSocket = s
                    if (old != null && old !== s) {
                        try { old.close() } catch (ignored: Exception) {}
                    }
                } else if (activeSocket !== s) {
                    break
                }
                val bmp = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) ?: continue
                runOnUiThread {
                    imageView.setImageBitmap(bmp)
                    lastBitmap = bmp
                    statusView.visibility = View.GONE
                    modeLabel.text = "🟢 Live (Wi-Fi)"
                    modeLabel.visibility = View.VISIBLE
                }
            }
        } catch (e: Exception) {
            // connection ended
        } finally {
            try { s.close() } catch (ignored: Exception) {}
            pending.decrementAndGet()
            if (activeSocket === s) {
                activeSocket = null
                runOnUiThread {
                    statusView.text = "Sharing ended. Waiting…"
                    statusView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startCloudListener() {
        val topic = CryptoUtils.screenTopicFor(code)
        val request = Request.Builder().url("wss://ntfy.sh/$topic/ws").build()
        cloudSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCloudEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleCloudReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleCloudReconnect()
            }
        })
    }

    private fun scheduleCloudReconnect() {
        if (!running || reconnectPending) return
        reconnectPending = true
        mainHandler.postDelayed({
            reconnectPending = false
            if (running) startCloudListener()
        }, 4000)
    }

    private fun handleCloudEvent(text: String) {
        try {
            val json = JSONObject(text)
            if (json.optString("event") != "message") return
            val att = json.optJSONObject("attachment") ?: return
            val url = att.optString("url")
            val size = att.optLong("size", 0L)
            if (url.isBlank() || size <= 0 || size > CloudScreen.MAX_DOWNLOAD) return
            cloudExecutor.execute {
                // Only authenticated + fresh + in-order snapshots are accepted.
                val enc = CloudScreen.download(url) ?: return@execute
                val frame = ScreenProtocol.decodeFrame(enc, code, lastCloudSeq) ?: return@execute
                lastCloudSeq = frame.seq
                val bmp = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: return@execute
                runOnUiThread {
                    if (activeSocket == null) { // a live Wi-Fi stream always wins
                        imageView.setImageBitmap(bmp)
                        lastBitmap = bmp
                        statusView.visibility = View.GONE
                        modeLabel.text = "☁️ Cloud snapshot • ${timeFormat.format(Date())}"
                        modeLabel.visibility = View.VISIBLE
                    }
                }
            }
        } catch (e: Exception) {
            // ignore malformed relay frames
        }
    }

    private fun saveScreenshot() {
        val bmp = lastBitmap
        if (bmp == null) {
            Toast.makeText(this, "No screen received yet", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "CallMirror_${System.currentTimeMillis()}.jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CallMirror")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("insert failed")
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Toast.makeText(this, "Saved to Pictures/CallMirror", Toast.LENGTH_SHORT).show()
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
                val file = File(dir, name)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save screenshot", Toast.LENGTH_SHORT).show()
        }
    }
}
