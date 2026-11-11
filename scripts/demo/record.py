"""PitCrew 시연 영상을 장면별로 녹화한다.

에뮬레이터·로컬 서버·Gleo를 실제로 구동한다. 음성은 Gleo 테스트 입력(ai.umos.intent.action.ASR)으로 넣고,
Gleo가 실제로 한 말은 로그에서 뽑아 자막 데이터(scenes.json)로 남긴다. Gleo 반응이 기대와 다르면 그 장면만 다시 찍는다.

사용: python3 scripts/demo/record.py [출력 폴더]
전제: 에뮬레이터 부팅, 서버 실행(127.0.0.1:3000), debug APK 설치.
"""
from __future__ import annotations

import json
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
MAX_TRIES = 4
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


def debug(action: str, *extras: str) -> None:
    """debug 빌드 전용 방송(먼저 묻기 확인·답 넣기·주기 확인 끄기)."""
    shell(" ".join(["am broadcast", "-a", f"ai.pitcrew.app.debug.{action}", *extras, "-n", f"{PKG}/.debug.DebugVoiceReceiver"]))


def device_time_after(match: re.Match[str]) -> str:
    """로그 줄 앞의 기기 시각. 그 줄 이후만 보도록 since로 쓴다."""
    line_start = match.string.rfind("\n", 0, match.start()) + 1
    return match.string[line_start:line_start + 18]


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
    # 본문 없는 요청(DELETE 등)에 JSON 헤더를 붙이면 서버가 빈 본문을 거절한다
    headers = {"content-type": "application/json"} if data is not None else {}
    request = urllib.request.Request(f"{SERVER}{path}", data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        text = response.read().decode()
        return json.loads(text) if text else {}


def reset_demo_orders() -> None:
    """앞선 촬영의 오늘 주문이 남으면 단골 제안이 '오늘 이미 시킴'으로 빠지므로 시드 이력만 남긴다. 로컬 개발 DB 전용."""
    subprocess.run(
        ["docker", "exec", "pitcrew-mysql", "mysql", "-upitcrew", "-ppitcrew", "pitcrew",
         "-e", "DELETE FROM waitlists; DELETE FROM orders WHERE id NOT LIKE '3c7e9a1b-%';"],
        check=True, capture_output=True,
    )


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


def optional_scene(rec: Recorder, scenes: list[dict], name: str, title: str, body) -> None:
    """상황이 맞을 때만 생기는 장면. 한 번만 시도하고 안 되면 빼고 넘어간다."""
    shell(f"log -t PitCrewDemo PITCREW_DEMO_{name}_1_{int(time.time())}")
    since = device_time()
    rec.start(name)
    ok = body(rec, since)
    path = rec.stop()
    if ok:
        scenes.append({"name": name, "title": title, "video": path.name, "events": rec.events})
        print(f"[ok] {name}")
    else:
        path.unlink(missing_ok=True)
        print(f"[skip] {name}")


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

    # 시연 데이터: ETA(약 40분)보다 이른 10분 단위 예약을 잡아 15~25분 지각이 나게 한다
    # 앱은 기기 시간대로 시각을 해석하므로 예약·발화 시각도 기기 시간대로 계산한다
    zone = ZoneInfo(shell("getprop persist.sys.timezone").strip() or "UTC")
    now = datetime.now(zone)
    dinner = (now + timedelta(minutes=25)).replace(second=0, microsecond=0)
    dinner = dinner.replace(minute=dinner.minute // 10 * 10)
    api("PUT", f"/v1/reservations/{DINNER_ID}", {
        "placeName": "온담 한식당", "reservedAt": dinner.isoformat(timespec="seconds"), "partySize": 3, "companionIds": COMPANIONS,
    })
    reset_demo_orders()
    utterance_time = spoken_time(dinner)
    print(f"demo reservation at {dinner:%H:%M} ({utterance_time})")

    adb("logcat", "-G", "16M")
    adb("logcat", "-c")
    vconsole("park")
    vconsole("battery", "72")
    shell(f"am force-stop --user 10 {PKG}")
    # 앞선 촬영의 경로·경유지가 남지 않게 안내를 끝낸다(내비를 강제 종료하면 이후 경로 안내가 바로 끝나 버린다)
    open_navi()
    time.sleep(3)
    end = find_text("안내 종료")
    if end is not None:
        tap(*end)
        time.sleep(2)
    open_app()
    time.sleep(6)
    # 장면 순서는 녹화가 정한다. 1분 주기 확인이 엉뚱한 장면에 끼지 않게 끈다
    debug("PERIODIC", "--ez", "on", "false")

    def home(rec: Recorder, since: str) -> bool:
        open_app()
        time.sleep(4)
        return True

    def route(rec: Recorder, since: str) -> bool:
        # 목적지 설정은 PitCrew 기능이 아니고 Gleo 응답이 매번 달라, 내비 SDK로 바로 경로를 건다
        rec.mark("caption", "내비게이션에 강남역 경로가 잡혀 있어요. PitCrew는 이 도착 예정 시각으로 판단해요")
        debug("ROUTE", "--es", "name", "강남역", "--ef", "lat", "37.4979", "--ef", "lon", "127.0276")
        if not wait_for(r"onRouteStarted", 15, since):
            return False
        open_navi()
        time.sleep(4)
        return True

    def proactive(rec: Recorder, since: str, reply: str, expect: str) -> bool:
        """PitCrew가 먼저 묻는 장면. 질문을 다 말한 뒤 운전자 답을 넣고 결과 안내까지 기다린다."""
        # 경유지가 막 바뀌면 내비 ETA가 몇 초 뒤에 갱신되므로 질문이 나올 때까지 몇 번 다시 보게 한다
        asked = None
        for _ in range(6):
            debug("CHECK_NOW")
            asked = wait_for(r"ProactiveAssistant: asking: (.+)", 5, since)
            if asked:
                break
        if not asked or expect not in asked.group(1):
            return False
        rec.mark("pitcrew", asked.group(1).strip())
        # TTS가 질문을 마치고 듣기 시작할 때까지(한국어 TTS 초당 약 7자)
        time.sleep(max(3.0, len(asked.group(1)) / 7))
        rec.mark("user", reply)
        debug("ANSWER", "--es", "text", reply.split(",")[0])
        answered = wait_for(r"ProactiveAssistant: answer: YES", 15, since)
        if not answered:
            return False
        # 질문도 같은 TTS 로그로 남으므로 답을 받은 뒤의 결과 안내만 본다
        said = wait_for(r"speak\(ai\.pitcrew\.app\) received final\. texts=(.+)", 20, device_time_after(answered))
        if said:
            rec.mark("pitcrew", said.group(1).strip())
        time.sleep(3)
        return True

    def drive_late(rec: Recorder, since: str) -> bool:
        open_app()
        time.sleep(2)
        rec.mark("caption", "주행이 시작되면 화면은 운전에 필요한 것만 남고, PitCrew가 상황을 보고 먼저 물어요")
        vconsole("drive", "60")
        time.sleep(3)
        return proactive(rec, since, "응, 그렇게 해줘", "늦어요")

    def gleo_errand(rec: Recorder, since: str, function: str, ask: str, reply: str) -> bool:
        rec.mark("user", ask)
        asr(ask)
        if not wait_for(rf"GleoCallback: sent .*{function}.*\"status\":\"asking\"", 40, since):
            return False
        rec.mark("gleo", "")
        if not wait_for(r"ListeningPassiveState, previousState:AskingState", 40, since):
            return False
        rec.mark("user", reply)
        asr(reply.replace(",", ""))
        if not wait_for(rf"GleoCallback: sent .*{function}.*\"status\":\"success\"", 40, since):
            return False
        rec.mark("gleo", "")
        wait_for(r"SleepingState, previousState:SpeakingState", 30, since)
        return True

    def pickup(rec: Recorder, since: str) -> bool:
        if not gleo_errand(rec, since, "ORDER_PICKUP_ON_ROUTE", "피트크루 앱으로 가는 길에 노을 베이커리에서 크루아상 픽업 주문해줘", "응, 피트크루 앱으로 주문해줘"):
            return False
        open_navi()
        rec.mark("caption", "주문한 매장이 내비게이션 경유지로 들어가요")
        time.sleep(4)
        return True

    def undo(rec: Recorder, since: str) -> bool:
        rec.mark("caption", "잘못 시켰다면 3분 안에 말로 되돌려요")
        if not gleo_errand(rec, since, "CANCEL_LAST_ERRAND", "피트크루 앱으로 방금 거 취소해줘", "응, 피트크루 앱으로 취소해줘"):
            return False
        open_navi()
        rec.mark("caption", "주문이 취소되고 경유지도 빠졌어요")
        time.sleep(4)
        open_app()
        time.sleep(2)
        return True

    def routine(rec: Recorder, since: str) -> bool:
        open_app()
        rec.mark("caption", "자주 픽업하던 시간이면 단골 메뉴를 먼저 권해요")
        return proactive(rec, since, "응, 주문해줘", "오늘도")

    def follow_up(rec: Recorder, since: str) -> bool:
        # 에뮬레이터 ETA는 경유지가 붙을 때만 늘어나므로, 단골 픽업 경유지로 미룬 예약에 또 늦을 때만 찍힌다
        rec.mark("caption", "픽업 매장을 들르느라 도착이 더 늦어지면, 미룬 예약도 다시 맞춰요")
        return proactive(rec, since, "응, 다시 바꿔줘", "또")

    def battery(rec: Recorder, since: str) -> bool:
        vconsole("battery", "18")
        rec.mark("caption", "배터리가 18%로 떨어지면 충전소와, 충전하는 동안 기다릴 식당을 함께 찾아요")
        time.sleep(2)
        return proactive(rec, since, "응, 그렇게 해줘", "배터리")

    def parked(rec: Recorder, since: str) -> bool:
        vconsole("park")
        open_app()
        time.sleep(2)
        rec.mark("caption", "주차하면 PitCrew가 한 일과 진행 단계를 화면에서 확인할 수 있어요")
        time.sleep(2)
        row = None
        shell("uiautomator dump /data/local/tmp/ui.xml")
        for line in shell("cat /data/local/tmp/ui.xml").split("<node"):
            if "온담 한식당 · " in line and "늦음" in line:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", re.search(r'bounds="([^"]+)"', line).group(1)))
                row = ((x1 + x2) // 2, (y1 + y2) // 2)
                break
        if row is None:
            return False
        tap(*row)
        time.sleep(5)
        return True

    scene(rec, scenes, "02-home", "출발 전", home)
    scene(rec, scenes, "03-route", "목적지 설정", route)
    scene(rec, scenes, "04-late", "늦을 것 같으면 먼저 물어요", drive_late)
    scene(rec, scenes, "05-pickup", "가는 길에 픽업", pickup)
    scene(rec, scenes, "06-undo", "잘못 시켰으면 되돌려요", undo)
    scene(rec, scenes, "07-routine", "자주 시키던 메뉴", routine)
    optional_scene(rec, scenes, "08-followup", "또 늦어지면 다시 맞춰요", follow_up)
    scene(rec, scenes, "09-battery", "충전하는 동안 줄서기", battery)
    scene(rec, scenes, "10-parked", "주차한 뒤", parked)

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

    notifications = [n for n in api("GET", "/v1/notifications?limit=10")["notifications"] if "다시 잡았어요" in n["message"]][:2]
    meta = {
        "reservation": dinner.strftime("%H:%M"),
        "notifications": [{"name": n["recipientName"], "message": n["message"]} for n in notifications],
        "scenes": scenes,
    }
    (out / "scenes.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))
    vconsole("park")
    vconsole("battery", "72")
    print(f"recorded to {out}")


if __name__ == "__main__":
    main()
