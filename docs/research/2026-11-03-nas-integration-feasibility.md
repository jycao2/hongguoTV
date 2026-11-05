# 红果TV 接入家庭 NAS 可行性分析

- 日期：2026-11-03
- 版本基线：0.25.0（kotlin-tv，Media3 1.8.0 / OkHttp 4.12.0 / minSdk 26）
- 结论摘要：**可行，建议先做 WebDAV 版本，SMB 作为第二期**

---

## 1. 目标与边界

目标：在 TV 端应用内新增「NAS」入口，浏览家庭 NAS 上的视频文件并直接播放、记录进度、续播。

边界：

- **只做播放与浏览**，不做转码。NAS 侧不做实时转码，播放能力取决于电视本身的解码器。
- **不内置任何地址**，账号、路径全部由用户自己填写（与「片源」模块"默认空源"的原则一致）。
- NAS 与电视需在同一局域网（或用户自行保证可达），不处理公网穿透。
- 不引入账号体系外的云端依赖。

---

## 2. 现有架构事实（决定插入点）

| 事实 | 位置 | 对 NAS 接入的影响 |
|---|---|---|
| 播放走自定义取流：`RemoteVideo` 用 OkHttp 发 Range 请求、解析 `Content-Range`、解密分块 | `core/.../RemoteVideo.kt` L15-L63、L95-L178 | NAS 明文文件**不需要**这条链路；强行复用会带来无谓的解密/预读开销 |
| Media3 数据源由 `VideoDataSource` 桥接 `RemoteVideo.read()` | `app/.../VideoDataSource.kt` L10-L33 | 新增 NAS 播放路径时**应绕开**它，直接给 Media3 一个 URL |
| 应用已能播放本地文件（快传后 `MediaItem.fromUri(Uri.fromFile(file))`） | `app/.../TvTools.kt` L421-L431 | 证明「Media3 直接吃 URL」这条路在本项目已验证，NAS 可复用同一模式 |
| 起播入口集中在 `playEpisode()`，内部分支决定取流方式 | `app/.../MainActivity.kt` L2649-L2812 | NAS 播放的最小插入点就是这里加一个分支 |
| 「片源」模块的源模型只有三项：`ThirdSource(id, name, api)`，api 是 CMS JSON 接口根地址 | `app/.../ThirdSourceScreen.kt` L852 | NAS 不是 CMS 接口，**不适合塞进片源模块**；应独立模块 |
| 依赖极简：okhttp 4.12.0、media3-exoplayer/ui/hls 1.8.0、zxing，release 开 R8 + 资源裁剪，APK 约 1.6-1.9 MB | `app/build.gradle.kts` L26-L45 | 新增 SMB 库会明显撑大体积，需实测 |

结论：NAS 接入不是「改造取流」，而是**在片源与播放之间新增一条独立的本地网络文件浏览 + 直连播放通道**。

---

## 3. 协议方案对比

| 方案 | 实现成本 | 新增依赖/体积 | 覆盖 NAS 比例 | 关键风险 |
|---|---|---|---|---|
| **WebDAV**（HTTP） | 低。目录列表=PROPFIND+XML，播放=带 Basic Auth 的 HTTP Range | 仅 `media3-datasource-okhttp`（几十 KB）；XML 用系统 XmlPullParser | 高（群晖、威联通、TrueNAS、Alist/OpenList 都支持） | PROPFIND 的 XML 各厂略有差异，需容错解析 |
| **SMB2/3** | 中高。需引入 smbj 或 jcifs-ng，处理认证协商、路径编码、并发读 | smbj ≈ 1-1.5 MB；jcifs-ng ≈ 1 MB 且 Android 上需额外配置 | 最高（Windows 共享、几乎所有 NAS 默认开启） | 体积翻倍；R8 混淆规则；老电视内存与线程占用；随机 seek 性能弱于 HTTP |
| **DLNA/UPnP** | 中。SSDP 发现 + SOAP Browse + HTTP 直取 | 无新依赖（OkHttp 自实现） | 中（需 NAS 开启 DLNA 服务） | 各家实现差异大、分集排序混乱、缺少鉴权，调试成本高 |
| **NFS** | 高 | 无成熟纯 Java 客户端，需 root 或自写协议 | 低（家用少见） | 不建议 |
| **HTTP 网关**（Alist/OpenList 暴露 WebDAV/HTTP） | 零客户端成本（等同 WebDAV 方案） | 同 WebDAV | 中 | 需用户在 NAS 侧额外部署一个服务 |

**推荐路径：WebDAV 优先。** 若用户 NAS 不支持 WebDAV，再在 NAS 侧装 Alist/OpenList 作为网关，客户端代码零改动；SMB 作为第二期增量能力。

---

## 4. 推荐方案技术设计（WebDAV 版）

### 4.1 新增模块

- `core/NasWebDav.kt`：PROPFIND 目录列举、Basic Auth、路径拼接、URL 编码、大小/MIME/修改时间解析
- `core/NasLibrary.kt`：把「目录树 + 文件名」解析成剧集/集数模型（复用现有 `Series`/`Episode` 结构）
- `app/NasScreen.kt`：浏览 UI（复用现有货架/网格、焦点链、`GridLayoutManager` 焦点守卫）
- `app/NasStore.kt`：NAS 连接配置与凭据的本地持久化（沿用 `AccountStore` 的存储与 `toString()=redacted` 脱敏策略）

### 4.2 播放路径

新增 `playNasEpisode()`，与现有 `playEpisode()` 并列，不改动 `RemoteVideo`：

```kotlin
val factory = OkHttpDataSource.Factory(http)
    .setDefaultRequestProperties(mapOf("Authorization" to basicAuth))
val mediaItem = MediaItem.fromUri(nasFileUrl)
player.setMediaItem(mediaItem)
```

需要新增一条依赖：`androidx.media3:media3-datasource-okhttp:1.8.0`（体积可忽略），以获得带自定义 Header 的 HTTP 数据源与 Range 支持，避免自己写 DataSource。

### 4.3 目录 → 剧集的解析规则（工作量主要在这里）

- 剧名目录 + `S01E01` / `第01集` / `EP01` / 纯数字文件名
- 同目录下多文件按集号排序；单文件目录直接当"单集影片"
- 字幕/封面/多码率同名文件需过滤（`.srt/.ass/.nfo/.jpg` 等）
- 无法解析时降级为"文件列表直接播放"，不做强约束

### 4.4 与现有功能的衔接

| 能力 | 复用方式 |
|---|---|
| 播放进度、续播 | 现有 `library.saveProgress`（按 episodeId 记录，NAS 用 `nas:{path}` 作 id） |
| 首页「接着看」 | 复用现有 home 货架，数据源增加一类 |
| 收藏 / 稍后看 | 复用现有库，NAS 条目参与同一套数据结构 |
| 本地备份 | NAS 配置与进度一并进 `backup`（路径与凭据需排除密码或加密存放） |
| 设置入口 | 在设置页「片单与设备」组内新增「NAS 接入」，子页管理连接、扫描、重扫 |

---

## 5. 风险与限制

1. **解码能力**：不转码，H.265/AV1 在老电视上可能无法播放。需在播放失败时给出明确提示，而不是黑屏。
2. **网络带宽**：4K 高码率在百兆网口/2.4G WiFi 下会频繁缓冲。建议首期明确支持 1080p 以内，并在 UI 提示。
3. **SMB 体积与稳定性**：smbj/jcifs-ng 使 APK 从 ~1.6 MB 涨到 2.6-3 MB，且需要为 R8 编写 keep 规则（反射、SPI、资源文件）。
4. **凭据安全**：NAS 账号密码必须本地存储且不写日志；沿用 `AccountCredentials` 的脱敏做法。明文备份会把密码带进备份文件，需单独处理。
5. **多用户/多目录**：首期只支持单连接、单根目录，避免范围蔓延。
6. **合规**：NAS 内容是用户自有资源，与「片源」模块一样不内置、不预置。

---

## 6. 分期与工作量拆解

### 一期（WebDAV MVP）

1. `NasWebDav` 协议层（PROPFIND 解析、鉴权、路径编码）
2. `NasScreen` 浏览页 + 设置页入口 + 焦点链对接
3. 文件名解析为剧集（含单文件降级）
4. `playNasEpisode()` + `media3-datasource-okhttp` 接入
5. 进度记录、续播、备份与恢复
6. 单元测试：XML 解析、路径拼接、文件名解析、Basic Auth 头生成

### 二期（可选）

- SMB2/3（smbj）：评估体积与稳定性后决定；或直接引导用户用 Alist 网关
- 海报刮削（本地 `.nfo`/`poster.jpg` 读取），不联网刮削

### 三期（视需求）

- DLNA 发现、多目录、局域网内文件索引缓存

---

## 7. 验收标准（一期）

- 输入 `http(s)://NAS地址` + 账号密码后可浏览目录，10 秒内列出首屏
- 选中视频后 3 秒内起播，暂停/快退可用，进度写入本机并出现在「接着看」
- 断网/权限错误/文件不存在时给出可读提示，不崩溃
- 安装包体积增幅 ≤ 0.2 MB
- 单元测试覆盖解析与鉴权，`lintRelease` 无新增 error

---

## 8. 结论

- **技术可行性：高**。现有 Media3 直连 URL 播放路径已在快传功能中验证，NAS 只需补齐「列目录 + 鉴权 + 命名解析」三件事。
- **产品性价比：WebDAV 明显优于 SMB**（成本低、体积几乎不增、覆盖面够用）。
- **建议 Go**，先做 WebDAV 一期；SMB 单独评估，不作为一期交付条件。

---

## 9. 附：把 NAS 做成「单独模块」的四种形态

| 形态 | 做法 | 收益 | 代价 / 限制 |
|---|---|---|---|
| **A. Gradle 库模块 `:nas`**（推荐度：中） | 新建 Android library，依赖 `:core`，封装协议与解析；`app` 依赖它 | 编译隔离、单测独立、未来可整块删除 | **不减包体**（release APK 只有一份，R8 照样打包）；跨模块要用 `Series`/`Episode`（在 `:core`，可复用）；但 UI 工具（`TvStyle`、`TvDialog`、`addButton`）都在 `:app` 私有，若把浏览页也搬进 `:nas`，得先把这些抽成公共 `:ui`，属于一次性重构 |
| **B. 动态特性模块**（推荐度：不支持） | Dynamic Feature，SMB 库按需下载 | 能让 SMB 依赖不进主包 | **本项目分发方式用不了**：Play Core 的动态交付依赖 AAB + Play，我们是 GitHub Release 直接发 APK，侧载 APK 不支持动态特性 |
| **C. 独立 APK 应用**（推荐度：低） | 单独一个 App，主应用用 Intent/DeepLink 跳转 | 体积完全隔离 | 用户要装两个包；播放器、进度、收藏、备份全部要重做或跨进程共享，体验割裂 |
| **D. 代码层独立（`NasScreen` + `core/Nas*`，不新建 Gradle 模块）**（推荐度：高） | 协议与解析放 `:core`（纯 Kotlin，可 JUnit），UI 与起播分支放 `:app` 内独立类 | 成本最低、无循环依赖、单测覆盖解析；与现有「片源」模块的组织方式一致 | 无实质代价，只是"模块"停留在代码层而非 Gradle 层 |

**结论**：真正想要的是「代码边界清楚、可独立测试、可整体摘除」，这用形态 D 就能拿到；A 形态只有在后续要引入 SMB 这类大依赖、准备做"可选能力"时才值得，而且包体一点也省不下来。若将来确实要让 SMB 依赖不进主包，唯一可行的替代方案是把带 SMB 的版本单独打一个 APK 供用户选择，而不是拆动态特性。

