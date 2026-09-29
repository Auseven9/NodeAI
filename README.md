# NodeAI (Android)

A native Android app scaffold built with **Kotlin** and **Jetpack Compose**.

## Building the APK

GitHub Actions builds a debug APK automatically on every push (see
`.github/workflows/android.yml`). To download it:

1. Open the **Actions** tab on GitHub.
2. Click the latest **Android CI** run.
3. Download the **`nodeai-debug-apk`** artifact and unzip it.
4. Copy the `.apk` to an Android device and install it (enable
   "Install unknown apps" for your file manager first).

## Building locally

Requires JDK 17+ and the Android SDK.

```bash
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

## Project layout

```
app/
  src/main/
    java/com/auseven/nodeai/MainActivity.kt   # Compose UI entry point
    res/                                       # resources, icons, theme
    AndroidManifest.xml
.github/workflows/android.yml                  # CI: builds debug APK
gradle/libs.versions.toml                      # dependency versions
```

## Tech

| | |
|---|---|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose (Material 3) |
| Min SDK | 24 (Android 7.0) |
| Target/Compile SDK | 35 |
| Build | Gradle 8.14 + AGP 8.7 |
