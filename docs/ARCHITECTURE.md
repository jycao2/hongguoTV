# React Native 与多平台方案

更新日期：2026-09-21。状态：已按本方案实现首版 RN TV 客户端和独立 Node 服务；具体构建、媒体与设备验证证据见 [交付说明](FIRST_RELEASE.md)。

## 已确认的约束

- 首版用于自家电视，按 Android 8.0（API 26）设计。
- 优先采用 React Native，考虑后续分平台构建与部署。
- 后续平台具体范围尚未确定；本文中的移动端、Apple TV 和 Web 是扩展方向，不是首版交付承诺。

## 优先技术路线

| 部分 | 选择与边界 |
| --- | --- |
| 共享语言 | TypeScript，承载数据模型、搜索与选集流程、收藏和续播规则 |
| TV 客户端 | react-native-tvos 0.81.5-2，基于 Community CLI TV 模板；启用新架构以支持 TV 原生焦点事件 |
| Android 基线 | 应用 minSdk 设为 26；compileSdk / targetSdk 随锁定的工具链配置，不与 minSdk 混为一谈 |
| 播放器 | react-native-video 6.19.3，由 Player 组件隔离加载、暂停、跳转、进度、结束和错误事件 |
| 原生扩展 | 仅在播放器或平台能力确有缺口时补充 Kotlin / Swift 模块，业务和主要界面优先保留在 RN / TypeScript 层 |
| 内容来源 | 通过 ContentProvider 接口访问，首版实现 BridgeProvider，连接独立 Node 服务 |

React Native 官方将 TV 支持列为社区平台。react-native-tvos 维护 Android TV / Apple TV 的焦点与遥控器能力，其当前说明列出 0.77 之后版本的 Android 最低 API 为 24，低于本项目目标 API 26；这只是系统版本条件，仍需验证所选依赖组合和电视硬件。[RN 平台说明](https://reactnative.dev/docs/out-of-tree-platforms)、[TV 分支说明](https://github.com/react-native-tvos/react-native-tvos)

react-native-video 的 v6 文档列出 Android ExoPlayer、iOS / tvOS AVPlayer 和 Web HTML5 播放后端。首版已锁定 6.19.3，原生端的成功不能代表 Web 或 tvOS 的媒体兼容性。[播放器文档](https://docs.thewidlarzgroup.com/react-native-video/docs/v6/intro/)

## 共享范围与平台边界

共享核心保持为不依赖 RN UI、Node 内置模块或浏览器 DOM 的 TypeScript。首版先在一个 TV 应用中按模块组织，新增第二个平台时再按实际需要提取共享包。

- **业务核心**：剧集与分集模型、分页状态、自动下一集规则、收藏与观看进度规则。续播记录保存剧集 ID、分集 ID 和播放位置，不把临时播放 URL 当成永久标识。
- **内容适配**：ContentProvider 提供搜索、详情、分集和播放解析。播放描述包含地址、必要请求头、媒体类型，以及已知时的有效期；未掌握的字段保持未知。播放描述需要能承载后续确认的原生播放配置，不能假定所有内容只是一个 URL。
- **播放适配**：PlayerAdapter 对业务暴露统一操作与事件，各平台处理自己的播放器配置、生命周期和错误。
- **存储适配**：ProgressStore / FavoritesStore 隔离平台存储实现；首版保存本地数据，多设备同步另行设计。
- **交互界面**：尽量复用展示组件和页面逻辑。TV 单独处理焦点、方向键与返回键；移动端处理触摸；Web 处理浏览器输入和布局。

平台差异集中在适配模块和平台文件中。RN 支持 `.android`、`.ios`、`.native` 等文件划分；TV 特定后缀需要按 TV 模板配置 Metro，不能假定默认启用。[平台代码组织](https://reactnative.dev/docs/platform-specific-code)、[TV 模板与文件解析](https://github.com/react-native-tvos/react-native-tvos#how-to-support-tv-specific-file-extensions)

## 分平台构建与部署

| 平台 | 定位 | 部署与独立验收 |
| --- | --- | --- |
| Android TV / 安卓盒子 | 首版交付 | Android 8.0 可安装 APK；验证 CPU 架构、遥控器、解码、连播与重启续播 |
| Apple TV | 后续候选 | 独立 tvOS 构建、签名与安装验证；重新验证播放器和遥控器行为 |
| Android / iOS 移动端 | 后续候选 | 复用业务并适配触摸布局，设置各自构建目标；iOS 与 tvOS 按工具链要求分开配置 |
| Web | 后续候选 | 评估 React Native Web，独立生成站点产物；浏览器媒体、跨域与请求头限制需重新验证 |

各平台分别管理应用标识、构建入口、环境配置和发布产物。共享源码不等于同一安装包适用于各平台；Android 上的成功也不代表 tvOS 或浏览器已经通过。RN 官方列出了 React Native Web 等独立平台实现，实际引入时需逐项核对依赖支持。[平台扩展说明](https://reactnative.dev/docs/out-of-tree-platforms)

## 内容服务部署选择

两种模式使用相同的 ContentProvider 边界。首版已选择独立服务模式，端内直接接入尚未实现：

1. **客户端直接接入**：若请求和媒体处理能够在目标 RN / 原生运行时可靠完成，可交付独立 APK。
2. **客户端连接独立服务**：若仍依赖 Node 运行时或独立的媒体处理链路，可将服务部署在电脑 / NAS 等环境，各端配置服务地址。服务器部署位置和访问范围应在选择此模式时明确。

既有研究中的 JavaScript 源依赖 Node 的 crypto、Buffer 及 drpys 运行时，不能因为同为 JavaScript 就直接视作 RN 可用模块。先验证最小调用链，再评估移植成本与服务部署成本。[已有源码研究](research/2026-09-21-hongguo-open-source.md)

首版服务同时处理元数据和媒体代理，视频流量会经过服务。需要服务设备持续在线；电视设置服务地址后才能访问真实内容。分段读取与解码的验证记录见交付说明，长期带宽、资源占用和家庭 Wi-Fi 表现需继续测量。

## 第一轮交付与验收

1. 最小 RN TV 工程：Android 8.0 能安装启动，方向键、确定键、返回键及焦点反馈可用；锁定通过验证的依赖版本。
2. 内容验证程序：选 3 部剧，检查搜索、详情、完整分集与播放描述，保存脱敏结果和失败原因。
3. 最小播放页面：先用已知可播放媒体核对播放器，再接入真实内容；在电视上验证暂停、跳转、连续播放 3 集、退出续播和地址重新解析。
4. 部署结论：根据实测选择端内接入或独立服务，记录所需环境和仍未覆盖的条件。

先确认首版平台闭环，再建设完整页面和其他平台构建。模拟器、接口 JSON 和测试视频的成功分别记录，不能代替目标电视上的真实内容验收。

## TV 展示与输入边界

`src/ui/tv/TVGrid.tsx` 管理列表焦点、整行滚动与返回定位；`DetailTV.tsx` 管理详情和 20 集分组；`layout.ts` 提供逻辑尺寸安全边距与位置边界。`Player.tsx` 将视频主操作状态与更多操作面板分开，主状态的方向键直接跳转，面板内的方向键移动焦点。

`src/data/useCatalog.ts` 在页面切换期间保存按栏目 / 查询隔离的分页结果，避免进入详情或播放器后返回时重新请求首页。浏览位置按剧集 ID 恢复，已删除项目退到有效位置。缓存属于本次应用会话，收藏、进度和服务设置仍通过原有存储持久化。切换内容服务会更换目录缓存。

后续手机 / Web 可以复用 domain、内容协议和存储模型，但应提供自己的布局、输入和媒体适配，不能直接把电视遥控器交互套在触屏上。
