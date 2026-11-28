#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""导出未收录进英文字典的 UI 文案清单，供志愿者协助翻译（2026-11-28 台湾用户反馈）。

用法：
    python scripts/gen-l10n-missing.py

产出：outputs/l10n-missing.md
- 每行一条：中文原文 | 当前繁中（opencc s2twp 自动转换结果）| 英文（留空待填）
- 志愿者填好英文列回传后，把条目补进 core/src/main/kotlin/com/hongguotv/core/L10nEnglish.kt
- 繁中字典由 opencc 自动生成，无需翻译；若对个别台湾用词不满意，可在此文件
  的「繁中用词建议」列直接改（改完一并人工收录进 gen-l10n-dict.py 的覆盖表）。

复用 gen-l10n-dict.py 的提取逻辑（collect / runtime_keys / _unescape）。
"""
import sys
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location(
    "gen_l10n_dict", ROOT / "scripts" / "gen-l10n-dict.py"
)
g = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(g)

OUT = ROOT / "outputs" / "l10n-missing.md"


def english_keys():
    """L10nEnglish 里已收录的中文 key。"""
    return set(g.runtime_keys()) if False else _keys_from(g.EN)


def _keys_from(path):
    out = set()
    if not path.exists():
        return out
    import re
    text = path.read_text(encoding="utf-8")
    for m in g.KT_KEY.finditer(text):
        # 英文字典行是 "key" to "value"；片段表是 "key",——这里只要英文字典
        if m.group(1) is not None:
            out.add(g._unescape(m.group(1)))
        else:
            out.add(g._unescape(m.group(2)))
    return out


def main():
    try:
        import opencc
    except ImportError:
        raise SystemExit("需要 opencc-python-reimplemented：pip install opencc-python-reimplemented")
    conv = opencc.OpenCC("s2twp")
    keys = list(dict.fromkeys(g.collect() + sorted(g.runtime_keys())))
    en = _keys_from(g.EN)

    def translatable(s: str) -> bool:
        # 插值模板拆出的碎片（「》第」「中…」）没有独立翻译价值，过滤：至少 2 个汉字
        han = sum(1 for c in s if "一" <= c <= "鿿")
        return han >= 2

    missing = [k for k in keys if k not in en and translatable(k)]
    missing.sort(key=lambda s: (len(s), s))
    lines = [
        "# 未收录英文翻译的 UI 文案清单",
        "",
        f"生成时间：{__import__('datetime').datetime.now().strftime('%Y-%m-%d %H:%M')}  ",
        f"共 {len(missing)} 条（英文覆盖 {len(en & set(keys))} / {len(keys)}）。",
        "",
        "说明：",
        "- **英文列**留空待填，填好后整表回传，会被收录进 `L10nEnglish.kt`。",
        "- **繁中列**是 opencc 自动转换结果，无需翻译；如有台湾用词建议，直接改这一列并注明。",
        "- 剧名、题材等接口下发内容不在清单里（由 App 内 s2t 显示层自动繁化）。",
        "",
        "| 中文原文 | 当前繁中 | 英文 |",
        "|---|---|---|",
    ]
    for k in missing:
        lines.append(f"| {k.replace('|', '｜')} | {conv.convert(k).replace('|', '｜')} | |")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"共 {len(missing)} 条缺失（英文覆盖 {len(en & set(keys))}/{len(keys)}）→ {OUT}")


if __name__ == "__main__":
    main()
