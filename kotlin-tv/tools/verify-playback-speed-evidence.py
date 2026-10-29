"""Verify playback buffering evidence from a captured logcat.

Usage:
    adb logcat -c && adb logcat -s HongguoTV > playback.log
    # play one episode end to end, then exit playback so the Prefetch line is written
    python tools/verify-playback-speed-evidence.py playback.log

Checks the three things the A1/A2 buffering work is supposed to move:
startup latency, mid-play rebuffering, and how much of the stream read-ahead served.
"""
import json
import re
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

path = Path(sys.argv[1] if len(sys.argv) > 1 else "playback.log")
if not path.exists():
    sys.exit(f"logcat file not found: {path}")

text = path.read_text(encoding="utf-8", errors="replace")

READY = re.compile(r"Playback ready episode=(\d+).*?elapsedMs=(\d+)")
FIRST_FRAME = re.compile(r"Playback first frame episode=(\d+).*?elapsedMs=(\d+)")
REBUF_START = re.compile(r"Rebuffer start episode=(\d+) positionMs=(\d+)")
REBUF_END = re.compile(r"Rebuffer end episode=(\d+) stalledMs=(\d+)")
PREFETCH = re.compile(
    r"Prefetch(?: end)? episode=(\d+) (\d+) reader / (\d+) ahead chunks, "
    r"hit=(\d+) miss=(\d+), aheadShare=(\d+)%, fetched=(\d+)MB")

ready_ms = [int(m.group(2)) for m in READY.finditer(text)]
first_frame_ms = [int(m.group(2)) for m in FIRST_FRAME.finditer(text)]
rebuf_start = [(int(m.group(1)), int(m.group(2))) for m in REBUF_START.finditer(text)]
rebuf_end = [int(m.group(2)) for m in REBUF_END.finditer(text)]
prefetch = [m.groups() for m in PREFETCH.finditer(text)]

checks = []


def check(name, ok, detail=""):
    checks.append({"check": name, "ok": bool(ok), "detail": detail})


check("捕获到起播日志", bool(ready_ms), f"{len(ready_ms)} 次")
check("捕获到首帧日志", bool(first_frame_ms), f"{len(first_frame_ms)} 次")
check("捕获到预读统计（退出播放时写出）", bool(prefetch),
      "若为空说明没退出播放或日志被清，覆盖不到预读数据")

if ready_ms:
    worst = max(ready_ms)
    check("起播耗时 < 3000ms", worst < 3000, f"最慢 {worst}ms，样本 {ready_ms}")
if first_frame_ms:
    worst = max(first_frame_ms)
    check("首帧耗时 < 4000ms", worst < 4000, f"最慢 {worst}ms，样本 {first_frame_ms}")

stalled_total = sum(rebuf_end)
check("中途缓冲次数 <= 1", len(rebuf_start) <= 1,
      f"触发 {len(rebuf_start)} 次，已结算卡顿时长 {stalled_total}ms")
if rebuf_end:
    check("单次卡顿 < 2000ms", max(rebuf_end) < 2000, f"最长 {max(rebuf_end)}ms")

if prefetch:
    _, reader, ahead, hit, miss, share, mb = prefetch[-1]
    total_chunks = int(reader) + int(ahead)
    hit_ratio = int(hit) * 100 // max(1, int(hit) + int(miss))
    check("预读线程确实在拉流", int(ahead) > 0, f"预读 {ahead} 块 / 共 {total_chunks} 块")
    check("预读承担 > 50% 拉流", int(share) > 50, f"aheadShare={share}%")
    check("读请求缓存命中 > 80%", hit_ratio > 80, f"hit={hit} miss={miss} ({hit_ratio}%)")
    check("预读窗口未失控", int(mb) <= 200, f"本次共拉取 {mb}MB（2MB/块 × 8MB 前瞻，超 200MB 视为无效重拉）")

passed = sum(1 for c in checks if c["ok"])
failed = [c for c in checks if not c["ok"]]
print(json.dumps({
    "passed": passed,
    "failed": len(failed),
    "startupMs": ready_ms,
    "firstFrameMs": first_frame_ms,
    "rebufferCount": len(rebuf_start),
    "rebufferTotalMs": stalled_total,
    "prefetch": prefetch[-1] if prefetch else None,
    "checks": checks,
}, ensure_ascii=False, indent=2))

sys.exit(1 if failed else 0)
