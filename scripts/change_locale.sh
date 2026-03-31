#!/usr/bin/env bash
#
# 에뮬레이터 로케일 변경 헬퍼
# 사용법: ./change_locale.sh <serial> <locale>
# 예시:   ./change_locale.sh emulator-5554 ko
#
# 이 스크립트는 ADB 앱 기반 로케일 변경을 사용합니다.
# ADB shell만으로는 로케일 변경이 불완전할 수 있어서
# adb-change-locale APK를 사용하는 방식도 지원합니다.
#

set -euo pipefail

SERIAL="${1:?Usage: $0 <serial> <locale>}"
LOCALE="${2:?Usage: $0 <serial> <locale>}"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"

# Locale mappings
declare -A LANG_MAP=(
  [en]="en-US" [ko]="ko-KR" [zh-CN]="zh-CN" [de]="de-DE"
  [no]="nb-NO" [fr]="fr-FR" [nl]="nl-NL" [ja]="ja-JP" [es]="es-ES"
)

BCP47="${LANG_MAP[$LOCALE]:-$LOCALE}"

echo "Changing locale on $SERIAL to $BCP47"

# Method 1: persist props (requires root or userdebug build)
"$ADB" -s "$SERIAL" shell "setprop persist.sys.locale $BCP47" 2>/dev/null || true

# Method 2: settings provider
"$ADB" -s "$SERIAL" shell "settings put system system_locales $BCP47" 2>/dev/null || true

# Method 3: activity manager configuration change (API 34+)
"$ADB" -s "$SERIAL" shell "cmd locale set-app-locales com.castla.mirror --locales $BCP47" 2>/dev/null || true

# Method 4: Reboot for guaranteed locale change (optional, slower)
# Uncomment if the above methods are not sufficient:
# "$ADB" -s "$SERIAL" reboot
# sleep 30

echo "Locale change requested. App may need restart to reflect."
