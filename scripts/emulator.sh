#!/usr/bin/env bash
set -euo pipefail

AVD="${PITCREW_AVD:-Pleos_Connect_2_1}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
LOG_DIR="${PITCREW_EMU_LOG_DIR:-/Volumes/T7/android-sdk-extra/logs}"
BOOT_TIMEOUT="${PITCREW_BOOT_TIMEOUT:-300}"
ADB="$SDK/platform-tools/adb"
CONSOLE_PORT=8088

usage() {
  echo "usage: $0 start [--window] | stop | status" >&2
}

running() {
  "$ADB" devices | grep -q '^emulator-'
}

start() {
  if running; then
    echo "emulator already running"
    return
  fi
  local window_flag="-no-window"
  [[ "${1:-}" == "--window" ]] && window_flag=""
  mkdir -p "$LOG_DIR"
  # Pleos 이미지는 스냅샷 복원 시 차량 서비스가 붙지 않아 cold boot가 필요하다
  nohup "$SDK/emulator/emulator" -avd "$AVD" $window_flag -no-snapshot -no-boot-anim -gpu host \
    >"$LOG_DIR/emulator.log" 2>&1 &
  "$ADB" wait-for-device
  local waited=0
  until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( waited >= BOOT_TIMEOUT )); then
      echo "boot timeout (${BOOT_TIMEOUT}s), see $LOG_DIR/emulator.log" >&2
      exit 1
    fi
    sleep 2
    waited=$((waited + 2))
  done
  "$ADB" forward tcp:$CONSOLE_PORT tcp:$CONSOLE_PORT >/dev/null
  echo "booted (${waited}s), virtual console on http://127.0.0.1:$CONSOLE_PORT"
}

stop() {
  "$ADB" forward --remove tcp:$CONSOLE_PORT 2>/dev/null || true
  if running; then
    "$ADB" emu kill
  else
    echo "emulator not running"
  fi
}

case "${1:-}" in
  start) start "${2:-}" ;;
  stop) stop ;;
  status) "$ADB" devices ;;
  *) usage; exit 2 ;;
esac
