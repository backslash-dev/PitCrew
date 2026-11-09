"""record.py가 찍은 장면들에 자막·카드를 입혀 시연 영상 하나로 합친다.

이 맥의 ffmpeg에는 자막 필터(libass)가 없어 자막을 PNG로 그려 overlay로 얹는다.
사용: python3 scripts/demo/compose.py <녹화 폴더>
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 1920, 1080, 30
FONT = "/System/Library/Fonts/AppleSDGothicNeo.ttc"
REGULAR, MEDIUM, BOLD = 0, 2, 6
INK = (19, 20, 23)
PAPER = (247, 248, 250)
MUTED = (104, 106, 114)

# 장면마다 쓰인 플랫폼 기능. 심사 항목(SDK 활용)을 화면에서 바로 읽히게 한다
SDK = {
    "02-home": "PitCrew 서버 · NaviHelper",
    "03-route": "Gleo · NaviHelper",
    "04-late": "CarUxRestrictions · NaviHelper ETA · TextToSpeech · SpeechToText",
    "05-pickup": "Gleo Interface · NaviHelper 경유지 · FusedLocation",
    "06-undo": "Gleo Interface · NaviHelper 경유지 삭제 · PitCrew 서버",
    "07-routine": "주문 이력 · TextToSpeech · SpeechToText",
    "08-followup": "NaviHelper ETA · PitCrew 서버 재알림",
    "09-battery": "Vehicle 배터리 SOC · NaviHelper 경유지",
    "10-parked": "주차 중 화면 승인 · 진행 단계",
}
# 장면 전환이 늘어지지 않게 기본으로 빠르게 재생하고, 기다리는 구간이 긴 장면은 더 빠르게 한다
DEFAULT_SPEED = 1.3
SPEED = {"03-route": 2.2, "05-pickup": 1.6, "06-undo": 1.6, "04-late": 1.4, "07-routine": 1.4, "08-followup": 1.4, "09-battery": 1.4}
# 마지막 자막 뒤로 이만큼만 남기고 자른다
TAIL_SECONDS = 2.2
MIN_SUBTITLE_SECONDS = 2.2
# 자막 뒤에 보여 줄 화면 동작(상세 화면 열기)이 있는 장면은 자르지 않는다
NO_TRIM = {"10-parked"}
SCENE_NOTE = {
    "02-home": "출발 전 · 오늘 예약과 늦으면 알릴 일행을 PitCrew가 알고 있어요",
}


def font(size: int, weight: int = MEDIUM) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT, size, index=weight)


def wrap(text: str, f: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines, line = [], ""
    for word in text.split(" "):
        candidate = f"{line} {word}".strip()
        if f.getlength(candidate) <= width or not line:
            line = candidate
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def subtitle(path: Path, speaker: str | None, text: str) -> Path:
    """화면 하단 자막. 반투명 어두운 판에 흰 글자, 화자는 흐린 글자로 앞에 둔다."""
    body = font(40, MEDIUM)
    label = font(30, BOLD)
    lines = wrap(text, body, 1500)
    line_h = 56
    pad_x, pad_y = 44, 28
    head = 44 if speaker else 0
    box_w = max(int(body.getlength(l)) for l in lines) + pad_x * 2
    box_w = max(box_w, int(label.getlength(speaker)) + pad_x * 2) if speaker else box_w
    box_h = head + line_h * len(lines) + pad_y * 2
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    x0, y0 = (W - box_w) // 2, H - box_h - 64
    d.rounded_rectangle((x0, y0, x0 + box_w, y0 + box_h), radius=24, fill=(12, 13, 15, 214))
    y = y0 + pad_y
    if speaker:
        d.text((x0 + pad_x, y), speaker, font=label, fill=(176, 180, 188))
        y += head
    for l in lines:
        d.text((x0 + pad_x, y), l, font=body, fill=(255, 255, 255))
        y += line_h
    im.save(path)
    return path


def chip(path: Path, title: str, sdk: str) -> Path:
    """장면 이름과 쓰인 기능. 화면 위쪽 좌우에 작게 둔다."""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    t = font(34, BOLD)
    s = font(26, MEDIUM)
    tw = int(t.getlength(title)) + 48
    d.rounded_rectangle((32, 96, 32 + tw, 96 + 64), radius=16, fill=(12, 13, 15, 214))
    d.text((56, 108), title, font=t, fill=(255, 255, 255))
    sw = int(s.getlength(sdk)) + 40
    d.rounded_rectangle((W - 32 - sw, 100, W - 32, 100 + 56), radius=14, fill=(12, 13, 15, 190))
    d.text((W - 32 - sw + 20, 112), sdk, font=s, fill=(214, 218, 224))
    im.save(path)
    return path


def card(path: Path, lines: list[tuple[str, int, int, tuple[int, int, int]]], dark: bool = False) -> Path:
    """전체 화면 카드. lines는 (문장, 크기, 굵기, 색) 목록이며 위에서부터 왼쪽 정렬로 놓는다."""
    im = Image.new("RGB", (W, H), INK if dark else PAPER)
    d = ImageDraw.Draw(im)
    total = sum(int(size * 1.5) for _, size, _, _ in lines)
    y = (H - total) // 2
    for text, size, weight, color in lines:
        d.text((180, y), text, font=font(size, weight), fill=color)
        y += int(size * 1.5)
    im.save(path)
    return path


def notification_card(path: Path, reservation: str, notifications: list[dict]) -> Path:
    im = Image.new("RGB", (W, H), PAPER)
    d = ImageDraw.Draw(im)
    d.text((180, 170), "같은 시각, 일행에게 간 알림", font=font(64, BOLD), fill=INK)
    d.text((180, 262), "PitCrew 서버 발송 내역 · 실제 문자 연동은 발신번호 등록 후 같은 경로로 붙어요", font=font(32, MEDIUM), fill=MUTED)
    y = 380
    for n in notifications:
        d.text((180, y), n["name"], font=font(34, BOLD), fill=INK)
        bubble = wrap(n["message"], font(36, MEDIUM), 1300)
        bh = 40 + 54 * len(bubble)
        d.rounded_rectangle((180, y + 54, 180 + 1400, y + 54 + bh), radius=28, fill=(255, 255, 255))
        ty = y + 74
        for line in bubble:
            d.text((220, ty), line, font=font(36, MEDIUM), fill=INK)
            ty += 54
        y += 54 + bh + 56
    im.save(path)
    return path


def spoken(hhmm: str) -> str:
    """운전자 대사와 같은 말투(12시간제, 반)로 시각을 쓴다."""
    hour, minute = map(int, hhmm.split(":"))
    hour = hour % 12 or 12
    return f"{hour}시" if minute == 0 else f"{hour}시 반" if minute == 30 else f"{hour}시 {minute}분"


def run(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


ENCODE = ["-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", str(FPS), "-an"]


def still(png: Path, seconds: float, out: Path) -> Path:
    run("-loop", "1", "-t", str(seconds), "-i", str(png), "-vf", f"scale={W}:{H}", *ENCODE, str(out))
    return out


def scene_clip(src: Path, overlays: list[tuple[Path, float, float]], speed: float, length: float, out: Path) -> Path:
    inputs = ["-i", str(src)]
    chain = [f"[0:v]setpts=PTS/{speed},scale={W}:{H},fps={FPS}[v0]"]
    for i, (png, start, end) in enumerate(overlays, start=1):
        inputs += ["-i", str(png)]
        chain.append(f"[v{i-1}][{i}:v]overlay=0:0:enable='between(t,{start:.2f},{end:.2f})'[v{i}]")
    run(*inputs, "-filter_complex", ";".join(chain), "-map", f"[v{len(overlays)}]", "-t", f"{length:.2f}", *ENCODE, str(out))
    return out


def duration(path: Path) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)], capture_output=True, text=True)
    return float(out.stdout.strip())


def main() -> None:
    folder = Path(sys.argv[1])
    meta = json.loads((folder / "scenes.json").read_text())
    work = folder / "compose"
    work.mkdir(exist_ok=True)
    clips: list[Path] = []

    clips.append(still(card(work / "title.png", [
        ("PitCrew", 96, BOLD, (255, 255, 255)),
        ("운전하는 동안, 차 밖의 일은 맡기세요", 52, MEDIUM, (224, 226, 230)),
        ("개발 보고용 시연 · Pleos Connect 에뮬레이터 실동작 녹화 · 음성은 자막으로 표시", 30, REGULAR, (150, 154, 162)),
    ], dark=True), 2.5, work / "00-title.mp4"))
    clips.append(still(card(work / "problem.png", [
        (f"{spoken(meta['reservation'])} 예약인데, 늦을 것 같다", 60, BOLD, INK),
        ("운전 중에는 휴대폰으로 예약을 바꾸거나 일행에게 연락할 수 없어요", 40, MEDIUM, MUTED),
        ("PitCrew는 내비 도착 시간으로 먼저 알아채고, 말 한마디로 처리해요", 40, MEDIUM, MUTED),
    ]), 3.5, work / "01-problem.mp4"))

    for scene in meta["scenes"]:
        name = scene["name"]
        src = folder / scene["video"]
        speed = SPEED.get(name, DEFAULT_SPEED)
        full = duration(src) / speed
        length = full
        overlays: list[tuple[Path, float, float]] = [(chip(work / f"{name}-chip.png", scene["title"], SDK.get(name, "")), 0, length)]
        events = [dict(e, t=e["t"] / speed) for e in scene["events"]]
        if name in SCENE_NOTE:
            events.insert(0, {"t": 0.3, "kind": "caption", "text": SCENE_NOTE[name]})
        # 자막이 겹치지 않게 앞 자막을 최소 시간만큼 보여 준 뒤 다음 자막을 띄운다
        starts: list[float] = []
        for i, e in enumerate(events):
            start = e["t"]
            if e["kind"] == "gleo" and i > 0:
                # Gleo 발화는 말이 끝난 시점에 기록되므로, 앞 대사 뒤부터 보이게 당긴다
                start = max(events[i - 1]["t"] + 2.0, start - 7.0)
            if starts:
                start = max(start, starts[-1] + MIN_SUBTITLE_SECONDS)
            starts.append(start)
        # 마지막 자막 뒤로 조금만 남기고 자르되 원본 길이는 넘지 않는다
        if name not in NO_TRIM:
            length = min(full, max((starts[-1] if starts else 0) + MIN_SUBTITLE_SECONDS + TAIL_SECONDS, 4.0))
        for i, e in enumerate(events):
            start = starts[i]
            end = starts[i + 1] if i + 1 < len(events) else length
            speaker = {"user": "운전자", "gleo": "Gleo", "pitcrew": "PitCrew", "caption": None}[e["kind"]]
            text = f"“{e['text']}”" if e["kind"] == "user" else e["text"]
            overlays.append((subtitle(work / f"{name}-{i}.png", speaker, text), start, min(end, length)))
        clips.append(scene_clip(src, overlays, speed, length, work / f"{name}.mp4"))
        if name == "04-late":
            clips.append(still(notification_card(work / "notify.png", meta["reservation"], meta["notifications"]), 4, work / "04b-notify.mp4"))

    clips.append(still(card(work / "end.png", [
        ("Pleos Connect SDK 5종을 핵심 기능에 사용", 56, BOLD, (255, 255, 255)),
        ("Vehicle · 주행 상태와 배터리     NaviHelper · ETA와 경유지     FusedLocation · 경로상 매장", 32, MEDIUM, (214, 218, 224)),
        ("SpeechToText · 먼저 물은 질문에 답 듣기     TextToSpeech · 질문·결과·도착 직전 안내", 32, MEDIUM, (214, 218, 224)),
        ("Gleo Interface 함수 3개 · 지각 대응, 경로 픽업, 되돌리기 · 확대: 약속 조율, 업무 메일", 32, MEDIUM, (150, 154, 162)),
    ], dark=True), 4, work / "99-end.mp4"))

    listing = work / "concat.txt"
    listing.write_text("".join(f"file '{c.resolve()}'\n" for c in clips))
    final = folder / "pitcrew-demo.mp4"
    run("-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", "-movflags", "+faststart", str(final))
    print(f"{final} ({duration(final):.1f}s)")


if __name__ == "__main__":
    main()
