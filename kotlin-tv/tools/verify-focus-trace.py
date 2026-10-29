#!/usr/bin/env python3
"""焦点轨迹回归：用 adb 驱动遥控器按键，逐键记录焦点落点，可与基线比对。

电视端的焦点问题（焦点跳走、按键无响应、焦点跑到别的分区）没法靠 JVM 单测覆盖，
这是唯一能自动发现「改了 UI 之后焦点行为变了」的手段。

用法：
    # 记录一条轨迹并保存为基线
    python tools/verify-focus-trace.py --keys RIGHT,RIGHT,DOWN,DOWN,UP --save base.json

    # 改动后重跑，与基线比对（一致 exit 0，有差异 exit 1）
    python tools/verify-focus-trace.py --keys RIGHT,RIGHT,DOWN,DOWN,UP --baseline base.json

约定：
    轨迹只记录「当前焦点元素的可读标识」，即 content-desc > text > 归一化后的 bounds，
    因此它对比的是语义位置而不是像素坐标，换分辨率不会误报。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

DEFAULT_ADB = os.environ.get("ADB", "D:/Android/Sdk/platform-tools/adb.exe")
KEYCODES = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "ENTER": 66, "BACK": 4, "MENU": 82}


def run(adb, serial, args, timeout=30):
    cmd = [adb] + (["-s", serial] if serial else []) + args
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")


def dump_ui(adb, serial):
    """导出当前界面控件树，返回 XML 根节点。"""
    remote = "/data/local/tmp/focus-trace.xml"
    run(adb, serial, ["shell", "uiautomator", "dump", remote])
    local = os.path.join(tempfile.gettempdir(), "focus-trace.xml")
    for _ in range(3):
        if run(adb, serial, ["pull", remote, local]).returncode == 0 and os.path.exists(local):
            try:
                return ET.parse(local).getroot()
            except ET.ParseError:
                pass
        time.sleep(0.4)
    return None


def focused_node(root):
    if root is None:
        return None
    for node in root.iter("node"):
        if node.get("focused") == "true":
            return node
    return None


def label_of(node):
    """把一个节点变成稳定的可读标识，优先语义描述而不是坐标。"""
    if node is None:
        return "none"
    desc = (node.get("content-desc") or "").strip()
    if desc:
        return desc
    text = (node.get("text") or "").strip()
    if text:
        return text
    bounds = node.get("bounds") or ""
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if match:
        left, top, right, bottom = (int(v) for v in match.groups())
        # 坐标归一化到 10 像素网格，避免渲染抖动造成假差异
        return "box@%d,%d-%d,%d" % (left // 10 * 10, top // 10 * 10, right // 10 * 10, bottom // 10 * 10)
    return "unknown"


def focused_label(adb, serial, attempts=3, interval=0.5):
    """取当前焦点标识；界面刚切换时 might 还拿不到，短暂重试避免把 none 写进基线。"""
    for _ in range(attempts):
        node = focused_node(dump_ui(adb, serial))
        if node is not None:
            return label_of(node)
        time.sleep(interval)
    return "none"


def trace(adb, serial, keys, setup_keys, settle, warmup):
    time.sleep(warmup)
    # 归位：连续向上顶到顶部导航，再按一次向下落到第一行第一张，保证两次录制的起点相同。
    for key in setup_keys:
        name = key.strip().upper()
        if name not in KEYCODES:
            raise SystemExit("未知归位按键 %s" % name)
        run(adb, serial, ["shell", "input", "keyevent", str(KEYCODES[name])])
        time.sleep(0.25)
    if setup_keys:
        time.sleep(settle)
    steps = [{"key": "start", "focus": focused_label(adb, serial)}]
    for key in keys:
        name = key.strip().upper()
        if name not in KEYCODES:
            raise SystemExit("未知按键 %s，可用：%s" % (name, ",".join(KEYCODES)))
        run(adb, serial, ["shell", "input", "keyevent", str(KEYCODES[name])])
        time.sleep(settle)
        steps.append({"key": name, "focus": focused_label(adb, serial)})
    return steps


def compare(actual, baseline):
    diffs = []
    if len(actual) != len(baseline):
        diffs.append("步数不同：实际 %d，基线 %d" % (len(actual), len(baseline)))
    for index, (got, want) in enumerate(zip(actual, baseline)):
        if got["key"] != want["key"]:
            diffs.append("第 %d 步按键不同：%s vs %s" % (index, got["key"], want["key"]))
        elif got["focus"] != want["focus"]:
            diffs.append("第 %d 步（%s）焦点不同：\n    实际 %s\n    基线 %s" % (index, got["key"], got["focus"], want["focus"]))
    return diffs


def main():
    parser = argparse.ArgumentParser(description="TV 焦点轨迹回归")
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    parser.add_argument("--keys", default="RIGHT,RIGHT,DOWN,DOWN,DOWN,UP,UP,LEFT", help="逗号分隔的按键序列")
    parser.add_argument("--setup", default="UP,UP,UP,UP,UP,DOWN", help="录制前执行的归位按键，不记入轨迹；传空字符串关闭")
    parser.add_argument("--settle", type=float, default=0.6, help="每个按键后的稳定等待秒数")
    parser.add_argument("--warmup", type=float, default=1.0, help="开始前的等待秒数")
    parser.add_argument("--save", help="把本次轨迹保存为 JSON 基线")
    parser.add_argument("--baseline", help="与指定基线比对，不一致时退出码为 1")
    args = parser.parse_args()

    keys = [k for k in args.keys.split(",") if k.strip()]
    setup_keys = [k for k in args.setup.split(",") if k.strip()]
    actual = trace(args.adb, args.serial, keys, setup_keys, args.settle, args.warmup)

    print("焦点轨迹：")
    for index, step in enumerate(actual):
        print("  %2d. %-6s -> %s" % (index, step["key"], step["focus"]))

    if args.save:
        with open(args.save, "w", encoding="utf-8") as handle:
            json.dump(actual, handle, ensure_ascii=False, indent=2)
        print("已保存基线：%s" % args.save)

    if args.baseline:
        with open(args.baseline, encoding="utf-8") as handle:
            baseline = json.load(handle)
        diffs = compare(actual, baseline)
        if diffs:
            print("\n焦点行为与基线不一致：")
            for line in diffs:
                print("  - " + line)
            return 1
        print("\n与基线一致（%d 步）" % len(actual))
    return 0


if __name__ == "__main__":
    sys.exit(main())
