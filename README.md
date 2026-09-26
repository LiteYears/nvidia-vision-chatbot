# NVIDIA Vision Chatbot

<div align="center">

![Android](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.x-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)
![NVIDIA](https://img.shields.io/badge/AI-NVIDIA%20NIM-76B900?logo=nvidia&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

**A high-performance Android multimodal AI assistant powered by NVIDIA NIM and vision-language models, featuring on-device RAG, real-time voice mode, and offline Room persistence.**

</div>

---

## 📥 Download & Install

Ready to test on your device immediately? You can grab the pre-compiled APK directly from the repo:
- **Direct APK:** [`apk/nvidia-vision-chatbot.apk`](apk/nvidia-vision-chatbot.apk)
- **ZIP Package:** [`apk/nvidia-vision-chatbot.zip`](apk/nvidia-vision-chatbot.zip)

See the [installation instructions](apk/README.txt) for side-loading steps on Android.

---

## 🌟 Overview

**NVIDIA Vision Chatbot** is a modern native Android application built using Jetpack Compose and Kotlin. It connects directly to NVIDIA's high-speed inference microservices (NIM) and OpenAI-compatible endpoints to deliver conversational intelligence, image understanding, and document synthesis.

Whether analyzing complex diagrams, transcribing and summarizing documents on-device, or having a voice conversation with speech-to-text and text-to-speech feedback, the app is engineered for responsiveness, crash resilience, and privacy.

---

## ✨ Features

- **👁️ Multimodal Vision & Analysis**
  - Attach images directly from your camera or photo gallery.
  - Smart, memory-efficient image downsampling (`inJustDecodeBounds` + dynamic `inSampleSize`) preventing `OutOfMemoryError` even on ultra-high-resolution photos.
  - Vision processing powered by cutting-edge vision-language models on NVIDIA NIM.

- **🎙️ Real-Time Voice Mode**
  - Hands-free voice conversations with native Android `SpeechRecognizer`.
  - Partial real-time streaming speech transcription.
  - Automatic Text-to-Speech (TTS) playback of AI responses with interruptible controls.

- **📚 On-Device Document RAG (Retrieval-Augmented Generation)**
  - Upload text, markdown, and document files directly into the conversation.
  - Hardened local text extraction with binary detection, chunking, and TF-IDF / cosine keyword ranking.
  - Automatically incorporates the most relevant document sections into the model's context window.

- **⚙️ Model Tuning & Hyperparameters**
  - Switch between various NVIDIA-hosted models (DeepSeek, Llama 3.2 Vision, Cosmos Nemotron, and more).
  - Fine-tune generation parameters directly in-app:
    - Temperature
    - Top-P
    - Max Tokens
    - Presence & Frequency Penalties

- **💾 Offline Persistence & Crash Resilience**
  - Full local chat history powered by Room SQLite database.
  - Resilient storage: Keeps large image data referenced by URI to prevent SQLite `CursorWindowAllocationException` (2MB cursor limit).
  - Conversations and custom settings persist across app restarts and device reboots.

- **🎨 Modern Material 3 Interface**
  - Custom dark theme optimized for OLED displays.
  - Markdown text rendering with code syntax blocks.
  - Interactive bottom sheets, animated drawer navigation, and fluid Compose transitions.

- **🔒 Privacy & Security First**
  - Direct client-to-API communication with strict HTTPS enforcement (`usesCleartextTraffic="false"`).
  - Backup rules (`allowBackup="false"`) protecting API keys from unauthorized ADB extraction.
  - Dedicated Developer Mode unlocked via the About dialog.

---

## 🏗️ Architecture & Tech Stack

- **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material Design 3.
- **Language:** Kotlin 2.x with Coroutines & StateFlow.
- **Architecture Pattern:** MVVM (Model-View-ViewModel) + Repository pattern.
- **Local Database:** [Room](https://developer.android.com/training/data-storage/room) with KSP (Kotlin Symbol Processing).
- **Networking:** [OkHttp 4](https://square.github.io/okhttp/) with streaming response handling and connection leak prevention.
- **Speech Engine:** Android `SpeechRecognizer` + `TextToSpeech` APIs.
- **Build System:** Gradle 9.3.1 with Android Gradle Plugin (AGP) 9.1.1.

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio:** Ladybug (2024.2+) or Meerkat (2024.3+) recommended.
- **JDK:** Java 17 or Java 21.
- **Android SDK:** Compile SDK 36 (Android 16) / Target SDK 36, Min SDK 24 (Android 7.0+).
- **NVIDIA API Key:** Obtain a free API key from [build.nvidia.com](https://build.nvidia.com).

### Installation & Build

1. **Clone the repository:**
   ```bash
   git clone https://github.com/LiteYears/nvidia-vision-chatbot.git
   cd nvidia-vision-chatbot
   ```

2. **Configure your API Key (Optional):**
   Copy `.env.example` to `.env` and set your key:
   ```bash
   cp .env.example .env
   ```
   *Note: You can also enter or update your NVIDIA API key directly inside the app under the About dialog.*

3. **Build the Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```
   The resulting APK will be generated at:
   `app/build/outputs/apk/debug/app-debug.apk`

4. **Install on device or emulator:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 📁 Project Structure

```
nvidia-vision-chatbot/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/
│   │   │   ├── MainActivity.kt
│   │   │   ├── data/
│   │   │   │   ├── local/          # Room Database, DAOs & Entities
│   │   │   │   ├── model/          # Message data models
│   │   │   │   ├── preferences/    # Encrypted settings & API key storage
│   │   │   │   ├── rag/            # On-device document retrieval engine
│   │   │   │   ├── remote/         # NVIDIA NIM API client & OkHttp engine
│   │   │   │   └── repository/     # Chat repository
│   │   │   ├── ui/
│   │   │   │   ├── components/     # Compose dialogs, sheets, bars & bubbles
│   │   │   │   ├── screens/        # ChatScreen & SplashScreen
│   │   │   │   ├── theme/          # Material 3 colors, typography & shapes
│   │   │   │   └── viewmodel/      # ChatViewModel state management
│   │   └── res/                    # Drawables, mipmaps, strings, and themes
│   └── build.gradle.kts
├── gradle/
│   ├── libs.versions.toml          # Centralized version catalog
│   └── wrapper/                    # Gradle wrapper binaries (9.3.1)
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
└── README.md
```

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
