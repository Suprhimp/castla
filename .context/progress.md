# Jakarta Mirror - Progress Tracker

## Project Overview
Tesla 차량 브라우저로 Android 폰 화면을 스트리밍하는 미러링 앱 (Tesor 대안)

**Architecture:** Android MediaProjection → H.264 HW Encode → WebSocket → WebCodecs Decode → Canvas

---

## Phase Status

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Project Scaffold + Web Server | DONE |
| 2 | Screen Capture + H.264 Encoding + WS Streaming | DONE |
| 3 | WebCodecs Decoding + Canvas Rendering | DONE |
| 4 | Touch Relay | DONE |
| 5 | Shizuku Integration (Virtual Display) | DONE |
| 6 | Network + Hotspot | DONE (code written, not wired to UI fully) |
| 7 | Polish + Settings UI | NOT STARTED |

---

## Test Suite (42 tests, all passing)

| Suite | Tests | Coverage |
|-------|-------|----------|
| MirrorServerTokenTest | 8 | Token generation, uniqueness, broadcast, listeners |
| MirrorServerAuthTest | 8 | Auth enforcement, sub-resource bypass, MIME types |
| TouchInjectorTest | 10 | Coord scaling, action mapping, multi-touch, VD routing |
| TouchEventTest | 5 | Data class behavior, equality, copy |
| VirtualDisplayManagerTest | 5 | Create/release lifecycle, Shizuku fallback |
| ShizukuStateTest | 6 | Sealed class states, equality |

---

## Review Fixes Applied

### Round 1
| Issue | Fix | Status |
|-------|-----|--------|
| TouchInjector was log-only | Added Shizuku InputManager reflection + Accessibility fallback | DONE |
| FallbackDecoder was broken MSE stub | Replaced with working MJPEG decoder (createImageBitmap) | DONE |
| No auth — anyone on LAN could access | Added SecureRandom 32-char token, validated on HTTP + WS | DONE |
| IP not updating on network change | MainActivity now collects NetworkMonitor StateFlow | DONE |
| Global cleartext traffic allowed | Replaced with network_security_config.xml (private IPs only) | DONE |

### Round 2
| Issue | Fix | Status |
|-------|-----|--------|
| Static resources (JS/CSS) blocked by 403 | Sub-resources (.js/.css/images) skip token check; only HTML + WS require token | DONE |
| UnauthorizedSocket compile error | WebSocketFrame → NanoWSD.WebSocketFrame fully qualified | DONE |
| MJPEG fallback has no server-side support | Removed non-functional fallback; show clear error if WebCodecs unavailable | DONE |

### Round 3
| Issue | Fix | Status |
|-------|-----|--------|
| Token read via 500ms delay — timing dependent | Replaced with Service Binding (LocalBinder); token read via onServiceConnected | DONE |
| network_security_config IP domains don't match actual IPs | Simplified to base-config cleartext=true; access control via token auth | DONE |
| Activity recreation doesn't rebind to running service | Added onStart() bind with flags=0 (no auto-create) + onStop() unbind | DONE |

---

## Key Files

### Android (Kotlin)
```
app/src/main/java/com/jakarta/mirror/
├── JakartaApp.kt                          # Application class
├── MainActivity.kt                        # UI + permissions + NetworkMonitor
├── capture/
│   ├── ScreenCaptureManager.kt            # MediaProjection lifecycle
│   ├── VideoEncoder.kt                    # H.264 HW encoder (MediaCodec)
│   └── VirtualDisplayManager.kt           # Shizuku virtual display + input routing
├── server/
│   ├── MirrorServer.kt                    # NanoWSD HTTP+WS + token auth
│   ├── VideoStreamSocket.kt               # Binary WS for video frames
│   └── ControlSocket.kt                   # JSON WS for touch events
├── input/
│   ├── TouchInjector.kt                   # Shizuku InputManager + VD routing + Accessibility fallback
│   └── InputService.kt                    # AccessibilityService for gesture dispatch
├── network/
│   ├── NetworkMonitor.kt                  # ConnectivityManager + StateFlow
│   └── HotspotManager.kt                  # LocalOnlyHotspot API
├── shizuku/
│   ├── ShizukuSetup.kt                    # Shizuku permission management
│   └── PrivilegedService.kt               # Shizuku UserService (virtual display + input injection)
└── service/
    └── MirrorForegroundService.kt         # Pipeline orchestrator (foreground service)
```

### AIDL
```
app/src/main/aidl/com/jakarta/mirror/shizuku/
└── IPrivilegedService.aidl                # Shizuku privileged service interface
```

### Web Client (Tesla Browser - Chromium 109)
```
app/src/main/assets/web/
├── index.html
├── css/player.css
└── js/
    ├── main.js         # Orchestrator, WS connect, token passing
    ├── decoder.js      # WebCodecs H.264 VideoDecoder
    ├── renderer.js     # Canvas 2D renderer with aspect ratio
    ├── touch.js        # Touch capture + normalized coord relay
    └── fallback.js     # MJPEG fallback decoder
```

### Tests
```
app/src/test/java/com/jakarta/mirror/
├── capture/VirtualDisplayManagerTest.kt
├── input/TouchInjectorTest.kt
├── server/
│   ├── MirrorServerTokenTest.kt
│   ├── MirrorServerAuthTest.kt
│   └── TouchEventTest.kt
└── shizuku/ShizukuStateTest.kt
```

### Config
```
app/src/main/AndroidManifest.xml           # Permissions, services, Shizuku provider
app/src/main/res/xml/network_security_config.xml  # Cleartext traffic config
app/src/main/res/xml/accessibility_config.xml     # Accessibility service config
```

---

## Remaining Work

### Phase 7: Polish (Priority: MEDIUM)
- [ ] StatusScreen.kt — Compose UI with QR code for URL
- [ ] SettingsScreen.kt — Resolution/bitrate/FPS controls
- [ ] Server-side MJPEG encoding mode for fallback clients
- [ ] Audio streaming (AudioPlaybackCapture → Opus → Web Audio)

### Known Limitations
- Shizuku InputManager init uses reflection — may fail without Shizuku running
- Virtual display creation uses hidden DisplayManagerGlobal API — device-dependent
- MJPEG fallback requires server-side JPEG encoding (not yet implemented)
- No audio streaming yet
- DRM content cannot be mirrored (MediaProjection limitation)
- Token is logged in plaintext (should be removed for production)

---

## Tech Stack
- **Language:** Kotlin (Android), JavaScript (Web client)
- **Min SDK:** 26 (Android 8.0)
- **Server:** NanoHTTPD 2.3.1 (NanoWSD)
- **Streaming:** H.264 over WebSocket → WebCodecs VideoDecoder
- **Touch:** Shizuku InputManager / VirtualDisplay injection / AccessibilityService
- **Auth:** Per-session SecureRandom token
- **Testing:** JUnit 4 + MockK + Robolectric (42 tests)
- **Target Latency:** 17-43ms on LAN
