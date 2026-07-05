# AudioMoth Config Editor (Android)

A compact Android app to create and apply AudioMoth recording configurations via USB HID. The app is a mobile-friendly editor; it no longer attempts to read a full device configuration from the AudioMoth. The device exposes only a small diagnostics set over USB (Device ID, firmware description, battery level and device time) — the editor opens with a local configuration that you then apply to the device.

## Key points
- Editor opens automatically when an AudioMoth is connected (after you grant USB permission).
- The app only reads supported device metadata: Device ID, firmware description, battery and device time.
- The app does NOT attempt to read the full recorded config from the device — that read path and caching were removed intentionally.
- Diagnostics and the debug log are available on-demand via the Info (i) button or the "Device Diagnostics" button (they are not shown automatically).
- Dark mode: follows system theme.

## Quick links (files you may want)
- App entry + UI/USB implementation:
  - `app/src/main/java/com/audiomoth/configeditor/MainActivity.kt`
- USB config packet builder and config helpers:
  - `app/src/main/java/com/audiomoth/configeditor/AcousticConfigBuilder.kt`
- GitHub Actions minimal CI workflow:
  - `.github/workflows/android-ci.yml`
- Git attributes to enforce LF for scripts:
  - `.gitattributes`

## Prerequisites (developer machine)
- JDK 17 (or the version set in Gradle)
- Android SDK (platform + build tools matching project target)
- Gradle wrapper (project includes `gradlew` / `gradlew.bat`)
- Android device for USB testing (AudioMoth)
- USB OTG (On-The-Go) adapter/cable to connect the AudioMoth to an Android device

## Build (local)
1. Open a terminal in the project root.
2. Build the debug APK (uses the repository gradle wrapper):
   - Windows:
     - `./gradlew.bat assembleDebug`
   - macOS / Linux (ensure `gradlew` is executable):
     - `./gradlew assembleDebug`
3. The debug APK will be at:
   - `app/build/outputs/apk/debug/app-debug.apk`

## Compile-only check (faster)
- `./gradlew :app:compileDebugKotlin`

## Install to device (USB ADB)
1. Enable Developer Options and USB debugging on your Android device.
2. Connect device by USB, then run:
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
(Replace with the .aab or signed APK for production installs.)

## Install to device (Wi‑Fi ADB / Pairing)
- Android 11+ (recommended approach):
  1. On device: Settings → Developer options → Wireless debugging → Pair device with QR code / pairing code.
  2. On your computer: use `adb pair <ip>:<port>` and the code shown on device.
  3. Then connect: `adb connect <ip>:<port>`
  4. Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Older devices can use `adb tcpip 5555` and `adb connect <device-ip>:5555` (less secure).

## Notes about USB behavior and device capabilities
- The AudioMoth can provide device ID, firmware info, battery status and time — the app only uses these supported fields.
- The app does not claim to read the current in-device configuration because that isn’t supported reliably by the hardware/firmware.
- To physically connect an AudioMoth to an Android phone/tablet you will need a USB OTG (On-The-Go) connector/cable. Make sure the OTG adapter supports data (some low-cost OTG cables only provide power).
- Diagnostics remain available in the dialog triggered from the top bar or the Home screen’s Device Diagnostics button.

## CI (GitHub Actions)
- Minimal CI workflow added: `.github/workflows/android-ci.yml`
  - Runs on push to `main/master` and on pull requests
  - Steps: checkout, set up JDK, normalize `gradlew` line endings, make `gradlew` executable, compile, build debug APK, upload debug APK as artifact
  - If you want Play Store publishing later, we can extend the workflow to produce a signed AAB and push to Play using encrypted secrets.

## Common issues & fixes
- `gradlew` syntax error on Linux runners (e.g. “Syntax error: "(" unexpected”): typically caused by corrupted `gradlew` or CRLF endings. Fixed in this repo by regenerating a correct `gradlew` and adding `.gitattributes`. If you modify wrapper files on Windows, make sure Git preserves LF for shell scripts (the repo now includes `.gitattributes` to enforce that).
- If the workflow cannot find the APK to upload, add a listing step before artifact upload:
  - `run: ls -R app/build/outputs/apk`
  - then confirm exact path and upload that explicit file (`app-debug.apk`).

## Versioning and releases
- Bump `versionCode` and `versionName` in your app module prior to a release (commonly in `app/build.gradle` or `app/build.gradle.kts`).
- For Play Store: create a release keystore, set up `signingConfig` in your Gradle script, build an Android App Bundle (`.aab`) and follow Play Console steps.

## Releases and signing (short)
- Generate a keystore:
  - `keytool -genkeypair -v -keystore release-keystore.jks -alias audiomoth-release -keyalg RSA -keysize 2048 -validity 10000`
- Configure signing in `build.gradle` (use environment variables / local properties for secrets)
- Build release bundle:
  - `./gradlew bundleRelease`
- Upload the generated AAB to Play Console (or use Gradle Play Publisher with a Play service account JSON stored as GitHub secret to automate).

## Contributing
- Please open issues for bugs or feature requests.
- For code changes:
  - Fork the repo, make a branch, implement changes, run the CI locally (`./gradlew assembleDebug`), and open a pull request.
- Keep debug-only tools gated behind explicit UI actions (we follow that approach here — diagnostics are on-demand).

## License
- Check the repository `LICENSE` or `LICENSE.md` file (if present) for project license information.
