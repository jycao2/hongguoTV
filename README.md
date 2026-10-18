# hongguoTV

自家电视使用的非官方红果短剧客户端，与红果官方无关联。当前主线是 **Kotlin 原生独立 APK**：安装后联网即可浏览播放，无需 Node / Docker / 服务器。最低 Android 8.0，电视遥控器操作。

**[下载最新原生版 APK](https://github.com/jycao2/hongguoTV/releases/latest)** · [原生版说明与版本记录](kotlin-tv/README.md) · [自动更新机制](kotlin-tv/AUTO_UPDATES.md)

当前版本 0.19.5，主要功能：收藏追剧更新提示、系列剧自动连播与播完推荐、漫剧 / 短剧热播榜、题材浏览、片头片尾跳过、双系统风格（深夜影院 / 暖玉白）等；支持应用内自动更新，覆盖安装保留本机收藏与观看记录。

## hongguotv-bridge 容器镜像

`ghcr.io/jycao2/hongguotv-bridge` 是旧版 React Native 客户端的配套内容服务镜像（**Kotlin 原生版不需要它**），面向家庭网络中的电脑或 NAS，提供 `linux/amd64` 与 `linux/arm64`，标签为固定版本（如 `0.1.1`）与 `latest`。

直接运行：

```sh
docker run -d --name hongguotv-bridge --restart unless-stopped \
  -p 8787:8787 ghcr.io/jycao2/hongguotv-bridge:latest
```

或使用根目录 Compose（固定到 `0.1.1`，可配置口令与绑定网卡）：

```sh
cp .env.example .env
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `BRIDGE_TOKEN` | 空 | 访问口令，留空不启用；设置后电视端填写相同值，未认证请求返回 401 |
| `BRIDGE_BIND_IP` | `0.0.0.0` | 宿主机监听网卡（Compose），可填宿主机家庭网络地址 |
| `BRIDGE_PORT` | `8787` | 宿主机发布端口（Compose），容器内始终为 8787 |
| `BRIDGE_HOST` / `PORT` | `0.0.0.0` / `8787` | 容器内监听地址与端口 |

电视（旧版客户端）「设置」中填 `http://宿主机局域网IP:8787`，不要填容器内部地址或 `127.0.0.1`。无口令时可在宿主机用 `curl --fail http://127.0.0.1:8787/health` 自查。镜像以非 root 用户运行；Compose 配置另启用只读文件系统、自动重启与日志轮转。服务按家庭网络设计，公网或跨家庭访问需另行设计访问控制。完整部署、更新与排查见 [Docker 部署指南](docs/DOCKER.md)。

镜像由 [verify-publish.yml](.github/workflows/verify-publish.yml) 在推送 `v*` 版本标签时构建发布，推送与 PR 时执行类型检查、测试、Compose 校验及 AMD64 / ARM64 构建启动检查；认证使用临时 `GITHUB_TOKEN`，第三方 Actions 固定提交 SHA。

## 旧版 React Native 客户端（保留资料）

首版客户端 v0.1.1（React Native TV + TypeScript）需搭配上面的 bridge 服务使用：**[v0.1.1 APK、源码及 Docker 部署包](https://github.com/jycao2/hongguoTV/releases/tag/v0.1.1)**。功能包括推荐、搜索、分页、详情选集、自动连播、本地收藏、断点续播与口令鉴权。

源码方式运行服务（Node.js 22+）：

```sh
npm ci
npm run bridge -- --lan
# 或只部署服务：npm ci --prefix server && node server/index.mjs --lan
```

服务默认仅监听 `127.0.0.1`，`--lan` 显式开放家庭网络访问；可通过 `BRIDGE_TOKEN` 设置访问口令。开发与验证：

```sh
npm run typecheck
npm test
npm run probe        # 访问真实平台，结果写入 outputs/probe.json
npm run build:android  # 需 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 27.1.12297006
```

## 项目资料

- [Docker 部署](docs/DOCKER.md)
- [接口说明](docs/API.md)
- [v0.1.1 交付与验收](docs/RELEASE_0.1.1.md)
- [首版交付与验收](docs/FIRST_RELEASE.md)
- [项目状态](docs/PROJECT_STATUS.md)
- [React Native 与多平台架构](docs/ARCHITECTURE.md)
- [接口研究](docs/research/2026-09-21-hongguo-open-source.md)

## 来源与许可证

项目与红果官方无关联。内容接口为非官方实现，平台变化可能导致失效；错误会明确显示，不会替换为虚假内容。

Node 服务使用固定提交的 `drpys` 源，保留原文件、GPL-3.0 许可证和来源记录，见 [第三方说明](server/vendor/NOTICE.md)。Android / tvOS 工程骨架源于 MIT 许可的 RN TV 模板。本项目代码按 [GPL-3.0](LICENSE) 提供，第三方部分保留各自许可。
