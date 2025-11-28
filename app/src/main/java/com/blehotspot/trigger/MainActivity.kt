package com.blehotspot.trigger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blehotspot.trigger.databinding.ActivityMainBinding

/**
 * MainActivity for BLE Hotspot Trigger app.
 * This activity manages the BLE GATT server that receives commands from a Mac client
 * to enable/disable mobile hotspot via Samsung Routines.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bleService: BleGattServerService? = null
    private var serviceBound = false

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleGattServerService.LocalBinder
            bleService = binder.getService()
            serviceBound = true
            bleService?.setHotspotCommandListener(hotspotCommandListener)
            updateServiceStatus(true)
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

        setupUI()
        checkBluetoothSupport()
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

        updateServiceStatus(false)
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
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
     * Samsung Routines can be configured to listen for specific intents
     * and perform actions like toggling hotspot.
     */
    private fun triggerSamsungRoutine(enable: Boolean) {
        try {
            // Intent action for Samsung Routines integration
            // The user needs to set up a Samsung Routine that listens for this broadcast
            val action = if (enable) {
                HotspotConstants.ACTION_ENABLE_HOTSPOT
            } else {
                HotspotConstants.ACTION_DISABLE_HOTSPOT
            }

            val intent = Intent(action).apply {
                putExtra(HotspotConstants.EXTRA_HOTSPOT_STATE, enable)
                // Add package name so Samsung Routines can identify the source
                setPackage("com.samsung.android.app.routines")
            }

            sendBroadcast(intent)

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
