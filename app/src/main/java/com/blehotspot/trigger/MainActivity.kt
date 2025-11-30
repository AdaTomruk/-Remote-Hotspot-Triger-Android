package com.blehotspot.trigger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blehotspot.trigger.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity for BLE Hotspot Trigger app.
 * This activity manages the BLE GATT server that receives commands from a Mac client
 * to enable/disable mobile hotspot via Samsung Routines.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bleService: BleGattServerService? = null
    private var serviceBound = false
    private var isPasswordVisible = false
    private var currentPassword: String = ""

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleGattServerService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
            bleService?.setHotspotCommandListener(hotspotCommandListener)
            updateServiceStatus(true)
            refreshCredentialsDisplay()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bleService = null
            serviceBound = false
            updateServiceStatus(false)
        }
    }

    private val hotspotCommandListener = object : BleGattServerService.HotspotCommandListener {
        override fun onHotspotEnable() {
            runOnUiThread {
                binding.tvLastCommand.text = getString(R.string.last_command_enable)
                triggerSamsungRoutine(enable = true)
            }
        }

        override fun onHotspotDisable() {
            runOnUiThread {
                binding.tvLastCommand.text = getString(R.string.last_command_disable)
                triggerSamsungRoutine(enable = false)
            }
        }

        override fun onCredentialsSent(ssid: String) {
            runOnUiThread {
                val timestamp = dateFormat.format(Date())
                binding.tvCredentialStatus.text = getString(R.string.credentials_sent_success, ssid)
                binding.tvCredentialStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
                binding.tvCredentialStatus.visibility = View.VISIBLE
                binding.tvLastAction.text = getString(R.string.last_action_time, timestamp)
                Toast.makeText(this@MainActivity, getString(R.string.toast_credentials_sent, ssid), Toast.LENGTH_SHORT).show()
                refreshCredentialsDisplay()
            }
        }

        override fun onCredentialSendFailed(error: String) {
            runOnUiThread {
                val timestamp = dateFormat.format(Date())
                binding.tvCredentialStatus.text = getString(R.string.credentials_send_failed, error)
                binding.tvCredentialStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                binding.tvCredentialStatus.visibility = View.VISIBLE
                binding.tvLastAction.text = getString(R.string.last_action_time, timestamp)
                Toast.makeText(this@MainActivity, getString(R.string.toast_credentials_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startBleService()
        } else {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkPermissionsAndStartService()
        } else {
            Toast.makeText(this, R.string.bluetooth_required, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createRoutineTriggerNotificationChannel()
        setupUI()
        checkBluetoothSupport()
    }

    /**
     * Creates a notification channel for routine trigger notifications.
     * Uses IMPORTANCE_LOW to avoid sound/vibration.
     */
    private fun createRoutineTriggerNotificationChannel() {
        val channel = NotificationChannel(
            HotspotConstants.ROUTINE_TRIGGER_CHANNEL_ID,
            HotspotConstants.ROUTINE_TRIGGER_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.routine_trigger_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun setupUI() {
        binding.btnStartServer.setOnClickListener {
            if (serviceBound) {
                stopBleService()
            } else {
                checkPermissionsAndStartService()
            }
        }

        binding.btnTestEnable.setOnClickListener {
            triggerSamsungRoutine(enable = true)
        }

        binding.btnTestDisable.setOnClickListener {
            triggerSamsungRoutine(enable = false)
        }

        // Credential preview section
        binding.btnTogglePassword.setOnClickListener {
            togglePasswordVisibility()
        }

        binding.btnCopyPassword.setOnClickListener {
            copyPasswordToClipboard()
        }

        binding.btnRefreshCredentials.setOnClickListener {
            refreshCredentialsDisplay()
        }

        updateServiceStatus(false)
        updatePasswordVisibilityUI()
    }

    /**
     * Refreshes the credential display from the current hotspot configuration.
     */
    private fun refreshCredentialsDisplay() {
        val credentials = bleService?.getCurrentHotspotCredentials()
        
        if (credentials != null) {
            val (ssid, password) = credentials
            binding.tvCurrentSsid.text = ssid
            currentPassword = password
            updatePasswordDisplay()
            binding.cardCredentials.visibility = View.VISIBLE
        } else {
            binding.tvCurrentSsid.text = getString(R.string.credentials_not_available)
            binding.tvCurrentPassword.text = getString(R.string.password_placeholder)
            currentPassword = ""
            binding.cardCredentials.visibility = View.VISIBLE
        }
    }

    /**
     * Toggles the visibility of the password field.
     */
    private fun togglePasswordVisibility() {
        isPasswordVisible = !isPasswordVisible
        updatePasswordVisibilityUI()
        updatePasswordDisplay()
    }

    /**
     * Updates the password visibility button and display.
     */
    private fun updatePasswordVisibilityUI() {
        if (isPasswordVisible) {
            binding.btnTogglePassword.text = getString(R.string.hide_password)
        } else {
            binding.btnTogglePassword.text = getString(R.string.show_password)
        }
    }

    /**
     * Updates the password display based on visibility state.
     */
    private fun updatePasswordDisplay() {
        if (currentPassword.isEmpty()) {
            binding.tvCurrentPassword.text = getString(R.string.password_placeholder)
        } else if (isPasswordVisible) {
            binding.tvCurrentPassword.text = currentPassword
        } else {
            binding.tvCurrentPassword.text = "•".repeat(currentPassword.length.coerceAtMost(16))
        }
    }

    /**
     * Copies the current password to the clipboard.
     */
    private fun copyPasswordToClipboard() {
        if (currentPassword.isEmpty()) {
            Toast.makeText(this, R.string.no_password_to_copy, Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Hotspot Password", currentPassword)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.password_copied, Toast.LENGTH_SHORT).show()
    }

    private fun checkBluetoothSupport() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_available, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun checkPermissionsAndStartService() {
        // Check if Bluetooth is enabled
        if (bluetoothAdapter?.isEnabled != true) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (hasBluetoothConnectPermission()) {
                enableBluetoothLauncher.launch(enableBtIntent)
            } else {
                requestPermissions()
            }
            return
        }

        // Check permissions
        val requiredPermissions = getRequiredPermissions()
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startBleService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(getRequiredPermissions().toTypedArray())
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        // Add POST_NOTIFICATIONS permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }

    private fun startBleService() {
        val serviceIntent = Intent(this, BleGattServerService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopBleService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        val serviceIntent = Intent(this, BleGattServerService::class.java)
        stopService(serviceIntent)
        updateServiceStatus(false)
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        if (isRunning) {
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnStartServer.text = getString(R.string.stop_server)
        } else {
            binding.tvStatus.text = getString(R.string.status_stopped)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.btnStartServer.text = getString(R.string.start_server)
        }
    }

    /**
     * Triggers a Samsung Routine to enable or disable mobile hotspot.
     * Posts a silent notification with a specific keyword that Samsung Routines
     * can detect and trigger actions based on.
     */
    private fun triggerSamsungRoutine(enable: Boolean) {
        try {
            val notificationId = if (enable) {
                HotspotConstants.NOTIFICATION_ID_ENABLE
            } else {
                HotspotConstants.NOTIFICATION_ID_DISABLE
            }
            
            val triggerKeyword = if (enable) {
                HotspotConstants.TRIGGER_ENABLE_HOTSPOT
            } else {
                HotspotConstants.TRIGGER_DISABLE_HOTSPOT
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            
            val notification = NotificationCompat.Builder(this, HotspotConstants.ROUTINE_TRIGGER_CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_hotspot_automation_title))
                .setContentText(triggerKeyword)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(HotspotConstants.NOTIFICATION_TIMEOUT_MS)
                .build()

            notificationManager.notify(notificationId, notification)

            val message = if (enable) {
                getString(R.string.hotspot_enable_triggered)
            } else {
                getString(R.string.hotspot_disable_triggered)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.routine_trigger_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}
