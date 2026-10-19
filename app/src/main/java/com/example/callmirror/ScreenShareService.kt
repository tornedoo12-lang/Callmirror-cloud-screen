package com.example.callmirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shares this phone's screen with the Receiver (encrypted):
 *  - MODE_LOCAL: live JPEG stream over Wi-Fi/Hotspot (~3 fps)
 *  - MODE_CLOUD: slow snapshots (every ~10 s) through the cloud relay
 * Requires the system screen-capture consent dialog and shows a persistent notification.
 */
class ScreenShareService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        const val ACTION_STOP = "com.example.callmirror.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_TARGET = "target_ip"
        const val EXTRA_MODE = "share_mode"
        const val MODE_LOCAL = "local"
        const val MODE_CLOUD = "cloud"

        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIF_ID = 4711

        private const val LOCAL_LONG_SIDE = 960
        private const val LOCAL_JPEG_QUALITY = 55
        private const val LOCAL_INTERVAL_MS = 300L
        private const val LOCAL_HEARTBEAT_MS = 2000L

        // Cloud limits (ntfy.sh): ~1 request / 5 s, 100 MB stored per visitor for 3 h -> keep files small & rare.
        private const val CLOUD_LONG_SIDE = 720
        private const val CLOUD_JPEG_QUALITY = 45
        private const val CLOUD_BASE_INTERVAL_MS = 10_000L
        private const val CLOUD_MAX_INTERVAL_MS = 60_000L
        private const val CLOUD_HEARTBEAT_MS = 20_000L

        @Volatile var isRunning = false
        @Volatile var isCloudMode = false
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var seq = 0
    @Volatile private var lastSentAt = 0L
    @Volatile private var cloudInterval = CLOUD_BASE_INTERVAL_MS
    private val uploading = AtomicBoolean(false)

    private var lastCaptureAt = 0L
    private var lastJpeg: ByteArray? = null
    private var heldImage: Image? = null
    private var trailingScheduled = false
    private var width = 0
    private var height = 0
    private var code = ""
    private var cloudMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) {
            startAsForeground() // keep the foreground contract if started twice
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        code = prefs.getString("cloud_pairing_code", "") ?: ""
        cloudMode = intent?.getStringExtra(EXTRA_MODE) == MODE_CLOUD
        val target = intent?.getStringExtra(EXTRA_TARGET)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        // A foreground notification must be shown first (required for media projection).
        startAsForeground()

        val targetOk = cloudMode || !target.isNullOrBlank()
        if (!CryptoUtils.isStrongCode(code) || !targetOk || data == null || resultCode != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            projection = proj

            val thread = HandlerThread("cm-screen").also { it.start() }
            handlerThread = thread
            val h = Handler(thread.looper)
            handler = h

            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, h)

            setupCapture(proj, h)
            isCloudMode = cloudMode
            isRunning = true
            h.postDelayed(heartbeat, heartbeatPeriod())
            if (!cloudMode && target != null) startConnectionLoop(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen sharing")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val stopIntent = Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("CallMirror is sharing your screen")
            .setContentText(if (cloudMode) "Cloud snapshots every ~10 s. Tap Stop to end." else "Tap Stop to end screen sharing")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()

        ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun setupCapture(proj: MediaProjection, h: Handler) {
        val metrics = resources.displayMetrics
        val longSide = if (cloudMode) CLOUD_LONG_SIDE else LOCAL_LONG_SIDE
        val scale = Math.min(1f, longSide.toFloat() / Math.max(metrics.widthPixels, metrics.heightPixels))
        width = Math.max(2, (metrics.widthPixels * scale).toInt() / 2 * 2)
        height = Math.max(2, (metrics.heightPixels * scale).toInt() / 2 * 2)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(r: ImageReader) {
                handleImage(r)
            }
        }, h)

        virtualDisplay = proj.createVirtualDisplay(
            "CallMirrorScreen", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, h
        )
    }

    private fun currentInterval(): Long = when {
        cloudMode -> cloudInterval
        out != null -> LOCAL_INTERVAL_MS
        else -> 1000L // keep a fresh frame ready while waiting for the receiver
    }

    private val trailing = Runnable {
        trailingScheduled = false
        val img = heldImage
        heldImage = null
        if (img != null) encodeAndSend(img)
    }

    private fun handleImage(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        val wait = currentInterval() - (SystemClock.elapsedRealtime() - lastCaptureAt)
        if (wait <= 0) {
            heldImage?.close()
            heldImage = null
            encodeAndSend(image)
        } else {
            // Too soon: keep only the newest image and encode it when the interval is over,
            // so the receiver never ends up stuck on an outdated screen.
            heldImage?.close()
            heldImage = image
            if (!trailingScheduled) {
                trailingScheduled = true
                handler?.postDelayed(trailing, wait)
            }
        }
    }

    private fun encodeAndSend(image: Image) {
        try {
            lastCaptureAt = SystemClock.elapsedRealtime()
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, width, height).also { full.recycle() }

            val baos = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, if (cloudMode) CLOUD_JPEG_QUALITY else LOCAL_JPEG_QUALITY, baos)
            bmp.recycle()
            val jpeg = baos.toByteArray()
            lastJpeg = jpeg
            dispatch(jpeg)
        } catch (e: Exception) {
            Log.e(TAG, "Frame capture failed")
        } finally {
            image.close()
        }
    }

    private fun dispatch(jpeg: ByteArray) {
        if (cloudMode) uploadCloud(jpeg) else sendFrame(jpeg)
    }

    private fun sendFrame(jpeg: ByteArray) {
        val o = out ?: return
        try {
            ScreenProtocol.writeFrame(o, seq++, jpeg, code)
            lastSentAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            closeConnection()
        }
    }

    private fun uploadCloud(jpeg: ByteArray) {
        if (!uploading.compareAndSet(false, true)) return
        val frameSeq = (System.currentTimeMillis() / 1000).toInt()
        val c = code
        scope.launch {
            try {
                val status = CloudScreen.upload(c, jpeg, frameSeq)
                if (status in 200..299) {
                    lastSentAt = SystemClock.elapsedRealtime()
                    // Slowly speed back up after a back-off.
                    cloudInterval = Math.max(CLOUD_BASE_INTERVAL_MS, cloudInterval - 5000L)
                } else if (status == 413 || status == 429 || status == 507) {
                    // Relay says "too much": slow down.
                    cloudInterval = Math.min(CLOUD_MAX_INTERVAL_MS, cloudInterval * 2)
                    Log.w(TAG, "Cloud relay limit hit, slowing down")
                }
            } finally {
                uploading.set(false)
            }
        }
    }

    private fun heartbeatPeriod(): Long = if (cloudMode) CLOUD_HEARTBEAT_MS else LOCAL_HEARTBEAT_MS

    // Re-sends the last frame when the screen is static (keeps the receiver alive + gives it a first frame).
    private val heartbeat = object : Runnable {
        override fun run() {
            val jpeg = lastJpeg
            val period = heartbeatPeriod()
            val ready = cloudMode || out != null
            if (ready && jpeg != null && SystemClock.elapsedRealtime() - lastSentAt >= period) {
                dispatch(jpeg)
            }
            handler?.postDelayed(this, if (cloudMode) 5000L else period)
        }
    }

    private fun startConnectionLoop(target: String) {
        scope.launch {
            while (isActive) {
                if (out == null) {
                    try {
                        val s = Socket()
                        s.connect(InetSocketAddress(target, ScreenProtocol.PORT), 3000)
                        s.tcpNoDelay = true
                        seq = 0
                        socket = s
                        out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                    } catch (e: Exception) {
                        delay(2000)
                    }
                } else {
                    delay(1000)
                }
            }
        }
    }

    private fun closeConnection() {
        out = null
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
    }

    override fun onDestroy() {
        isRunning = false
        isCloudMode = false
        scope.cancel()
        handler?.removeCallbacksAndMessages(null)
        try { heldImage?.close() } catch (ignored: Exception) {}
        heldImage = null
        closeConnection()
        try { virtualDisplay?.release() } catch (ignored: Exception) {}
        try { imageReader?.close() } catch (ignored: Exception) {}
        try { projection?.stop() } catch (ignored: Exception) {}
        handlerThread?.quitSafely()
        virtualDisplay = null
        imageReader = null
        projection = null
        super.onDestroy()
    }
}
