"""record.py가 찍은 장면들에 자막을 입혀 시연 영상 하나로 합친다. 별도 카드 없이 실제 화면만 쓴다.

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
MEDIUM, BOLD = 2, 6

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


def chip(path: Path, title: str) -> Path:
    """장면 이름. 화면 왼쪽 위에 작게 둔다."""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    t = font(34, BOLD)
    tw = int(t.getlength(title)) + 48
    d.rounded_rectangle((32, 96, 32 + tw, 96 + 64), radius=16, fill=(12, 13, 15, 214))
    d.text((56, 108), title, font=t, fill=(255, 255, 255))
    im.save(path)
    return path


def run(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


ENCODE = ["-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-r", str(FPS), "-an"]


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

    for scene in meta["scenes"]:
        name = scene["name"]
        src = folder / scene["video"]
        speed = SPEED.get(name, DEFAULT_SPEED)
        full = duration(src) / speed
        length = full
        overlays: list[tuple[Path, float, float]] = [(chip(work / f"{name}-chip.png", scene["title"]), 0, length)]
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

    listing = work / "concat.txt"
    listing.write_text("".join(f"file '{c.resolve()}'\n" for c in clips))
    final = folder / "pitcrew-demo.mp4"
    run("-f", "concat", "-safe", "0", "-i", str(listing), "-c", "copy", "-movflags", "+faststart", str(final))
    print(f"{final} ({duration(final):.1f}s)")


if __name__ == "__main__":
    main()
