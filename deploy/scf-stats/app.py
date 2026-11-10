# -*- coding: utf-8 -*-
"""红果TV 匿名安装统计 Web 函数（腾讯云 SCF）。

GET /ping?u=<匿名UUID>&v=<版本名>&c=<版本号>&a=<设备键哈希>&s=<API级别>：校验参数后输出一行 JSON 日志，
其中 a / s 自 0.30.11 起可选上报（a = ANDROID_ID 的 SHA-256 前 16 位，用于跨重装去重；s = Build.VERSION.SDK_INT）。
由 SCF 关联的 CLS 日志主题采集，在 CLS 控制台用 SQL 去重统计累计安装/活跃/版本分布。
GET /update_fail?u=&f=&t=&s=：更新失败上报（当前版本、目标版本、失败阶段）。
GET /health：健康检查。仅记录随机安装 ID 与版本号，不含任何个人信息。
"""
import json
import re
import time

from flask import Flask, jsonify, request

app = Flask(__name__)
UUID_RE = re.compile(r'^[A-Za-z0-9_-]{8,64}$')
VER_RE = re.compile(r'^[0-9A-Za-z.+:-]{1,32}$')
DEVICE_KEY_RE = re.compile(r'^[0-9a-f]{16}$')
STAGES = {'parse', 'download', 'verify', 'install'}


@app.route('/ping', methods=['GET'])
def ping():
    u = (request.args.get('u') or '').strip()
    v = (request.args.get('v') or '').strip()
    c = (request.args.get('c') or '').strip()
    a = (request.args.get('a') or '').strip()
    s = (request.args.get('s') or '').strip()
    if not UUID_RE.match(u) or not VER_RE.match(v) or (c and not c.isdigit()):
        return jsonify(ok=False), 400
    row = {'evt': 'ping', 'u': u, 'v': v, 'c': int(c) if c else 0, 'ts': int(time.time())}
    # a / s 是 0.30.11 起新增的可选字段：老客户端不发，新客户端发 ANDROID_ID 哈希与 SDK_INT。
    # 格式不对就丢弃该字段，不拒整条请求——宁可这一条少一个维度，也不能让统计断流。
    if DEVICE_KEY_RE.match(a):
        row['a'] = a
    if s.isdigit() and 21 <= int(s) <= 100:
        row['s'] = int(s)
    print(json.dumps(row, ensure_ascii=False), flush=True)
    resp = jsonify(ok=True)
    resp.headers['Cache-Control'] = 'no-store'
    return resp


@app.route('/update_fail', methods=['GET'])
def update_fail():
    """更新失败上报：u 安装 ID、f 当前版本、t 目标版本、s 失败阶段（parse/download/verify/install）。"""
    u = (request.args.get('u') or '').strip()
    f = (request.args.get('f') or '').strip()
    t = (request.args.get('t') or '').strip()
    s = (request.args.get('s') or '').strip()
    if not UUID_RE.match(u) or not VER_RE.match(f) or not VER_RE.match(t) or s not in STAGES:
        return jsonify(ok=False), 400
    print(json.dumps({'evt': 'update_fail', 'u': u, 'f': f, 't': t, 's': s, 'ts': int(time.time())},
                     ensure_ascii=False), flush=True)
    resp = jsonify(ok=True)
    resp.headers['Cache-Control'] = 'no-store'
    return resp


@app.route('/health', methods=['GET'])
def health():
    return 'ok'


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=9000)
