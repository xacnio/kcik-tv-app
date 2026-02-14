# KCIKTV - Android Client (TV & Mobile)

An unofficial client for the Kick.com streaming platform. Designed for both **Android TV** (fully D-Pad compatible) and **Mobile devices**, featuring low-latency playback, chat integration, and a modern UI.

[![Build and Release](https://github.com/xacnio/kcik-tv-app/actions/workflows/release.yml/badge.svg)](https://github.com/xacnio/kcik-tv-app/actions/workflows/release.yml)
[![Website](https://img.shields.io/badge/Website-KCIKTV-53FC18)](https://xacnio.github.io/kcik-tv-app/)


## 🌟 Features & Usage

For a comprehensive list of features, screenshots, and usage instructions, please visit our official website:
**[xacnio.github.io/kcik-tv-app](https://xacnio.github.io/kcik-tv-app/)**

## 📋 Target Devices

This project is optimized and tested for the following form factors:

*   📱 **Mobile (Portrait):** Android Phones (Foldable & Standard).
*   📺 **TV (Landscape):** Android TV, Google TV, Fire TV Stick, Nvidia Shield.
*   📐 **Tablets (Landscape/Portrait):** Responsive tablet UI.
*   💻 **Desktop/Chromebooks:** Large screen support with keyboard/mouse navigation.

## 🛠️ Tech Stack

### Core
*   **Language:** Kotlin (100%)
*   **Architecture:** MVVM (Model-View-ViewModel)
*   **Async:** Kotlin Coroutines & Flow

### UI & Presentation
*   **TV UI:** AndroidX Leanback SDK
*   **Mobile UI:** Material Design Components 3
*   **Image Loading:** Glide (Verified Memory Caching) + Glide Transformations
*   **Animations:** APNG (for animated verified badges)

### Networking & Data
*   **API Client:** Retrofit 2 + OkHttp 3
*   **Serialization:** Gson
*   **WebSocket:** Native OkHttp WebSocket (Kick Chat)

### Media & Playback
*   **Player Engine:** Amazon IVS Player SDK (Native Low-Latency)
*   **Background Playback:** Android Foreground Services + MediaSessionCompat

### Features & Utilities
*   **QR Code:** ZXing Core + ZXing Android Embedded
*   **HTML Parsing:** Jsoup (Link Previews)
*   **Browser Integration:** AndroidX WebKit
*   **Background Tasks:** AndroidX WorkManager
*   **Analytics:** Firebase Analytics (Privacy-focused / GDPR Compliant configuration)

## 🏗️ Project Architecture

The codebase follows a modular monolithic approach, separating platform-specific logic while sharing the core data and business layer.

```
app/src/main/java/dev/xacnio/kciktv/
├── mobile/                     # Mobile (Touch) Implementation
│   ├── ui/                     # UI Logic Managers (Delegation Pattern)
│   ├── LoginActivity.kt        # Mobile Authentication UI
│   └── MobilePlayerActivity.kt # Main Mobile Activity
│
├── tv/                         # Android TV (D-Pad) Implementation
│   └── PlayerActivity.kt       # Main TV Activity
│
└── shared/                     # Shared Core & Business Logic
    ├── data/
    │   ├── api/                # Retrofit Interfaces
    │   ├── chat/               # Chat Flow & Connection Logic
    │   ├── model/              # Data Entites
    ├── ui/                     # Shared UI Adapters & ViewHolders
    ├── websocket/              # Low-level WebSocket Handling
    ├── LauncherActivity.kt     # Entry Point: Detects Device & Routes
    └── PlaybackService.kt      # Foreground Service for Background Audio
```

## 🌍 Translation
The app supports multiple languages. If you want to contribute a translation:

1.  Fork the repository.
2.  Navigate to `app/src/main/res/`.
3.  Create a new values directory for your language code (e.g., `values-fr` for French).
4.  Copy `strings.xml` from `values/` and translate the strings.
5.  Register your new language in `app/src/main/java/dev/xacnio/kciktv/shared/util/SupportedLanguages.kt`.
6.  Run the verification script to ensure everything is correct:
    ```bash
    python3 scripts/verify_translations_strict.py
    ```
7.  Submit a Pull Request!

> **⚠️ IMPORTANT:** Creating a translation file is NOT enough. You MUST add the language code and name to the `SupportedLanguages.kt` file for it to appear in the app settings.

> **⚠️ WARNING:** Do NOT remove any lines or change the order of keys. The `strings.xml` structure must match the English version exactly line-by-line for our automation scripts to work correctly.

### 🌍 Supported Languages

| Language |
| :--- |
| 🇺🇸 English |
| 🇹🇷 Turkish |
| 🇪🇸 Spanish |
| 🇫🇷 French |
| 🇩🇪 German |
| 🇸🇦 Arabic |

## 🚀 Getting Started

### Prerequisites
- Android Studio Iguana or newer.
- Android SDK 24+ (Android 7.0+).
- Gradle 8.5+.

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/xacnio/kcik-tv-app.git
   ```
2. Set up Firebase:
   - Copy `app/google-services.json.example` to `app/google-services.json`.
   - OR place your own `google-services.json` in the `app/` directory.
3. Open the project in Android Studio.
4. Sync Project with Gradle Files.
5. Run on your Android TV or Mobile device (or Emulator).

## 🔧 Build Commands

```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

## 📝 License

Distributed under the MIT License.

## 🌐 Website & Releases

- **Landing Page:** [xacnio.github.io/kcik-tv-app](https://xacnio.github.io/kcik-tv-app/)
- **Latest Release:** [Download APK](https://github.com/xacnio/kcik-tv-app/releases/latest)


## ☕ Support
If you like my work, you can support me by buying a coffee!

<a href="https://buymeacoffee.com/xacnio"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" width="200" ></a>


## 👤 Developer

Maintainer: **xacnio** (Alperen Cetin)

---
*Developed with the assistance of AI technology.*