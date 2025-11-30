package com.blehotspot.trigger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.UUID

/**
 * BLE GATT Server Service that advertises and handles connections from Mac clients.
 * When a command is received via BLE, it triggers the appropriate hotspot action.
 */
class BleGattServerService : Service() {

    companion object {
        private const val TAG = "BleGattServerService"
        private const val NOTIFICATION_CHANNEL_ID = "ble_hotspot_channel"
        private const val NOTIFICATION_ID = 1

        // Custom UUID for the Hotspot Control Service
        // Note: Consider generating unique UUIDs for production use to avoid conflicts
        // Use a UUID generator like https://www.uuidgenerator.net/
        val SERVICE_UUID: UUID = UUID.fromString("C15ABA22-C32C-4A01-A770-80B82782D92F")
        // Characteristic UUID for hotspot commands
        val COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("19A0B431-9E31-41C4-9DB0-D8EA70E81501")

        // Standard Client Characteristic Configuration Descriptor (CCCD) UUID
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Command values
        const val COMMAND_ENABLE_HOTSPOT: Byte = 0x01
        const val COMMAND_DISABLE_HOTSPOT: Byte = 0x00

        // Valid command set for input validation
        private val VALID_COMMANDS = setOf(COMMAND_ENABLE_HOTSPOT, COMMAND_DISABLE_HOTSPOT)

        // Credential retry constants
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MS = 3000L
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_SSID_LENGTH = 32

        // Broadcast action for credential send status
        const val ACTION_CREDENTIAL_STATUS = "com.blehotspot.trigger.CREDENTIAL_STATUS"
        const val EXTRA_CREDENTIAL_SUCCESS = "credential_success"
        const val EXTRA_CREDENTIAL_SSID = "credential_ssid"
        const val EXTRA_CREDENTIAL_ERROR = "credential_error"
    }

    private val binder = LocalBinder()
    private var hotspotCommandListener: HotspotCommandListener? = null

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var connectedDevice: BluetoothDevice? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    interface HotspotCommandListener {
        fun onHotspotEnable()
        fun onHotspotDisable()
        fun onCredentialsSent(ssid: String)
        fun onCredentialSendFailed(error: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleGattServerService = this@BleGattServerService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        initializeBluetooth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        startForeground(NOTIFICATION_ID, createNotification())
        startAdvertising()
        startGattServer()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopAdvertising()
        stopGattServer()
    }

    fun setHotspotCommandListener(listener: HotspotCommandListener?) {
        hotspotCommandListener = listener
    }

    private fun initializeBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE Advertising not supported on this device")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startAdvertising() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return
        }

        val advertiser = bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE Advertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Started BLE advertising")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        if (!hasBluetoothPermissions()) {
            return
        }

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Log.d(TAG, "Stopped BLE advertising")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertising started successfully")
            isAdvertising = true
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error"
            }
            Log.e(TAG, "BLE Advertising failed: $errorMsg (code: $errorCode)")
            isAdvertising = false
        }
    }

    private fun startGattServer() {
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions for GATT server")
            return
        }

        try {
            gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)

            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            val commandCharacteristic = BluetoothGattCharacteristic(
                COMMAND_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                        BluetoothGattCharacteristic.PROPERTY_READ or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE or
                        BluetoothGattCharacteristic.PERMISSION_READ
            )

            // Add Client Characteristic Configuration Descriptor (CCCD) for notifications
            val descriptor = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            commandCharacteristic.addDescriptor(descriptor)

            service.addCharacteristic(commandCharacteristic)
            gattServer?.addService(service)

            Log.d(TAG, "GATT Server started with service UUID: $SERVICE_UUID")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting GATT server: ${e.message}")
        }
    }

    private fun stopGattServer() {
        if (!hasBluetoothPermissions()) {
            return
        }

        try {
            gattServer?.close()
            gattServer = null
            Log.d(TAG, "GATT Server stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopping GATT server: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val state = if (newState == BluetoothGatt.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED"
            Log.d(TAG, "Device ${device?.address} $state")
            
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connectedDevice = device
            } else {
                connectedDevice = null
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded && hasBluetoothPermissions()) {
                try {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                    Log.d(TAG, "Descriptor write response sent")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException sending descriptor response: ${e.message}")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic?.uuid == COMMAND_CHARACTERISTIC_UUID && value != null && value.isNotEmpty()) {
                val command = value[0]
                handleCommand(command)

                // Send credentials after enabling hotspot
                if (command == COMMAND_ENABLE_HOTSPOT) {
                    sendHotspotCredentials()
                }

                // Always send acknowledgment response
                if (responseNeeded && hasBluetoothPermissions()) {
                    try {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            0,
                            null
                        )
                        Log.d(TAG, "Write response sent")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException sending response: ${e.message}")
                    }
                }
            } else {
                // Send error response for invalid requests
                if (responseNeeded && hasBluetoothPermissions()) {
                    try {
                        gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null
                        )
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException sending error response: ${e.message}")
                    }
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Service added successfully: ${service?.uuid}")
            } else {
                Log.e(TAG, "Failed to add service: $status")
            }
        }
    }

    private fun handleCommand(command: Byte) {
        Log.d(TAG, "Received command: $command")
        
        // Validate command before processing
        if (command !in VALID_COMMANDS) {
            Log.w(TAG, "Invalid command received: $command. Expected ${VALID_COMMANDS.joinToString()}")
            return
        }
        
        when (command) {
            COMMAND_ENABLE_HOTSPOT -> {
                Log.d(TAG, "Enable hotspot command received")
                hotspotCommandListener?.onHotspotEnable()
            }
            COMMAND_DISABLE_HOTSPOT -> {
                Log.d(TAG, "Disable hotspot command received")
                hotspotCommandListener?.onHotspotDisable()
            }
        }
    }

    /**
     * Retrieves the current hotspot SSID and password.
     * 
     * For Android 11+ (API 30+): Uses the official softApConfiguration API.
     * For Android 8.0-10 (API 26-29): Uses reflection to access getWifiApConfiguration().
     * 
     * Note: The reflection-based approach for older Android versions may not work on all
     * devices or manufacturers. Samsung, Huawei, and other OEMs may have custom implementations.
     * If credential retrieval fails, check the logs for specific errors and consider
     * device-specific workarounds if needed.
     * 
     * @return Pair of (SSID, Password) or null if unable to retrieve
     */
    @Suppress("DEPRECATION")
    private fun getHotspotCredentials(): Pair<String, String>? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            
            // For Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val softApConfig = wifiManager.softApConfiguration
                    val ssid = softApConfig.ssid
                    val password = softApConfig.passphrase ?: ""
                    
                    if (!ssid.isNullOrEmpty()) {
                        Log.d(TAG, "Retrieved hotspot credentials (API 30+): SSID=$ssid")
                        return Pair(ssid, password)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException reading softApConfiguration - missing permissions", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading softApConfiguration", e)
                }
            } else {
                // For older Android versions (API 26-29), use reflection
                // Note: This approach may not work on all device manufacturers
                try {
                    val method = wifiManager.javaClass.getMethod("getWifiApConfiguration")
                    val config = method.invoke(wifiManager)
                    
                    if (config != null) {
                        val ssidField = config.javaClass.getDeclaredField("SSID")
                        ssidField.isAccessible = true
                        val ssid = ssidField.get(config) as? String
                        
                        val passwordField = config.javaClass.getDeclaredField("preSharedKey")
                        passwordField.isAccessible = true
                        val password = passwordField.get(config) as? String ?: ""
                        
                        if (!ssid.isNullOrEmpty()) {
                            Log.d(TAG, "Retrieved hotspot credentials (reflection): SSID=$ssid")
                            return Pair(ssid, password)
                        }
                    }
                } catch (e: NoSuchMethodException) {
                    Log.e(TAG, "getWifiApConfiguration method not found - device may have custom implementation", e)
                } catch (e: NoSuchFieldException) {
                    Log.e(TAG, "SSID or preSharedKey field not found - device may have custom implementation", e)
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during reflection - missing permissions", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Reflection failed to get hotspot config", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hotspot credentials", e)
        }
        
        Log.w(TAG, "Unable to retrieve hotspot credentials")
        return null
    }

    /**
     * Sends hotspot credentials to the connected Mac device via BLE notification.
     * Uses retry logic with exponential backoff (3s, 6s, 9s) for robustness.
     */
    private fun sendHotspotCredentials() {
        Log.d(TAG, "Starting credential sending process with retry logic")
        sendHotspotCredentialsWithRetry(attempt = 1)
    }

    /**
     * Sends hotspot credentials with retry logic.
     * Implements exponential backoff: 3s, 6s, 9s delays.
     * 
     * @param attempt Current attempt number (1-based)
     */
    private fun sendHotspotCredentialsWithRetry(attempt: Int) {
        val delay = attempt * BASE_RETRY_DELAY_MS // 3s, 6s, 9s
        
        Log.d(TAG, "Scheduling credential retrieval attempt $attempt/$MAX_RETRY_ATTEMPTS with ${delay}ms delay")
        
        mainHandler.postDelayed({
            val device = connectedDevice
            if (device == null) {
                Log.w(TAG, "No connected device to send credentials to")
                notifyCredentialSendFailure("No connected device")
                return@postDelayed
            }
            
            if (!hasBluetoothPermissions()) {
                Log.e(TAG, "Missing Bluetooth permissions to send credentials")
                notifyCredentialSendFailure("Missing Bluetooth permissions")
                return@postDelayed
            }
            
            val credentials = getHotspotCredentials()
            
            if (credentials != null && validateCredentials(credentials)) {
                sendCredentialsViaBLE(device, credentials)
            } else if (attempt < MAX_RETRY_ATTEMPTS) {
                val reason = if (credentials == null) "credentials not available" else "credentials validation failed"
                Log.w(TAG, "Credentials not ready ($reason), retry $attempt/$MAX_RETRY_ATTEMPTS")
                sendHotspotCredentialsWithRetry(attempt + 1)
            } else {
                val errorMsg = "Failed to retrieve valid credentials after $MAX_RETRY_ATTEMPTS attempts"
                Log.e(TAG, errorMsg)
                notifyCredentialSendFailure(errorMsg)
            }
        }, delay)
    }

    /**
     * Validates hotspot credentials before sending.
     * 
     * @param credentials Pair of (SSID, Password)
     * @return true if credentials are valid for sending
     */
    private fun validateCredentials(credentials: Pair<String, String>): Boolean {
        val (ssid, password) = credentials
        
        // Check SSID
        if (ssid.isEmpty()) {
            Log.w(TAG, "Credential validation failed: SSID is empty")
            return false
        }
        
        if (ssid.length > MAX_SSID_LENGTH) {
            Log.w(TAG, "Credential validation warning: SSID exceeds $MAX_SSID_LENGTH characters (${ssid.length})")
            // Still allow sending, just log warning
        }
        
        // Check password - WPA/WPA2 requires minimum 8 characters
        if (password.length < MIN_PASSWORD_LENGTH) {
            Log.w(TAG, "Credential validation failed: Password too short (${password.length} < $MIN_PASSWORD_LENGTH)")
            return false
        }
        
        // Check for properly formatted strings (no null characters)
        if (ssid.contains('\u0000') || password.contains('\u0000')) {
            Log.w(TAG, "Credential validation failed: Contains null characters")
            return false
        }
        
        Log.d(TAG, "Credential validation passed: SSID='$ssid', password length=${password.length}")
        return true
    }

    /**
     * Sends validated credentials to the connected device via BLE notification.
     * 
     * @param device The connected Bluetooth device
     * @param credentials Pair of (SSID, Password)
     */
    private fun sendCredentialsViaBLE(device: BluetoothDevice, credentials: Pair<String, String>) {
        val (ssid, password) = credentials
        
        // Create JSON response with properly escaped strings
        val jsonResponse = """{"ssid":"${escapeJsonString(ssid)}","password":"${escapeJsonString(password)}"}"""
        val responseData = jsonResponse.toByteArray(Charsets.UTF_8)
        
        Log.d(TAG, "Sending credentials to Mac: SSID=$ssid, JSON length=${responseData.size}")
        
        try {
            // Find the characteristic
            val service = gattServer?.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(COMMAND_CHARACTERISTIC_UUID)
            
            if (characteristic != null) {
                characteristic.value = responseData
                val notificationSent = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                
                if (notificationSent == true) {
                    Log.d(TAG, "Credentials sent successfully via BLE notification")
                    notifyCredentialSendSuccess(ssid)
                } else {
                    Log.e(TAG, "BLE notification failed - notifyCharacteristicChanged returned false")
                    notifyCredentialSendFailure("BLE notification delivery failed")
                }
            } else {
                Log.e(TAG, "Characteristic not found for sending credentials")
                notifyCredentialSendFailure("BLE characteristic not found")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sending credentials: ${e.message}")
            notifyCredentialSendFailure("Security exception: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending credentials", e)
            notifyCredentialSendFailure("Error: ${e.message}")
        }
    }

    /**
     * Notifies listeners that credentials were sent successfully.
     * 
     * @param ssid The SSID that was sent
     */
    private fun notifyCredentialSendSuccess(ssid: String) {
        Log.d(TAG, "Notifying credential send success: SSID=$ssid")
        
        // Notify via listener callback
        mainHandler.post {
            hotspotCommandListener?.onCredentialsSent(ssid)
        }
        
        // Also send local broadcast for other components
        val intent = Intent(ACTION_CREDENTIAL_STATUS).apply {
            putExtra(EXTRA_CREDENTIAL_SUCCESS, true)
            putExtra(EXTRA_CREDENTIAL_SSID, ssid)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Notifies listeners that credential sending failed.
     * 
     * @param error Description of the error
     */
    private fun notifyCredentialSendFailure(error: String) {
        Log.e(TAG, "Notifying credential send failure: $error")
        
        // Notify via listener callback
        mainHandler.post {
            hotspotCommandListener?.onCredentialSendFailed(error)
        }
        
        // Also send local broadcast for other components
        val intent = Intent(ACTION_CREDENTIAL_STATUS).apply {
            putExtra(EXTRA_CREDENTIAL_SUCCESS, false)
            putExtra(EXTRA_CREDENTIAL_ERROR, error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * Gets the current hotspot credentials.
     * Can be called from MainActivity to display in UI.
     * 
     * @return Pair of (SSID, Password) or null if not available
     */
    fun getCurrentHotspotCredentials(): Pair<String, String>? {
        return getHotspotCredentials()
    }

    /**
     * Escapes a string for safe JSON encoding
     * Handles backslash, quotes, and control characters
     */
    private fun escapeJsonString(input: String): String {
        val sb = StringBuilder()
        for (char in input) {
            when (char) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (char.code < 32) {
                        // Escape other control characters as unicode
                        sb.append("\\u${String.format("%04x", char.code)}")
                    } else {
                        sb.append(char)
                    }
                }
            }
        }
        return sb.toString()
    }
}
