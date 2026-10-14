# vitRemote

Android app (Kotlin) that uses an **Accessibility Service** to let you view and control your phone from a web browser on the local network.

## Features
- **Live H.264 video** of the screen: MediaProjection -> hardware encoder (zero copy, very low CPU), up to 60 fps
  - Browser plays it with WebCodecs (`optimizeForLatency`, on localhost/https) or MSE + a tiny built-in fMP4 muxer (plain http on LAN)
  - Static screen = almost no traffic; slow network => old frames are dropped instead of adding latency
  - Fallback without the capture permission: JPEG via the Accessibility screenshot API (Android 11+, ~3 fps)
- **Real-time touch** (Android 8+): the finger follows the mouse live while dragging (continued gestures); mouse wheel scrolls, right-click = Back
- **Quality**: Auto (default, follows the window size) / Low 480p / Medium 720p / High 1080p60
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
| `d:x,y` · `m:x,y` · `u:x,y` | Finger down / move / up, live (coordinates 0..10000) |
| `t:x,y` / `l:x,y` | Tap / long-press |
| `s:x1,y1,x2,y2,ms` | Swipe |
| `v:up\|down` | Media volume |
| `x:text` · `b` · `X` · `e` | Type · backspace · clear field · Enter |
| `P` | Power key: screen off / wake |
| `c` · `V:width,kbps,fps` | Full screenshot (Android 11+) · start/adjust stream (width 0 = stop) |
| `i` · `p` | Device info · ping |

Binary messages, first byte: `3` = H.264 config `[w:2][h:2][SPS+PPS]`, `4` = frame `[key:1][Annex-B]`, `1` = JPEG live frame, `2` = JPEG screenshot.

> ⚠️ Use on trusted networks only: traffic is plain HTTP/WS protected by the PIN.
