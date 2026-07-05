# AudioMoth Config Editor (Android)

A compact Android app to create and apply AudioMoth recording configurations via USB HID. 

## Key points
- Editor opens automatically when an AudioMoth is connected (after you grant USB permission).
- The app reads supported device metadata: Device ID, firmware description, battery and device time.
- Diagnostics and the debug log are available on-demand via the Info (i) button or the "Device Diagnostics" button (they are not shown automatically).
- Connecting the AudioMoth to an Android device requires a USB OTG (On-The-Go) connector or adapter appropriate for your phone/tablet (e.g. USB-A to USB-C, USB-A to Micro-USB, or direct USB-C OTG cable depending on your device).

## Quick links (files you may want)
- App entry + UI/USB implementation:
  - `app/src/main/java/com/audiomoth/configeditor/MainActivity.kt`
- USB config packet builder and config helpers:
  - `app/src/main/java/com/audiomoth/configeditor/AcousticConfigBuilder.kt`
- GitHub Actions minimal CI workflow:
  - `.github/workflows/android-ci.yml`

## Prerequisites (developer machine)

- JDK 17 (or the version set in Gradle)
- Android SDK (platform + build tools matching project target)
- Gradle wrapper (project includes gradlew / gradlew.bat)
- Android device for USB testing (AudioMoth) — a USB OTG adapter may be required depending on your device

## Build (local)

1. Open a terminal in the project root.
2. Build the debug APK (uses the repository gradle wrapper):
   - Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

   - macOS / Linux (ensure gradlew is executable):

```bash
./gradlew assembleDebug
./gradlew installDebug
```

3. The debug APK will be at:
   - `app/build/outputs/apk/debug/app-debug.apk`

## Compile-only check (faster)

- `./gradlew :app:compileDebugKotlin`

## Install to device (USB ADB)

1. Enable Developer Options and USB debugging on your Android device.
2. Connect device by USB, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

(Replace with the .aab or signed APK for production installs.)

## Install to device (Wi‑Fi ADB / Pairing)

- Android 11+ (recommended approach):
  1. On device: Settings → Developer options → Wireless debugging → Pair device with QR code / pairing code.
  2. On your computer: use `adb pair <ip>:<port>` and the code shown on device.
  3. Then connect: `adb connect <ip>:<port>`
  4. Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Older devices can use `adb tcpip 5555` and `adb connect <device-ip>:5555` (less secure).

Notes about USB behavior and device capabilities
- The AudioMoth can provide device ID, firmware info, battery status and time — the app only uses these supported fields.
- The app does not claim to read the current in-device configuration because that isn’t supported reliably by the hardware/firmware.
- Diagnostics remain available in the dialog triggered from the top bar or the Home screen’s Device Diagnostics button.

CI (GitHub Actions)
- Minimal CI workflow added: `.github/workflows/android-ci.yml`
  - Runs on push to main/master and on pull requests
  - Steps: checkout, set up JDK, normalize gradlew line endings, make gradlew executable, compile, build debug APK, upload debug APK as artifact

Contributing
- Please open issues for bugs or feature requests.
- For code changes:
  - Fork the repo, make a branch, implement changes, run the CI locally (`./gradlew assembleDebug`), and open a pull request.
- Keep debug-only tools gated behind explicit UI actions (we follow that approach here — diagnostics are on-demand).

License
- Check the repository LICENSE file for project license information.