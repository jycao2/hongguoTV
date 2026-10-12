# Docker 部署

Docker 运行独立内容服务，电视安装 APK 后连接它。目标为家庭网络中的电脑或 NAS，提供 `linux/amd64` 和 `linux/arm64` 镜像。收藏与观看进度保存在电视本地，服务无需挂载数据卷。

## 使用源码或 Docker 部署包

从 [v0.1.1 Release](https://github.com/N3urda/hongguoTV/releases/tag/v0.1.1) 下载 `hongguotv-0.1.1-docker.zip` 并解压，也可以克隆完整仓库。在解压后的根目录执行：

```sh
cp .env.example .env
docker compose --env-file .env -f server/compose.yaml up -d --build
docker compose --env-file .env -f server/compose.yaml ps
```

此方式只安装服务依赖，无需 Android SDK、React Native 或本机 Node。首次构建需要联网下载 Node 基础镜像和 npm 依赖。

可以在 `.env` 中设置：

```dotenv
BRIDGE_BIND_IP=0.0.0.0
BRIDGE_PORT=8787
BRIDGE_TOKEN=
HONGGUOTV_VERSION=0.1.1
```

`BRIDGE_TOKEN` 留空表示不启用口令，填入值后电视端也应填写相同口令；不要提交实际 `.env`。`BRIDGE_BIND_IP` 控制宿主机监听网卡，默认开放宿主机各网卡；可填宿主机家庭网络地址。`BRIDGE_PORT` 是电视访问的宿主机端口，容器内始终为 8787。`HONGGUOTV_VERSION` 仅供下面的预构建镜像模式使用。

## 使用 GitHub 预构建镜像

镜像地址：`ghcr.io/jycao2/hongguotv-bridge:0.1.1`，同时提供 `latest`。根目录的 `compose.yaml` 默认固定到 `0.1.1`，Docker 根据宿主机架构选择镜像。

当前仓库与容器包按私有访问使用，需要有包读取权限的 GitHub 账号先登录 GHCR。可使用 classic PAT 的 `read:packages` 权限；通过 Docker 的交互式密码提示输入，不要把令牌写入命令历史或 Compose 文件：

```sh
docker login ghcr.io -u YOUR_GITHUB_USERNAME
docker compose --env-file .env pull
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

容器仓库认证规则见 [GitHub 官方文档](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry#authenticating-to-the-container-registry)。如不想配置镜像仓库令牌，使用上面的源码构建方式即可。

## 电视连接和排查

在电视「设置」中填入 `http://宿主机局域网IP:8787`，例如 `http://192.168.1.10:8787`。不要填容器内部的 `172.x` 地址或电视自身的 `127.0.0.1`。如果修改了 `BRIDGE_PORT`，这里也使用新端口。服务设备保持开机，防火墙允许家庭网络访问该端口。

无口令时可以在宿主机检查：

```sh
curl --fail http://127.0.0.1:8787/health
docker compose -f server/compose.yaml logs --tail=100
```

如果绑定了指定网卡，curl 使用对应宿主机地址。配置口令后，无认证 curl 返回 401 属于预期；容器内置健康检查会使用配置的口令。`healthy` 只说明服务进程与认证正常，真实内容需要在电视打开剧集验证，或在开发环境运行 `npm run probe`。

容器以非 root 用户、只读文件系统运行，配置自动重启和日志轮转。更改 `.env` 后重新执行对应的 `up -d`；停止服务使用对应的 `down`，电视本地收藏不受影响。需要公网访问或跨家庭访问时，应另行设计网络和访问控制。

## 构建和发布流程

`.github/workflows/verify-publish.yml` 在代码推送和 PR 时执行 TypeScript 检查、自动化测试、Compose 配置校验，以及 AMD64 / ARM64 镜像构建与启动检查。ARM64 启动检查使用 QEMU。检查包含非 root 运行、只读文件系统、宿主机映射端口、带口令健康检查，以及未认证请求被拒绝。

推送稳定版本标签（如 `v0.1.1`）时，所有检查通过后发布对应版本和 `latest` 标签到 GHCR。第三方 Actions 固定提交 SHA，认证使用 GitHub 提供的临时 `GITHUB_TOKEN`，不在仓库内存储发布令牌。

当前执行结果见 [GitHub Actions](https://github.com/N3urda/hongguoTV/actions/workflows/verify-publish.yml)。容器启动检查与媒体接口、电视播放分别验收，不能相互替代。

2026-09-21 首次云端验证已通过：[运行 35563476910](https://github.com/N3urda/hongguoTV/actions/runs/35563476910)。Node 22 类型检查、9 项测试、两份 Compose 配置校验，以及 AMD64 / ARM64 镜像构建和容器启动检查全部成功；ARM64 使用 QEMU，并非实际 NAS 硬件验收。
