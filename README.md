# BLE Hotspot Trigger for Android

An Android Kotlin application that enables/disables mobile hotspot via Bluetooth Low Energy (BLE) commands from a Mac. This app integrates with Samsung Routines to control the hotspot functionality.

## Features

- **BLE GATT Server**: Advertises and accepts connections from BLE clients (Mac)
- **Samsung Routines Integration**: Triggers Samsung Routines to enable/disable hotspot
- **Foreground Service**: Runs reliably in the background
- **Simple UI**: Easy-to-use interface with status monitoring
- **Credential Sharing**: Automatically sends WiFi credentials to Mac via BLE
- **Credential Preview**: View and copy hotspot credentials directly in the app

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
2. Wait for the hotspot to initialize (with retry logic)
3. Read and validate the hotspot SSID and password
4. Send the credentials back to the Mac/client via BLE notification
5. Update the UI to show credential send status

### Mac Clipboard-Based Workflow

> **Important:** Due to macOS API limitations, the Mac app cannot automatically join WiFi networks. Instead, the Mac app uses a clipboard-based workflow.

**How the Mac clipboard workflow works:**
1. Mac sends "Enable Hotspot" command via BLE
2. Android receives command and enables hotspot via Samsung Routine
3. Android sends WiFi credentials via BLE notification
4. Mac receives credentials and **copies the password to clipboard**
5. Mac shows a system notification with the SSID
6. User clicks on WiFi menu on Mac
7. User selects the hotspot network and **pastes the password** (Cmd+V)
8. Mac connects to the hotspot

This approach ensures reliable connectivity even with macOS security restrictions.

### Credential Response Format

The app sends credentials as a JSON string via BLE notification:
```json
{"ssid":"MyHotspot","password":"12345678"}
```

### Retry Logic for Robust Delivery

The Android app implements retry logic to ensure credentials are sent reliably:
- **Attempt 1**: Waits 3 seconds after hotspot enable command
- **Attempt 2**: Waits 6 seconds (if first attempt fails)
- **Attempt 3**: Waits 9 seconds (if second attempt fails)

This exponential backoff ensures credentials are captured even if the hotspot takes longer to initialize on some devices.

### Credential Validation

Before sending credentials, the app validates:
- SSID is not empty
- Password is at least 8 characters (WPA/WPA2 minimum)
- No null characters in strings
- SSID doesn't exceed 32 characters (warning only)

### UI Feedback

The Android app provides visual feedback:
- Toast notification when credentials are sent successfully
- Status indicator showing the sent SSID
- Timestamp of last action
- Error messages if credential sending fails

### Credential Preview

The app includes a credential preview section where you can:
- View the current hotspot SSID
- Show/hide the password
- Copy the password to clipboard for manual sharing
- Refresh credentials display

### How It Works (Technical Details)

When Mac sends "Enable Hotspot" (0x01):
1. Android receives BLE write request
2. Android triggers Samsung Routine via notification
3. Hotspot starts
4. Android schedules credential retrieval with retry logic (3s, 6s, 9s delays)
5. Android reads and validates hotspot SSID and password
6. Android sends JSON credentials via BLE notification
7. Android updates UI with send status
8. Mac receives notification and copies password to clipboard
9. Mac shows notification prompting user to connect

When Mac sends "Disable Hotspot" (0x00):
1. Android receives BLE write request
2. Android triggers Samsung Routine via notification
3. Hotspot stops
4. No credentials are sent

## BLE Protocol

### Service UUID
```
C15ABA22-C32C-4A01-A770-80B82782D92F
```

### Command Characteristic UUID
```
19A0B431-9E31-41C4-9DB0-D8EA70E81501
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
    
    let serviceUUID = CBUUID(string: "C15ABA22-C32C-4A01-A770-80B82782D92F")
    let commandUUID = CBUUID(string: "19A0B431-9E31-41C4-9DB0-D8EA70E81501")
    
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
        if let characteristic = service.characteristics?.first(where: { $0.uuid == commandUUID }) {
            commandCharacteristic = characteristic
            // Subscribe to notifications to receive credentials
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }
    
    // Handle credential notifications from Android
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == commandUUID, let data = characteristic.value {
            if let json = String(data: data, encoding: .utf8) {
                print("Received credentials: \(json)")
                // Parse JSON and copy password to clipboard
                // Show notification to user
            }
        }
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
- Ensure the Mac has subscribed to notifications on the characteristic (call `setNotifyValue(true, for: characteristic)`)
- Check Android logs for errors reading hotspot configuration
- Verify WiFi permissions are granted to the app
- On Android 11+, the app may need additional permissions to read hotspot config
- The app retries up to 3 times with increasing delays (3s, 6s, 9s)

### Credential validation failing
- Ensure your hotspot password is at least 8 characters
- Check that the hotspot is properly configured in Android settings
- Use the "Refresh Credentials" button in the app to verify credentials are readable

### Mac can't auto-join WiFi network
This is expected behavior due to macOS limitations. The Mac app uses a clipboard-based workflow:
1. When credentials are received, the password is copied to your Mac's clipboard
2. A notification appears on your Mac
3. Click the WiFi icon in the menu bar
4. Select your hotspot network
5. Press Cmd+V to paste the password
6. Click Join

### Timing issues
If credentials are not being captured:
- The hotspot may take longer to initialize on some devices
- The app will retry up to 3 times with 3-second intervals
- Check Android logs for detailed timing information

## License

This project is licensed under the Mozilla Public License 2.0 - see the [LICENSE](LICENSE) file for details.
