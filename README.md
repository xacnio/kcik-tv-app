# KcikTV - Android TV Client

An Android TV client for the Kick.com streaming platform. Designed for TV screens with D-Pad navigation and low-latency playback.

[Türkçe Versiyon için Tıklayın](README_tr.md)

[![Build and Release](https://github.com/xacnio/kcik-tv-app/actions/workflows/release.yml/badge.svg)](https://github.com/xacnio/kcik-tv-app/actions/workflows/release.yml)
[![Website](https://img.shields.io/badge/Website-KcikTV-53FC18)](https://xacnio.github.io/kcik-tv-app/)


## 📺 Features

- **Ultra Low-Latency Playback**: Optimized using Amazon IVS and Media3 for the fastest possible HLS streaming with minimal delay.
- **Full Remote Control Optimization**: Native D-Pad navigation for a seamless TV experience.
- **Advanced Chat System**:
    - Real-time interaction with user badges.
    - Full Emote Support: Supports static and **animated (GIF/WebP)** emotes.
    - Optimized rendering for smooth performance on TV hardware.
- **Dual Login Methods**:
    - **QR Code Login**: Quick and easy login by scanning a QR code with your mobile device.
    - **Manual Login**: Login via username/email and password with full **2FA (OTP)** support.
- **Dynamic Interface**:
    - **Focus System**: Clear visual state for focused items using semi-transparent backgrounds and borders.
    - **Theme Engine**: Multiple theme colors (Electric Cyan, Midnight Indigo, Ocean Blue, etc.).
    - **Adjustable Spacing**: Layout optimized for TV viewing distances.
- **Global Stream Discovery**:
    - **Language Options**: Consolidated multi-language selection sidebar.
    - **Dynamic Sorting**: Featured, Viewers (High/Low).
- **Stream Stats & Diagnostics**: Real-time technical info (Resolution, FPS, Bitrate, Latency, Buffer).
- **Fast Channel Switching**: Navigate through channels using CH+/CH- or numerical input.
- **Picture-in-Picture (PIP) Support (Mobile)**: Continue watching your favorite streams while using other apps. Includes playback and "Live Edge" controls.
- **Background Audio Mode (Mobile)**: 
    - Full background playback support with system media notification.
    - **Automatic Data Saving**: Dynamically lowers video quality (to 360p or lower) in background mode.
    - Integrated with Android Media Session for music player-like controls.
- **Advanced Gesture Engine (Mobile)**:
    - **Edge Deadzones**: 48dp deadzones to prevent conflicts with system navigation gestures.
    - **Two-Finger Pan**: Drag and move video content in FILL mode.
    - **Auto-Hide Chat**: Chat panel hides automatically when opening the channel sidebar for better usability.

## 🎮 Navigation & Controls

| Key | Action |
|-----|--------|
| **D-Pad Up** | Next channel (Zap) |
| **D-Pad Down** | Previous channel (Zap) |
| **D-Pad Left** | Open Channel List (Press again for Main Menu) |
| **D-Pad Right** | Toggle Chat panel |
| **D-Pad Center (OK)** | Show Info Overlay (Channel info / Stats) |
| **OK (while Info open)** | Open Quick Menu (Quality, Refresh, Stats) |
| **Back** | Close current menu / Search panel / Exit app |
| **Numeric (0-9)** | Jump to specific channel index |
| **CH+ / CH-** | Next / Previous channel |

### 📱 Mobile Touch Controls

| Gesture | Action |
|---------|--------|
| **Swipe Up** | Next channel |
| **Swipe Down** | Previous channel |
| **Swipe Left** | Open Channel List / Menu |
| **Swipe Right** | Toggle Chat (only when watching) |
| **Swipe Right** | Back (when menu is open) |
| **Single Tap** | Show Info Overlay |
| **Double Tap** | Toggle video format (Fit/FILL) |
| **Pinch Zoom** | Toggle video format (Fit/FILL) |
| **Two-Finger Pan** | Move video (only in FILL mode) |
| **Home Button** | Enter PIP mode (if playing) |

## 🛠️ Tech Stack

- **Kotlin** - 100% Kotlin codebase.
- **Media3 / Amazon IVS** - High-performance video playback.
- **Retrofit 2** - REST API integration.
- **Pusher Client** - Real-time WebSocket connection for Chat.
- **Glide** - Image loading with animated WebP/GIF support.
- **Coroutines & Flow** - Modern reactive asynchronous handling.
- **Material Components** - TV-optimized UI design.

## 🏗️ Project Architecture

```
app/src/main/java/dev/xacnio/kciktv/
├── data/
│   ├── api/          # Retrofit Service Definitions
│   ├── chat/         # WebSocket & Chat Logic
│   ├── model/        # Data Structures (Kick API entities)
│   ├── prefs/        # Local Preferences & Auth storage
│   └── repository/   # Data Layer / API abstractions
└── ui/
    ├── activity/     # PlayerActivity (Main UI Controller)
    └── adapter/      # Optimized Adapters (Chat, Channels, Settings)
```

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or newer.
- Android SDK 21+ (Compatible with most TV Boxes/Sticks).
- Gradle 8.2+.

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/xacnio/kcik-tv-app.git
   ```
2. Open the project in Android Studio.
3. Sync Project with Gradle Files.
4. Run on your Android TV device or Emulator.

## 🔧 Build Commands

```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```

## 📋 Target Devices

- Android TV Box / Stick (Xiaomi Mi Box, Shield TV, etc.)
- Smart TVs (Sony, Philips, TCL, etc.)
- Amazon Fire TV / FireStick.
- Google TV.

## 📝 License

Distributed under the MIT License.

## 📸 Screenshots

You can find application screenshots in the [docs/screenshots/](/docs/screenshots) directory.

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