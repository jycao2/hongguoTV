#!/usr/bin/env python3
"""Publish signed APK, update manifest, and checksums as a GitHub release."""
import hashlib, json, os, subprocess, sys

TAG = os.environ["UPDATE_TAG"]
VERSION_NAME = os.environ["HONGGUOTV_VERSION_NAME"]
VERSION_CODE = int(os.environ["HONGGUOTV_VERSION_CODE"])
APK_SRC = "kotlin-tv/app/build/outputs/apk/release/app-release.apk"
# APK 文件名必须保持 -android8.apk：0.28.0 及更早的存量客户端只认这个后缀，
# 改成 -android5.apk 会让所有旧客户端报「更新信息格式不正确」而无法自动更新
# （0.30.9 起新客户端两种后缀都认）。等存量旧客户端消化完再考虑改回 android5。
APK_NAME = f"hongguotv-kotlin-{VERSION_NAME}-android8.apk"
# 清单 minSdk 与更新通道 APK 的实际 minSdk 必须一致（MANIFEST_MIN_SDK=26 配合默认构建）：
# 0.28.0 起的客户端安装前校验 `candidate.minSdkVersion != update.minSdk` 即拦截，
# 且旧客户端解析要求 minSdk in 26..100，声明 21 连清单都过不了。
# Android 5-7 兼容包（实际 minSdk 21）只挂 Release 页供手动安装，不进更新通道。
MANIFEST_MIN_SDK = int(os.environ.get("HONGGUOTV_MANIFEST_MIN_SDK", "26"))
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