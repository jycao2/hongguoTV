# 0.1.x React Native 方案存档

2026-09 项目首版采用 **React Native TV + 独立 Node 内容服务** 的架构，该方案已被 Kotlin 原生直连方案整体取代，本文仅作历史记录。

## 当时的架构

- 电视客户端：React Native TV（RN 0.81.5-2 / React 19.1.0 / react-native-video 6.19.3 / TypeScript），包名 `com.hongguotv`，versionCode 1-2，与 Kotlin 版无签名连续性。
- 内容服务：局域网内的独立 Node 服务，负责上游请求签名、媒体 Range / HEAD / 背压处理；提供 API v1（`/api/home`、`/api/search`、`/api/series/:id`、`/api/episodes/:id/play`、`/api/episodes/:id/stream`），支持可选口令与 Docker 部署（AMD64 / ARM64 镜像）。
- 交付：v0.1.0（2026-09-21 首版交付）、v0.1.1（TV 焦点与展示适配），发布于 N3urda/hongguoTV Releases；当时的交付说明、Docker 部署指南与 API 文档（FIRST_RELEASE.md / DOCKER.md / API.md）随方案废弃一并移除。

## 终止原因

0.3.0 起（2026-09 下旬）项目改用 `kotlin-tv/` 的 Kotlin 原生方案：单 APK 直连内容站点、本机保存收藏与进度、无需部署任何服务，RN 工程与 Node 服务源码移出本仓库。此方案不再维护，如需恢复请参考当时 Release 附件中的源码包。
