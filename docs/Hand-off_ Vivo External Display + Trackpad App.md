# Project Hand-off: Vivo External Display + Phone Trackpad

## เป้าหมาย

สร้าง Android App สำหรับ Vivo X300 Pro ที่ทำงานในรูปแบบคล้าย Samsung DeX / Huawei Desktop Mode แต่ไม่ต้องเปิด Android `Force Desktop Mode`

Use case:

1. Vivo X300 Pro ต่อ USB-C → External Display
2. ระบบสามารถเปิด YouTube หรือแอปที่เลือกไปยัง External Display ได้อยู่แล้ว
3. แอปบน External Display สามารถแสดง fullscreen 16:9 ได้
4. มือถือยังคงใช้หน้าจอหลักเป็น Trackpad
5. Trackpad ต้องควบคุม pointer/input บน External Display
6. ไม่ใช้ Shizuku
7. ไม่ใช้ Root
8. ไม่ต้องเปิด `Force Desktop Mode`
9. ไม่ต้อง reboot เพื่อใช้งาน

ภาพรวม:

```text
Vivo X300 Pro
┌──────────────────────────┐
│                          │
│      Trackpad App        │
│                          │
│   Finger → Cursor        │
│                          │
│   1 finger = move        │
│   tap = left click       │
│   2 finger = right click │
│   2 finger = scroll      │
│   drag = mouse drag      │
│                          │
└────────────┬─────────────┘
             │
             │ Android input/display APIs
             ▼
       External Display
┌──────────────────────────┐
│                          │
│         YouTube          │
│                          │
│          16:9            │
│       Fullscreen         │
│                          │
└──────────────────────────┘
```

---

# สิ่งที่พิสูจน์แล้ว

## External Display

บน Vivo X300 Pro:

- USB-C DisplayPort output ใช้งานได้
- Android เห็น External Display เป็น secondary display
- สามารถสั่งเปิดแอปไปยัง External Display ได้
- แอปสามารถแสดง fullscreen บน External Display ได้
- YouTube สามารถถูกเปิดไปยัง External Display ได้
- ไม่จำเป็นต้องเปิด `Force Desktop Mode` เพื่อให้ Activity ไป External Display

ดังนั้นปัญหาไม่ได้อยู่ที่ rendering หรือ Activity placement

## ปัญหาปัจจุบัน

เมื่อไม่เปิด `Force Desktop Mode`:

- USB mouse ไม่สามารถย้าย pointer ไป External Display ได้ตามปกติ
- input/pointer ยังคงถูกจัดการโดยระบบในลักษณะที่ไม่เหมือน Desktop Mode

ดังนั้น remaining problem คือ:

> Input routing จาก Trackpad App → External Display

---

# ข้อกำหนดสำคัญ

ห้ามใช้:

- Shizuku
- Root
- ADB runtime dependency
- `Force Desktop Mode`
- Samsung DeX-specific API
- Huawei-specific API

ต้องพยายามทำเป็น:

> Standard Android APK

ถ้า Android public API ไม่สามารถทำ raw input injection ไปยัง secondary display ได้ ให้พิสูจน์ข้อจำกัดนั้นก่อน อย่าหาวิธี workaround แบบ unsafe หรือ hack ระบบทันที

---

# Phase 1 — Feasibility Test

ยังไม่ต้องทำ UI สวย

สร้าง minimal Android app เพื่อทดสอบ:

1. ตรวจหา Display ทั้งหมดผ่าน `DisplayManager`
2. แสดง:
   - displayId
   - resolution
   - density
   - flags
   - state
   - display type
3. ระบุว่า display ใดคือ External Display
4. ทดลองสร้าง `Presentation` บน External Display
5. ทดลอง launch Activity ไปยัง external display ถ้า Android API อนุญาต
6. ตรวจสอบว่า app สามารถรับ touch/motion event จากมือถือได้
7. ศึกษาว่าสามารถส่ง input event ไปยัง external display ด้วย public Android API ได้หรือไม่

ตัวอย่างข้อมูลที่ต้อง log:

```text
Display 0
type=BUILT_IN
width=1260
height=2800

Display 1
type=EXTERNAL
width=1920
height=1080
flags=...
```

อย่าสมมติว่า External Display = displayId 1 ต้อง detect จริง

---

# Phase 2 — Input Feasibility

ต้องหาคำตอบให้ชัดเจน:

> Android public API อนุญาตให้ normal application ส่ง pointer/mouse event ไปยัง specific secondary display หรือไม่?

ค้นและทดลอง API ที่เกี่ยวข้อง เช่น:

- `DisplayManager`
- `Display`
- `Presentation`
- `WindowManager`
- `Window`
- `InputEvent`
- `MotionEvent`
- `InputDevice`
- `MediaRouter`
- multi-display APIs
- `WindowContext`
- Activity launch/display APIs

อย่า assume ว่า `Presentation` สามารถ route mouse ได้

ต้องแยกให้ชัด:

```text
Rendering on Display 1
        ≠
Input routing to Display 1
```

---

# Phase 3 — Trackpad Prototype

ถ้า Phase 2 พบวิธีที่ไม่ต้องใช้ privileged API:

สร้าง Trackpad UI แบบ minimal:

```text
┌──────────────────────────────┐
│                              │
│                              │
│          TRACKPAD            │
│                              │
│                              │
│                              │
│                              │
├──────────────────────────────┤
│   Back     Home     Keyboard │
└──────────────────────────────┘
```

Gesture:

### 1 finger

Movement:

```text
finger dx/dy
      ↓
pointer dx/dy
```

### Tap

Left click

### Double tap

Double click

### Long press + move

Drag

### 2 finger movement

Scroll

### 2 finger tap

Right click

Sensitivity ต้อง configurable

---

# Phase 4 — External Display Controller

เพิ่ม UI สำหรับเลือก target display:

```text
External Displays

○ Display 0 — Phone
● Display 1 — HDMI 1920x1080

[Connect]
```

ถ้ามี display เดียว ให้เลือกอัตโนมัติ

ถ้า external display disconnect:

- stop sending input
- return to normal phone mode
- ไม่ crash

ถ้า reconnect:

- detect display ใหม่
- reconnect automatically ถ้าเป็นไปได้

---

# Phase 5 — Force Open App

สร้าง feature:

```text
Select App

[ YouTube ]

Target:
[ External Display ]

[ Launch ]
```

เป้าหมายคือ:

```text
YouTube Activity
       ↓
External Display
       ↓
Fullscreen
```

ไม่ต้องทำ Desktop Mode

ถ้า launch Activity ไป secondary display ทำได้ด้วย public API ให้ใช้ API นั้น

ถ้าต้องใช้ privileged shell command ให้ระบุเป็น limitation และอย่าผูก implementation หลักกับ ADB/Shizuku

---

# Architecture ที่ต้องการ

แยก module ให้ชัด:

```text
app
├── display/
│   ├── DisplayManager
│   ├── ExternalDisplayDetector
│   └── PresentationController
│
├── input/
│   ├── TrackpadController
│   ├── GestureDetector
│   ├── PointerController
│   └── InputRouter
│
├── launcher/
│   └── ExternalActivityLauncher
│
└── ui/
    ├── TrackpadActivity
    ├── DisplaySelector
    └── AppSelector
```

อย่าเอา input logic ไปผูกกับ UI

---

# Critical Feasibility Question

ก่อน implement เต็มระบบ ต้องตอบคำถามนี้:

> Can a normal Android application, without root, Shizuku, Accessibility abuse, or Force Desktop Mode, inject or route pointer/mouse input to a specific secondary display?

ถ้า:

### YES

ดำเนินการสร้าง Trackpad จริง

### PARTIAL

ระบุว่า API ใดทำได้ และข้อจำกัดคืออะไร

จากนั้นเลือกวิธีที่เป็น standard Android API มากที่สุด

### NO

หยุดก่อนทำ UI เต็มระบบ

รายงาน:

1. API ที่ทดลอง
2. permission ที่ขาด
3. security restriction
4. สิ่งที่ Samsung DeX ทำเพิ่มใน system layer
5. วิธีที่ต้องใช้ privileged access
6. วิธีที่สามารถทำได้โดยไม่ใช้ Shizuku/Root

อย่าสร้าง fake cursor ที่แค่แสดงวงกลมบน External Display เพราะนั่นไม่ใช่ real input routing

---

# UX เป้าหมายสุดท้าย

ต้องการ behavior แบบ:

```text
USB-C connected
        │
        ▼
External Display detected
        │
        ├───────────────┐
        │               │
        ▼               ▼
Phone               External
Trackpad             Display
        │               │
        │               ▼
        │            YouTube
        │               │
        │            16:9
        │           Fullscreen
        │
        └── pointer control
```

มือถือไม่ต้องกลายเป็น Desktop UI

External Display ไม่ต้องแสดง Android launcher

ไม่มี taskbar

ไม่มี freeform window

ไม่มี Force Desktop Mode

แนวคิดคือ:

> **Phone = Touchpad / Controller**
>
> **External Display = Dedicated fullscreen application**

---

# Priority

เรียงลำดับความสำคัญ:

1. ตรวจสอบ public Android input routing capability
2. พิสูจน์ pointer movement ไป External Display
3. click
4. scroll
5. drag
6. launch app ไป External Display
7. fullscreen behavior
8. Trackpad UI
9. app selector
10. polish

อย่าเริ่มจาก UI ก่อนพิสูจน์ข้อ 1–2

---

# Development Environment

Target:

- Vivo X300 Pro
- Android / OriginOS version ให้ detect runtime
- USB-C DisplayPort external display
- External display อย่างน้อย 1920×1080

Development:

- Kotlin
- Android Studio
- Modern Android SDK
- minSdk ตาม API ที่จำเป็น
- targetSdk เป็น current stable SDK

ทุก API ที่ใช้ต้องตรวจ Android version compatibility

---

# Deliverables

Phase 1:

```text
DisplayDiagnosticActivity
```

แสดงข้อมูลทุก display และสามารถ capture log ได้

Phase 2:

```text
ExternalDisplayTestActivity
```

แสดง test UI บน Display 1

Phase 3:

```text
TrackpadPrototypeActivity
```

แสดง touch coordinates / dx / dy และทดลอง input routing

Phase 4:

```text
ExternalDisplayController
```

เปิด application ที่เลือกไปยัง external display

Phase 5:

รวมเป็น:

```text
Vivo External Trackpad
```

---

# Important

ห้ามสรุปว่า feature ทำได้เพียงเพราะ:

- Activity เปิดบน external display ได้
- Presentation render ได้
- Trackpad รับ touch ได้

สามอย่างนี้ไม่ได้แปลว่า input สามารถถูก route ไปยัง secondary display ได้

ต้องมีหลักฐานจากการทดลองจริงบน Vivo X300 Pro

ถ้า Android security model ป้องกันไว้ ให้รายงานตามจริง
และเสนอทางเลือกถัดไปแยกเป็น:

- Standard API
- Accessibility
- Shizuku
- Root
- System/OEM integration

โดย **ไม่เปลี่ยน requirement เอง**