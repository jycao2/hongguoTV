# 项目现状

更新日期：2026-11-10。

## 当前形态

- 客户端：`kotlin-tv/` —— **Kotlin + Android 原生 View + Media3** 的独立电视 APK，安装后联网直连内容站点。无自建服务端，无需 Node / Docker / 服务器地址。最低 Android 5.0（API 21），ARM32 / ARM64 / x86 / x86_64 通用。
- 当前版本：`0.30.10`（versionCode = 10400 + CI 构建编号）。版本改动见 [更新日志](CHANGELOG.md)，构建与发布细则见 [kotlin-tv/README](../kotlin-tv/README.md) 与 [自动更新机制](../kotlin-tv/AUTO_UPDATES.md)。
- 发版：手动触发 `.github/workflows/release.yml`，出双包——`-android8.apk`（minSdk 26，自动更新通道）与 `-android5.apk`（minSdk 21，Release 页手动安装）；Release 同步至 hongguoTV-updates 仓库。
- 配套仓库：[hongguoTV](https://github.com/jycao2/hongguoTV)（本仓库，电视端）· [hongguoTV-remote](https://github.com/jycao2/hongguoTV-remote)（手机遥控入口管理）· [hongguoTV-updates](https://github.com/jycao2/hongguoTV-updates)（版本更新、下载与 issue）。

## 主要能力（截至 0.30.10）

浏览（首页接着看 / 收藏有更新 / 稍后看 / 热门发现、题材分类、热播榜、随机荐剧）、搜索（键盘/联想/结果三栏同屏、扫码输入）、播放（自动连播、跨季连播、跳片头片尾、倍速、清晰度、定时停止、鼠标手势）、手机遥控（扫码直连、一句话指令、指令集帮助页）、片源（三方 CMS 源、聚合搜索、换源播放、测速优选）、本地库（WebDAV / NAS）、弹幕（官方弹幕源、关键词屏蔽）、收藏（分组、更新检查、红果云端收藏）、账号（扫码登录 + 手机号验证码登录、云端观看记录/收藏同步）、本地备份与自动恢复、应用内自动更新（含国内镜像兜底）、网络代理（实验室功能）。

功能明细与隐私说明见根目录 [README](../README.md)。

## 与 0.1.x React Native 方案的关系

项目最初（2026-09）采用 React Native TV 客户端 + 独立 Node 内容服务（Docker 部署，API v1 协议），v0.1.0 / v0.1.1 发布于 N3urda/hongguoTV Releases。该方案已被 Kotlin 原生直连方案整体取代，RN 工程与 Node 服务源码均不在本仓库，相关交付文档已随方案废弃移除；如需追溯可参考当时 Release 附件中的源码包。

## docs 文档索引

| 文档 | 内容 | 状态 |
| --- | --- | --- |
| [CHANGELOG.md](CHANGELOG.md) | 面向使用者的版本改动（0.28.0 基线起） | 随发版更新 |
| [ACCOUNT_SYNC.md](ACCOUNT_SYNC.md) | 红果账号登录与云端同步 | 扫码/手机号登录、手动同步均已实现 |
| [THIRD_SOURCES.md](THIRD_SOURCES.md) | 三方片源模块原始设计与二期规划 | 已实现（0.20.0 起），二期未做 |
| [LOCAL_BACKUP_RESTORE.md](LOCAL_BACKUP_RESTORE.md) | 本地备份与自动恢复设计 | 已实现 |
| [ALIYUN_DRIVE_BACKUP.md](ALIYUN_DRIVE_BACKUP.md) | 阿里云盘云备份方案 | 待评审，未实施 |
| [MAINACTIVITY_SPLIT_PLAN.md](MAINACTIVITY_SPLIT_PLAN.md) | MainActivity 渐进式拆分计划 | 已评审，暂缓实施 |
| [CARD_METRICS_FEASIBILITY.md](CARD_METRICS_FEASIBILITY.md) | 评分/收藏数展示可行性报告 | 方案 1 已实现，方案 2 否决 |
| [SHORT_DRAMA_PLATFORM_RESEARCH.md](SHORT_DRAMA_PLATFORM_RESEARCH.md) | 接入其他短剧平台调研 | 调研结论仍有效 |
| [voice-cheatsheet.md](voice-cheatsheet.md) | 语音指令速查表（脚本生成，勿手改） | 随词表重新生成 |
| [xiaoai-voice.md](xiaoai-voice.md) | 巴法云语音链路搭建指南 | 可选自建链路，保留参考 |
| handover/ | 会话交接文档 | 未跟踪入库与否待定 |
