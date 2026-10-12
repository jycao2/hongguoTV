# GitHub 自动发布与电视应用内更新

公开渠道：`jycao2/hongguoTV-updates`。原开发仓库仍为私有。首次需要手动把带更新功能的正式 APK 安装到电视，此后不用逐次传文件。

## 电视上的行为

- 前台浏览时每 6 小时检查一次最新正式版；首次进入应用延迟 8 秒，避免抢首屏。播放或离开前台会取消更新请求，返回浏览页后可继续下载。
- 默认自动检查并下载，设置 → 版本与更新可以关闭，也可以手动检查。下载失败保留当前应用；部分文件清理，自动下载重试至少间隔 30 分钟。
- 下载完显示版本信息，选择「安装新版」。第一次需允许本应用安装未知来源应用，之后调用系统安装确认。普通 Android 8 没有静默安装权限，仍须确认。
- 选择「稍后」推迟该版本提醒一天；不会中断正在播放的视频。同版本、旧版本和要求更高 Android 版本的安装包不会自动安装。
- APK 写入应用私有缓存，仅临时授权系统安装程序读取。下载使用 HTTPS、文件长度和 SHA-256 校验，并验证 APK 实际包名、版本号、最低 SDK 和当前安装应用的签名；不在 APK 中保存 GitHub 令牌。

## 从推送到发布

1. 向私有仓库 `codex/kotlin-standalone` 推送原生客户端改动。
2. `kotlin-public-sync.yml` 导出 HEAD 中跟踪的原生工程、构建文件及许可证到公开仓库 main。不会复制私有 Git 历史、未跟踪文件、构建目录或本地签名材料。
3. 公开仓库 `release.yml` 执行 JVM 测试、Release lint 和正式签名构建。
4. 构建成功后，先创建草稿、上传 APK、源码 ZIP、`update.json` 与 `SHA256SUMS`，确认 GitHub 附件摘要一致，再正式发布并设为 latest。构建失败不替换旧版。
5. 电视通过公开 Release 的 `latest/download/update.json` 获取新版本，无需 GitHub 登录。

同步只对原生代码、构建和同步流程相关路径触发；不因旧 React Native 服务或无关研究资料的提交发布 APK。旧的验证 workflow 继续保留。

## 签名与同步配置

配置动作需仓库所有者授权。公开仓库的 Actions Secrets：

- `HONGGUOTV_KEYSTORE_B64`：与旧正式版相同的 keystore 的 Base64 编码。
- `HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`：原签名配置。

私有仓库的 `TV_UPDATES_DEPLOY_KEY` 是独立 SSH 私钥，对应公钥只授予公开更新仓库写入权限。签名私钥只在运行签名构建的临时目录中恢复，任务结束删除；源码和 APK 均不包含这些凭据。

完成授权及凭据配置后，将私有仓库 Actions variable `TV_UPDATES_ENABLED` 设为 `true`，下一次相关推送即启用同步。在未启用时，同步任务跳过，常规构建验证照常执行。

不要改成在 APK 中嵌入 PAT，也不要向公开仓库同步私有 Git 历史。撤销自动发布时可禁用同步工作流并删除该部署密钥；签名 Secrets 可以单独撤销。

## 版本规则与本地构建

云端 `versionCode = 10000 + release workflow run_number`，显示版本为 `0.9.0+run_number`。同一次已发布构建的重跑不覆盖原 APK；旧构建重跑也不能取代更新的 latest。保留此工作流的递增编号，后续调整版本显示名称时仍维持 versionCode 单调递增。

本地未传环境变量时生成 0.9.0 / versionCode 9，供首次验证。复现云端源码时，应从该 Release 的 `update.json` 读取版本，传入 `HONGGUOTV_VERSION_CODE` 和 `HONGGUOTV_VERSION_NAME`；本地低版本号的 APK 无法覆盖较高云端版本。正式分发以云端签名包为准。

电视系统没有安装器、禁止未知来源或无法访问 GitHub 时，自动更新不能完成；旧版仍可继续使用。最低 API 26 不等于 Android 8 实体电视已验收。
