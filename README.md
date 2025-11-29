# BLE Hotspot Trigger for Android

An Android Kotlin application that enables/disables mobile hotspot via Bluetooth Low Energy (BLE) commands from a Mac. This app integrates with Samsung Routines to control the hotspot functionality.

## Features

- **BLE GATT Server**: Advertises and accepts connections from BLE clients (Mac)
- **Samsung Routines Integration**: Triggers Samsung Routines to enable/disable hotspot
- **Foreground Service**: Runs reliably in the background
- **Simple UI**: Easy-to-use interface with status monitoring

## Requirements

- Samsung Android device with Android 8.0 (API 26) or higher
- Bluetooth Low Energy (BLE) support
- Samsung Routines app installed

## How It Works

1. The Android app runs a BLE GATT server that advertises a custom service
2. A Mac client connects to the Android device via BLE
3. The Mac sends a command byte to the command characteristic
4. The Android app receives the command and triggers the appropriate Samsung Routine
5. Samsung Routines enables or disables the mobile hotspot

## Hotspot Credential Sharing

When you enable the hotspot via BLE command, the Android app will automatically:
1. Enable the hotspot via Samsung Routines notification trigger
2. Read the hotspot SSID and password
3. Send the credentials back to the Mac/client via BLE notification
4. The Mac can then automatically join the WiFi network

### Credential Response Format

The app sends credentials as a JSON string via BLE notification:
```json
{"ssid":"MyHotspot","password":"12345678"}
```

This allows the Mac app to automatically connect to the hotspot without manual entry.

### How It Works (Technical Details)

When Mac sends "Enable Hotspot" (0x01):
1. Android receives BLE write request
2. Android triggers Samsung Routine via notification
3. Hotspot starts
4. Android waits 3 seconds for hotspot to fully initialize
5. Android reads hotspot SSID and password
6. Android sends JSON credentials via BLE notification
7. Mac receives notification and can join the WiFi network

When Mac sends "Disable Hotspot" (0x00):
1. Android receives BLE write request
2. Android triggers Samsung Routine via notification
3. Hotspot stops
4. No credentials are sent

## BLE Protocol

### Service UUID
```
12345678-1234-5678-1234-567812345678
```

### Command Characteristic UUID
```
12345678-1234-5678-1234-567812345679
```

### Commands
- `0x01` - Enable Hotspot
- `0x00` - Disable Hotspot

## Samsung Routines Setup

Before using this app, you need to set up Samsung Routines on your device. This app uses the **Notification Bridge** method which is compatible with newer Samsung devices.

### Enable Hotspot Routine:
1. Open **Samsung Routines** app
2. Create a new routine
3. Set **If** condition: `Notification received` → App: "BLE Hotspot Trigger" → Content contains "ENABLE_HOTSPOT_TRIGGER"
4. Set **Then** action: `Mobile hotspot` → Turn on
5. Save and enable the routine

### Disable Hotspot Routine:
1. Create another routine
2. Set **If** condition: `Notification received` → App: "BLE Hotspot Trigger" → Content contains "DISABLE_HOTSPOT_TRIGGER"
3. Set **Then** action: `Mobile hotspot` → Turn off
4. Save and enable the routine

### How the Notification Bridge Works

When a BLE command is received:
1. The app posts a silent notification with the keyword "ENABLE_HOTSPOT_TRIGGER" or "DISABLE_HOTSPOT_TRIGGER"
2. The notification auto-dismisses after 1 second
3. Samsung Routines detects the notification content and triggers the configured action
4. User sees a toast message confirming the trigger

### Note on Permissions (Android 13+)

On Android 13 and above, you'll need to grant notification permission when prompted. This is required for the app to post the trigger notifications that Samsung Routines will detect.

## Mac Client Example (Swift)

Here's a simple Swift example for sending commands from a Mac:

```swift
import CoreBluetooth

class BLEHotspotClient: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?
    
    let serviceUUID = CBUUID(string: "12345678-1234-5678-1234-567812345678")
    let commandUUID = CBUUID(string: "12345678-1234-5678-1234-567812345679")
    
    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            centralManager.scanForPeripherals(withServices: [serviceUUID])
        }
    }
    
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                       advertisementData: [String : Any], rssi RSSI: NSNumber) {
        self.peripheral = peripheral
        centralManager.stopScan()
        centralManager.connect(peripheral)
    }
    
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else { return }
        peripheral.discoverCharacteristics([commandUUID], for: service)
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        commandCharacteristic = service.characteristics?.first(where: { $0.uuid == commandUUID })
    }
    
    func enableHotspot() {
        guard let characteristic = commandCharacteristic else { return }
        peripheral?.writeValue(Data([0x01]), for: characteristic, type: .withResponse)
    }
    
    func disableHotspot() {
        guard let characteristic = commandCharacteristic else { return }
        peripheral?.writeValue(Data([0x00]), for: characteristic, type: .withResponse)
    }
}
```

## Building the App

### Prerequisites
- Android Studio Arctic Fox or later
- JDK 17
- Android SDK with API 34

### Build Steps
1. Clone the repository
2. Open the project in Android Studio
3. Sync Gradle files
4. Build the project: `./gradlew assembleDebug`
5. Install on your Samsung device

### Command Line Build
```bash
./gradlew assembleDebug
```

The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

## Permissions

The app requires the following permissions:
- `BLUETOOTH` / `BLUETOOTH_ADMIN` (Android 11 and below)
- `BLUETOOTH_ADVERTISE` (Android 12+)
- `BLUETOOTH_CONNECT` (Android 12+)
- `BLUETOOTH_SCAN` (Android 12+)
- `ACCESS_FINE_LOCATION` (Required for BLE on older Android versions)
- `ACCESS_WIFI_STATE` (Required for reading hotspot configuration)
- `CHANGE_WIFI_STATE` (Required for reading hotspot configuration)
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `POST_NOTIFICATIONS` (Android 13+, required for trigger notifications)

## Troubleshooting

### BLE Server not starting
- Ensure Bluetooth is enabled
- Grant all required permissions
- Restart the app

### Samsung Routine not triggering
- Verify the routine is enabled
- Check if the app notification appears
- Try the test buttons in the app first

### Mac cannot find the device
- Ensure the Android device is advertising (server status shows "Running")
- Check that the Mac's Bluetooth is enabled
- Try moving devices closer together

### Credentials not being received on Mac
- Ensure the Mac has subscribed to notifications on the characteristic
- Check Android logs for errors reading hotspot configuration
- Verify WiFi permissions are granted to the app
- On Android 11+, the app may need additional permissions to read hotspot config

## License

This project is licensed under the Mozilla Public License 2.0 - see the [LICENSE](LICENSE) file for details.
