# VivoDex 🖥️📱

**VivoDex** brings a desktop-like experience (similar to Samsung DeX or Huawei Desktop Mode) to **Vivo smartphones** (tested on Vivo X300 Pro) when connected to an external monitor via USB-C DisplayPort — **without requiring Root, Shizuku, ADB, or "Force Desktop Mode"**.

---

## 🎯 Key Highlights

* **No Root / No Shizuku / No ADB Runtime:** Pure standard Android public APIs.
* **No "Force Desktop Mode" Required:** Works natively on OriginOS / Funtouch OS.
* **Secondary Display Launching:** Launch apps (YouTube, Chrome, etc.) directly onto the external monitor in fullscreen (16:9).
* **Immersive Virtual Trackpad:** Your phone screen becomes a full-size, tactile trackpad to control a mouse pointer on the external monitor.
* **Real-time Tactile Gestures & Haptics:** Tap-to-click, two-finger smooth scroll, double-tap drag, and dedicated mouse buttons.
* **AMOLED Screen Dimmer:** Turn the phone display pure black while working on the monitor to save battery and stay cool.

---

## 🏗️ Architecture Overview

```text
       Vivo Smartphone (Phone Display)
  ┌─────────────────────────────────────────┐
  │  [ VivoDex App ]                        │
  │                                         │
  │  ┌───────────────────────────────────┐  │
  │  │                                   │  │
  │  │       Virtual Trackpad            │  │
  │  │                                   │  │
  │  │   • 1 Finger  → Move Pointer      │  │
  │  │   • 1 Tap     → Left Click        │  │
  │  │   • 2 Taps    → Right Click       │  │
  │  │   • 2 Fingers → Smooth Scroll     │  │
  │  │   • Double-Tap→ Drag & Drop       │  │
  │  │                                   │  │
  │  └───────────────────────────────────┘  │
  │   [   Left Click   ]  [ Right Click ]   │
  └────────────────────┬────────────────────┘
                       │ USB-C DisplayPort
                       ▼
          External Display / Monitor
  ┌─────────────────────────────────────────┐
  │                                         │
  │   ↖ Virtual Mouse Pointer Overlay       │
  │                                         │
  │   [ Target App Fullscreen (16:9) ]      │
  │   e.g. YouTube, Browser, Media Player   │
  │                                         │
  └─────────────────────────────────────────┘
```

### How It Works:
1. **Display Detection:** Uses Android `DisplayManager` and `DisplayListener` to detect secondary displays and resolutions dynamically.
2. **App Launching:** Launches target activities onto the external monitor using:
   ```kotlin
   val options = ActivityOptions.makeBasic().apply {
       setLaunchDisplayId(externalDisplayId)
   }
   startActivity(intent, options.toBundle())
   ```
3. **Cursor Overlay:** `RemoteGestureService` (`AccessibilityService`) attaches an overlay cursor (`ic_mouse_pointer`) to the external display's Window Manager using `TYPE_ACCESSIBILITY_OVERLAY` and `createDisplayContext(externalDisplay)`.
4. **Input Injection:** Touches from the phone's trackpad are converted into Android `GestureDescription` strokes dispatched directly to the external display ID via `AccessibilityService.dispatchGesture(gesture, null, null)`.

---

## 🕹️ Trackpad Gestures & Controls

| Gesture | Action |
| :--- | :--- |
| **1-Finger Glide** | Moves the virtual cursor across the external display |
| **1-Finger Tap** | Left-click with tactile haptic feedback |
| **2-Finger Tap** | Right-click / Context menu with haptic feedback |
| **2-Finger Drag (Up/Down)** | Real-time continuous smooth scrolling with haptic ticks |
| **Double-Tap & Hold** | Drag & drop (window moving or text selection) |
| **Dedicated Mouse Bar** | Bottom buttons for physical-style **Left Click** and **Right Click** |
| **Pointer Speed Button** | Cycle sensitivity preset: `1.0x` • `1.5x` • `2.0x` • `2.5x` • `3.0x` |
| **Cursor Toggle** | Show / Hide mouse cursor overlay |
| **Screen Dimmer** | Blackout phone screen with tap-to-wake hint for OLED battery saving |

---

## 📱 Requirements

* **Device:** Vivo smartphone supporting DisplayPort Alternate Mode over USB-C (e.g. Vivo X300 Pro, X100 Pro, X90 Pro+).
* **OS:** Android 14+ (API Level 34+).
* **Hardware:** USB-C to HDMI / DisplayPort cable or USB-C Hub / Portable Monitor.

---

## 🚀 Getting Started

### 1. Build & Install
Clone the repository and build the debug APK:
```bash
git clone https://github.com/JohnKubota-V8/vivoDex.git
cd vivoDex
./gradlew assembleDebug
```
Or open the project directly in **Android Studio** and click **Run**.

### 2. Enable Accessibility Service
1. Open **VivoDex** on your phone.
2. Tap **"Enable Accessibility Service"** on the Display page (or navigate to `Settings → Accessibility → Downloaded Apps → VivoDex`).
3. Toggle the switch to **On** and allow permissions.

### 3. Connect to Monitor
1. Connect your Vivo phone to an external monitor via USB-C.
2. VivoDex will detect the secondary display (showing connection status, resolution, and refresh rate).
3. Tap any app in your **Favorite Apps** list to launch it on the monitor.
4. Switch to the **Touchpad** tab and enjoy desktop control!

---

## 📁 Project Structure

```text
vivoDex/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/vivodex/
│   │   │   ├── MainActivity.kt         # Display detection, launcher & trackpad coordinator
│   │   │   ├── RemoteGestureService.kt # Accessibility service: cursor overlay & input injection
│   │   │   ├── TrackpadView.kt         # Custom full-screen multitouch trackpad view
│   │   │   └── HapticHelper.kt         # Tactile vibration feedback manager
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml# Material 3 dual-page UI layout
│   │   │   ├── drawable/               # Custom vector icons & mouse pointer
│   │   │   └── menu/bottom_nav_menu.xml# Navigation bar items
│   │   └── AndroidManifest.xml         # Service & Activity declarations
│   └── build.gradle.kts
├── docs/                               # Hand-off documents & research specs
├── gradle/
└── README.md
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
