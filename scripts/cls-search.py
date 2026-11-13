# -*- coding: utf-8 -*-
"""通过腾讯云 CLS SearchLog API 执行分析 SQL（免开控制台）。

用法：
    python scripts/cls-search.py --sql "SELECT ..." [--hours 168]
    python scripts/cls-search.py --sql-file <file> [--hours 168]

凭据复用本机 tccli 配置（~/.tccli/default.credential），不落库。
Topic 与地域见 deploy/scf-stats/cls-queries.md。
"""
import argparse
import datetime
import json
import os
import re
import sys

import requests
from tencentcloud.common import credential
from tencentcloud.common.profile.client_profile import ClientProfile
from tencentcloud.common.profile.http_profile import HttpProfile
from tencentcloud.cls.v20201016 import cls_client, models

TOPIC_ID = "54038a8e-4355-4706-8436-648636fde31e"
REGION = "ap-guangzhou"


def load_credentials():
    cred_path = os.path.expanduser("~/.tccli/default.credential")
    text = open(cred_path, encoding="utf-8").read()
    sid = re.search(r"secretId\s*=\s*(\S+)", text).group(1)
    skey = re.search(r"secretKey\s*=\s*(\S+)", text).group(1)
    return sid, skey


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--sql")
    g.add_argument("--sql-file")
    ap.add_argument("--hours", type=int, default=168,
                    help="回溯小时数（默认 168 = 7 天，CLS 仅保留 7 天日志）")
    args = ap.parse_args()

    sql = args.sql or open(args.sql_file, encoding="utf-8").read().strip()
    to_ms = int(datetime.datetime.now().timestamp() * 1000)
    from_ms = to_ms - args.hours * 3600 * 1000

    sid, skey = load_credentials()
    http = HttpProfile(endpoint="cls.tencentcloudapi.com")
    prof = ClientProfile(httpProfile=http)
    client = cls_client.ClsClient(credential.Credential(sid, skey, ""), REGION, prof)
    req = models.SearchLogRequest()
    req.TopicId = TOPIC_ID
    req.From = from_ms
    req.To = to_ms
    req.QueryString = f"* | {sql}"
    req.QuerySyntax = 1
    req.UseNewAnalysis = True

    resp = client.SearchLog(req)
    data = json.loads(resp.to_json_string())
    for rec in data.get("AnalysisRecords") or []:
        try:
            print(json.dumps(json.loads(rec), ensure_ascii=False))
        except json.JSONDecodeError:
            print(rec)
    if not data.get("AnalysisRecords"):
        for r in data.get("Results") or []:
            print(r.get("LogJson", ""))
    print(f"-- TotalCount={data.get('TotalCount')}", file=sys.stderr)


if __name__ == "__main__":
    main()
