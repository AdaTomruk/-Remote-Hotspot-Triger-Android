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

Before using this app, you need to set up Samsung Routines on your device:

### For Enabling Hotspot:
1. Open **Samsung Routines** app
2. Create a new routine
3. Set **If** condition: `Receive notification` → Select this app → Notification contains "Enable"
4. Set **Then** action: `Mobile hotspot` → Turn on
5. Save and enable the routine

### For Disabling Hotspot:
1. Create another routine
2. Set **If** condition: `Receive notification` → Select this app → Notification contains "Disable"
3. Set **Then** action: `Mobile hotspot` → Turn off
4. Save and enable the routine

### Alternative Setup (Broadcast-based):
If your Samsung Routines version supports custom broadcasts:
1. Set **If** condition to receive broadcast with action:
   - Enable: `com.blehotspot.trigger.ENABLE_HOTSPOT`
   - Disable: `com.blehotspot.trigger.DISABLE_HOTSPOT`

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
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`

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

## License

This project is licensed under the Mozilla Public License 2.0 - see the [LICENSE](LICENSE) file for details.
