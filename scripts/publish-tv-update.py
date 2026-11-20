#!/usr/bin/env python3
"""Publish signed APK, update manifest, and checksums as a GitHub release."""
import hashlib, json, os, subprocess, sys

TAG = os.environ["UPDATE_TAG"]
VERSION_NAME = os.environ["HONGGUOTV_VERSION_NAME"]
VERSION_CODE = int(os.environ["HONGGUOTV_VERSION_CODE"])
APK_SRC = "kotlin-tv/app/build/outputs/apk/release/app-release.apk"
# 单包分发：APK 实际 minSdk 21（Android 5.0+），清单同样声明 21，Android 5-7 与 8+ 共用一个包。
# 文件名继续沿用 -android8.apk：≥0.30.9 的客户端两种后缀都认，但沿用旧名可以少一个变量，
# 也让历史上存过这个链接的人不落空（改成 -android5.apk 同样能过解析）。
APK_NAME = f"hongguotv-kotlin-{VERSION_NAME}-android8.apk"
# 清单 minSdk 必须与 APK 实际值一致：0.30.10 的客户端装前校验 `candidate.minSdkVersion != update.minSdk` 即拦截。
# 声明 21 让清单对所有设备放行（`minSdk <= 设备 sdk` 恒成立），从而一个包覆盖 Android 5.0+。
# ≤0.28.0 的旧客户端要求声明 26 且只认 -android8.apk，已放弃（2026-11-16 决定）。
MANIFEST_MIN_SDK = int(os.environ.get("HONGGUOTV_MANIFEST_MIN_SDK", "21"))
OUTPUTS = "outputs"
os.makedirs(OUTPUTS, exist_ok=True)
apk_path = os.path.join(OUTPUTS, APK_NAME)
subprocess.run(["cp", APK_SRC, apk_path], check=True)
sha256 = hashlib.sha256(open(apk_path, "rb").read()).hexdigest()
size = os.path.getsize(apk_path)

def changelog():
    prev = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", "kotlin-v*"],
                          capture_output=True, text=True)
    base = prev.stdout.strip() if prev.returncode == 0 else ""
    rng = [f"{base}..HEAD"] if base else ["-n", "20"]
    log = subprocess.run(["git", "log", "--no-merges", "--pretty=format:- %s"] + rng,
                         capture_output=True, text=True).stdout.strip()
    if not log:
        return "本次为重新构建，无新增提交。"
    return f"更新日志（自 {base} 起）：\n{log}" if base else f"更新日志：\n{log}"

notes = os.environ.get("RELEASE_NOTES", "").strip() or changelog()
# 更新说明会显示在电视弹窗里，长度必须可控（0.30.10 写了 16 条技术细节，把弹窗撑满）：
# 面向用户最多 8 条、每条一句，总量 1200 字封顶；超了直接失败，逼着写短话。
MAX_NOTES_CHARS = 1200
MAX_NOTES_ITEMS = 8
items = [ln for ln in notes.splitlines() if ln.strip()]
if len(notes) > MAX_NOTES_CHARS or len(items) > MAX_NOTES_ITEMS:
    sys.exit(f"RELEASE_NOTES 过长：{len(notes)} 字 / {len(items)} 行（上限 {MAX_NOTES_CHARS} 字 / {MAX_NOTES_ITEMS} 行）。"
             "面向用户的日志请压到 5 条以内、每条一句话，技术细节写进 docs/CHANGELOG.md。")
manifest = {
    "schema": 1,
    "packageName": "com.hongguotv.nativeapp",
    "versionCode": VERSION_CODE,
    "versionName": VERSION_NAME,
    "minSdk": MANIFEST_MIN_SDK,
    "tag": TAG,
    "apk": {"name": APK_NAME, "size": size, "sha256": sha256},
    "notes": notes,
}
json_path = os.path.join(OUTPUTS, "update.json")
with open(json_path, "w") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)

sha_path = os.path.join(OUTPUTS, "SHA256SUMS")
with open(sha_path, "w") as f:
    f.write(f"{sha256}  {APK_NAME}\n")

subprocess.run(["gh", "release", "create", TAG, "--title", f"红果 TV {VERSION_NAME}", "--notes",
    f"红果 TV {VERSION_NAME}\n\n{notes}", "--latest"], check=True)
for asset in [apk_path, json_path, sha_path]:
    subprocess.run(["gh", "release", "upload", TAG, asset, "--clobber"], check=True)
print(f"Published {TAG}: {APK_NAME} ({size} bytes, sha256={sha256[:16]}...)")