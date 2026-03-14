# AudioMoth Config Editor - README

A Kotlin/Jetpack Compose Android application for editing AudioMoth configuration files (.config).

## Features

- **Create new configurations** from scratch with sensible defaults
- **Load and edit** existing .config files
- **Validate** configuration parameters
- **Save** configurations back to JSON format
- **Support for all AudioMoth 1.10.2 parameters**

## Requirements

- Android 7.0+ (API 24+)
- Android Studio or equivalent build environment

## Building

```bash
./gradlew build
./gradlew installDebug
```

## Project Structure

```
├── app/
│   ├── src/main/
│   │   ├── java/com/audiomoth/configeditor/
│   │   │   ├── MainActivity.kt          - Main UI and Compose screens
│   │   │   ├── AudioMothConfig.kt       - Data model
│   │   │   └── ConfigFileManager.kt     - File I/O operations
│   │   ├── AndroidManifest.xml
│   │   └── res/
│   │       ├── layout/                  - Layout resources
│   │       └── values/                  - Strings, colors, styles
│   └── build.gradle                     - App dependencies and config
├── build.gradle                         - Root build configuration
└── settings.gradle                      - Gradle settings
```

## Usage

1. Launch the app on your Android device or emulator
2. Choose **Create New Configuration** or **Load Configuration from File**
3. Edit parameters (sample rate, record duration, gain, etc.)
4. Preview the JSON before saving
5. Save to device storage

## Configuration File Format

Configuration files are stored as JSON with the AudioMoth 1.10.2 schema, including:
- Recording parameters (sample rate, duration, sleep, gain)
- Time periods for recording schedules
- Filter settings (high-pass, low-pass, amplitude, frequency)
- Device settings (LED, battery checks, GPS sync, etc.)

Example sample rates:
- 384000 Hz (high quality)
- 192000 Hz (balanced)
- 48000 Hz (lower quality, longer battery life)

## License

AudioMoth Config Editor - Open source
