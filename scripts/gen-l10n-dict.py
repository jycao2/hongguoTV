#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成多语言字典：从 Kotlin 源码提取中文文案，用 opencc s2twp 生成繁中字典。

用法（依赖：pip install opencc-python-reimplemented）：
    python scripts/gen-l10n-dict.py

产出：kotlin-tv/core/src/main/kotlin/com/hongguotv/core/L10nDict.kt

注意：
- 只提取单行字符串字面量，跳过含 Kotlin 插值（$）的——那些运行时才成形，无法静态翻译；
  它们回退简体中文，属预期行为。
- 英文字典（L10nEnglish.kt）是人工维护的高频词条，本脚本不覆盖它。
- 新增 UI 文案后重新跑本脚本即可补全繁中字典。
"""
import re
import glob
import collections
from pathlib import Path

try:
    import opencc
except ImportError:
    raise SystemExit("需要 opencc-python-reimplemented：pip install opencc-python-reimplemented")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "kotlin-tv"
OUT = SRC / "core/src/main/kotlin/com/hongguotv/core/L10nDict.kt"

PAT = re.compile(r'"([^"\n]*[\u4e00-\u9fff][^"\n]*)"')
# trFmt 整条模板含 {0} 占位符，普通 PAT 会切开；单独抓整条供繁中整串命中
TFMT = re.compile(r'trFmt\(\s*"((?:[^"\\]|\\.)*)"')
# 整页 HTML 模板用的是三引号原始字符串，上面的单行正则会把它切碎，这里单独整块取出来
TRIPLE = re.compile(r'"""(.*?)"""', re.S)
NOISE = ("//", "/*", "*/", "Log.", "http", "class ", "fun ", "return ", "import ", "package ")
# Kotlin 字符串插值：`${expr}` 与 `$name`。切开后剩下的字面片段才是可静态翻译的部分。
INTERP = re.compile(r"\$\{[^{}]*\}|\$[A-Za-z_][A-Za-z0-9_]*")
# 界面上的「标题  ·  值」分隔符（见 MainActivity.setting()）
SEGMENT = re.compile(r"\s*[·•]\s*")
# HTML 标签：整页模板里文案都夹在标签之间，按标签切开才能取到文字节点
TAG = re.compile(r"<[^>]{0,200}>")


def _fragments(lit: str):
    """把带插值的字面量拆成可翻译片段。

    `"快进步长  ·  ${extras.seekStepSeconds} 秒"` → ["快进步长", "秒"]；
    界面是按 `  ·  ` 拆成「标题 / 右侧值」两段显示的，所以这两个片段会单独作为 key。

    手机网页那几张整页 HTML 是一个大字符串，文案夹在标签和脚本引号里：
    这里把「插值 / HTML 标签 / 引号」都当分隔符切开，标签内的文字节点与脚本字符串
    才能各自成为一个词条（否则整页 key 几百字，永远命不中）。
    """
    broken = TAG.sub("\u0001", INTERP.sub("\u0000", lit))
    out = []
    for piece in SEGMENT.split(broken):
        for chunk in re.split("[\u0000\u0001'\"]", piece):
            chunk = chunk.strip()
            if chunk:
                out.append(chunk)
    return out


def _unescape(lit: str) -> str:
    """把源码字面量里的转义还原成运行时字符串。

    字典是**按运行时字符串**查的，源码里的 `\\n` 到运行时是真正的换行；
    不还原就会出现「key 里是反斜杠 + n、运行时是换行」永远查不中（2026-11-27 实际踩到）。
    """
    out = []
    i = 0
    while i < len(lit):
        c = lit[i]
        if c == "\\" and i + 1 < len(lit):
            nxt = lit[i + 1]
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "$": "$"}.get(nxt, "\\" + nxt))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def collect():
    counts = collections.Counter()
    for pattern in (
        "app/src/main/kotlin/**/*.kt",
        "core/src/main/kotlin/**/*.kt",
    ):
        for path in SRC.glob(pattern):
            if "/test/" in str(path) or "L10n" in path.name:
                continue
            text = path.read_text(encoding="utf-8")
            for lit in PAT.findall(text) + TRIPLE.findall(text):
                for cand in [lit] + _fragments(lit):
                    if "$" in cand or "{" in cand or "}" in cand:
                        continue
                    cand = _unescape(cand)
                    if len(cand) > 120 or len(cand.strip()) < 2:
                        continue
                    if any(t in cand for t in NOISE):
                        continue
                    counts[cand] += 1
    return list(counts)


def kt(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


EN = SRC / "core/src/main/kotlin/com/hongguotv/core/L10nEnglish.kt"
PHRASES = SRC / "core/src/main/kotlin/com/hongguotv/core/L10nPhrases.kt"
# 既要匹配片段表的 `"key",`，也要匹配英文字典的 `"key" to "value",`。
# 旧版只认前者，英文字典里人工维护的词条（如 "5 分钟"、许可说明逐行文案）永远进不了繁中字典。
KT_KEY = re.compile(
    r'^\s*"((?:[^"\\]|\\.)*)"\s*to\s+"(?:[^"\\]|\\.)*"\s*,?\s*$'
    r'|^\s*"((?:[^"\\]|\\.)*)"\s*,\s*$',
    re.M,
)


def runtime_keys():
    """英文字典与片段表里的 key 也要有繁中。

    这些 key 有的不是「源码里独立出现的字面量」（例如片段表里的 `私钥  `、` 台设备`），
    collect() 抓不到，只靠源码扫描会让繁中界面漏译。这里直接读两张表的 key 补进来。
    """
    out = set()
    for path in (EN, PHRASES):
        if not path.exists():
            continue
        for m in KT_KEY.finditer(path.read_text(encoding="utf-8")):
            key = m.group(1) if m.group(1) is not None else m.group(2)
            out.add(_unescape(key))
    # trFmt 模板整条（含 {0}）也要有繁中：整串命中优先于片段替换，避免半中半英
    for pattern in ("app/src/main/kotlin/**/*.kt", "core/src/main/kotlin/**/*.kt"):
        for path in SRC.glob(pattern):
            if "/test/" in str(path):
                continue
            for lit in TFMT.findall(path.read_text(encoding="utf-8")):
                if any("一" <= c <= "鿿" for c in lit):
                    out.add(_unescape(lit))
    return out


def main():
    items = list(dict.fromkeys(collect() + sorted(runtime_keys())))
    conv = opencc.OpenCC("s2twp")
    pairs = [(s, conv.convert(s)) for s in items if conv.convert(s) != s]
    # 常用单字词：源码里可能只作为长句的一部分出现，单独补上供 tr("网络") 这类调用命中
    base = [
        "网络", "信息", "视频", "设置", "备份", "恢复", "搜索", "文件", "数据", "软件", "硬件",
        "屏幕", "声音", "画面", "主题", "时钟", "网速", "缓存", "账号", "登录", "二维码",
        "上传", "下载", "删除", "清空", "刷新", "重试", "保存", "取消", "确认", "关闭",
        "打开", "返回", "继续", "暂停", "播放", "收藏", "观看", "记录", "历史", "剧集",
        "选集", "清晰度", "倍速", "弹幕", "屏保", "遥控", "手机", "电视", "首页", "排行",
        "本地", "片源", "快传", "关于", "使用", "说明", "系统", "语言", "默认", "当前",
        "正在", "完成", "失败", "成功", "错误", "提示", "等待", "检查", "更新", "安装",
        "权限", "存储", "云端", "本机", "自动", "手动", "无", "未", "已",
    ]
    existing = {s for s, _ in pairs}
    for w in base:
        if w not in existing and conv.convert(w) != w:
            pairs.append((w, conv.convert(w)))

    lines = [
        "// SPDX-License-Identifier: GPL-3.0-only",
        "// 自动生成：简→繁（opencc s2twp），由 scripts/gen-l10n-dict.py 维护，勿手工编辑",
        "package com.hongguotv.core",
        "",
        "internal object L10nDict {",
        "    /** 简中原文 → 繁中译文。只收录存在差异的条目，其余原文即译文。 */",
        "    val zhTW = mapOf(",
    ]
    for s, t in pairs:
        lines.append(f"        {kt(s)} to {kt(t)},")
    lines += ["    )", "}"]
    with OUT.open("w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"生成 {len(pairs)} 条繁中映射 → {OUT}")


if __name__ == "__main__":
    main()
