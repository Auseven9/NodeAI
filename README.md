# NodeAI

A native Android **chat interface for local LLMs**. Load a `.gguf` model from
your device and chat with it fully on-device — no network, no server. Inference
runs on [llama.cpp](https://github.com/ggerganov/llama.cpp), compiled from
source as a native module via the Android NDK.

Built with **Kotlin + Jetpack Compose** (Material 3).

## How it works

- **Load a model** — tap **Load .gguf** and pick a `.gguf` file you've already
  downloaded to your phone (via the Android file picker). The app copies it into
  its private storage and loads it with llama.cpp.
- **Chat** — type a message and the model streams its reply token-by-token.
- Everything runs locally on the CPU (arm64). No data leaves the device.

### Getting a model

Download a small quantized `.gguf` to your phone first — something in the
~0.5B–3B parameter range with a `Q4` quantization runs comfortably on modern
phones. Larger models need a lot of RAM. Hugging Face hosts many (search for
"GGUF").

## Building the APK

GitHub Actions builds a debug APK on every push (see
`.github/workflows/android.yml`) — it installs the NDK + CMake, compiles
llama.cpp, and uploads the result. To install it:

1. Open the **Actions** tab on GitHub → latest **Android CI** run.
2. Download the **`nodeai-debug-apk`** artifact and unzip it.
3. Copy the `.apk` to an **arm64** Android device and install it.

### Building locally

Requires JDK 17+, the Android SDK, NDK `26.3.11579264`, and CMake `3.22.1`.

```bash
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads and compiles llama.cpp, so it takes several minutes.

## Project layout

```
app/src/main/
  cpp/
    CMakeLists.txt        # fetches + builds llama.cpp (pinned tag), builds JNI lib
    llama-android.cpp     # JNI: load model, tokenize, streaming generation
  java/com/auseven/nodeai/
    LlamaBridge.kt        # JNI declarations
    ChatViewModel.kt      # model loading, prompt building, token streaming
    MainActivity.kt       # Compose chat UI + .gguf file picker
```

## Notes & limitations

- **arm64-v8a only** — runs on physical phones, not the default x86_64 emulator.
- **Greedy sampling** (deterministic). Temperature / top-p sampling isn't wired
  up yet.
- **Prompt template** is ChatML (`<|im_start|>…`), which suits many modern
  instruct models (e.g. Qwen). Models expecting a different template may format
  oddly — using each model's embedded chat template is a natural next step.
- Each turn re-feeds the full conversation (KV cache is cleared per turn) for
  simplicity, so very long chats get slower.

## Tech

| | |
|---|---|
| Language | Kotlin 2.0, C++ (JNI) |
| UI | Jetpack Compose (Material 3) |
| Inference | llama.cpp (built via NDK + CMake) |
| Min SDK | 24 (Android 7.0) |
| ABI | arm64-v8a |
| Build | Gradle 8.14 + AGP 8.7, NDK r26 |
