<div align="center">

# vitRemote

**View and control your Android phone from any web browser on your local network — with low-latency H.264 video and real-time touch.**

No root · No ADB · No desktop app · No cloud · ~1.4 MB APK

[![Platform](https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)](app/src/main/java/com/vvtool/vitremote)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/vitvu/vitRemote?include_prereleases)](https://github.com/vitvu/vitRemote/releases)
[![Donate](https://img.shields.io/badge/Donate-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/vudinhviet)

</div>

---

## Table of contents

- [Overview](#overview)
- [Features](#features)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Usage](#usage)
- [Permissions](#permissions)
- [Performance & latency](#performance--latency)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [WebSocket protocol](#websocket-protocol)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Support the project](#support-the-project)
- [License](#license)

## Overview

vitRemote turns your phone into a small web server. Open the address shown in the app from a
browser on the same network, enter the PIN, and you get a live view of the screen you can click,
drag, scroll and type into, plus hardware keys (power, volume, Back / Home / Recents) and detailed
device information.

Everything runs on the phone itself: a hand-written HTTP + WebSocket server with no third-party
libraries, an Accessibility Service for input, and MediaProjection + the hardware video encoder for
the screen stream.

## Features

| | |
|---|---|
| 🎥 **Live screen** | H.264 hardware-encoded video, up to 60 fps, adaptive quality (Auto / Low / Medium / High) |
| 👆 **Real-time touch** | Finger follows the mouse while dragging (Android 8+); tap, long-press, swipe, mouse-wheel scroll, right-click = Back |
| ⌨️ **Keyboard** | Type on your computer keyboard straight into the focused field on the phone; Backspace, Enter, paste |
| 🔘 **Hardware keys** | Power (tap = screen off / wake, hold = power menu), volume ± (hold to repeat), Back / Home / Recents |
| 📸 **Screenshot** | Full-resolution capture (Android 11+) or the current video frame |
| 📊 **Device info** | Model, Android version, battery, RAM, storage, volume, IP, uptime, stream statistics |
| 🔒 **Secure by default** | 6-digit PIN, LAN only, optional HTTPS with a per-device key in the Android Keystore |
| 🪶 **Lightweight** | ~0 % CPU while idle, nothing captured when nobody is watching, ~6 KB gzipped web UI |

## How it works

```
 Phone                                                      Browser
┌──────────────────────────────────────────────┐          ┌──────────────────────────┐
│ MediaProjection → VirtualDisplay              │          │                          │
│        → MediaCodec H.264 (hardware, surface) │  binary  │ WebCodecs (HTTPS)        │
│        → SPS rewrite (no frame reordering)    │ ───────► │   or MSE + fMP4 muxer    │
│        → per-viewer queue (drops stale frames)│    WS    │ → <canvas>/<video>       │
│                                               │          │                          │
│ AccessibilityService                          │   text   │ pointer / keyboard events│
│   dispatchGesture (continued strokes),        │ ◄─────── │ "d:x,y" "m:x,y" "a:home" │
│   global actions, ACTION_SET_TEXT             │    WS    │                          │
└──────────────────────────────────────────────┘          └──────────────────────────┘
```

## Requirements

| | Minimum | Notes |
|---|---|---|
| Android | **7.0** (API 24) | Real-time drag requires 8.0+, full-resolution screenshots require 11+ |
| Browser | Any modern Chrome, Edge, Firefox or Safari | Chromium-based browsers give the lowest latency (WebCodecs) |
| Network | Phone and computer on the same LAN / hotspot, or USB via `adb forward` | |

## Installation

1. Download `vitRemote-x.y.z.apk` from [Releases](https://github.com/vitvu/vitRemote/releases)
   (or [build it yourself](#building-from-source)).
2. Install it (allow installing from unknown sources if asked).
3. Open **vitRemote** and grant the permissions listed under **Permissions** in the app.

## Usage

1. In the app, check that the status says **Ready**, then note the address and PIN, e.g.
   `http://192.168.1.17:8080` and `305734`.
2. Open the address in a browser on the same network and enter the PIN.
3. The first time, tap **Start now** on the phone to allow screen capture
   (tick *Don't show again* where available).
4. For the lowest latency, click **Switch to low-latency mode (HTTPS)** on the web page
   (port `8081`) and accept the self-signed certificate once.

**Over USB** (no Wi-Fi needed):

```bash
adb forward tcp:8080 tcp:8080
# then open http://localhost:8080 — localhost is a secure context, so WebCodecs is used
```

### Controls

| Action | How |
|---|---|
| Tap / long-press / drag | Click / hold / drag on the screen |
| Scroll | Mouse wheel over the screen |
| Back | Right-click on the screen, `Esc`, or the ◀ button |
| Type text | Tap a text field on the phone, then just type; paste with `Ctrl+V` |
| Screen off / wake | Tap the power button |
| Power menu | Hold the power button for 0.6 s |
| Volume | Click or hold the volume buttons |

## Permissions

| Permission | Why | Required |
|---|---|---|
| Accessibility service | Inject touches, keys and text; global actions (Back, Home, …) | ✅ |
| Screen capture (MediaProjection) | Video stream of the screen | ✅ (asked on first view) |
| Screen lock (device admin) | Turn the screen off from the web on **Android < 9** only | Optional |
| Unrestricted battery | Keep the service alive in the background | Recommended |
| Notifications | Foreground-service notification while streaming | Optional |
| Internet / Wi-Fi state | Local web server and showing the LAN address | ✅ |

vitRemote collects no data and never connects to the internet. The web page loads only the
Roboto font and Material Symbols icons from Google Fonts. It works without them, but icons fall back to text.

## Performance & latency

Measured on a Samsung Galaxy J7 Prime (Exynos 7870, Android 8.1) over Wi-Fi:

| Stage | Time |
|---|---|
| On-phone encode (480p / 720p) | ~20 ms / ~31 ms |
| Network round trip | 3–9 ms |
| Browser decode → display | ~1 ms |
| Bandwidth, static screen | ≈ 0–2 KB/s |
| Bandwidth, scrolling (480p60) | ≈ 100–250 KB/s |

Key techniques:

- **Zero-copy capture**: the virtual display renders directly into the encoder's input surface.
- **SPS rewrite**: the encoder's SPS is patched to declare `max_num_reorder_frames = 0`, so
  decoders output every frame immediately instead of buffering ~10 frames (~250 ms).
- **Live-edge playback**: 1 ms sample durations in MSE and `optimizeForLatency` in WebCodecs.
- **Backpressure**: each viewer has its own send queue; when it backs up, P-frames are dropped
  until the next keyframe, so latency never accumulates.
- **Idle cost**: the encoder is torn down when nobody watches; blocking I/O keeps the server at ~0 % CPU.

## Building from source

**Prerequisites:** Android Studio (or JDK 17+) and Android SDK Platform 37.

```bash
git clone https://github.com/vitvu/vitRemote.git
cd vitRemote
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

> The release build type is signed with the debug key so it installs straight away.
> Configure your own `signingConfig` before publishing to a store.

## Project structure

```
app/src/main/
├── java/com/vvtool/vitremote/
│   ├── MainActivity.kt        # Minimal Material 3 UI: status, address/PIN, permissions, device info
│   ├── RemoteService.kt       # AccessibilityService: command handling, real-time gestures, JPEG fallback
│   ├── CaptureService.kt      # MediaProjection → hardware H.264 encoder (foreground service)
│   ├── ProjectionActivity.kt  # Transparent activity that requests screen-capture consent
│   ├── WebServer.kt           # HTTP + WebSocket server (RFC 6455), per-viewer video queues
│   ├── SpsFix.kt              # H.264 SPS rewriter (adds VUI bitstream_restriction)
│   ├── Tls.kt                 # Self-signed TLS identity in the Android Keystore
│   ├── DeviceInfo.kt          # Device information + tiny JSON encoder
│   ├── AdminReceiver.kt       # Device admin (screen lock on Android < 9)
│   └── Prefs.kt               # Port, PIN, LAN addresses
├── assets/index.html          # Single-file web client (player, fMP4 muxer, controls)
└── res/                       # Layouts, themes, accessibility & device-admin configs
```

## WebSocket protocol

Endpoint: `ws://<phone>:<port>/ws?k=<PIN>` (or `wss://<phone>:<port+1>/ws?k=<PIN>`).

**Client → phone** (text):

| Command | Meaning |
|---|---|
| `V:width,kbps,fps` | Start / adjust the video stream (`width` 0 = stop) |
| `d:x,y` · `m:x,y` · `u:x,y` | Finger down / move / up (coordinates 0–10000 of the screen) |
| `t:x,y` · `l:x,y` · `s:x1,y1,x2,y2,ms` | Tap · long-press · swipe |
| `a:back\|home\|recents\|notif\|qs\|power\|lock\|shot` | Global action |
| `P` | Power key: screen off / wake |
| `v:up\|down\|mute` | Media volume |
| `x:text` · `b` · `X` · `e` | Type · backspace · clear field · Enter |
| `c` | Full-resolution screenshot (Android 11+) |
| `i` · `p` | Device info · ping |

**Phone → client:** text messages are JSON (`{"t":"info",…}`, `{"t":"err","m":…}`).
Binary messages start with a type byte:

| Byte | Payload |
|---|---|
| `3` | H.264 config: `[width:u16][height:u16][SPS+PPS, Annex-B]` |
| `4` | H.264 access unit: `[keyframe:u8][Annex-B data]` |
| `1` | JPEG live frame (fallback without screen-capture permission) |
| `2` | JPEG full-resolution screenshot |

## Security

- Access requires the 6-digit PIN (regenerate it anytime in the app).
- The server listens on the local network only, and only while the Accessibility Service is on
  and **Web server** is enabled.
- Plain `http` traffic is **not encrypted**. Use the HTTPS port or USB (`adb forward`) on networks you don't fully trust.
- The HTTPS certificate is self-signed. Its private key is generated on the device and never
  leaves the Android Keystore.
- Never expose the port to the internet (no port forwarding on your router).

## Troubleshooting

| Problem | Fix |
|---|---|
| Status shows **Inactive** | Enable vitRemote in *Settings › Accessibility* (on some phones: *Installed apps*). |
| Black screen in the browser | Tap **Start now** on the phone, or toggle **Screen capture** in the app. |
| "Port busy" | Change the port in the app (1024–65535). |
| Page doesn't load | Phone and computer must be on the same network. Some routers isolate Wi-Fi clients ("AP isolation"). |
| Service stops after a while | Allow **Unrestricted battery** and lock the app in Recents (MIUI, One UI, ColorOS…). |
| Typing does nothing | Tap a text field on the phone first so it has focus. |
| Power button only opens the menu (Android < 9) | Grant **Screen lock** in the app. |

## Contributing

Issues and pull requests are welcome.

1. Fork the repository and create a branch: `git checkout -b feature/my-change`
2. Keep the app dependency-free and lightweight, and follow the existing Kotlin style.
3. Build and test on a real device: `./gradlew assembleDebug`
4. Open a pull request that describes the change and how you tested it.

## Support the project

If vitRemote is useful to you, consider buying me a coffee ☕

[![Donate with PayPal](https://img.shields.io/badge/Donate-PayPal-00457C?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/vudinhviet)

**https://paypal.me/vudinhviet**

Starring ⭐ the repository also helps others find it.

## License

Copyright © 2026 Vũ Đình Việt ([@vitvu](https://github.com/vitvu)).

Released under the [MIT License](LICENSE). You may use, modify and distribute this software,
provided the copyright notice and license text are included in all copies or substantial portions.

*Android is a trademark of Google LLC. This project is not affiliated with or endorsed by Google.*
