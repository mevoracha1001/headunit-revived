# Headunit Revived – Architecture

This document describes the architecture of the Android Auto Protocol (AAP) head unit emulator, designed to run on **Android Automotive OS (AAOS)** and standard Android devices without relying on system-level projection APIs.

## Design Principles

- **User-space only**: No CarProjectionService, no privileged permissions, no root
- **Modular separation**: Transport, protocol, rendering, and input are clearly separated
- **Standard permissions**: INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, FOREGROUND_SERVICE

---

## 1. Head Unit Emulator (Core)

### AAP Server

The app implements an **in-app AAP server** that accepts connections from a phone running Android Auto (or AA Wireless dongle).

| Component | Responsibility |
|-----------|---------------|
| **AapTransport** | Session negotiation, SSL handshake, channel multiplexing |
| **AapRead** / **AapReadSingleMessage** / **AapReadMultipleMessages** | Message framing and dispatch |
| **AapMessageHandlerType** | Routes messages by channel ID to handlers |

### Channels (Multiplexing)

| Channel | ID | Handler |
|---------|-----|---------|
| Control (CTR) | 0 | AapControlGateway |
| Sensors (SEN) | 1 | AapControlSensor |
| Video (VID) | 2 | AapVideo |
| Input (INP) | 3 | Touch/key events |
| Audio (AUD, AU1, AU2) | 5–7 | AapAudio |
| Microphone (MIC) | 8 | MicRecorder |
| Bluetooth (BTH) | 9 | Limited |
| Media Playback (MPB) | 10 | AapMediaPlayback |
| Navigation (NAV) | 11 | AapNavigation |

### Handshake Flow

1. Version request/response (channel 0, type 2)
2. SSL handshake via `AapSslContext` (JSSE + Conscrypt) or `AapSslNative` (OpenSSL)
3. Status OK sent
4. Capability negotiation (service discovery, video/audio config)

---

## 2. Transport Layer

### Connection Abstraction

```
AccessoryConnection (interface)
├── UsbAccessoryConnection   (USB accessory mode)
└── SocketAccessoryConnection (TCP over WiFi)
```

- **UsbAccessoryConnection**: USB AOA (Android Open Accessory) for wired phone connection
- **SocketAccessoryConnection**: TCP to phone; `soTimeout=3000`, `tcpNoDelay`, `keepAlive`, buffered I/O (64KB)

### Ports

| Port | Role |
|------|------|
| 5288 | Head unit server (WirelessServer) |
| 5277 | Phone head unit server (client connects here) |
| 5289 | WiFi launcher |

### Discovery

- **mDNS (JmDNS)**: In-app mDNS via JmDNS library. Registers `_aawireless._tcp` on port 5288. No NsdManager/GMS dependency — pure Java, works on restricted devices.
- **NetworkDiscovery**: Subnet scan (1–254), checks 5289 and 5277
- **Manual IP**: User can enter IP:port in settings

### AAOS Note

On Android Automotive OS, USB host is often unavailable. Use **WiFi server mode** (WirelessServer) or **WiFi client mode** (NetworkDiscovery / manual IP) with a phone or AA Wireless dongle.

---

## 3. Video Pipeline

```
Phone (H.264/HEVC) → AapVideo → VideoDecoder (MediaCodec) → Surface
```

| Component | Responsibility |
|-----------|---------------|
| **AapVideo** | Assembles fragmented frames (flags 8/9/10/11), passes to decoder |
| **VideoDecoder** | MediaCodec H.264/AVC, H.265/HEVC; SPS/PPS parsing; hardware/software selection |
| **Views** | ProjectionView (SurfaceView), TextureProjectionView, GlProjectionView |

### Optimizations

- Hardware acceleration preferred
- Reduced max input size (1MB) for memory
- Output thread at `THREAD_PRIORITY_DISPLAY`
- `VIDEO_SCALING_MODE_SCALE_TO_FIT`

---

## 4. Audio Pipeline

```
Phone (PCM/AAC) → AapAudio → AudioDecoder → AudioTrack
```

| Component | Responsibility |
|-----------|---------------|
| **AapAudio** | Routes by channel (AUD, AU1, AU2) |
| **AudioDecoder** | SparseArray of AudioTrackWrapper per channel |
| **AudioTrackWrapper** | PCM or AAC (MediaCodec), gain, queue-based playback |

### Sample Rates

- 48 kHz (media), 16 kHz (assistant)

---

## 5. Input Handling

### Touch

| Component | Responsibility |
|-----------|---------------|
| **OverlayTouchView** | Transparent overlay over projection |
| **TouchEvent** | `motionEventToAction` maps `ACTION_DOWN`, `ACTION_MOVE`, etc. |
| **HeadUnitScreenConfig** | Coordinate correction |

### Key Events

- **KeyCodeReceiver**: Broadcast receiver for `KeyIntent`
- **AapTransport.send(keyCode, isPress)**: Maps via `settings.keyCodes`
- **MediaSession**: Routes media buttons to AAP

### Gestures

- Tap, swipe, scroll supported via `TouchEvent` pointer data

---

## 6. Connection Management

### CommManager State Machine

```
Disconnected → Connecting → Connected → StartingTransport → HandshakeComplete → TransportStarted
     ↑                                                                              │
     +──────────────── disconnect / read error / bye-bye ──────────────────────────+
```

### Lifecycle

- **connect()** → **startHandshake()** → **startReading()**
- **disconnect(sendByeBye)** or **transportedQuited(isClean)** → **doDisconnect()**
- TLS session resumption via shared `AapSslContext`

### Auto-Reconnect

- **Server mode**: Restarts discovery loop after 2s
- **Auto WiFi mode**: One-shot scan on unclean disconnect
- **USB**: Retry after delay (handles dongle re-enumeration)

---

## 7. UI Layer

| Component | Responsibility |
|-----------|---------------|
| **AapProjectionActivity** | Fullscreen projection, touch overlay, video surface |
| **AapService** | Foreground service (connectedDevice + mediaPlayback) |
| **SystemUI** | Immersive mode, hide system bars |
| **WAKE_LOCK** | Keep screen awake |

---

## 8. GMS / System Independence

The app avoids GMS and system projection APIs:

- **mDNS**: Uses JmDNS (in-app) instead of NsdManager
- **SSL**: Conscrypt (bundled) or OpenSSL via JNI — no system TLS
- **Self Mode fallback**: When Android Auto app is unavailable (no GMS), copies connection info to clipboard and shows instructions

## 9. AAOS Compatibility

| Feature | Behavior |
|---------|----------|
| **Car mode** | `enableCarMode` skipped on AAOS (device already in car mode) |
| **USB** | Optional; many AAOS devices lack USB host |
| **WiFi** | Primary transport; use server or client mode |
| **AutomotiveUtils** | `isAutomotiveOs()`, `hasUsbHost()` for runtime detection |

---

## 10. Permissions (Standard Only)

- `INTERNET`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `CHANGE_NETWORK_STATE`
- `ACCESS_NETWORK_STATE`
- `ACCESS_FINE_LOCATION` (WiFi scanning on Android 9+)
- `NEARBY_WIFI_DEVICES` (Android 10+, neverForLocation)
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `WAKE_LOCK`
- `SYSTEM_ALERT_WINDOW`
- `BLUETOOTH*` (steering wheel controls)
- `RECORD_AUDIO` (microphone)
- `MODIFY_AUDIO_SETTINGS`
- `POST_NOTIFICATIONS`
- `RECEIVE_BOOT_COMPLETED`

---

## 11. Directory Structure

```
app/src/main/java/com/adamate/aaforaaos/
├── aap/                    # Protocol layer
│   ├── AapService.kt        # Foreground service
│   ├── AapTransport.kt     # Handshake, read loop
│   ├── AapVideo.kt
│   ├── AapAudio.kt
│   └── protocol/           # Messages, channels
├── connection/              # Transport layer
│   ├── CommManager.kt
│   ├── SocketAccessoryConnection.kt
│   ├── UsbAccessoryConnection.kt
│   ├── NetworkDiscovery.kt
│   └── WifiDirectManager.kt
├── decoder/                 # Video/audio decode
│   ├── VideoDecoder.kt
│   └── AudioDecoder.kt
├── view/                    # Rendering
│   ├── ProjectionView.kt
│   ├── OverlayTouchView.kt
│   └── GlProjectionView.kt
└── utils/
    ├── AutomotiveUtils.kt   # AAOS detection
    └── Settings.kt
```

---

## 12. Optional Enhancements

- **Latency diagnostics**: Settings → Show Latency Diagnostics (FPS + frame gap overlay)
- **Auto-start on boot**: `BootCompleteReceiver` + `RECEIVE_BOOT_COMPLETED`
- **Wireless pairing**: QR/code flow via Wireless Helper app
