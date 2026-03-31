#!/usr/bin/env bash
#
# Play Store 스크린샷 자동화 스크립트
# 사용법: ./scripts/take_screenshots.sh [--skip-emulator-create] [--locale en]
#
# Play Store 스크린샷 규격:
#   Phone:        1080x2400
#   7-inch Tab:   1200x1920
#   10-inch Tab:  1600x2560
#

set -eo pipefail

# ─── Configuration ──────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/screenshots"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"

PACKAGE="com.castla.mirror"
MAIN_ACTIVITY="com.castla.mirror.MainActivity"
DESKTOP_ACTIVITY="com.castla.mirror.ui.DesktopActivity"

SYS_IMAGE_DIR="system-images/android-36.1/google_apis_playstore/arm64-v8a/"
AVD_DIR="$HOME/.android/avd"

LOCALES="en ko zh-CN de no fr nl ja es"
SCREENS="main streaming desktop"

SKIP_CREATE=false
FILTER_LOCALE=""

# ─── Lookup functions ───────────────────────────────────────────

get_lang() {
  case "$1" in
    en) echo "en" ;; ko) echo "ko" ;; zh-CN) echo "zh" ;; de) echo "de" ;;
    no) echo "nb" ;; fr) echo "fr" ;; nl) echo "nl" ;; ja) echo "ja" ;; es) echo "es" ;;
    *) echo "$1" ;;
  esac
}

get_country() {
  case "$1" in
    en) echo "US" ;; ko) echo "KR" ;; zh-CN) echo "CN" ;; de) echo "DE" ;;
    no) echo "NO" ;; fr) echo "FR" ;; nl) echo "NL" ;; ja) echo "JP" ;; es) echo "ES" ;;
    *) echo "US" ;;
  esac
}

# device -> "width height density category avd_name port"
get_device_info() {
  case "$1" in
    phone)  echo "1080 2400 420 phone castla_phone 5554" ;;
    tab7)   echo "1200 1920 213 7-inch-tablet castla_tab7 5556" ;;
    tab10)  echo "1600 2560 320 10-inch-tablet castla_tab10 5558" ;;
  esac
}

DEVICE_LIST="phone tab7 tab10"

# ─── Parse args ─────────────────────────────────────────────────

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-emulator-create) SKIP_CREATE=true; shift ;;
    --locale) FILTER_LOCALE="$2"; shift 2 ;;
    --output) OUTPUT_DIR="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--skip-emulator-create] [--locale <code>] [--output <dir>]"
      exit 0 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ─── Helper functions ───────────────────────────────────────────

log() { echo "▸ $*" >&2; }
warn() { echo "⚠ $*" >&2; }
die() { echo "✗ $*" >&2; exit 1; }

wait_for_boot() {
  local serial="$1"
  local timeout=240
  log "Waiting for $serial to boot (timeout ${timeout}s)..."
  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    local status
    status=$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n') || true
    if [ "$status" = "1" ]; then
      log "$serial booted. Waiting for system to settle..."
      # Wait for launcher to fully load and system to stabilize
      sleep 15
      # Dismiss any initial setup dialogs / ANR
      dismiss_dialogs "$serial"
      sleep 5
      return 0
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  die "Timeout waiting for $serial to boot"
}

# Dismiss ANR dialogs and other system popups
dismiss_dialogs() {
  local serial="$1"
  # Close all system dialogs (ANR, crash, permission, etc.) via broadcast only
  # Do NOT use KEYCODE_ENTER — it can accidentally tap UI elements in the app
  "$ADB" -s "$serial" shell "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS" &>/dev/null || true
  sleep 1
  "$ADB" -s "$serial" shell "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS" &>/dev/null || true
}

# AVD를 파일 시스템에 직접 생성 (avdmanager 우회)
create_avd() {
  local avd_name="$1" width="$2" height="$3" density="$4"
  local avd_folder="$AVD_DIR/${avd_name}.avd"
  local avd_ini="$AVD_DIR/${avd_name}.ini"

  # Always recreate to apply updated config (RAM, GPU, etc.)
  if [ -d "$avd_folder" ]; then
    log "Removing old AVD '$avd_name' to apply updated config..."
    rm -rf "$avd_folder"
    rm -f "$avd_ini"
  fi

  log "Creating AVD: $avd_name (${width}x${height} @ ${density}dpi)"

  mkdir -p "$avd_folder"

  # Top-level .ini (pointer file)
  cat > "$avd_ini" <<TOPINI
avd.ini.encoding=UTF-8
path=${avd_folder}
path.rel=avd/${avd_name}.avd
target=android-36.1
TOPINI

  # Main config.ini
  cat > "$avd_folder/config.ini" <<CFG
AvdId=${avd_name}
PlayStore.enabled=true
abi.type=arm64-v8a
avd.ini.displayname=${avd_name}
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.chosenSnapshotFile=
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=virtualscene
hw.camera.front=emulated
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=Generic
hw.device.name=medium_phone
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density=${density}
hw.lcd.height=${height}
hw.lcd.width=${width}
hw.mainKeys=no
hw.ramSize=4096
hw.sdCard=yes
hw.sensors.light=yes
hw.sensors.magnetic_field=yes
hw.sensors.orientation=yes
hw.sensors.pressure=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=${SYS_IMAGE_DIR}
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=no
skin.dynamic=yes
skin.name=${width}x${height}
skin.path=${width}x${height}
tag.display=Google Play
tag.displaynames=Google Play
tag.id=google_apis_playstore
tag.ids=google_apis_playstore
target=android-36.1
vm.heapSize=512
CFG

  log "AVD '$avd_name' created at $avd_folder"
}

start_emulator() {
  local avd_name="$1" port="$2"
  local serial="emulator-${port}"

  if "$ADB" devices 2>/dev/null | grep -q "$serial"; then
    log "Emulator $serial already running."
    echo "$serial"
    return 0
  fi

  log "Starting emulator: $avd_name on port $port"
  "$EMULATOR" -avd "$avd_name" \
    -port "$port" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu host \
    -no-snapshot \
    &>/dev/null &

  wait_for_boot "$serial"
  echo "$serial"
}

kill_emulator() {
  local serial="$1"
  log "Killing emulator $serial"
  "$ADB" -s "$serial" emu kill 2>/dev/null || true
  sleep 3
}

change_locale() {
  local serial="$1" locale="$2"
  local lang country
  lang=$(get_lang "$locale")
  country=$(get_country "$locale")

  log "Changing locale on $serial -> ${lang}-${country}"

  "$ADB" -s "$serial" shell "setprop persist.sys.locale ${lang}-${country}" 2>/dev/null || true
  "$ADB" -s "$serial" shell "setprop persist.sys.language ${lang}" 2>/dev/null || true
  "$ADB" -s "$serial" shell "setprop persist.sys.country ${country}" 2>/dev/null || true
  "$ADB" -s "$serial" shell "settings put system system_locales ${lang}-${country}" 2>/dev/null || true
  "$ADB" -s "$serial" shell "am broadcast -a com.android.intent.action.LOCALE_CHANGED" 2>/dev/null || true
  "$ADB" -s "$serial" shell "cmd locale set-app-locales $PACKAGE --locales ${lang}-${country}" 2>/dev/null || true

  sleep 3
}

install_apk() {
  local serial="$1"
  local apk="$PROJECT_DIR/app/build/outputs/apk/playstore/release/app-playstore-release.apk"

  if [ ! -f "$apk" ]; then
    apk="$PROJECT_DIR/app/build/outputs/apk/playstore/debug/app-playstore-debug.apk"
  fi
  if [ ! -f "$apk" ]; then
    warn "No APK found. Building debug APK first..."
    (cd "$PROJECT_DIR" && ./gradlew assemblePlaystoreDebug)
    apk="$PROJECT_DIR/app/build/outputs/apk/playstore/debug/app-playstore-debug.apk"
  fi
  if [ ! -f "$apk" ]; then
    die "APK not found at $apk"
  fi

  log "Installing APK on $serial"
  "$ADB" -s "$serial" install -r -t "$apk"
}

grant_permissions() {
  local serial="$1"
  log "Granting permissions on $serial"
  for perm in \
    "android.permission.BLUETOOTH_CONNECT" \
    "android.permission.BLUETOOTH_SCAN" \
    "android.permission.POST_NOTIFICATIONS" \
    "android.permission.RECORD_AUDIO" \
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" \
    "android.permission.SYSTEM_ALERT_WINDOW"; do
    "$ADB" -s "$serial" shell "pm grant $PACKAGE $perm" 2>/dev/null || true
  done

  # Disable "Viewing full screen" immersive mode confirmation dialog
  "$ADB" -s "$serial" shell "settings put secure immersive_mode_confirmations confirmed" 2>/dev/null || true

  # Disable ANR dialogs globally
  "$ADB" -s "$serial" shell "settings put global always_finish_activities 0" 2>/dev/null || true
  "$ADB" -s "$serial" shell "settings put global anr_show_background 0" 2>/dev/null || true

  # Hide status bar notifications that may clutter screenshots
  "$ADB" -s "$serial" shell "cmd statusbar collapse" 2>/dev/null || true
}

launch_screen() {
  local serial="$1" screen="$2"

  # Dismiss any lingering dialogs before launching
  dismiss_dialogs "$serial"

  case "$screen" in
    main)
      log "Launching main screen (portrait)"
      # Ensure portrait orientation
      "$ADB" -s "$serial" shell "settings put system accelerometer_rotation 0" 2>/dev/null || true
      "$ADB" -s "$serial" shell "settings put system user_rotation 0" 2>/dev/null || true
      sleep 1
      "$ADB" -s "$serial" shell "am start -n ${PACKAGE}/${MAIN_ACTIVITY}" 2>/dev/null
      ;;
    settings)
      log "Launching settings screen"
      "$ADB" -s "$serial" shell "am start -n ${PACKAGE}/${MAIN_ACTIVITY} --ez open_settings true" 2>/dev/null
      ;;
    streaming)
      log "Launching streaming demo screen (portrait)"
      "$ADB" -s "$serial" shell "settings put system accelerometer_rotation 0" 2>/dev/null || true
      "$ADB" -s "$serial" shell "settings put system user_rotation 0" 2>/dev/null || true
      sleep 1
      "$ADB" -s "$serial" shell "am start -n ${PACKAGE}/${MAIN_ACTIVITY} --ez demo_streaming true" 2>/dev/null
      ;;
    desktop)
      log "Launching desktop/app launcher screen (landscape)"
      # Rotate to landscape for Tesla browser view
      "$ADB" -s "$serial" shell "settings put system accelerometer_rotation 0" 2>/dev/null || true
      "$ADB" -s "$serial" shell "settings put system user_rotation 1" 2>/dev/null || true
      sleep 2
      "$ADB" -s "$serial" shell "am start -n ${PACKAGE}/${DESKTOP_ACTIVITY}" 2>/dev/null
      ;;
  esac

  # Wait for activity to render (10s to avoid splash screen capture)
  sleep 10
}

take_screenshot() {
  local serial="$1" output_path="$2"
  mkdir -p "$(dirname "$output_path")"

  # Final dialog dismissal right before capture
  "$ADB" -s "$serial" shell "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS" 2>/dev/null || true
  sleep 1

  "$ADB" -s "$serial" exec-out screencap -p > "$output_path"
  log "Screenshot saved: $output_path"
}

# ─── Main ───────────────────────────────────────────────────────

log "=== Castla Play Store Screenshot Automation ==="
log "Output directory: $OUTPUT_DIR"

# Verify system image exists
if [ ! -d "$SDK/$SYS_IMAGE_DIR" ]; then
  die "System image not found at $SDK/$SYS_IMAGE_DIR — install via Android Studio SDK Manager."
fi

# Filter locales
if [ -n "$FILTER_LOCALE" ]; then
  LOCALES="$FILTER_LOCALE"
fi

# Kill any existing emulators to start fresh
log "Cleaning up any existing emulators..."
running_emus=$("$ADB" devices 2>/dev/null | grep "emulator-" | awk '{print $1}') || true
if [ -n "$running_emus" ]; then
  echo "$running_emus" | while read -r emu; do
    "$ADB" -s "$emu" emu kill 2>/dev/null || true
  done
  sleep 3
fi

# Step 1: Create AVDs
if [ "$SKIP_CREATE" = false ]; then
  for dev in $DEVICE_LIST; do
    info=$(get_device_info "$dev")
    w=$(echo "$info" | awk '{print $1}')
    h=$(echo "$info" | awk '{print $2}')
    d=$(echo "$info" | awk '{print $3}')
    avd=$(echo "$info" | awk '{print $5}')
    create_avd "$avd" "$w" "$h" "$d"
  done
fi

# Step 2: Process each device
for dev in $DEVICE_LIST; do
  info=$(get_device_info "$dev")
  w=$(echo "$info" | awk '{print $1}')
  h=$(echo "$info" | awk '{print $2}')
  d=$(echo "$info" | awk '{print $3}')
  cat_name=$(echo "$info" | awk '{print $4}')
  avd=$(echo "$info" | awk '{print $5}')
  port=$(echo "$info" | awk '{print $6}')

  log ""
  log "========================================="
  log "Device: $dev ($cat_name) — ${w}x${h} @${d}dpi"
  log "========================================="

  local_serial=$(start_emulator "$avd" "$port")
  install_apk "$local_serial"
  grant_permissions "$local_serial"

  for locale in $LOCALES; do
    log ""
    log "--- Locale: $locale ---"
    change_locale "$local_serial" "$locale"

    "$ADB" -s "$local_serial" shell "am force-stop $PACKAGE" 2>/dev/null || true
    sleep 2

    # Determine which screens to capture per device type
    device_screens="$SCREENS"
    if [ "$dev" = "phone" ]; then
      device_screens="main streaming"  # Phone: main + streaming (portrait)
    fi

    for screen in $device_screens; do
      launch_screen "$local_serial" "$screen"

      output_file="$OUTPUT_DIR/${cat_name}/${locale}/${screen}.png"
      take_screenshot "$local_serial" "$output_file"

      "$ADB" -s "$local_serial" shell "am force-stop $PACKAGE" 2>/dev/null || true
      sleep 1
    done
  done

  kill_emulator "$local_serial"
  sleep 3
done

# ─── Summary ────────────────────────────────────────────────────

log ""
log "=== Screenshot generation complete ==="
log ""
log "Output structure:"
log "  $OUTPUT_DIR/"
log "  ├── phone/<locale>/main.png, desktop.png"
log "  ├── 7-inch-tablet/<locale>/..."
log "  └── 10-inch-tablet/<locale>/..."
log ""

total=$(find "$OUTPUT_DIR" -name "*.png" 2>/dev/null | wc -l | tr -d ' ')
locale_count=0
for _ in $LOCALES; do locale_count=$((locale_count + 1)); done
screen_count=0
for _ in $SCREENS; do screen_count=$((screen_count + 1)); done
log "Total screenshots: $total"
log "Expected: $((locale_count * screen_count * 3)) (${locale_count} locales × ${screen_count} screens × 3 devices)"
