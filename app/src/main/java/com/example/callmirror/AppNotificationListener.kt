package com.example.callmirror

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "AppNotificationListener"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        if (packageName == applicationContext.packageName) return // Ignore own notifications

        val prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        val mirrorNotifications = prefs.getBoolean("mirror_notifications", true)
        if (!mirrorNotifications) return

        val currentMode = prefs.getInt("app_mode", 0)
        val isCloudTransmitter = currentMode == 2 && prefs.getInt("cloud_role", 0) == 0
        val isTransmitterMode = currentMode == 0 || isCloudTransmitter
        if (!isTransmitterMode) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence("android.title")?.toString() ?: ""

        // Extract message body thoroughly (supports WhatsApp single/group/multiline messages)
        var messageBody = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
        if (messageBody.isNullOrBlank()) {
            messageBody = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        }
        if (messageBody.isNullOrBlank()) {
            val textLines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)
            if (!textLines.isNullOrEmpty()) {
                messageBody = textLines.joinToString("\n")
            }
        }
        if (messageBody.isNullOrBlank()) {
            // Android MessagingStyle bundle extraction (standard WhatsApp format)
            val messagesBundle = extras.getParcelableArray(android.app.Notification.EXTRA_MESSAGES)
            if (!messagesBundle.isNullOrEmpty()) {
                val lastMsg = messagesBundle.lastOrNull() as? android.os.Bundle
                messageBody = lastMsg?.getCharSequence("text")?.toString()
            }
        }

        val text = messageBody ?: ""
        if (title.isBlank() && text.isBlank()) return

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, "Captured notification from $appName")

        val payload = CallPayload(
            status = CallPayload.STATUS_NOTIFICATION,
            appName = appName,
            title = title,
            text = text
        )

        // Send via TransmitterService if running
        if (TransmitterService.isRunning) {
            TransmitterService.broadcastNotificationPayload(payload)
            return
        }

        // Fallback dispatch if service was not in memory
        val receiverIpRaw = prefs.getString("receiver_ip", "") ?: ""
        val receiverPort = prefs.getInt("receiver_port", 8080)
        val pairingCode = prefs.getString("cloud_pairing_code", "") ?: ""

        scope.launch {
            if (receiverIpRaw.isNotBlank()) {
                val targetIps = receiverIpRaw.split("[,;\\s]+".toRegex())
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(4)
                targetIps.forEach { ip ->
                    SecureTransport.send(ip, receiverPort, payload, pairingCode)
                }
            }

            if (prefs.getBoolean("enable_cloud_transmit", true) && pairingCode.isNotBlank()) {
                CloudMirrorManager.publishOnce(pairingCode, payload)
            }
        }
    }
}
