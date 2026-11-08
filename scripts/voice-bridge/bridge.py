#!/usr/bin/env python3
"""红果TV 语音/自动化桥：把一句中文话术翻成电视遥控命令。

为什么需要它：米家/小爱不能直接给局域网设备发 HTTP，能发 HTTP 的那一环
（Home Assistant、快捷指令、巴法云回调、Node-RED……）又只会传一句纯文本。
这个进程把「我要看黄石第五季」「打开片源」翻成电视认的 JSON 命令：

    POST <电视地址>/cmd   {"cmd":"watch","query":"黄石第五季"}
    POST <电视地址>/cmd   {"cmd":"open","module":"source"}

只依赖标准库，任何有 Python 3.8 的机器（NAS、树莓派、常开的电脑）都能跑。

用法：
    python bridge.py                     # 读同目录 bridge.json
    python bridge.py --tv http://192.168.31.38:40749/<token> --port 8817
    python bridge.py --selftest          # 只跑话术解析，不联网

调整见 README.md。电视地址在电视「设置 → 设备与数据 → 遥控地址」里。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "bridge.json")

# ---------------------------------------------------------------- 话术解析

# 模块词表：越具体的放前面（「最近观看」要在「观看」之前判掉）
MODULE_WORDS = {
    "首页": "home", "主页": "home", "主界面": "home",
    "搜索": "search", "搜片": "search",
    "排行榜": "rank", "榜单": "rank", "排行": "rank",
    "收藏": "favorite", "我的收藏": "favorite",
    "最近观看": "history", "观看历史": "history", "历史记录": "history", "历史": "history",
    "设置": "settings", "设定": "settings",
    "片源": "source", "三方源": "source",
    "本地库": "library", "本地": "library", "网盘": "library",
}

# 导航动词：出现在模块词前面时，整句按「跳模块」处理
NAV_VERBS = ("打开", "进入", "跳到", "跳转", "切到", "切换到", "回到", "回到", "去", "看下", "看看")

# 播放动词：按长度倒序匹配，先吃掉「开播」再轮到「播」
PLAY_VERBS = ("开播", "播放", "我要看", "我想看", "想看", "放一下", "来一集", "看", "放", "播")

# 话术里的口水词，剥掉后剩下的才是剧名
NOISE = (
    "小爱同学", "你好", "麻烦", "帮我", "帮忙", "给我", "请", "能不能", "可不可以",
    "我要", "我想", "现在", "马上", "一集", "第一集", "第1集", "吧", "呗", "呀", "啊", "哦", "呢",
)

_PUNCT = re.compile(r"[\s，。！？、,.!?~～…:：;；\"'“”‘’（）()\[\]【】《》〈〉]+")


def _clean(text: str) -> str:
    return _PUNCT.sub("", text or "")


def parse_intent(text: str):
    """一句话 → (kind, value)。

    kind: "open"（value 是模块键）/ "watch"（value 是片名）/ None（听不懂）。
    纯函数，便于单测；这里刻意不做模糊联想，宁可听不懂也不要把人送到错的地方。
    """
    raw = _clean(text)
    if not raw:
        return None, None
    for word in NOISE:
        raw = raw.replace(word, "")
    if not raw:
        return None, None

    # 1) 「打开片源」「回到首页」这类：导航动词 + 模块词
    for verb in NAV_VERBS:
        if raw.startswith(verb):
            rest = raw[len(verb):]
            for name in sorted(MODULE_WORDS, key=len, reverse=True):
                if rest == name or rest.startswith(name):
                    return "open", MODULE_WORDS[name]
            break

    # 2) 整句就是一个模块名（「片源」「排行榜」）
    if raw in MODULE_WORDS:
        return "open", MODULE_WORDS[raw]

    # 3) 播放动词 + 剧名；剥掉动词后如果剩下的是模块名，仍按跳模块处理
    for verb in PLAY_VERBS:
        if raw.startswith(verb):
            rest = raw[len(verb):].lstrip("一下个部集")
            if not rest:
                continue
            if rest in MODULE_WORDS:
                return "open", MODULE_WORDS[rest]
            return "watch", rest

    # 4) 没有动词：短词当剧名直接搜（「黄石第五季」），但别把无意义的单字丢出去
    if len(raw) >= 2 and raw not in NAV_VERBS and raw not in PLAY_VERBS:
        return "watch", raw
    return None, None


# ---------------------------------------------------------------- 电视调用


def tv_url_from_config() -> str:
    env = os.environ.get("HONGGUO_TV_URL")
    if env:
        return env.rstrip("/")
    try:
        with open(CONFIG_PATH, encoding="utf-8") as handle:
            data = json.load(handle)
        return str(data.get("tv", "")).rstrip("/")
    except FileNotFoundError:
        return ""
    except Exception as problem:  # 配置写坏了要看得见，而不是静默变成空地址
        print(f"[bridge] bridge.json 读取失败：{problem}", file=sys.stderr)
        return ""


def send_command(tv: str, payload: dict, timeout: float = 6.0):
    """把命令 POST 给电视。返回 (http_status, 说明)。"""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        f"{tv}/cmd", data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read(200).decode("utf-8", "replace")
    except urllib.error.HTTPError as problem:
        return problem.code, (problem.reason or "")
    except urllib.error.URLError as problem:
        return 0, f"连不上电视：{problem.reason}"
    except Exception as problem:
        return 0, f"{problem.__class__.__name__}: {problem}"


def health(tv: str, timeout: float = 3.0):
    """探活：/resume 是不带副作用的最小读接口，能拿到 200 就说明地址与 token 都对。"""
    try:
        with urllib.request.urlopen(f"{tv}/resume", timeout=timeout) as response:
            return response.status == 200
    except Exception:
        return False


# ---------------------------------------------------------------- HTTP 服务


class Handler(BaseHTTPRequestHandler):
    server_version = "HongguoVoiceBridge/1.0"
    tv = ""
    key = ""

    def log_message(self, fmt, *args):  # 默认日志太吵，只留一行结论
        sys.stderr.write("[bridge] %s\n" % (fmt % args))

    def _reply(self, status: int, payload: dict):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorized(self, key: str) -> bool:
        return not self.key or key == self.key

    def _run(self, kind: str, value: str):
        if not self.tv:
            return self._reply(500, {"ok": False, "error": "还没配置电视地址，见 README"})
        if kind == "open":
            payload = {"cmd": "open", "module": value}
        elif kind == "watch":
            payload = {"cmd": "watch", "query": value}
        else:
            return self._reply(422, {"ok": False, "error": "听不懂这句话"})
        status, detail = send_command(self.tv, payload)
        self._reply(200 if status == 200 else 502, {
            "ok": status == 200, "intent": kind, "value": value,
            "command": payload, "tv_status": status, "tv_reply": detail,
        })

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if not self._authorized((query.get("key") or [""])[0]):
            return self._reply(403, {"ok": False, "error": "key 不对"})
        if parsed.path in ("/", "/health"):
            return self._reply(200, {"ok": True, "tv": bool(self.tv), "tv_online": health(self.tv) if self.tv else False})
        if parsed.path == "/open":
            return self._run("open", (query.get("module") or query.get("m") or [""])[0])
        if parsed.path == "/watch":
            return self._run("watch", (query.get("q") or query.get("query") or [""])[0])
        if parsed.path == "/say":
            text = (query.get("text") or query.get("t") or query.get("q") or [""])[0]
            kind, value = parse_intent(text)
            return self._run(kind, value)
        self._reply(404, {"ok": False, "error": "未知路径"})

    def do_POST(self):
        length = min(int(self.headers.get("Content-Length") or 0), 64 * 1024)
        raw = self.rfile.read(length) if length else b""
        try:
            payload = json.loads(raw.decode("utf-8"))
            text = str(payload.get("text", ""))
            if not self._authorized(str(payload.get("key", ""))):
                return self._reply(403, {"ok": False, "error": "key 不对"})
        except Exception:
            text = raw.decode("utf-8", "replace")
        kind, value = parse_intent(text)
        self._run(kind, value)


def selftest():
    cases = [
        ("小爱同学，打开片源", ("open", "source")),
        ("打开设置", ("open", "settings")),
        ("回到首页", ("open", "home")),
        ("我想看黄石第五季", ("watch", "黄石第五季")),
        ("播放《黄石 第五季》", ("watch", "黄石第五季")),
        ("开播文盲修仙吧", ("watch", "文盲修仙")),
        ("我要看最近观看", ("open", "history")),
        ("排行榜", ("open", "rank")),
        ("黄石第五季", ("watch", "黄石第五季")),
        ("", (None, None)),
        ("打开", (None, None)),
    ]
    failed = 0
    for text, expect in cases:
        got = parse_intent(text)
        flag = "ok " if got == expect else "FAIL"
        if got != expect:
            failed += 1
        print(f"{flag} {text!r} -> {got}  期望 {expect}")
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description="红果TV 语音/自动化桥")
    parser.add_argument("--tv", default="", help="电视地址，形如 http://192.168.31.38:40749/<token>")
    parser.add_argument("--port", type=int, default=0, help="本机监听端口，默认 8817")
    parser.add_argument("--bind", default="0.0.0.0", help="监听地址，默认所有网卡")
    parser.add_argument("--selftest", action="store_true", help="只跑话术解析自检")
    args = parser.parse_args()

    if args.selftest:
        return selftest()

    tv = (args.tv or tv_url_from_config()).rstrip("/")
    Handler.tv = tv
    try:
        with open(CONFIG_PATH, encoding="utf-8") as handle:
            Handler.key = str(json.load(handle).get("key", ""))
    except Exception:
        Handler.key = ""
    port = args.port
    if not port:
        port = 8817
        try:
            with open(CONFIG_PATH, encoding="utf-8") as handle:
                port = int(json.load(handle).get("port", 8817))
        except Exception:
            pass

    if not tv:
        print("[bridge] 没有电视地址：用 --tv 或 bridge.json 里的 tv 字段填上（电视设置里的「遥控地址」）", file=sys.stderr)
    elif not health(tv):
        print(f"[bridge] 电视地址 {tv} 暂时连不上：确认电视开机、同网段、地址没换", file=sys.stderr)

    server = ThreadingHTTPServer((args.bind, port), Handler)
    print(f"[bridge] 监听 http://{args.bind}:{port}/say?text=…  电视 {tv or '(未配置)'}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[bridge] 退出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
