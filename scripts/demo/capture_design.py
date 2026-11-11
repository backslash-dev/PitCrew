"""디자인 검수용으로 앱의 모든 화면·상태를 캡처한다.

사용: python3 scripts/demo/capture_design.py [출력 폴더] [driving,parked,dark 중 고를 묶음]
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import record as r

SERVER_DIR = Path(__file__).resolve().parents[2] / "server"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/Volumes/T7/pitcrew-captures/design-review")
folder = OUT / "light"
shots: list[str] = []


def shot(name: str) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    r.shell("screencap -p /sdcard/design.png")
    r.adb("pull", "/sdcard/design.png", str(folder / f"{name}.png"))
    shots.append(f"{folder.name}/{name}")
    print("shot", folder.name, name)


def nodes() -> list[tuple[str, int, int]]:
    r.shell("uiautomator dump /data/local/tmp/ui.xml")
    xml = r.shell("cat /data/local/tmp/ui.xml")
    found = []
    for node in re.findall(r"<node [^>]*>", xml):
        text = re.search(r'text="([^"]*)"', node)
        if text and text.group(1):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r'bounds="([^"]+)"', node).group(1)))
            found.append((text.group(1), (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def tap(text: str, partial: bool = False, wait: float = 2.0) -> bool:
    for label, x, y in nodes():
        if label == text or (partial and text in label):
            r.tap(x, y)
            time.sleep(wait)
            return True
    print("  (not found)", text)
    return False


def back(wait: float = 1.5) -> None:
    r.shell("input keyevent KEYCODE_BACK")
    time.sleep(wait)


def server(up: bool) -> None:
    subprocess.run(["pkill", "-f", "dist/server.js"], check=False)
    if up:
        subprocess.Popen(["node", "--env-file-if-exists=.env", "dist/server.js"], cwd=SERVER_DIR, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(20):
            try:
                r.api("GET", "/health")
                return
            except Exception:
                time.sleep(0.5)
        raise RuntimeError("server did not start")
    time.sleep(1)


def late_reservation() -> None:
    now = datetime.now(ZoneInfo("Asia/Seoul"))
    dinner = (now + timedelta(minutes=25)).replace(second=0, microsecond=0)
    dinner = dinner.replace(minute=dinner.minute // 10 * 10)
    r.api("PUT", f"/v1/reservations/{r.DINNER_ID}", {
        "placeName": "온담 한식당", "reservedAt": dinner.isoformat(timespec="seconds"), "partySize": 3, "companionIds": r.COMPANIONS,
    })


def fresh_app(route: bool) -> str:
    r.reset_demo_orders()
    late_reservation()
    r.vconsole("park")
    r.vconsole("battery", "72")
    r.open_navi()
    time.sleep(3)
    end = r.find_text("안내 종료")
    if end:
        r.tap(*end)
        time.sleep(2)
    r.shell(f"am force-stop --user 10 {r.PKG}")
    r.shell(f"run-as {r.PKG} --user 10 rm -f files/errands.json")
    r.open_app()
    time.sleep(7)
    r.debug("PERIODIC", "--ez", "on", "false")
    since = r.device_time()
    if route:
        r.debug("ROUTE", "--es", "name", "강남역", "--ef", "lat", "37.4979", "--ef", "lon", "127.0276")
        r.wait_for(r"onRouteStarted", 15, since)
        r.open_app()
        time.sleep(3)
    return since


def suggest(answer: str | None, since: str) -> str | None:
    asked = None
    for _ in range(4):
        r.debug("CHECK_NOW")
        asked = r.wait_for(r"ProactiveAssistant: asking: (.+)", 6, since)
        if asked:
            break
    if not asked:
        return None
    if answer is None:
        r.wait_for(r"ProactiveAssistant: answer: NONE", 25, since)
    else:
        time.sleep(max(3.0, len(asked.group(1)) / 7))
        r.debug("ANSWER", "--es", "text", answer)
        r.wait_for(r"ProactiveAssistant: answer: \w+", 15, since)
        time.sleep(4)
    return asked.group(1)


def resume_route() -> None:
    """에뮬레이터 내비는 경유지가 바뀌면 '안내 시작'을 다시 눌러야 경로가 잡힌다."""
    time.sleep(2)
    r.open_navi()
    time.sleep(3)
    start = r.find_text("안내 시작")
    if start:
        r.tap(*start)
        time.sleep(3)
    r.open_app()
    time.sleep(3)


def shot_on_log(pattern: str, since: str, name: str, timeout: float = 20) -> None:
    """화면 트리 덤프는 몇 초씩 걸려 짧은 상태(듣는 중)를 놓치므로 로그로 시점을 잡는다."""
    if not r.wait_for(pattern, timeout, since):
        print("  (no log)", pattern)
    time.sleep(1)
    shot(name)


def tap_rightmost(text: str, wait: float = 2.0) -> bool:
    """레일 메뉴와 같은 이름의 탭(예: 예약)이 있어 오른쪽 것을 누른다."""
    matches = [(x, y) for label, x, y in nodes() if label == text]
    if not matches:
        print("  (not found)", text)
        return False
    r.tap(*max(matches))
    time.sleep(wait)
    return True


def tap_time_button() -> bool:
    return tap("오전 ", partial=True) or tap("오후 ", partial=True)


def home() -> None:
    back(1)
    back(1)
    tap("홈")


def driving_set() -> None:
    since = fresh_app(route=True)
    r.vconsole("drive", "60")
    time.sleep(4)
    r.open_app()
    time.sleep(2)
    shot("D01-driving-route")
    r.debug("CHECK_NOW")
    asked = r.wait_for(r"ProactiveAssistant: asking: (.+)", 15, since)
    if asked:
        shot_on_log(r"SpeechToText: \[[^\]]*\]request", since, "D02-driving-asking-listening")
        r.wait_for(r"ProactiveAssistant: answer: NONE", 25, since)
        time.sleep(1)
        shot("D03-driving-awaiting-no-answer")
    r.vconsole("park")
    time.sleep(3)
    tap("거절")
    s2 = r.device_time()
    r.vconsole("drive", "60")
    time.sleep(3)
    if suggest("응", s2):
        resume_route()
        shot("D04-driving-done-undo-hint")
    r.vconsole("park")
    time.sleep(2)


def parked_set() -> None:
    fresh_app(route=False)
    shot("A01-home-no-route")
    if tap("말하기", wait=1.5):
        shot("A02-voice-listening")
        time.sleep(11)
        shot("A03-voice-not-heard")
        tap("닫기")

    since = fresh_app(route=True)
    shot("A04-home-route")
    suggest(None, since)
    shot("A05-home-suggestion-late")
    tap("늦어요", partial=True)
    shot("A06-detail-late-awaiting")
    tap("거절")
    shot("A07-detail-late-declined")
    home()

    s2 = r.device_time()
    if suggest(None, s2):
        tap("에서", partial=True)
        shot("A08-detail-pickup-awaiting")
        tap("승인", wait=5)
        resume_route()
        shot("A09-detail-pickup-completed")
        tap("되돌리기", wait=5)
        resume_route()
        shot("A10-detail-pickup-undone")
    home()

    r.vconsole("battery", "18")
    s3 = r.device_time()
    if suggest(None, s3):
        tap("충전하고", partial=True)
        shot("A11-detail-charge-awaiting")
        server(False)
        tap("승인", wait=6)
        shot("A12-detail-charge-failed")
        server(True)
    r.vconsole("battery", "72")
    home()
    shot("A13-home-recent")
    tap("기록")
    shot("A14-history")

    tap("예약")
    shot("A15-reservations")
    tap("일행")
    shot("A16-contacts")
    tap("김민수")
    shot("A17-contact-edit")
    back()
    tap("일행 추가")
    tap("저장")
    shot("A18-contact-new-error")
    back()
    tap_rightmost("예약")
    tap("예약 추가")
    shot("A19-reservation-new")
    tap("저장")
    shot("A20-reservation-new-error")
    back()
    tap("온담 한식당")
    shot("A21-reservation-edit")
    tap("일 (", partial=True)
    shot("A22-reservation-calendar")
    tap("닫기")
    tap_time_button()
    shot("A23-reservation-time")
    tap("취소")
    back()

    server(False)
    r.shell(f"am force-stop --user 10 {r.PKG}")
    r.open_app()
    time.sleep(7)
    tap("예약", wait=4)
    shot("A24-reservations-sync-error")
    server(True)

    tap("설정")
    shot("A25-settings")
    tap("차량 연결 진단", wait=3)
    shot("A26-diagnostics")
    back()
    tap("사용 안내 다시 보기")
    for page in range(1, 4):
        shot(f"A27-onboarding-{page}")
        if page < 3:
            tap("다음")
    tap("시작하기")


def dark_set() -> None:
    global folder
    folder = OUT / "dark"
    r.shell("cmd uimode night yes")
    time.sleep(2)
    since = fresh_app(route=True)
    shot("A04-home-route")
    suggest(None, since)
    shot("A05-home-suggestion-late")
    tap("늦어요", partial=True)
    shot("A06-detail-late-awaiting")
    tap("승인", wait=5)
    shot("A09-detail-late-completed")
    home()
    shot("A13-home-recent")
    tap("예약")
    tap("온담 한식당")
    shot("A21-reservation-edit")
    tap("일 (", partial=True)
    shot("A22-reservation-calendar")
    tap("닫기")
    tap_time_button()
    shot("A23-reservation-time")
    tap("취소")
    back()
    tap("설정")
    shot("A25-settings")
    r.vconsole("drive", "60")
    time.sleep(4)
    r.open_app()
    time.sleep(2)
    shot("D01-driving")
    r.vconsole("park")
    r.shell("cmd uimode night no")


if __name__ == "__main__":
    sets = sys.argv[2].split(",") if len(sys.argv) > 2 else ["driving", "parked", "dark"]
    r.adb("logcat", "-G", "16M")
    r.shell("settings put global auto_time_zone 0")
    r.shell("cmd alarm set-timezone Asia/Seoul")
    r.shell("cmd uimode night no")
    # 화면이 꺼지면 탭이 먹지 않고 검은 화면이 찍힌다
    r.shell("input keyevent KEYCODE_WAKEUP")
    r.shell("svc power stayon true")
    if "driving" in sets:
        driving_set()
    if "parked" in sets:
        parked_set()
    if "dark" in sets:
        dark_set()
    r.vconsole("park")
    print(f"{len(shots)} shots in {OUT}")
