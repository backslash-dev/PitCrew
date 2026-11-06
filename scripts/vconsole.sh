#!/usr/bin/env bash
set -euo pipefail

BASE="${PITCREW_CONSOLE_URL:-http://127.0.0.1:8088}"
CLIENT="pitcrew-cli"

usage() {
  cat >&2 <<USAGE
usage: $0 <command>
  park                  P단 정차
  drive <kph>           D단으로 kph 속도 주행
  gear <Park|Drive|Reverse|Neutral>
  battery <pct>         배터리 잔량(SOC) %
  state                 현재 차량 상태(JSON)
  hold                  콘솔 세션 유지(Ctrl+C로 종료)
USAGE
}

# 콘솔은 1초 간격 heartbeat가 끊기면 503(emulator_inactive)을 돌려준다
heartbeat() {
  curl -sf -X POST "$BASE/browser/session?client=$CLIENT" >/dev/null
}

put() {
  heartbeat
  curl -sf -X PUT "$BASE$1" >/dev/null || { echo "request failed: PUT $1" >&2; exit 1; }
}

controls() {
  put "/vehicle/controls?speed=$1&accelerator=0&brake=0&steering=0"
}

case "${1:-}" in
  park)
    controls 0
    put "/vehicle/gear?gear=Park"
    ;;
  drive)
    [[ "${2:-}" =~ ^[0-9]+$ ]] || { usage; exit 2; }
    put "/vehicle/gear?gear=Drive"
    controls "$2"
    ;;
  gear)
    [[ "${2:-}" =~ ^(Park|Drive|Reverse|Neutral)$ ]] || { usage; exit 2; }
    put "/vehicle/gear?gear=$2"
    ;;
  battery)
    [[ "${2:-}" =~ ^[0-9]+$ ]] || { usage; exit 2; }
    put "/vehicle/ev-battery?field=soc&value=$2"
    ;;
  state)
    heartbeat
    curl -sf "$BASE/vehicle/state"
    echo
    ;;
  hold)
    trap 'curl -sf -X POST "$BASE/browser/session?client=$CLIENT&closing=1" >/dev/null || true; exit 0' INT TERM
    while true; do heartbeat || echo "heartbeat failed" >&2; sleep 1; done
    ;;
  *) usage; exit 2 ;;
esac
