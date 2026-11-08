# -*- coding: utf-8 -*-
"""小爱同学 → 红果TV：把小爱听到的剧名写进巴法云主题。

跑在小米小爱开放平台的「函数计算」技能里，不需要自己的服务器、域名、内网穿透。
平台把语音识别结果解析成 JSON 丢进来，原文在 `query`；这里洗干净动作词，交给巴法云，
电视订阅着那个主题，收到文本就开始搜。

为什么能这么走：米家只允许「打开/关闭设备」，指令里没有文本字段，所以固定昵称那条路
永远只能喊预设的几个名字。而巴法云的 msg 字段接受任意文本，小爱技能又能拿到原始识别
文本，两件事凑在一起，就是任意剧名。

改这里之前先看 docs/xiaoai-voice.md 的「任意剧名」一节。
"""

import json
import re
import urllib.request

# ---- 两处必填：巴法云私钥 + 主题名 ----
UID = "在这里填巴法云私钥"
TOPIC = "hgplay1001"

PUSH_URL = "https://apis.bemfa.com/va/postJsonMsg"

# 句首的动作词。只收明显不是片名开头的词：
# - 单字「放」「看」会误伤真剧名（放羊的星星 / 看见味道的你），不碰
# - 「打开」「请」「给我」同样有真片名（打开生活的正确方式 / 请回答1988 / 给我一支烟），不碰
# 剥错了也只是搜不到并提示，不会播错片，但仍然宁少勿多。
LEAD = (
    "麻烦你帮我", "麻烦帮我", "麻烦你", "麻烦", "帮我",
    "我想看一下", "我想看看", "我要看一下", "我要看看",
    "我想看", "我要看", "我想听", "我要听",
    "播放一下", "搜索一下", "查一下", "看一下", "看看", "来一集", "来点",
    "播放", "搜索", "搜一下", "查找",
)

# 句尾的客套与语气，剥掉不影响片名。
TAIL = (
    "这部电视剧", "这部片子", "这部电影", "这部", "的电视剧", "的电视",
    "吧", "啊", "呀", "呢", "哦", "呗",
    "。", "，", "！", "？", ".", ",", "!", "?", " ", "\u3000",
)

# 单字动词后面紧跟空格，就是口头分隔（「看 狂飙」），可以安全剥掉；
# 没有空格时说明它可能是片名的一部分，不动。
VERB_GAP = re.compile(r"^[看放搜找听]\s+")

QUIT = ("退出", "不看了", "算了", "关闭技能")


def clean(raw):
    """识别文本 → 片名。剥多了也能播，剥错了会播错片，所以只剥确定的。"""
    text = (raw or "").strip()
    if not text:
        return ""

    # 长词优先：「我要看」比「我要」先匹配上
    for word in sorted(LEAD, key=len, reverse=True):
        if text.startswith(word):
            rest = text[len(word):].strip()
            # 剥完只剩一两个字，多半是把片名本身剥掉了，退回原文
            if len(rest) >= 2:
                text = rest
            break

    text = VERB_GAP.sub("", text, count=1)

    changed = True
    while changed:
        changed = False
        for word in TAIL:
            if text.endswith(word) and len(text) - len(word) >= 2:
                text = text[: -len(word)].strip()
                changed = True
    return text


def _from_dict(event):
    """不开小爱 SDK 时的兜底取值：不同版本的 event 字段位置不完全一样。"""
    candidates = [event.get("query")]
    request = event.get("request") or {}
    candidates.append(request.get("query"))
    intent = request.get("intent") or {}
    candidates.append(intent.get("query"))
    slots = (request.get("slot_info") or {}).get("slots") or intent.get("slots") or []
    for slot in slots:
        if isinstance(slot, dict):
            candidates.append(slot.get("value"))
    for value in candidates:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def heard(event):
    """拿到用户说的原话。优先用小爱 SDK，取不到就自己从 dict 里挖。"""
    try:
        req = xiaoai_request(event)  # noqa: F821 —— 平台内置，本地跑不了
        text = getattr(req, "query", "") or ""
        if text.strip():
            return text.strip()
    except Exception:
        pass
    return _from_dict(event)


def push(text):
    """把片名写进巴法云主题。电视那边订阅着它，载荷就是这条 msg。"""
    body = json.dumps(
        {"uid": UID, "topic": TOPIC, "type": 1, "msg": text},
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        PUSH_URL, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=3) as response:
        result = json.loads(response.read().decode("utf-8"))
    if result.get("code") != 0:
        raise RuntimeError("巴法云返回 %s" % result)
    return result


def reply(text, end=False):
    return {
        "version": "1.0",
        "is_session_end": end,
        "response": {
            "open_mic": False,
            "to_speak": {"type": 0, "text": text},
            "to_display": {"type": 0, "text": text},
        },
    }


def main(event):
    raw = heard(event)
    return handle(raw)


def handle(raw):
    """拆出来是为了本地能直接跑：python main.py 播放黄石第五季"""
    if not raw:
        return reply("你要看什么？再说一次片名就行。")

    text = clean(raw)
    if not text:
        return reply("没听清，再说一次片名。")
    if text in QUIT:
        return reply("好的，已退出。", end=True)
    if len(text) > 40:
        return reply("名字有点长，说短一点试试？")

    try:
        push(text)
    except Exception as error:
        # 播报里带上原因，出问题时不至于只能猜
        return reply("电视没连上，稍后再试。%s" % error)

    return reply("好的，去电视上找%s" % text)


if __name__ == "__main__":
    import sys

    # 本地自测：只验证清洗逻辑，按 Ctrl+C 不会真的去推
    for line in (sys.argv[1:] or ["播放黄石第五季", "我想看放羊的星星", "搜索一下庆余年吧"]):
        print("%-14s → %s" % (line, clean(line)))
