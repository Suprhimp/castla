<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla App Icon">
  <h1 align="center">Castla</h1>
  <p align="center">
    <strong>테슬라를 위한 궁극의 Android Auto 대안. 네이버지도, T맵을 테슬라 브라우저에서 바로.</strong>
  </p>
  <p align="center">
    <a href="https://github.com/Suprhimp/castla/releases/latest"><img src="https://img.shields.io/github/v/release/Suprhimp/castla?style=flat-square" alt="Latest Release"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
    <a href="https://ko-fi.com/suprhimp"><img src="https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
  </p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.es.md">Español</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

<p align="center">
  <img src="docs/images/hero.jpg" width="700" alt="Castla - 안드로이드 화면을 테슬라에 미러링">
</p>

---

## Castla란?

테슬라에 **Android Auto**가 없어서 답답하셨나요? **네이버지도**, **T맵**을 큰 화면에서 쓰고 싶으셨나요?

Castla는 안드로이드 폰의 화면을 로컬 WiFi 네트워크를 통해 테슬라 내장 브라우저에 직접 스트리밍하는 무료 오픈소스 솔루션입니다. 인터넷 연결, 비싼 동글, 클라우드 서버, 구독 없이 — 폰과 차량 사이에서 빠르고 안전하게 동작합니다.

**주요 특징:**

- **Android Auto 경험** — 좋아하는 내비게이션과 음악 앱을 테슬라 화면에서 사용
- **실시간 미러링** — H.264 하드웨어 인코딩 + WebSocket 스트리밍으로 초저지연
- **완전한 터치 컨트롤** — 테슬라 화면에서 직접 터치, 스와이프, 조작 (Shizuku 사용)
- **오디오 스트리밍** — 기기 오디오를 테슬라 스피커로 직접 전송 (Android 10+)
- **100% 로컬 & 프라이빗** — 모든 데이터는 WiFi/핫스팟 내에서만. 인터넷 전송 제로.
- **완전 무료** — 광고 없음, 결제벽 없음. Apache-2.0 오픈소스.

## 기능

| 기능 | 상세 |
|------|------|
| **큰 화면 내비게이션** | **네이버지도**, **T맵** 등 최대 1080p @ 60fps로 부드럽게 실행 |
| **터치 입력** | Shizuku를 통한 완전한 터치 주입. 차량 화면에서 폰 조작 |
| **분할 화면** | 듀얼 패널 멀티태스킹. 왼쪽에 네이버지도, 오른쪽에 YouTube! |
| **가상 디스플레이** | 폰 화면을 켜두지 않아도 테슬라에서 독립적으로 앱 실행 |
| **오디오** | 시스템 오디오 캡처 (Android 10+, 실험적) |
| **테슬라 자동 감지** | BLE + 핫스팟 클라이언트 감지로 자동 연결 |
| **자동 핫스팟** | 미러링 시작/중지 시 핫스팟 자동 온/오프 |
| **OTT 브라우저** | DRM 콘텐츠용 내장 브라우저 (YouTube, Netflix 등) |
| **발열 보호** | 기기 과열 시 배터리 보호를 위해 자동 화질 조절 |
| **9개 언어** | EN, KO, DE, ES, FR, JA, NL, NO, ZH |

## 요구 사항

- Android 8.0+ (API 26)
- 터치 컨트롤 및 고급 기능을 위한 [Shizuku](https://shizuku.rikka.app/)
- 웹 브라우저가 있는 테슬라 차량
- 폰과 테슬라가 같은 WiFi 네트워크 (또는 폰 핫스팟)

## 설치

### APK 다운로드

1. [Releases](https://github.com/Suprhimp/castla/releases/latest)로 이동
2. 최신 `.apk` 파일 다운로드
3. 안드로이드 기기에 설치

### 소스에서 빌드

```bash
git clone https://github.com/Suprhimp/castla.git
cd castla
./gradlew assembleDebug
```

## 빠른 시작

1. **Shizuku 설치** — Castla를 열고 "Shizuku 설치"를 탭
2. **Shizuku 시작** — 개발자 옵션 → 무선 디버깅 → Shizuku 열기 → "무선 디버깅으로 시작"
3. **권한 부여** — Shizuku 사용 권한 허용
4. **연결** — 폰과 테슬라가 같은 WiFi에 연결 (또는 폰 핫스팟 사용)
5. **미러링 시작** — Castla에서 "미러링 시작" 탭
6. **테슬라에서 열기** — 표시된 URL을 테슬라 브라우저에 입력

## 기여

기여를 환영합니다! 자세한 내용은 [기여 가이드](CONTRIBUTING.md)를 참고하세요.

## 개인정보

Castla는 **어떠한 데이터도 수집하지 않습니다**. 분석, 크래시 리포트, 텔레메트리 없음. 자세한 내용은 [개인정보 처리방침](PRIVACY.md)을 확인하세요.

## 인디 개발자 후원하기

테슬라에 Android Auto가 없어서 답답했던 마음에 Castla를 만들었습니다. 모든 분이 더 나은 드라이빙 경험을 즐길 수 있도록 100% 무료 오픈소스로 공개합니다.

Castla 덕분에 드라이브가 즐거워지셨다면 커피 한 잔 사주세요! 여러분의 후원이 개발 비용을 충당하고 꾸준한 업데이트를 가능하게 합니다.

<a href="https://qr.kakaopay.com/Ej8mYEElE"><img src="https://img.shields.io/badge/카카오페이-후원하기-FFEB00?style=for-the-badge&logo=kakaotalk&logoColor=black" alt="카카오페이 후원"></a>

<a href="https://ko-fi.com/suprhimp"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Ko-fi에서 후원"></a>

## 사용된 오픈소스

Castla는 훌륭한 오픈소스 프로젝트들의 도움으로 만들어졌습니다:

- [Shizuku](https://shizuku.rikka.app/) — 루트 없이 특권 API 접근
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) — 내장 HTTP + WebSocket 서버
- [ZXing](https://github.com/zxing/zxing) — QR 코드 생성
- [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) — 현대적 Android UI 툴킷
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) — 비동기 스트리밍 파이프라인

일부 특권 모드 기법은 [scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0)의 접근 방식에서 영감을 받았으며, 구체적으로 다음과 같습니다:

- 화면 꺼짐 미러링을 위한 `SurfaceControl` 기반 디스플레이 패널 전원 제어
- shell UID 서비스에서 시스템 오디오를 캡처할 때 `AttributionSource`를 스푸핑하기 위한 `fillAppInfo` / `FakeContext` 패턴

scrcpy의 소스 코드는 포함되어 있지 않으며, Castla는 자체 아키텍처에 맞게 위 접근 방식을 재구현했습니다.

전체 서드파티 고지는 [NOTICE](NOTICE) 파일을 참조하세요.

## 라이선스

[Apache License 2.0](LICENSE)
