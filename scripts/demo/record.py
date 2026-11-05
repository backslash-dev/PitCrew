"""PitCrew 시연 영상을 장면별로 녹화한다.

에뮬레이터·로컬 서버·Gleo를 실제로 구동한다. 음성은 Gleo 테스트 입력(ai.umos.intent.action.ASR)으로 넣고,
Gleo가 실제로 한 말은 로그에서 뽑아 자막 데이터(scenes.json)로 남긴다. Gleo 반응이 기대와 다르면 그 장면만 다시 찍는다.

사용: python3 scripts/demo/record.py [출력 폴더]
전제: 에뮬레이터 부팅, 서버 실행(127.0.0.1:3000), debug APK 설치.
"""
from __future__ import annotations

import json
import math
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
SERVER = "http://127.0.0.1:3000"
CONSOLE = Path(__file__).resolve().parents[1] / "vconsole.sh"
PKG = "ai.pitcrew.app"
DINNER_ID = "8a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c01"
COMPANIONS = ["5f3c1b7e-2a4d-4c1e-9b8a-1d2e3f4a5b01", "5f3c1b7e-2a4d-4c1e-9b8a-1d2e3f4a5b02"]
MAX_TRIES = 3
NAVI = "ai.umos.maps.android.navigation.app/.MainActivity"


def adb(*args: str, check: bool = True) -> str:
    # 로그에 잘린 멀티바이트 문자가 섞여 있어 깨진 바이트는 대체 문자로 읽는다
    result = subprocess.run([ADB, *args], capture_output=True, encoding="utf-8", errors="replace", timeout=120)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def shell(command: str) -> str:
    return adb("shell", command)


def logcat(since: str | None = None) -> str:
    return adb("logcat", "-d", *(["-T", since] if since else []))


def device_time() -> str:
    return shell("date +'%m-%d %H:%M:%S.000'").strip()


def wait_for(pattern: str, timeout: float, since: str) -> re.Match[str] | None:
    """since(기기 시각) 이후 로그에서 pattern이 나올 때까지 기다린다."""
    deadline = time.time() + timeout
    regex = re.compile(pattern)
    while time.time() < deadline:
        match = regex.search(logcat(since))
        if match:
            return match
        time.sleep(1.0)
    return None


def asr(text: str) -> None:
    shell(f"am broadcast -a ai.umos.intent.action.ASR --es ASR '{text}'")


def vconsole(*args: str) -> None:
    subprocess.run(["bash", str(CONSOLE), *args], check=True, capture_output=True)


def tap(x: int, y: int) -> None:
    shell(f"input tap {x} {y}")


def find_text(text: str) -> tuple[int, int] | None:
    shell("uiautomator dump /data/local/tmp/ui.xml")
    xml = shell("cat /data/local/tmp/ui.xml")
    for node in re.findall(r"<node [^>]*>", xml):
        if f'text="{text}"' in node:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r'bounds="([^"]+)"', node).group(1)))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def open_app() -> None:
    shell(f"am start --user 10 -n {PKG}/.MainActivity")


def open_navi() -> None:
    shell(f"am start --user 10 -n {NAVI}")


def gleo_lines(log: str) -> list[str]:
    """Gleo가 말한 문장들. 스트리밍 응답은 조각을 이어 붙이고, 고정 응답은 textToSpeech를 쓴다."""
    lines: list[str] = []
    current = ""
    for line in log.splitlines():
        delta = re.search(r"StreamResponseData\(delta=([^,]*), isFirstDelta", line)
        if delta:
            # 응답마다 첫 TTS 조각 표시가 붙으므로 그 지점에서 앞 응답을 끊는다
            if "isFirstTtsChunk=true" in line and current.strip():
                lines.append(current.strip())
                current = ""
            current += delta.group(1)
            continue
        fixed = re.search(r'textToSpeech\\": \\"([^"\\]+)\\"', line)
        if fixed and fixed.group(1) != "##STREAMING##":
            lines.append(fixed.group(1))
    if current.strip():
        lines.append(current.strip())
    return [l for l in dict.fromkeys(lines) if l]


def api(method: str, path: str, body: dict | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(f"{SERVER}{path}", data=data, method=method, headers={"content-type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        text = response.read().decode()
        return json.loads(text) if text else {}


class Recorder:
    def __init__(self, out: Path):
        self.out = out
        self.process: subprocess.Popen | None = None
        self.started = 0.0
        self.events: list[dict] = []

    def start(self, name: str) -> None:
        self.name = name
        self.events = []
        shell("rm -f /sdcard/scene.mp4")
        self.process = subprocess.Popen([ADB, "shell", "screenrecord", "--size", "1920x1080", "--bit-rate", "6000000", "/sdcard/scene.mp4"])
        time.sleep(1.0)
        self.started = time.time()

    def mark(self, kind: str, text: str) -> None:
        self.events.append({"t": round(time.time() - self.started, 2), "kind": kind, "text": text})

    def stop(self) -> Path:
        shell("pkill -INT screenrecord")
        assert self.process is not None
        self.process.wait(timeout=20)
        time.sleep(1.5)
        target = self.out / f"{self.name}.mp4"
        adb("pull", "/sdcard/scene.mp4", str(target))
        return target


def scene(rec: Recorder, scenes: list[dict], name: str, title: str, body) -> None:
    """body가 True를 돌려줄 때까지 MAX_TRIES번 찍는다."""
    for attempt in range(1, MAX_TRIES + 1):
        marker = f"PITCREW_DEMO_{name}_{attempt}_{int(time.time())}"
        shell(f"log -t PitCrewDemo {marker}")
        since = device_time()
        rec.start(name)
        ok = body(rec, since)
        path = rec.stop()
        if ok:
            scenes.append({"name": name, "title": title, "video": path.name, "events": rec.events})
            print(f"[ok] {name} (try {attempt})")
            return
        print(f"[retry] {name} (try {attempt})")
        time.sleep(3)
    raise RuntimeError(f"scene {name} failed {MAX_TRIES} times")


def spoken_time(t: datetime) -> str:
    hour = t.hour % 12 or 12
    if t.minute == 0:
        return f"{hour}시"
    if t.minute == 30:
        return f"{hour}시 반"
    return f"{hour}시 {t.minute}분"


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/Volumes/T7/pitcrew-captures/demo") / datetime.now().strftime("%Y%m%d-%H%M")
    out.mkdir(parents=True, exist_ok=True)
    rec = Recorder(out)
    scenes: list[dict] = []

    # 시연 데이터: ETA(약 45분)보다 이른 10분 단위 예약을 잡아 15분 안팎 지각이 나게 한다
    # 앱은 기기 시간대로 시각을 해석하므로 예약·발화 시각도 기기 시간대로 계산한다
    zone = ZoneInfo(shell("getprop persist.sys.timezone").strip() or "UTC")
    now = datetime.now(zone)
    dinner = (now + timedelta(minutes=30)).replace(second=0, microsecond=0)
    dinner = dinner.replace(minute=0) + timedelta(minutes=math.ceil(dinner.minute / 10) * 10)
    api("PUT", f"/v1/reservations/{DINNER_ID}", {
        "placeName": "온담 한식당", "reservedAt": dinner.isoformat(timespec="seconds"), "partySize": 3, "companionIds": COMPANIONS,
    })
    utterance_time = spoken_time(dinner)
    print(f"demo reservation at {dinner:%H:%M} ({utterance_time})")

    adb("logcat", "-G", "16M")
    adb("logcat", "-c")
    vconsole("park")
    shell(f"am force-stop --user 10 {PKG}")
    open_app()
    time.sleep(6)

    def home(rec: Recorder, since: str) -> bool:
        open_app()
        time.sleep(7)
        return True

    def route(rec: Recorder, since: str) -> bool:
        rec.mark("user", "강남역으로 안내해줘")
        asr("강남역으로 안내해줘")
        if not wait_for(r"ListeningPassiveState, previousState:AskingState", 40, since):
            return False
        rec.mark("gleo", "")
        rec.mark("user", "응, 안내해줘")
        asr("응 안내해줘")
        if not wait_for(r"SET_DESTINATION", 30, since):
            return False
        wait_for(r"SleepingState, previousState:SpeakingState", 20, since)
        open_navi()
        time.sleep(4)
        arrow = find_text("안내 시작")
        if arrow is None:
            # 내비가 검색 결과에 멈춰 있으면 첫 결과를 고른 뒤 안내를 시작한다
            tap(1399, 293)
            time.sleep(5)
            arrow = find_text("안내 시작")
        if arrow is not None:
            tap(*arrow)
        time.sleep(5)
        return find_text("안내 종료") is not None

    def drive(rec: Recorder, since: str) -> bool:
        open_app()
        time.sleep(3)
        rec.mark("caption", "주행이 시작되면 화면은 운전에 필요한 것만 남기고 조작은 음성으로 바뀌어요")
        vconsole("drive", "60")
        time.sleep(7)
        return True

    def late(rec: Recorder, since: str) -> bool:
        text = f"피트크루 앱으로 {utterance_time} 예약 지각 처리해줘"
        rec.mark("user", text)
        asr(text)
        if not wait_for(r"GleoCallback: sent .*NOTIFY_LATE_ARRIVAL.*\"status\":\"asking\"", 40, since):
            return False
        rec.mark("gleo", "")
        if not wait_for(r"ListeningPassiveState, previousState:AskingState", 40, since):
            return False
        rec.mark("user", "응, 그렇게 해줘")
        asr("응 그렇게 해줘")
        if not wait_for(r"GleoCallback: sent .*NOTIFY_LATE_ARRIVAL.*\"status\":\"success\"", 40, since):
            return False
        rec.mark("gleo", "")
        wait_for(r"SleepingState, previousState:SpeakingState", 30, since)
        open_app()
        time.sleep(5)
        return True

    def pickup(rec: Recorder, since: str) -> bool:
        rec.mark("user", "가는 길에 아메리카노 픽업 주문해줘")
        asr("가는 길에 아메리카노 픽업 주문해줘")
        if not wait_for(r"GleoCallback: sent .*ORDER_PICKUP_ON_ROUTE.*\"status\":\"asking\"", 40, since):
            return False
        rec.mark("gleo", "")
        if not wait_for(r"ListeningPassiveState, previousState:AskingState", 40, since):
            return False
        rec.mark("user", "응, 주문해줘")
        asr("응 주문해줘")
        if not wait_for(r"GleoCallback: sent .*ORDER_PICKUP_ON_ROUTE.*\"status\":\"success\"", 40, since):
            return False
        rec.mark("gleo", "")
        wait_for(r"SleepingState, previousState:SpeakingState", 30, since)
        open_navi()
        rec.mark("caption", "주문한 매장이 내비게이션 경유지로 들어가요")
        time.sleep(6)
        open_app()
        time.sleep(4)
        return True

    def parked(rec: Recorder, since: str) -> bool:
        vconsole("park")
        open_app()
        time.sleep(3)
        rec.mark("caption", "주차하면 처리 내역과 진행 단계를 화면에서 확인할 수 있어요")
        time.sleep(3)
        row = find_text("온담 한식당 · ") or None
        if row is None:
            for line in shell("cat /data/local/tmp/ui.xml").split("<node"):
                if "온담 한식당 · " in line and "늦음" in line:
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r'bounds="([^"]+)"', line).group(1)))
                    row = ((x1 + x2) // 2, (y1 + y2) // 2)
                    break
        if row is None:
            return False
        tap(*row)
        time.sleep(7)
        return True

    scene(rec, scenes, "02-home", "출발 전", home)
    scene(rec, scenes, "03-route", "목적지 설정", route)
    scene(rec, scenes, "04-drive", "주행 시작", drive)
    scene(rec, scenes, "05-late", "지각 대응", late)
    scene(rec, scenes, "07-pickup", "경로 픽업", pickup)
    scene(rec, scenes, "08-parked", "정차 후", parked)

    # Gleo 실제 발화를 장면 이벤트에 채운다
    log = logcat()
    for s in scenes:
        # 재촬영했다면 마지막 시도의 로그를 쓴다
        segment = log[log.rfind(f"PITCREW_DEMO_{s['name']}_"):]
        segment = segment.split("\n", 1)[-1]
        nxt = re.search(r"PITCREW_DEMO_", segment)
        segment = segment[: nxt.start()] if nxt else segment
        said = iter(gleo_lines(segment))
        for e in s["events"]:
            if e["kind"] == "gleo":
                e["text"] = next(said, "")
        s["events"] = [e for e in s["events"] if e["text"]]

    notifications = api("GET", "/v1/notifications?limit=2")["notifications"]
    meta = {
        "reservation": dinner.strftime("%H:%M"),
        "notifications": [{"name": n["recipientName"], "message": n["message"]} for n in notifications],
        "scenes": scenes,
    }
    (out / "scenes.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))
    vconsole("park")
    print(f"recorded to {out}")


if __name__ == "__main__":
    main()
