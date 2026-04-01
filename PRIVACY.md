# Castla Mirror Privacy Policy

## English

Castla Mirror is a **fully local, open-source** application. Your privacy is our top priority.

### What we collect

**Nothing.** Castla Mirror does not collect, store, or transmit any personal data, usage analytics, crash reports, or telemetry of any kind.

### How it works

- All communication happens **exclusively on your local network** (WiFi / hotspot) between your Android phone and Tesla's browser.
- Screen capture data is streamed directly from your phone to the Tesla browser over a local WebSocket connection. **No data leaves your local network.**
- No external servers are contacted during mirroring.

### Permissions

Castla requires certain Android permissions solely for its mirroring functionality:

| Permission | Purpose |
|---|---|
| Screen Capture (MediaProjection) | Mirror your phone screen |
| Audio Capture | Stream device audio (optional) |
| Network (WiFi, Hotspot) | Local communication with Tesla |
| Bluetooth | Auto-detect Tesla nearby |
| Shizuku | Touch input injection, virtual display |

### No third-party SDKs

Castla Mirror contains **no advertising SDKs, no analytics SDKs, no crash reporting tools, and no tracking code**. As an open-source project, anyone can verify this by reading the source code.

### Update checks

The app may check for new versions via a GitHub API call. This is a simple HTTP GET request that does not transmit any personal or device information.

---

## Korean

Castla Mirror는 **완전한 로컬 기반 오픈소스** 앱입니다. 사용자의 개인정보를 최우선으로 보호합니다.

### 수집하는 정보

**없습니다.** Castla Mirror는 어떠한 개인정보, 사용 통계, 크래시 리포트, 텔레메트리도 수집, 저장, 전송하지 않습니다.

### 작동 방식

- 모든 통신은 **로컬 네트워크(WiFi/핫스팟)** 에서 안드로이드 폰과 Tesla 브라우저 사이에서만 이루어집니다.
- 화면 캡처 데이터는 로컬 WebSocket 연결을 통해 폰에서 Tesla 브라우저로 직접 스트리밍됩니다. **데이터가 로컬 네트워크를 벗어나지 않습니다.**
- 미러링 중 외부 서버에 연결하지 않습니다.

### 권한

Castla는 미러링 기능을 위해서만 다음 권한을 사용합니다:

| 권한 | 목적 |
|---|---|
| 화면 캡처 (MediaProjection) | 폰 화면 미러링 |
| 오디오 캡처 | 디바이스 오디오 스트리밍 (선택) |
| 네트워크 (WiFi, 핫스팟) | Tesla와 로컬 통신 |
| 블루투스 | Tesla 자동 감지 |
| Shizuku | 터치 입력, 가상 디스플레이 |

### 서드파티 SDK 없음

Castla Mirror에는 **광고 SDK, 분석 SDK, 크래시 리포팅 도구, 추적 코드가 일절 포함되어 있지 않습니다**. 오픈소스 프로젝트이므로 누구나 소스 코드를 읽어 이를 확인할 수 있습니다.

### 업데이트 확인

앱이 GitHub API를 통해 새 버전을 확인할 수 있습니다. 이는 단순한 HTTP GET 요청이며 개인정보나 기기 정보를 전송하지 않습니다.
