# AudioMoth Config Editor - Android App

## Project Overview
An Android application for editing AudioMoth audio recorder configuration files (JSON format). Users can create, load, and save configuration files on their mobile device with a user-friendly interface.

## Key Features
- Load existing AudioMoth configuration files (.config)
- Edit configuration parameters with validation
- Create new configurations from scratch
- Save configurations to device storage
- Support for multiple recording profiles

## Development Stack
- Android API Level 24+ (Android 7.0+)
- Kotlin programming language
- Jetpack Compose UI framework
- File I/O with Android Storage APIs
- JSON serialization/deserialization

## Setup Instructions
1. Install Android Studio
2. Clone/open project in Android Studio
3. Sync Gradle files
4. Run on emulator or physical device (API 24+)

## Build and Run
```
./gradlew build
./gradlew installDebug
```

## Project Structure
- `app/` - Main application module
  - `src/main/java/com/audiomoth/configeditor/` - Kotlin source files
  - `src/main/res/` - Android resources (layouts, strings, values)
- `gradle/` - Gradle build configuration
