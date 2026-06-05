# BTCPay Server for Clover

Accept Bitcoin and Lightning Network payments directly from your Clover POS device via your self-hosted BTCPay Server instance.

## How it works

Once installed, a **BTCPay Server** payment option appears alongside your existing payment methods in the Clover Register app. Customers scan a QR code with their phone and pay using Bitcoin, Lightning, or any other payment method enabled on your BTCPay Server store.

## Requirements

- A self-hosted or third-party BTCPay Server instance
- BTCPay Server API key with the following permissions:
  - `btcpay.store.canviewstoresettings`
  - `btcpay.store.canviewinvoices`
  - `btcpay.store.cancreateinvoice`
- Clover device (Mini 2nd Gen or later, Flex 2nd Gen or later, Station Pro, Station Duo 2nd Gen)

## Setup

1. Install the app from the Clover App Market
2. Open the **BTCPay Server** app on your Clover device
3. Enter your BTCPay Server URL, Store ID and API Key
4. Tap **Test Connection & Save**
5. BTCPay Server will appear as a payment option in Clover Register

> Only managers, admins and owners can access the settings screen.

---

## Development

### Prerequisites

- Android Studio
- Android SDK Build Tools
- ADB (Android Debug Bridge)
- Java JDK (for jarsigner)
- Python 3 (for install_apps.py)
- A Clover sandbox account: https://sandbox.dev.clover.com

---

### Emulator Setup

**1. Create a Clover virtual device**
- Download the Clover AVD profile and APKs from: https://sandbox.dev.clover.com/developers/dev-apks
- In Android Studio → `Tools` → `Device Manager` → `+` → `Create Virtual Device`
- Import the Clover Mini (3rd Gen) profile, API 29

**2. Install Clover engine**
```powershell
adb install "path\to\com.clover.engine-2411.apk"
```

**3. Download install script**
```powershell
curl.exe https://raw.githubusercontent.com/clover/clover-android-sdk/master/scripts/install_apps.py -o install_apps.py
```

**4. Add your sandbox account to the emulator**
- On the emulator → Settings → Accounts → Add account
- Log in with your sandbox credentials

**5. Install Clover apps**
```powershell
python install_apps.py
```
> Grant all permissions when prompted.

**6. Reboot**
```powershell
adb reboot
```

**7. Set launcher**
- After reboot, select **Launcher** (not Pixel Launcher) as your home app

---

### Building & Installing

#### Debug (for testing)

```powershell
# 1. Build
.\gradlew assembleDebug

# 2. Sign
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -sigfile CERT `
  -keystore "C:\path\btcpay-key.jks" `
  "app\build\outputs\apk\debug\app-debug-unsigned.apk" `
  btcpay -storepass btcpay

# 3. Align
& "C:\path\AppData\Local\Android\Sdk\build-tools\36.1.0\zipalign.exe" -v 4 `
  "app\build\outputs\apk\debug\app-debug-unsigned.apk" `
  "app\build\outputs\apk\debug\app-final.apk"

# 4. Install
adb install "app\build\outputs\apk\debug\app-final.apk"

# Reinstall (keep permissions)
adb install -r "app\build\outputs\apk\debug\app-final.apk"
```

#### Release (for Clover App Market submission)

```powershell
# 1. Build
.\gradlew assembleRelease

# 2. Sign (V1 required by Clover)
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -sigfile CERT `
  -keystore "C:\path\btcpay-key.jks" `
  "app\build\outputs\apk\release\app-release.apk" `
  btcpay -storepass btcpay

# 3. Align
& "C:path\AppData\Local\Android\Sdk\build-tools\36.1.0\zipalign.exe" -v 4 `
  "app\build\outputs\apk\release\app-release.apk" `
  "app\build\outputs\apk\release\app-release-final.apk"
```

Upload `app-release-final.apk` to the Clover Developer Dashboard.

---

### Keystore

The keystore file (`btcpay-key.jks`) must remain consistent across all builds. Keep it backed up securely. Losing it means you can no longer publish updates to the same app.

---

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-change`
3. Make your changes and commit: `git commit -m "describe your change"`
4. Push and open a Pull Request

## License

MIT

## Links

- [Clover Custom Tender Docs](https://docs.clover.com/dev/docs/creating-custom-tender-apps)
- [BTCPay Server Greenfield API](https://docs.btcpayserver.org/API/Greenfield/v1/)
- [GitHub Repository](https://github.com/TChukwuleta/btcpayserver-clover-app)
