# -*- coding: utf-8 -*-
"""小爱同学 → 红果TV：把听到的那句话原样写进巴法云主题。

跑在小米小爱开放平台的「函数计算」技能里，不需要自己的服务器、域名、内网穿透。
电视订阅着那个主题，收到文本自己去理解——**这里不做任何理解**。

为什么不在云端洗词：电视端知道「现在停在哪一屏、这个列表里有哪些条目」，
同一句「第二个」在首页和在历史页意思完全不同。云端只有一句话，剥掉动词就等于
把仅有的线索也扔了（「我要看历史记录第二个」里，真正有用的是「历史记录」和「第二个」）。
两边各有一套词表还会互相打架，所以清洗全部放在电视端的 VoiceIntent 里。

改这里之前先看 docs/xiaoai-voice.md。
"""

import json
import urllib.request

# ---- 两处必填：巴法云私钥 + 主题名 ----
UID = "在这里填巴法云私钥"
TOPIC = "hgplay1001"

PUSH_URL = "https://apis.bemfa.com/va/postJsonMsg"

# 结束语：让技能退出的说法
QUIT = ("退出", "不看了", "算了", "关闭技能", "退下吧")

# 一句话的上限。电视端协议 80 字，超了直接拒，别让巴法云里存一条没人认的脏数据。
MAX_CHARS = 80


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
    """把那句话写进巴法云主题。电视那边订阅着它，载荷就是这条 msg。"""
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
    return handle(heard(event))


def handle(raw):
    """拆出来是为了本地能直接跑：python main.py 检查有没有更新"""
    text = (raw or "").strip()
    if not text:
        return reply("你说什么？再说一次。")
    if text in QUIT:
        return reply("好的，已退出。", end=True)
    if len(text) > MAX_CHARS:
        return reply("这句话太长了，说短一点。")

    try:
        push(text)
    except Exception as error:
        # 播报里带上原因，出问题时不至于只能猜
        return reply("电视没连上，稍后再试。%s" % error)

    # 不报「好的，去电视上找 X」：现在这句话可能是「关掉弹幕」，报剧名会很怪。
    # 真正的反馈在电视上（每个动作都有自己的回执），这里只确认收到了。
    return reply("好")


if __name__ == "__main__":
    import sys

    # 本地自测：只验证「收到什么就发什么」，不联网。
    # 真正的理解在电视端，这里不该有任何改写动作。
    for line in (sys.argv[1:] or ["检查有没有更新", "我要看历史记录第二个", "关掉弹幕"]):
        payload = (line or "").strip()
        print("%-16s → push(%r)" % (line, payload))
