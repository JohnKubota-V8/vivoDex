# VivoDesktopLite — Phase 1: Display Capability Test

## Objective

สร้าง Android App ขนาดเล็กสำหรับทดสอบความสามารถของ **Vivo X300 Pro** ในการใช้งาน External Display ผ่าน USB-C โดยมีเงื่อนไขสำคัญ:

- ไม่เปิด Developer Options
- ไม่เปิด Force Desktop Mode
- ไม่ใช้ ADB ในการทำงานของ App
- ไม่ใช้ Root
- ไม่ใช้ Shizuku
- ไม่ใช้ Accessibility Service
- ไม่ใช้ Overlay permission
- ไม่ใช้ Network permission หากไม่จำเป็น
- ไม่ต้องสร้าง Desktop Environment
- ไม่ต้องทำ App Launcher ใน Phase นี้

เป้าหมายมีเพียงการตอบคำถาม:

> เมื่อ Vivo X300 Pro ต่อ USB-C → HDMI และระบบอยู่ใน Mirror Mode ตามปกติ Android App ของเราสามารถตรวจพบ External Display และสามารถ launch Activity ไปยัง Display นั้นโดยตรงได้หรือไม่?

---

## Technical Stack

ใช้ Native Android:

- Kotlin
- Android Studio
- Gradle Kotlin DSL
- Android SDK
- Jetpack Compose สำหรับ UI
- `DisplayManager`
- `ActivityOptions`
- `PackageManager` ไม่จำเป็นใน Phase 1
- ไม่มี third-party library ถ้าไม่จำเป็น

ใช้ package name ชั่วคราว:

`com.example.vivodesktoplite`

ตั้ง `minSdk` ให้เหมาะสมกับ API ที่ต้องใช้ โดยไม่ลด compatibility โดยไม่จำเป็น

---

# Phase 1 Scope

App ต้องทำ 4 อย่าง:

1. ตรวจสอบ Android Device / OS information
2. ตรวจสอบ Display ทั้งหมดที่ Android expose ให้ App
3. ตรวจสอบความสามารถเกี่ยวกับ secondary display
4. ทดลอง launch Test Activity ไปยัง External Display

ห้ามเพิ่มฟีเจอร์ Desktop Launcher, Taskbar หรือ App Management ใน Phase นี้

---

# UI

หน้าหลักเรียบง่าย:

```text
VivoDesktopLite

Device
Vivo X300 Pro
Android: XX
SDK: XX

Display Capability
Activities on Secondary Displays:
[SUPPORTED / NOT SUPPORTED]

Detected Displays
────────────────────────

Display #0
Name: ...
Size: 1260 x XXXX
Density: ...
State: ...
Flags: ...

Display #1
Name: ...
Size: 1920 x 1080
Density: ...
State: ...
Flags: ...

────────────────────────

[ Refresh Displays ]

[ Test Display #1 ]
```

ถ้าไม่มี External Display:

```text
No secondary display detected.
Connect USB-C → HDMI and press Refresh.
```

ปุ่ม Test ต้องแสดงเฉพาะ Display ที่สามารถใช้ทดสอบได้

---

# Display Detection

ใช้ Android `DisplayManager`.

ตรวจสอบ:

- `displayId`
- `name`
- `width`
- `height`
- `densityDpi`
- `state`
- `flags`
- refresh rate ถ้าหาได้
- supported display modes ถ้าหาได้

ตัวอย่างข้อมูลที่ต้องการ:

```text
Display ID: 1
Name: HDMI Display
Resolution: 1920 x 1080
Density: 160
State: ON
Flags: ...
Refresh Rate: 60 Hz
```

อย่าสมมติว่า Display ID ของ External Display ต้องเป็น `1`.

ค้นหา Display จากข้อมูลจริงเท่านั้น

---

# Secondary Display Capability

ตรวจสอบ:

```kotlin
packageManager.hasSystemFeature(
    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
)
```

แสดงผลให้ชัดเจน:

```text
Activities on Secondary Displays: SUPPORTED
```

หรือ

```text
Activities on Secondary Displays: NOT SUPPORTED
```

ห้ามตีความ `SUPPORTED` ว่าการ launch จะสำเร็จแน่นอน

มันเป็นเพียง capability ของระบบ

---

# Launch Test

สร้าง Activity แยก:

`DisplayTestActivity`

Activity นี้ต้องมี UI ที่เห็นได้ชัดเจนว่าเปิดอยู่บน Display ไหน

ตัวอย่าง:

```text
VivoDesktopLite

DISPLAY TEST

Display ID: 1

1920 × 1080

If you can see this screen
on the external monitor,
the Activity was launched
on the secondary display.
```

ใช้:

```kotlin
ActivityOptions.makeBasic()
```

และ:

```kotlin
launchDisplayId = targetDisplayId
```

จากนั้น launch Activity ไปยัง Display ที่เลือก

---

# Important Behavior

อย่าทำ fallback เงียบ ๆ

ถ้า launch ไป External Display ไม่สำเร็จ ต้องแสดง error ที่อ่านรู้เรื่อง เช่น:

```text
Launch failed

Target Display: 1

Exception:
...

Reason:
...
```

Logcat ต้องมีข้อมูลด้วย เช่น:

```text
[VivoDesktopLite]
Display test requested
targetDisplayId=1
displayName=HDMI Display
```

ก่อน launch:

```text
[VivoDesktopLite]
Attempting launchDisplayId=1
```

สำเร็จ:

```text
[VivoDesktopLite]
DisplayTestActivity launched
displayId=1
```

หรือถ้าล้มเหลว:

```text
[VivoDesktopLite]
Launch failed
exception=...
```

---

# Verify Actual Display

สำคัญมาก:

`DisplayTestActivity` ต้องตรวจสอบ Display ที่ Activity กำลังทำงานอยู่จริง และแสดง:

```kotlin
windowManager.defaultDisplay.displayId
```

หรือ API ที่เหมาะสมกับ target SDK

อย่าใช้แค่ค่าที่ส่งเข้ามาจาก MainActivity

เหตุผลคือเราต้องพิสูจน์ว่า:

```text
requested display = 1
```

และ:

```text
actual activity display = 1
```

จริงหรือไม่

---

# No Unnecessary Permissions

AndroidManifest ไม่ควรมี permission ที่ไม่จำเป็น

โดยเฉพาะ:

- INTERNET
- ACCESS_NETWORK_STATE
- SYSTEM_ALERT_WINDOW
- REQUEST_INSTALL_PACKAGES
- QUERY_ALL_PACKAGES
- Accessibility
- VPN
- Storage permission

Phase 1 ควรทำงานโดยไม่มี runtime permission

---

# Security / Privacy Requirement

App ต้อง:

- ไม่ส่งข้อมูลออก Internet
- ไม่เก็บข้อมูลส่วนตัว
- ไม่อ่านไฟล์ส่วนตัว
- ไม่ทำ analytics
- ไม่ใช้ advertising SDK
- ไม่โหลด executable/code จาก Internet
- ไม่มี remote configuration

สามารถใส่ network permission เป็นศูนย์ได้ถ้า build configuration ทำได้

---

# Logging

ใช้ Android Logcat อย่างเป็นระบบ

Tag:

```text
VivoDesktopLite
```

Log:

1. App startup
2. Device information
3. Display list
4. Display connection/disconnection
5. Secondary display capability
6. Launch request
7. Launch result
8. Exception

อย่า log sensitive information

---

# Display Change Detection

ใช้ `DisplayManager.DisplayListener` เพื่อ detect:

- display added
- display removed
- display changed

เมื่อเสียบ USB-C → HDMI:

```text
displayAdded()
```

ควรทำให้ UI refresh โดยไม่ต้อง restart App

เมื่อถอด HDMI:

```text
displayRemoved()
```

UI ต้อง update และเอา Display ที่หายออกจากรายการ

---

# Architecture

อย่าทำ architecture ใหญ่เกินไป

แนะนำ:

```text
MainActivity
     │
     ▼
DisplayViewModel
     │
     ├── DisplayRepository
     │       │
     │       └── DisplayManager
     │
     └── LaunchDisplayUseCase
             │
             └── ActivityOptions
```

สำหรับ Phase 1 สามารถลด abstraction ได้ถ้าการแยก class ทำให้ project ซับซ้อนเกินความจำเป็น

หลักสำคัญคือ code ต้องอ่านง่ายและตรวจสอบได้

---

# Suggested Project Structure

```text
VivoDesktopLite/
├── app/
│   └── src/main/
│       ├── java/com/example/vivodesktoplite/
│       │   ├── MainActivity.kt
│       │   ├── DisplayTestActivity.kt
│       │   │
│       │   ├── display/
│       │   │   ├── DisplayInfo.kt
│       │   │   └── DisplayRepository.kt
│       │   │
│       │   └── ui/
│       │       └── ...
│       │
│       ├── res/
│       └── AndroidManifest.xml
│
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

สามารถปรับโครงสร้างได้ถ้า Agent เห็นว่ามีวิธีที่ง่ายกว่า

---

# Critical Test Procedure

หลัง Build APK แล้วให้ทดสอบตามลำดับ:

### Test A — ไม่มี External Display

เปิด App โดยไม่ต่อ HDMI

Expected:

```text
Display #0 = Phone
No secondary display detected
```

---

### Test B — ต่อ USB-C → HDMI

ต่อ Vivo X300 Pro → USB-C → HDMI Monitor

ห้ามเปิด:

- Developer Options
- Force Desktop Mode
- Freeform
- Desktop Mode testing option

ปล่อย Vivo อยู่ใน default Mirror Mode

กด:

```text
Refresh Displays
```

บันทึกผล:

```text
Number of displays:
Display IDs:
Display names:
Resolution:
Flags:
FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS:
```

---

### Test C — Launch Test

ถ้ามี External Display:

กด:

```text
Test Display
```

เลือก External Display

Expected success:

```text
Phone:
MainActivity

External Display:
DisplayTestActivity
```

ถ้าไม่สำเร็จ ให้เก็บ exception และ Logcat

---

# Important Diagnostic Information

README ต้องมี section:

## Test Result

ให้ผู้ใช้กรอก:

```text
Device:
Vivo X300 Pro

OriginOS version:

Android version:

Build number:

USB-C display adapter:

External monitor:

Developer Options:
OFF

Force Desktop Mode:
OFF

Detected Displays:

FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS:

Launch Result:

Error:

Logcat:
```

---

# Do Not Implement Yet

ห้ามทำสิ่งเหล่านี้ใน Phase 1:

- Desktop launcher
- Taskbar
- Floating windows
- Window manager
- App list
- Recent apps
- Mouse management
- Keyboard management
- Wallpaper
- Screen casting
- VNC
- WebSocket
- GStreamer
- Root
- Shizuku
- ADB automation
- Accessibility
- Force Desktop Mode

Phase 1 มีเป้าหมายเดียว:

> Determine whether a normal third-party Android app can detect the Vivo X300 Pro's external USB-C display and launch an Activity directly onto it while Force Desktop Mode and Developer Options are OFF.

---

# Deliverables

Agent ต้องส่งมอบ:

1. Complete Android Studio project
2. Source code
3. `README.md`
4. Buildable debug APK
5. Exact build/install instructions
6. Explanation of the APIs used
7. Test procedure
8. Expected results
9. Known limitations
10. Logcat commands useful for diagnosing failure

ห้ามส่งเฉพาะ APK

Source code ต้องอยู่ใน project และสามารถ build APK จาก source ได้เอง

---

# Success Criteria

Phase 1 ถือว่าสำเร็จถ้า:

```text
USB-C HDMI connected
        │
        ▼
External Display detected by Android
        │
        ▼
No Developer Options
No Force Desktop Mode
        │
        ▼
VivoDesktopLite
        │
        ▼
launchDisplayId = External Display ID
        │
        ▼
DisplayTestActivity
        │
        ▼
Actually visible on external monitor
```

ถ้าทำไม่ได้ ให้ระบุ **จุดที่ล้มเหลวอย่างชัดเจน** แทนการหาวิธี workaround ใน Phase 1

โดยเฉพาะต้องแยกให้ออกว่า failure เกิดจาก:

1. Android ไม่ expose external display
2. Vivo expose display แต่ไม่อนุญาต Activity บน secondary display
3. `ActivityOptions.launchDisplayId` ถูกปฏิเสธ
4. Activity ถูก launch แต่ถูกย้ายกลับ Display 0
5. App/Activity ถูกจำกัดโดย Vivo
6. เป็นข้อจำกัดของ USB-C display implementation
7. เป็นข้อจำกัดอื่นของ OriginOS

อย่าสรุปว่า "Vivo ไม่รองรับ" จากการทดสอบครั้งเดียว ต้องแสดงหลักฐานจาก Display information และ Logcat ก่อน

---

## Development Principle

โปรเจกต์นี้ต้องเน้น:

**Minimal → Native → Auditable → No unnecessary permissions → No proprietary dependency**

อย่าเพิ่ม dependency หรือ permission เพียงเพื่อให้ prototype ทำงาน หาก Android SDK มี API สำหรับงานนั้นอยู่แล้ว