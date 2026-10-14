# vitRemote

Android app (Kotlin) that uses an **Accessibility Service** to let you view and control your phone from a web browser on the local network.

## Features
- **Live screen streaming** with tap / long-press / swipe directly on the image, mouse wheel to scroll, right-click = Back
  - MediaProjection: up to ~15 fps, any Android version (one-time "Start now" on the phone)
  - Accessibility screenshot API: ~3 fps fallback on Android 11+ (no prompt)
- Navigation: **Back / Home / Recents**
- **Power key**: tap = screen off / wake, hold 0.6 s = power menu (Android < 9 needs the optional "Screen lock" device-admin permission)
- Volume −/+ (hold to repeat), full-resolution screenshot download
- **Type directly from your keyboard** into the focused field on the phone (Backspace, Enter, paste supported); `Esc` = Back
- Device details: model, Android, battery, RAM, storage, volume, IP, uptime
- Stream resolution adapts to the window size and pauses when the tab is hidden
- 6-digit **PIN**, configurable port

## Lightweight by design
- Hand-written HTTP + WebSocket server, no libraries; blocking I/O => ~0% CPU while idle
- Commands are a few bytes (`a:home`, `t:5000,5000`), `TCP_NODELAY` => instant response
- Screen is captured only while someone is watching; unchanged frames are never re-sent
- Web page is gzipped (~6 KB); UI uses Material 3, Roboto and Material Symbols
- APK ~1.5 MB

## Usage
1. Install the APK, open it → **Open Accessibility settings** → enable **vitRemote**.
2. On a computer on the same Wi-Fi, open the address shown in the app (e.g. `http://192.168.1.17:8080`) and enter the PIN.
3. Over USB: `adb forward tcp:8080 tcp:8080`, then open `http://localhost:8080`.

## Build
```
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

## WebSocket protocol (`/ws?k=PIN`)
| Command | Meaning |
|---|---|
| `a:back\|home\|recents\|notif\|qs\|power\|lock\|shot` | Global action |
| `t:x,y` / `l:x,y` | Tap / long-press (coordinates 0..10000) |
| `s:x1,y1,x2,y2,ms` | Swipe |
| `v:up\|down` | Media volume |
| `x:text` · `b` · `X` · `e` | Type · backspace · clear field · Enter |
| `P` | Power key: screen off / wake |
| `c` · `L:width` | Full screenshot · live stream width (0 = off) |
| `i` · `p` | Device info · ping |

Images are binary messages: first byte `1` = live frame, `2` = screenshot; the rest is JPEG.

> ⚠️ Use on trusted networks only: traffic is plain HTTP/WS protected by the PIN.
