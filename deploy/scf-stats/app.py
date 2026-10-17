# -*- coding: utf-8 -*-
"""红果TV 匿名安装统计 Web 函数（腾讯云 SCF）。

GET /ping?u=<匿名UUID>&v=<版本名>&c=<版本号>：校验参数后输出一行 JSON 日志，
由 SCF 关联的 CLS 日志主题采集，在 CLS 控制台用 SQL 去重统计累计安装/活跃/版本分布。
GET /health：健康检查。仅记录随机安装 ID 与版本号，不含任何个人信息。
"""
import json
import re
import time

from flask import Flask, jsonify, request

app = Flask(__name__)
UUID_RE = re.compile(r'^[A-Za-z0-9_-]{8,64}$')
VER_RE = re.compile(r'^[0-9A-Za-z.+:-]{1,32}$')


@app.route('/ping', methods=['GET'])
def ping():
    u = (request.args.get('u') or '').strip()
    v = (request.args.get('v') or '').strip()
    c = (request.args.get('c') or '').strip()
    if not UUID_RE.match(u) or not VER_RE.match(v) or (c and not c.isdigit()):
        return jsonify(ok=False), 400
    print(json.dumps({'evt': 'ping', 'u': u, 'v': v,
                      'c': int(c) if c else 0, 'ts': int(time.time())},
                     ensure_ascii=False), flush=True)
    resp = jsonify(ok=True)
    resp.headers['Cache-Control'] = 'no-store'
    return resp


@app.route('/health', methods=['GET'])
def health():
    return 'ok'


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=9000)
