package com.example.callmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Socket

class TransmitterService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var prefs: SharedPreferences

    companion object {
        const val TAG = "TransmitterService"
        const val CHANNEL_ID = "transmitter_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.callmirror.TRANSMITTER_START"
        const val ACTION_STOP = "com.example.callmirror.TRANSMITTER_STOP"
        const val ACTION_SEND_CALL_STATE = "com.example.callmirror.TRANSMITTER_SEND"

        const val EXTRA_STATE = "extra_state"
        const val EXTRA_NUMBER = "extra_number"

        var isRunning = false
            private set

        var instance: TransmitterService? = null
            private set

        fun broadcastNotificationPayload(payload: CallPayload) {
            instance?.dispatchNotificationPayload(payload)
        }
    }

    private var cloudMirrorManager: CloudMirrorManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        createNotificationChannel()

        val isRemoteMode = prefs.getInt("app_mode", 0) == 2 // MODE_REMOTE
        val pairingCode = prefs.getString("cloud_pairing_code", "") ?: ""

        if (isRemoteMode && pairingCode.isNotBlank()) {
            cloudMirrorManager = CloudMirrorManager(
                context = applicationContext,
                onMessageReceived = { payload ->
                    if (payload.status == CallPayload.STATUS_REJECT) {
                        Log.d(TAG, "Received authenticated REJECT command")
                        rejectIncomingCall()
                    }
                }
            )
            cloudMirrorManager?.connect(pairingCode)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            cloudMirrorManager?.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_SEND_CALL_STATE) {
            val state = intent?.getStringExtra(EXTRA_STATE) ?: ""
            val number = intent?.getStringExtra(EXTRA_NUMBER)
            handleCallStateChange(state, number)
            return START_STICKY
        }

        // Start Foreground Service
        val notification = buildNotification("Monitoring incoming calls (Hotspot & Cloud)...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isRunning = true
            startCommandListener()

            val enableCloudTransmit = prefs.getBoolean("enable_cloud_transmit", true)
            val pairingCode = prefs.getString("cloud_pairing_code", "") ?: ""
            if (enableCloudTransmit && pairingCode.isNotBlank() && cloudMirrorManager == null) {
                Log.d(TAG, "Starting CloudMirrorManager for Transmitter")
                cloudMirrorManager = CloudMirrorManager(
                    context = applicationContext,
                    onMessageReceived = { payload ->
                        if (payload.status == CallPayload.STATUS_REJECT) {
                            Log.d(TAG, "Received authenticated REJECT command")
                            rejectIncomingCall()
                        }
                    }
                )
                cloudMirrorManager?.connect(pairingCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not startForeground: ${e.message}")
            isRunning = false
        }

        return START_STICKY
    }

    private var commandServerSocket: java.net.ServerSocket? = null

    private fun startCommandListener() {
        val cmdPort = prefs.getInt("transmitter_cmd_port", 8081)
        scope.launch {
            try {
                commandServerSocket?.close()
                commandServerSocket = java.net.ServerSocket(cmdPort)
                Log.d(TAG, "Transmitter command listener started on port $cmdPort")

                while (isRunning) {
                    val clientSocket = try {
                        commandServerSocket?.accept() ?: break
                    } catch (e: Exception) {
                        break
                    }

                    launch {
                        handleCommandClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command listener exception on port $cmdPort: ${e.message}")
            }
        }
    }

    private fun handleCommandClient(socket: Socket) {
        try {
            val code = prefs.getString("cloud_pairing_code", "") ?: ""
            val payload = SecureTransport.receive(socket, code)
            if (payload?.status == CallPayload.STATUS_REJECT) {
                rejectIncomingCall()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling command: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun rejectIncomingCall() {
        Log.d(TAG, "Rejecting incoming phone call via TelecomManager...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ANSWER_PHONE_CALLS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    @Suppress("DEPRECATION")
                    telecomManager?.endCall()
                    Log.d(TAG, "telecomManager.endCall() executed successfully")
                } else {
                    Log.w(TAG, "ANSWER_PHONE_CALLS permission not granted on transmitter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call: ${e.message}")
        }
    }

    private fun handleCallStateChange(stateStr: String, number: String?) {
        val receiverIpRaw = prefs.getString("receiver_ip", "") ?: ""
        val receiverPort = prefs.getInt("receiver_port", 8080)

        // Support up to 4 receiver devices (separated by commas, semicolons, or spaces)
        val targetIps = if (receiverIpRaw.isNotBlank()) {
            receiverIpRaw.split("[,;\\s]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4) // Max 4 receiver devices
        } else {
            emptyList()
        }

        scope.launch {
            when (stateStr) {
                "RINGING", TelephonyManager.EXTRA_STATE_RINGING -> {
                    var callerName: String? = null
                    val callerNumber = number

                    if (!callerNumber.isNullOrBlank()) {
                        callerName = ContactUtils.getContactName(applicationContext, callerNumber)
                    }

                    val payload = CallPayload(
                        status = CallPayload.STATUS_RINGING,
                        number = callerNumber ?: "Unknown",
                        name = callerName ?: callerNumber ?: "Unknown Caller"
                    )
                    
                    // Dispatch concurrently to all target devices on local network
                    targetIps.forEach { ip ->
                        launch {
                            sendPayload(ip, receiverPort, payload)
                        }
                    }

                    // Always dispatch to Cloud Relay if configured (even without local receiver IP)
                    cloudMirrorManager?.sendPayload(payload)
                    Log.d(TAG, "Dispatched RINGING payload to cloud and ${targetIps.size} local devices")
                }
                "OFFHOOK", "IDLE", TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                    val payload = CallPayload(
                        status = CallPayload.STATUS_ENDED
                    )
                    targetIps.forEach { ip ->
                        launch {
                            sendPayload(ip, receiverPort, payload)
                        }
                    }

                    cloudMirrorManager?.sendPayload(payload)
                    Log.d(TAG, "Dispatched ENDED payload to cloud and ${targetIps.size} local devices")
                }
            }
        }
    }

    private fun sendPayload(ip: String, port: Int, payload: CallPayload) {
        val code = prefs.getString("cloud_pairing_code", "") ?: ""
        SecureTransport.send(ip, port, payload, code)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transmitter Call Mirror Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone calls and mirrors them to Receiver device"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, TransmitterService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallMirror: Transmitter Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun dispatchNotificationPayload(payload: CallPayload) {
        val receiverIpRaw = prefs.getString("receiver_ip", "") ?: ""
        val receiverPort = prefs.getInt("receiver_port", 8080)

        val targetIps = if (receiverIpRaw.isNotBlank()) {
            receiverIpRaw.split("[,;\\s]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        } else {
            emptyList()
        }

        scope.launch {
            targetIps.forEach { ip ->
                launch {
                    sendPayload(ip, receiverPort, payload)
                }
            }
            cloudMirrorManager?.sendPayload(payload)
            Log.d(TAG, "Dispatched NOTIFICATION payload to cloud and ${targetIps.size} local devices")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isRunning = false
        try {
            commandServerSocket?.close()
        } catch (ignored: Exception) {}
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
