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
    }

    private val binder = LocalBinder()
    private var hotspotCommandListener: HotspotCommandListener? = null

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false
    private var connectedDevice: BluetoothDevice? = null

    interface HotspotCommandListener {
        fun onHotspotEnable()
        fun onHotspotDisable()
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
     * Retrieves the current hotspot SSID and password
     * @return Pair of (SSID, Password) or null if unable to retrieve
     */
    @Suppress("DEPRECATION")
    private fun getHotspotCredentials(): Pair<String, String>? {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            
            // For Android 11+ (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val softApConfig = wifiManager.softApConfiguration
                val ssid = softApConfig.ssid
                val password = softApConfig.passphrase ?: ""
                
                if (!ssid.isNullOrEmpty()) {
                    Log.d(TAG, "Retrieved hotspot credentials (API 30+): SSID=$ssid")
                    return Pair(ssid, password)
                }
            } else {
                // For older Android versions (API 26-29), use reflection
                try {
                    val method = wifiManager.javaClass.getMethod("getWifiApConfiguration")
                    val config = method.invoke(wifiManager)
                    
                    val ssidField = config?.javaClass?.getDeclaredField("SSID")
                    ssidField?.isAccessible = true
                    val ssid = ssidField?.get(config) as? String
                    
                    val passwordField = config?.javaClass?.getDeclaredField("preSharedKey")
                    passwordField?.isAccessible = true
                    val password = passwordField?.get(config) as? String ?: ""
                    
                    if (!ssid.isNullOrEmpty()) {
                        Log.d(TAG, "Retrieved hotspot credentials (reflection): SSID=$ssid")
                        return Pair(ssid, password)
                    }
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
     * Sends hotspot credentials to the connected Mac device via BLE notification
     */
    private fun sendHotspotCredentials() {
        val device = connectedDevice
        if (device == null) {
            Log.w(TAG, "No connected device to send credentials to")
            return
        }
        
        if (!hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions to send credentials")
            return
        }
        
        // Wait for hotspot to fully start before reading credentials
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val credentials = getHotspotCredentials()
            
            if (credentials != null) {
                val (ssid, password) = credentials
                
                // Create JSON response with escaped strings for safety
                val escapedSsid = ssid.replace("\\", "\\\\").replace("\"", "\\\"")
                val escapedPassword = password.replace("\\", "\\\\").replace("\"", "\\\"")
                val jsonResponse = """{"ssid":"$escapedSsid","password":"$escapedPassword"}"""
                val responseData = jsonResponse.toByteArray(Charsets.UTF_8)
                
                Log.d(TAG, "Sending credentials to Mac: SSID=$ssid")
                
                try {
                    // Find the characteristic
                    val service = gattServer?.getService(SERVICE_UUID)
                    val characteristic = service?.getCharacteristic(COMMAND_CHARACTERISTIC_UUID)
                    
                    if (characteristic != null) {
                        characteristic.value = responseData
                        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                        Log.d(TAG, "Credentials sent successfully")
                    } else {
                        Log.e(TAG, "Characteristic not found for sending credentials")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException sending credentials: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending credentials", e)
                }
            } else {
                Log.e(TAG, "Failed to retrieve hotspot credentials")
            }
        }, 3000) // Wait 3 seconds for hotspot to fully start
    }
}
