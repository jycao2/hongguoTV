# GitHub 自动发布与电视应用内更新

公开渠道：本仓库（`jycao2/hongguoTV`）的 GitHub Release。首次需要手动把带更新功能的正式 APK 安装到电视，此后不用逐次传文件。

## 电视上的行为

- 前台浏览时每 6 小时检查一次最新正式版；首次进入应用延迟 8 秒，避免抢首屏。播放或离开前台会取消更新请求，返回浏览页后可继续下载。
- 默认自动检查并下载，设置 → 版本与更新可以关闭，也可以手动检查。下载失败保留当前应用；部分文件清理，自动下载重试至少间隔 30 分钟。
- 下载完显示版本信息和更新日志，选择「安装新版」。第一次需允许本应用安装未知来源应用，之后调用系统安装确认。普通 Android 8 没有静默安装权限，仍须确认。
- 选择「稍后」推迟该版本提醒一天；不会中断正在播放的视频。同版本、旧版本和要求更高 Android 版本的安装包不会自动安装。
- APK 写入应用私有缓存，仅临时授权系统安装程序读取。下载使用 HTTPS、文件长度和 SHA-256 校验，并验证 APK 实际包名、版本号、最低 SDK 和当前安装应用的签名；不在 APK 中保存 GitHub 令牌。

## 从推送到发布

1. 向 `main` 推送原生客户端改动。
2. `release.yml` 从 Secrets 恢复签名密钥，设定本次版本（见下文版本规则），执行 JVM 测试、Release lint 和正式签名构建。
3. 构建成功后 `scripts/publish-tv-update.py` 创建 Release：标题为「红果 TV 版本号」，从上一个 `kotlin-v*` 标签到当前提交的提交记录自动生成更新日志，写入 Release 说明和 `update.json`；上传 APK、`update.json` 与 `SHA256SUMS`，并设为 latest。构建失败不替换旧版。
4. 电视通过本仓库 Release 的 `latest/download/update.json` 获取新版本，无需 GitHub 登录。

## 签名配置

配置动作需仓库所有者授权。本仓库的 Actions Secrets：

- `HONGGUOTV_KEYSTORE_B64`：发布 keystore 的 Base64 编码（0.9.2 起因原密钥遗失换用新密钥，此后各版本沿用）。
- `HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`：签名配置。

签名私钥只在运行签名构建的临时目录中恢复，任务结束删除；源码和 APK 均不包含这些凭据。不要在 APK 中嵌入 PAT。

## 版本规则与本地构建

- 云端 `versionCode = 10000 + release workflow run_number`，单调递增；显示版本为 `0.10.0+run_number`；Release 标签为 `kotlin-v0.10.0-build.run_number`，标题为「红果 TV 0.10.0+run_number」。
- 发布新版本前，将 `.github/workflows/release.yml` 中的版本号（当前 0.10.0）递增，并同步 `kotlin-tv/app/build.gradle.kts` 的默认 `versionName` 与 `kotlin-tv/README.md` 构建输出示例；调整版本显示名称时仍维持 versionCode 单调递增规则。
- 已发布构建重跑会因标签已存在而失败，不会覆盖原 APK；旧构建重跑也不能取代更新的 latest。
- 本地未传环境变量时生成 0.10.0 / versionCode 12，供首次验证。复现云端源码时，应从该 Release 的 `update.json` 读取版本，传入 `HONGGUOTV_VERSION_CODE` 和 `HONGGUOTV_VERSION_NAME`；本地低版本号的 APK 无法覆盖较高云端版本。正式分发以云端签名包为准。

电视系统没有安装器、禁止未知来源或无法访问 GitHub 时，自动更新不能完成；旧版仍可继续使用。最低 API 26 不等于 Android 8 实体电视已验收。
