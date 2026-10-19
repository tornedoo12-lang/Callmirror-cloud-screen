package com.example.callmirror

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.callmirror.databinding.ActivityMainBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var currentMode = MODE_TRANSMITTER

    private var pendingScreenMode = ScreenShareService.MODE_LOCAL

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val isCloud = pendingScreenMode == ScreenShareService.MODE_CLOUD
            val ip = getCombinedIps().split("[,;\\s]+".toRegex()).firstOrNull { it.isNotBlank() }
            if (!isCloud && ip.isNullOrBlank()) {
                Toast.makeText(this, "Enter the Receiver IP first", Toast.LENGTH_SHORT).show()
            } else {
                val svc = Intent(this, ScreenShareService::class.java).apply {
                    putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenShareService.EXTRA_DATA, data)
                    putExtra(ScreenShareService.EXTRA_MODE, pendingScreenMode)
                    putExtra(ScreenShareService.EXTRA_TARGET, ip ?: "")
                }
                ContextCompat.startForegroundService(this, svc)
                val msg = if (isCloud) {
                    "Cloud sharing started (a snapshot every ~10 s). Open 'View Transmitter Screen' on the Receiver."
                } else {
                    "Screen sharing started. Open 'View Transmitter Screen' on the Receiver."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                binding.root.postDelayed({ updateScreenShareButton() }, 700)
            }
        } else {
            Toast.makeText(this, "Screen sharing cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val MODE_TRANSMITTER = 0
        const val MODE_RECEIVER = 1
        const val MODE_REMOTE = 2

        const val PREFS_KEY_MODE = "app_mode"
        const val PREFS_KEY_RECEIVER_IP = "receiver_ip"
        const val PREFS_KEY_RECEIVER_PORT = "receiver_port"
        const val PREFS_KEY_LISTEN_PORT = "listen_port"
        const val PREFS_KEY_SOUND = "enable_sound"
        const val PREFS_KEY_VIBRATION = "enable_vibration"
        const val PREFS_KEY_PAIRING_CODE = "cloud_pairing_code"
        const val PREFS_KEY_CLOUD_ROLE = "cloud_role" // 0: Transmitter, 1: Receiver
        const val PREFS_KEY_MIRROR_NOTIFICATIONS = "mirror_notifications"
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
            updateUiState()
        } else {
            Toast.makeText(this, "Required permissions not granted.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("call_mirror_prefs", Context.MODE_PRIVATE)
        currentMode = prefs.getInt(PREFS_KEY_MODE, MODE_TRANSMITTER)

        setupListeners()
        loadSavedPreferences()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        refreshLocalIp()
        updateUiState()
        updateScreenShareButton()
    }

    private fun setupListeners() {
        binding.tabLayoutMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentMode = tab?.position ?: MODE_TRANSMITTER
                prefs.edit().putInt(PREFS_KEY_MODE, currentMode).apply()
                updateUiState()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.tabLayoutMode.getTabAt(currentMode)?.select()

        binding.btnToggleService.setOnClickListener {
            toggleActiveService()
        }

        binding.btnShareScreen.setOnClickListener {
            if (ScreenShareService.isRunning) {
                startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
                binding.root.postDelayed({ updateScreenShareButton() }, 700)
            } else {
                if (!requireStrongCode()) return@setOnClickListener
                if (getCombinedIps().isBlank()) {
                    Toast.makeText(this, "Wi-Fi sharing needs the Receiver IP. For internet use 'Share Screen via Cloud'.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                saveInputs()
                pendingScreenMode = ScreenShareService.MODE_LOCAL
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        binding.btnShareScreenCloud.setOnClickListener {
            if (ScreenShareService.isRunning) {
                startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP))
                binding.root.postDelayed({ updateScreenShareButton() }, 700)
            } else {
                if (!requireStrongCode()) return@setOnClickListener
                saveInputs()
                pendingScreenMode = ScreenShareService.MODE_CLOUD
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        binding.btnViewScreen.setOnClickListener {
            startActivity(Intent(this, ScreenViewActivity::class.java))
        }

        binding.btnOpenArchive.setOnClickListener {
            startActivity(Intent(this, ArchiveActivity::class.java))
        }

        binding.switchArchive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_archive", isChecked).apply()
        }

        binding.btnTestMirror.setOnClickListener {
            sendTestCallSignal()
        }

        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_SOUND, isChecked).apply()
        }

        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_VIBRATION, isChecked).apply()
        }

        binding.switchEnableCloudTransmit.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_cloud_transmit", isChecked).apply()
        }

        binding.switchMirrorNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_MIRROR_NOTIFICATIONS, isChecked).apply()
            updateUiState()
        }

        binding.switchEnableCloudReceive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_receiver_cloud", isChecked).apply()
        }

        binding.btnNotificationAccessPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnGenerateCode.setOnClickListener {
            val newCode = CryptoUtils.generateStrongCode()
            binding.etPairingCode.setText(newCode)
            binding.etReceiverCloudCode.setText(newCode)
            saveInputs()
            Toast.makeText(this, "Secure code generated. Copy it to your other device(s).", Toast.LENGTH_LONG).show()
        }

        binding.btnCopyCode.setOnClickListener {
            val code = binding.etPairingCode.text.toString().trim()
            if (code.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("CallMirror Code", code)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedPreferences() {
        val detectedClientIp = NetworkUtils.getConnectedHotspotClientIp()
        val savedIp = prefs.getString(PREFS_KEY_RECEIVER_IP, null) ?: detectedClientIp ?: "10.48.54.3"
        val savedPort = prefs.getInt(PREFS_KEY_RECEIVER_PORT, 8080)
        
        // Split saved IP string across the 4 fields
        val ipList = savedIp.split("[,;\\s]+".toRegex()).filter { it.isNotBlank() }
        binding.etIpAddress1.setText(ipList.getOrNull(0) ?: savedIp)
        binding.etIpAddress2.setText(ipList.getOrNull(1) ?: "")
        binding.etIpAddress3.setText(ipList.getOrNull(2) ?: "")
        binding.etIpAddress4.setText(ipList.getOrNull(3) ?: "")

        binding.etPort.setText(savedPort.toString())

        val savedCode = prefs.getString(PREFS_KEY_PAIRING_CODE, "")
        binding.etPairingCode.setText(savedCode)

        val enableCloudTransmit = prefs.getBoolean("enable_cloud_transmit", true)
        binding.switchEnableCloudTransmit.isChecked = enableCloudTransmit
        binding.layoutCloudTransmitCodeBox.visibility = View.VISIBLE

        binding.switchArchive.isChecked = prefs.getBoolean("enable_archive", true)
        binding.switchSound.isChecked = prefs.getBoolean(PREFS_KEY_SOUND, true)
        binding.switchVibration.isChecked = prefs.getBoolean(PREFS_KEY_VIBRATION, true)
        binding.switchMirrorNotifications.isChecked = prefs.getBoolean(PREFS_KEY_MIRROR_NOTIFICATIONS, true)

        val enableRecCloud = prefs.getBoolean("enable_receiver_cloud", false)
        binding.switchEnableCloudReceive.isChecked = enableRecCloud
        binding.layoutReceiverCloudCode.visibility = View.VISIBLE
        binding.etReceiverCloudCode.setText(prefs.getString("receiver_cloud_code", savedCode))
    }

    private fun refreshLocalIp() {
        val localIp = NetworkUtils.getLocalIpAddress(this)
        if (localIp != null) {
            binding.tvDeviceIp.text = "IP: $localIp"
        } else {
            binding.tvDeviceIp.text = "IP: Offline / AP Mode"
        }
    }

    private fun getCombinedIps(): String {
        return listOf(
            binding.etIpAddress1.text.toString().trim(),
            binding.etIpAddress2.text.toString().trim(),
            binding.etIpAddress3.text.toString().trim(),
            binding.etIpAddress4.text.toString().trim()
        ).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun saveInputs() {
        val combinedIps = getCombinedIps()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 8080
        val code = binding.etPairingCode.text.toString().trim().uppercase()
        val recCloudCode = binding.etReceiverCloudCode.text.toString().trim().uppercase()

        val editor = prefs.edit()
        editor.putString(PREFS_KEY_PAIRING_CODE, code)
        editor.putString("receiver_cloud_code", recCloudCode.ifBlank { code })
        editor.putBoolean("enable_cloud_transmit", binding.switchEnableCloudTransmit.isChecked)
        editor.putBoolean("enable_receiver_cloud", binding.switchEnableCloudReceive.isChecked)

        if (currentMode == MODE_TRANSMITTER) {
            editor.putString(PREFS_KEY_RECEIVER_IP, combinedIps)
            editor.putInt(PREFS_KEY_RECEIVER_PORT, port)
        } else if (currentMode == MODE_RECEIVER) {
            editor.putInt(PREFS_KEY_LISTEN_PORT, port)
        }
        editor.apply()
    }

    private fun updateUiState() {
        refreshLocalIp()

        when (currentMode) {
            MODE_TRANSMITTER -> {
                binding.tvModeHeader.text = "Transmitter Mode (Hotspot Phone)"
                binding.tvModeDescription.text = "Runs on your hotspot phone. Detects incoming calls & WhatsApp and mirrors them to up to 4 receivers locally and/or over Cloud."
                binding.tilIpAddress.visibility = View.VISIBLE
                binding.tilPort.visibility = View.VISIBLE
                binding.layoutCloudSettings.visibility = View.VISIBLE
                binding.layoutReceiverToggles.visibility = View.GONE
                binding.btnTestMirror.visibility = View.VISIBLE
                binding.btnShareScreen.visibility = View.VISIBLE
                binding.btnShareScreenCloud.visibility = View.VISIBLE
                binding.btnOverlayPermission.visibility = View.GONE

                val isRunning = TransmitterService.isRunning
                if (isRunning) {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                    binding.tvStatusTitle.text = "Transmitter Service Active"
                    binding.tvStatusSubtitle.text = "Forwarding calls & notifications (Hotspot & Cloud)"
                    binding.btnToggleService.text = "Stop Transmitter Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
                } else {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_amber)
                    binding.tvStatusTitle.text = "Transmitter Stopped"
                    binding.tvStatusSubtitle.text = "Press start to begin monitoring calls & messages"
                    binding.btnToggleService.text = "Start Transmitter Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
                }
            }
            MODE_RECEIVER -> {
                binding.tvModeHeader.text = "Receiver Mode (Primary Phone / Tablet)"
                binding.tvModeDescription.text = "Runs on your primary phone or tablet. Listens for incoming calls from hotspot and/or Cloud with full-screen overlay."
                binding.tilIpAddress.visibility = View.GONE
                binding.tilPort.visibility = View.VISIBLE
                binding.layoutCloudSettings.visibility = View.GONE
                binding.layoutReceiverToggles.visibility = View.VISIBLE
                binding.btnTestMirror.visibility = View.GONE
                binding.btnShareScreen.visibility = View.GONE
                binding.btnShareScreenCloud.visibility = View.GONE

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val hasOverlay = Settings.canDrawOverlays(this)
                    binding.btnOverlayPermission.visibility = if (hasOverlay) View.GONE else View.VISIBLE
                }

                val isRunning = ReceiverService.isRunning
                val listenPort = prefs.getInt(PREFS_KEY_LISTEN_PORT, 8080)
                if (isRunning) {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_green)
                    binding.tvStatusTitle.text = "Receiver Service Active"
                    val isCloudActive = prefs.getBoolean("enable_receiver_cloud", false)
                    binding.tvStatusSubtitle.text = if (isCloudActive) "Listening on port $listenPort & Cloud" else "Listening on port $listenPort"
                    binding.btnToggleService.text = "Stop Receiver Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_red)
                } else {
                    binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.accent_amber)
                    binding.tvStatusTitle.text = "Receiver Stopped"
                    binding.tvStatusSubtitle.text = "Press start to begin listening"
                    binding.btnToggleService.text = "Start Receiver Service"
                    binding.btnToggleService.backgroundTintList = ContextCompat.getColorStateList(this, R.color.purple_500)
                }
            }
        }

        updateNotificationSyncUi()
    }

    private fun updateNotificationSyncUi() {
        val isTransmitter = currentMode == MODE_TRANSMITTER
        if (isTransmitter) {
            binding.switchMirrorNotifications.text = "💬 Mirror Outgoing Notifications (WhatsApp, SMS)"
            binding.tvNotificationSyncHint.text = "Forward incoming messages & notifications from this phone to receiver devices."

            val hasNotificationAccess = isNotificationServiceEnabled()
            val isChecked = binding.switchMirrorNotifications.isChecked
            if (isChecked && !hasNotificationAccess) {
                binding.btnNotificationAccessPermission.visibility = View.VISIBLE
            } else {
                binding.btnNotificationAccessPermission.visibility = View.GONE
            }
        } else {
            binding.switchMirrorNotifications.text = "💬 Receive Mirrored App Notifications"
            binding.tvNotificationSyncHint.text = "Show popup notifications when WhatsApp or SMS arrives on the primary phone."
            binding.btnNotificationAccessPermission.visibility = View.GONE
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(pkgName)
    }

    private fun updateScreenShareButton() {
        val running = ScreenShareService.isRunning
        val cloud = ScreenShareService.isCloudMode
        binding.btnShareScreen.text = if (running && !cloud) "⏹ Stop Sharing My Screen" else "🖥️ Share My Screen to Receiver (Wi-Fi)"
        binding.btnShareScreenCloud.text = if (running && cloud) "⏹ Stop Cloud Sharing" else "☁️ Share Screen via Cloud (slow snapshots)"
        binding.btnShareScreen.isEnabled = !(running && cloud)
        binding.btnShareScreenCloud.isEnabled = !(running && !cloud)
    }

    private fun currentCode(): String {
        val txCode = binding.etPairingCode.text.toString().trim()
        val rxCode = binding.etReceiverCloudCode.text.toString().trim()
        return if (currentMode == MODE_RECEIVER) rxCode.ifBlank { txCode } else txCode
    }

    private fun requireStrongCode(): Boolean {
        if (CryptoUtils.isStrongCode(currentCode())) return true
        Toast.makeText(
            this,
            "Security code is required (at least ${CryptoUtils.MIN_CODE_LENGTH} letters/digits). Tap 'New Random Code' and use the same code on all devices.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun toggleActiveService() {
        val isRunningNow = if (currentMode == MODE_TRANSMITTER) TransmitterService.isRunning else ReceiverService.isRunning
        if (!isRunningNow && !requireStrongCode()) return
        saveInputs()

        if (currentMode == MODE_TRANSMITTER) {
            if (TransmitterService.isRunning) {
                val intent = Intent(this, TransmitterService::class.java).apply {
                    action = TransmitterService.ACTION_STOP
                }
                startService(intent)
            } else {
                checkAndRequestTransmitterPermissions()
            }
        } else {
            if (ReceiverService.isRunning) {
                val intent = Intent(this, ReceiverService::class.java).apply {
                    action = ReceiverService.ACTION_STOP
                }
                startService(intent)
            } else {
                checkAndRequestReceiverPermissions()
            }
        }

        binding.root.postDelayed({ updateUiState() }, 300)
    }

    private fun checkAndRequestTransmitterPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        if (binding.switchMirrorNotifications.isChecked && !isNotificationServiceEnabled()) {
            Toast.makeText(this, "Please grant 'Notification Access' to mirror notifications", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }

        val intent = Intent(this, TransmitterService::class.java).apply {
            action = TransmitterService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Transmitter service started", Toast.LENGTH_SHORT).show()
    }

    private fun checkAndRequestReceiverPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant 'Draw over other apps' overlay permission", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val intent = Intent(this, ReceiverService::class.java).apply {
            action = ReceiverService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Receiver service started", Toast.LENGTH_SHORT).show()
    }

    private fun sendTestCallSignal() {
        if (!requireStrongCode()) return
        saveInputs()
        val ipRaw = getCombinedIps()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 8080
        val isCloudEnabled = binding.switchEnableCloudTransmit.isChecked
        val pairingCode = binding.etPairingCode.text.toString().trim()

        val targetIps = if (ipRaw.isNotBlank()) {
            ipRaw.split("[,;\\s]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
        } else {
            emptyList()
        }

        if (targetIps.isEmpty() && !isCloudEnabled) {
            Toast.makeText(this, "Enter Receiver IP or enable Cloud", Toast.LENGTH_SHORT).show()
            return
        }

        val destinations = mutableListOf<String>()
        if (targetIps.isNotEmpty()) destinations.add(targetIps.joinToString(", "))
        if (isCloudEnabled) destinations.add("Cloud")
        Toast.makeText(this, "Testing: ${destinations.joinToString(" + ")}...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val payload = CallPayload(
                status = CallPayload.STATUS_RINGING,
                number = "+1 (555) 019-2834",
                name = "CallMirror Test Call"
            )

            var anySuccess = false
            var lastError = ""

            if (isCloudEnabled) {
                if (CloudMirrorManager.publishOnce(pairingCode, payload)) anySuccess = true
                else lastError = "Cloud publish failed"
            }

            targetIps.forEach { ip ->
                if (SecureTransport.send(ip, port, payload, pairingCode)) {
                    anySuccess = true
                } else if (!anySuccess) {
                    lastError = "Local IP ($ip) unreachable"
                }
            }

            runOnUiThread {
                if (anySuccess) {
                    Toast.makeText(this@MainActivity, "Encrypted test signal sent 🚀", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed: $lastError", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
