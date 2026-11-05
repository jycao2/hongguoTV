# 红果TV 手机遥控（局域网）需求与技术设计

- 日期：2026-11-05
- 版本基线：0.25.0（kotlin-tv，Media3 1.8.0 / OkHttp 4.12.0 / minSdk 26）
- 状态：**设计定稿，代码未开始**
- 结论摘要：**可行。纯本地零后端，复用现有局域网六件套的安全模型，增量约 400–500 行 Kotlin + 一个内嵌页面，APK 增幅 < 50 KB。**

---

## 1. 目标与边界

### 1.1 目标

电视端 App 内开启「手机遥控」后，用手机扫二维码进入一个网页，在手机上完成播放控制、选集、找片。手机与电视之间**只走局域网直连**，不经任何服务端。

### 1.2 明确不做（P0 范围外）

| 不做项 | 原因 |
|---|---|
| 手机端发弹幕 | `DanmakuRepository` 目前只有拉取，发散弹需新增签名接口，风险与工作量不成比例 |
| 截图 / 投屏 | 无现成能力，且引入 `MediaProjection` 权限与授权流程 |
| 页面内语音识别（Web Speech API） | 局域网明文 HTTP 非 secure context，`SpeechRecognition` 不可用；Chrome 实现还需境外服务 |
| 后台 / 息屏遥控 | 见 1.3，属架构级限制 |
| 多设备并发控制 | 已定 `maxConnections=1`，后来者顶替 |
| 电视端主动唤醒 | Android 10+ 后台启动 Activity 受限，且播放器已随 `onStop` 释放 |
| 电视「主页」键 | App 收不到 `KEYCODE_HOME`（系统保留）。改用 `ACTION_MAIN` + `CATEGORY_HOME` 确实能让电视回桌面，但会立刻触发 `onStop` → `releasePlayer()`，等于让遥控会话自杀 |
| 音量 ± 按键 | 电视遥控器的音量键同样是系统级，`dispatchKeyEvent` 收不到。改为在遥控面板用 `AudioManager.adjustStreamVolume(STREAM_MUSIC)` 直接调**电视的**系统音量，静音按钮已覆盖高频需求，不单独做 ± |

> 关于「App 收得到的键」：`MainActivity.dispatchKeyEvent` 实际处理的是
> `DPAD_UP/DOWN/LEFT/RIGHT`、`DPAD_CENTER`/`ENTER`、`BACK`、`MENU`，以及 `MEDIA_PLAY/PAUSE/PLAY_PAUSE/STOP/NEXT/PREVIOUS/FAST_FORWARD/REWIND`。
> 手机端只映射这一批，不臆造遥控器上存在但 App 收不到的键。

### 1.3 硬性约束：只在电视 App 前台有效

这不是设计取舍，是现有代码的既成事实：

```kotlin
// MainActivity.kt:3924
override fun onPause() { foreground = false; ...; player?.pause(); third?.pause() }
// MainActivity.kt:3930
override fun onStop()  { ...; if (screen == "player") { startPosition = currentPosition(); releasePlayer() } }
```

且 `skipEpisode` / `seekTo` 等方法首行即 `if (!foreground || screen != "player") return`。

**加前台 Service 也解决不了**：Service 能保住监听 Socket，但保不住一个已被 `releasePlayer()` 释放的播放器。要真正后台遥控，必须把整个播放器搬进 Service，属另一量级重构，不做。

因此产品语义定为：**只要电视上红果TV 在前台，遥控永不超时；一旦离开前台，页面明确显示「电视已暂停」，回到前台后自动恢复且状态正确。**不假装还能用。

---

## 2. 现有可复用资产

这次新增的绝大部分是协议层与页面，播放器内部不需要改。

| 能力 | 现有实现 | 复用方式 |
|---|---|---|
| 局域网 HTTP 服务 | `core/PhoneSearchServer.kt`（193 行，手写 `ServerSocket`，零依赖） | 同款骨架，扩成多端点 |
| 安全模型 | 路径随机 token + `isSiteLocalAddress` 双向校验 + Origin 校验 + CSP + `no-store`（同文件 L19/L68/L106/L124） | 直接沿用 |
| 局域网地址发现 | `TvTools.kt:53` `lanAddress()`，限 Wi-Fi/以太网且必须是 site-local IPv4 | 直接调用 |
| 二维码 | `TvTools.kt:149` `qr()`，ZXing 已在依赖内 | 直接调用 |
| 播放控制面 | `playEpisode`(2724) / `requestPlayback`(3059) / `seekTo`(3070) / `skipEpisode`(3075) / `currentPosition`(2966) / `seek`(3346) | 遥控接进同一层 |
| 三方播放控制面 | `ThirdSourceScreen.playEpisode`(2310) / `seek`(2711) / `jumpEpisode`(2722) / `pause`(367) / `stop`(369) | 经适配器接入 |
| 状态出口 | `syncMediaSession`(3081)、`updatePlaybackText`(3045)、三方 `updatePlayUi`(2730) | 快照挂点 |
| 媒体键抽象 | `TvMediaSession` 已把 play/pause/seek/skip 建成回调 | 证明「多输入源收敛」模式可行 |
| 测试基建 | `core/src/test` 有 27 个测试类，`PhoneSearchServerTest` 可用真实 `Socket` 打 | 仿写 |

**关键结论**：手机遥控是**第四条输入源**，与物理遥控器、`TvMediaSession`（蓝牙/CEC/系统媒体键）并列，全部收敛到同一批方法。因此不需要任何互斥锁——所有调用都在主线程串行执行。

---

## 3. 架构

### 3.1 分层

```
手机浏览器  ──HTTP──▶  LanRemoteHub (core)  ──主线程 post──▶  MainActivity
  内嵌页面              token 门禁 / SSE / 命令路由               │
  (HTML+JS)             @Volatile 快照只读                        ├── OfficialTarget ──▶ ExoPlayer
                        ▲                                        └── ThirdTarget ─────▶ ExoPlayer(三方)
                        └──────────── publish(snapshot) ─────────┘
```

- `core/LanRemote.kt`：纯数据模型 + JSON 编解码 + 命令白名单解析（可 JUnit）
- `core/LanRemoteHub.kt`：监听、token、SSE、心跳、看门狗、限速、顶替
- `core/LanRemotePage.kt` 或 `core/src/main/resources/remote.html`：内嵌页面
- `app/RemoteTargets.kt`：`OfficialTarget` / `ThirdTarget` 两个适配器

**页面以文件形式放 `core/src/main/resources/`，不硬编码成 Kotlin 字符串。**`core` 是纯 JVM 模块，读自身 resource 不需要 Android `Context`；好处是页面可以正常编辑与 diff，不会把 `MainActivity` 的可维护性再拉低一档。代价是需要在 `core/build.gradle.kts` 确认 resources 打进 jar（默认会）。

### 3.2 统一播放目标抽象

官方播放器是一维（`episodeIndex: Int`），三方是二维（`playGroup` + `playEpisode`）。**用二维模型去兼容一维，而不是反过来**：官方那一路填一个组 `EpisodeGroup(name = "正片")`，遥控代码里不会出现任何 `if (是三方)` 分支。

```kotlin
enum class PlaybackSource { OFFICIAL, THIRD, NONE }

interface RemoteTarget {
    val source: PlaybackSource
    /** 主线程调用：采集当前状态塞进 Hub 的 @Volatile 快照 */
    fun publish()
    /** 主线程调用：执行一条已校验的命令，返回是否被接受 */
    fun apply(cmd: RemoteCommand): Boolean
    /** 主线程调用：完整剧集列表（按需拉取，不进快照） */
    fun episodes(): List<EpisodeGroup>
}
```

Hub 只持有一个 `@Volatile var activeTarget: RemoteTarget?`，由 Activity 在切换时赋值：

| 时机 | activeTarget |
|---|---|
| 进入播放屏 | `OfficialTarget` |
| `tab==6` 且三方在播（`third != null`） | `ThirdTarget` |
| 都退出 / 回首页 | `null`，快照转 `NONE` |

**命令分两条路径，不能都塞进 `RemoteTarget`**：

| 命令 | 处理者 | 原因 |
|---|---|---|
| `play` / `seek` / `step` / `episode` / `speed` / `muted` / `danmaku` | `activeTarget.apply()` | 依赖当前播放器实例 |
| `dpad` / `back` / `menu` / `search` / `resume` | Hub 的 controller 直接处理 | 浏览模式下 `activeTarget` 为 `null`，但这批命令恰恰只在无播放时才有意义；`back` 的语义也随 `screen` 变化，必须交给 Activity 判断 |

如果硬把导航命令塞进 `RemoteTarget`，就会出现「电视在首页时没有 target，方向键全失效」的死角。

### 3.3 快照维护原则（不能省的架构约束）

快照**绝不能从 socket 线程直接读播放器**。`player?.currentPosition` 与 `third.player` 都只能在主线程访问，跨线程读会偶发崩溃。

```
主线程：状态变化 → target.publish() → hub.snapshot = PlaybackSnapshot(...)   // @Volatile
socket 线程：只读 hub.snapshot，序列化下发
```

挂点三个，覆盖全部输入源：

| 挂点 | 覆盖 |
|---|---|
| `MainActivity.syncMediaSession()`(3081) 尾部 | 播放/暂停、MediaSession、错误态、自动连播 |
| `MainActivity.updatePlaybackText()`(3045) | `seek()` 的 `pendingSeek` 变化（电视遥控器连点快进时手机端也要跟手） |
| `ThirdSourceScreen.updatePlayUi()`(2730) | 三方播放器全状态 |
| `MainActivity.onResume/onPause/onStop` | `tvForeground` 翻转 |

`publish()` 内部做 500 ms 节流，主线程只做一次字段赋值。

---

## 4. 协议设计

### 4.1 端点（全部带 token 门禁）

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/<token>/` | 手机页面 HTML |
| GET | `/<token>/events` | SSE 快照流 |
| POST | `/<token>/cmd` | 上行命令 |
| GET | `/<token>/episodes` | 完整剧集列表（含线路组），按需拉 |
| GET | `/<token>/resume` | 「继续观看」列表，浏览模式用 |
| GET | `/<token>/cover?u=<url>` | 封面代取，电视侧转发原始封面地址 |

CSP 与现有 `PhoneInputServer` 不同，必须放开两项：

```
default-src 'none';
style-src 'unsafe-inline';
script-src 'unsafe-inline';
connect-src 'self';
img-src 'self' data:;
```

`EventSource` 受 `connect-src` 约束，漏了会静默失败。`img-src` 只放开 `'self'` 与 `data:`，**不放开 `https:`**——封面一律走同源代理，页面里不出现任何外链图片。

`/events` 必须回一个**标准 HTTP 响应**：状态行 + `Content-Type: text/event-stream`（可直接写裸 SSE 数据的只有测试客户端，浏览器会把它判成网络错误并无限重连，页面上一句提示都没有）。同理，页面刷新后 `seq` 会从 1 重新计数，服务端必须在**新 SSE 接入时把 `lastCommandSeq` 复位**，否则旧会话的高水位会把刷新后的每条命令都判成过期。

#### 封面代取

「继续观看」的封面不能把原始地址直接塞给手机：

- 封面挂在第三方 CDN（`p3-novel.byteimg.com` 这类），部分会校验 `Referer`/`UA`，手机浏览器直接加载常常 403；
- 手机端也拿不到电视侧已有的缓存，等于每次滚到都要重新下一遍。

所以 `/resume` 里回的是原始地址，页面 encode 后交给 `/<token>/cover?u=`，由电视侧取字节再返回。电视侧**优先命中封面磁盘缓存**（用户看过的剧早就缓存过，秒回），未命中才回源一次并回填；手机侧再叠一层 `Cache-Control: max-age=86400`，来回切浏览模式不重复下载。

两个实现细节：

- **`+` 必须手动转义**。页面用 `encodeURIComponent`，它不转义 `+`；服务端 `URLDecoder` 又把 `+` 当空格，含加号的封面地址会直接解坏。前端补一个 `.replace(/\+/g, "%2B")`。
- **`u` 只接受 `http(s)` 且限长 1024**。token 已经挡住外人，这里再挡掉把它当成任意协议探针的可能；云端签名封面地址偏长，上限从 512 放宽。
- **云端封面是 HEIC，代理时转码成 JPEG**。红果云端的 `thumb_url` 走 `~tplv-noop.heic` 模板，签名绑死完整路径（换 `.jpeg` 扩展名直接 403），而手机浏览器解不了 HEIC——电视侧安卓原生能解所以一直没暴露。代理链路按魔数识别非浏览器格式（JPEG/PNG/GIF/WebP 之外），用本机解码后转 JPEG 返回，磁盘缓存直接存转码结果；解不出来原样返回，不比从前更差。

### 4.2 上行命令：绝对语义为主

```jsonc
{"cmd":"play","on":true}                              // 播放/暂停
{"cmd":"seek","to":12345}                             // 毫秒绝对位置
{"cmd":"step","by":10000}                             // ±10s，服务端基于快照换算成绝对 seek
{"cmd":"episode","group":0,"index":12}                // 选集（二维）
{"cmd":"speed","value":1.5}
{"cmd":"muted","on":true}
{"cmd":"danmaku","on":false}
{"cmd":"dpad","key":"up|down|left|right|center"}      // hold 字段保留但页面不发：多发一条等于多走一格
{"cmd":"back"}                                        // BACK 的 DOWN+UP
{"cmd":"menu","long":false}                           // 单击；按住 500ms 补发 long=true（快捷面板）
{"cmd":"search","query":"剧名"}                        // 主站页：正在播会先停播放再切搜索页；片源页：搜片源自己（见 6.6）
{"cmd":"resume","id":"..."}                           // 继续观看点播；片源页 id 为「源接口|影片号」；云端记录 id 带 cloud: 前缀
{"cmd":"detach"}                                       // 释放槽位，会话与 token 保留
```

**为什么是绝对语义**：连点 5 次快进、断线重连重复投递、多端乱序，这三类问题在绝对语义下自动消失。唯一保留的相对命令是 `step`，因为它是用户每次点击的显式意图；代价是它**不进重放队列**（见 7.3）。

每条命令由页面自动附加 `seq`（自增）与 `sid`（本次页面加载的随机标识，同时出现在 `/events?sid=` 上）。`sid` 解决的是两个手机交接时的竞态：被顶替的页面如果恰好在发命令，它的高 `seq` 会把水位线顶高，新页面的命令就被当成过期静默丢弃——带 `sid` 就能直接认出旧会话的命令并丢掉，而不是靠序号大小猜。

### 4.3 下行快照

```jsonc
{
  "epoch": 1793880941876,      // 会话标识（一次扫码一个值）
  "seq": 1234,                 // 每下发一帧自增；页面刷新后服务端复位
  "ts": 1762337000000,
  "tv": { "foreground": true, "screen": "player" },
  "source": "OFFICIAL",        // OFFICIAL | THIRD | NONE
  "title": "剧名",
  "groups": [{ "name": "正片", "count": 88 }],   // 只给摘要，完整集列表走 /episodes
  "group": 0,
  "episode": 12,
  "positionMs": 123456,
  "durationMs": 1450000,
  "playing": true,
  "buffering": false,
  "speed": 1.0,
  "muted": false,
  "danmaku": true,
  "caps": { "danmaku": true },  // 三方片源没有弹幕 → false，页面显示「无」并禁用
  "error": null,
  "remote": { "clients": 1, "evictions": 0 }
}
```

**集列表不进快照**。88 集每 500 ms 重发一次是纯浪费；拆成独立端点，仅在切集/切线路时由页面重新拉取。

### 4.4 为什么是 SSE 而不是 WebSocket

| 方案 | 实现量 | 断线恢复 | 结论 |
|---|---|---|---|
| 短轮询 500ms | ~30 行 | 无状态天然恢复 | 进度条会跳、请求量大 |
| **SSE + POST** | ~100 行 | `EventSource` 浏览器内建退避重连，**零代码** | **采用** |
| 手写 WebSocket(RFC6455) | ~200 行 | 要自己写重连 | 需处理 mask/fragmentation/ping，收益不成比例 |

现有 `PhoneInputServer` 的「一次请求-响应即关闭 + `consumed` 原子位」模型**不能直接套用**——`consumed` 必须删掉，改成「token 在整个会话内恒定，允许同一 token 多次连接」。

---

## 5. 会话模型

### 5.1 单槽位，抢占式

`maxConnections = 1`。注意规则不是「满了拒绝」，而是「后来者顶替」：

| 情况 | 行为 |
|---|---|
| 无连接 | 直接接入 |
| 已有连接 | 向旧连接发 `event: evicted` 后 close，新连接接管 |
| 同 token 重连（锁屏后自动重连） | 同上，视为顶替，不是拒绝 |

**为什么不能拒绝**：手机锁屏 → SSE 断开 → 用户回页面 → 浏览器自动重连。此时服务端可能还没检测到旧连接的死亡（TCP 半开要等看门狗 15s 才清理）。若新连接被 409 拒掉，用户会卡在「已有设备在遥控」，而实际上那台设备就是他自己——用户会认为功能坏了。

### 5.2 生命周期参数

```kotlin
idleTimeout      = 30 * 60_000   // 30 分钟无任何 HTTP 请求才回收；无绝对 TTL
serverHeartbeat  = 15_000        // SSE 注释行 ":hb\n\n"，兼作半开连接探测
writeWatchdog    = 40_000        // 超过 40s 未成功写入 → close()；必须明显大于心跳，否则误杀
snapshotInterval = 400           // 只有位置变化走节流；播放/暂停/切集等结构变化立即下发
cmdRateLimit     = 20 / sec      // 单槽位下等价于按客户端限速
maxConnections   = 1             // 抢占式
```

**看门狗不能等于心跳间隔**：初稿写的是 15s/15s，实现时发现健康的心跳恰好 15s 一次，看门狗会在两次心跳之间把正常连接杀掉。定为心跳 15s、看门狗 40s，留 2.5 倍以上余量。

**快照节流按变化类型分开**：进度每 400ms 发一次就够（人眼看不出更细），但切集、播放/暂停、换线路必须立刻下发，否则手机上的集数会慢半拍。实现上比较「除位置外的所有字段」的摘要，变了就跳过节流。

**Java 没有写超时**：阻塞式 `Socket` 只能设 `setSoTimeout`（读），`SO_SNDTIMEO` 未暴露。手机锁屏后 TCP 半开，服务端 `write()` 会永久阻塞，线程就漏了。唯一可靠解是看门狗：记录最后一次写入成功的时间戳，超时后直接 `close()` —— close 会让阻塞中的 `write()` 立刻抛异常退出。

现有六个 LAN 服务都没做这件事，因为它们都是短连接；遥控是长连接，必须做。

### 5.3 存活探测：只认 EOF，不认异常

SSE 循环每 120ms 用 1ms 读超时探一次对端有没有发 FIN（手机锁屏、直接关标签页都不会走 `detach`），读到 `-1` 才认定断开。

初版把「探测抛异常」也当成断开，写法是 `catch(Exception) { true }`。模拟器上一切正常，真机上连接**每 2 秒被自己掐一次**：Android 在这条路径上抛的并不总是 `SocketTimeoutException`，异常被误判成「对端已走」→ `break` → 服务端发 FIN → 手机 `EventSource` 立刻自动重连，形成「连上就断、断了又连」的循环。

更阴的是**完全看不出来**：每次重连都能收到首帧快照，页面把 `fails` 清零，绿点一直亮着，只有翻 `netstat` 才看得到一分钟几十个 `TIME_WAIT`。用户的感受是「遥控偶尔点得动、大部分时候没反应」——命令正好发在断连的空档里。

结论：**探测异常只说明这次没结论，不说明对端走了**。真断了会在下一次心跳写入时抛出，由看门狗兜底。修完之后同一条连接能稳定挂 100 秒以上，心跳每 15 秒准时到达。

### 5.4 顶替防拉锯

被顶掉的页面若继续自动重连，会反过来顶掉新的，两台设备无限互踢。两条规则：

1. **被顶掉的客户端收到 `evicted` 后主动 `close()` 打断 `EventSource` 自动重连**，页面显示「控制权已转移到其他设备」，只留一个手动「重新连接」按钮。
2. **服务端记 `evictionCount`**：同一会话 60 秒内被顶替超过 3 次，判定异常，直接销毁会话并要求重扫。

### 5.5 `detach` 与「关窗口」的语义区分

| 动作 | 效果 |
|---|---|
| `detach`（手机端点「断开遥控」） | 只释放槽位，**会话与 token 保留**；刷新页面可立即重连 |
| 手机扫码连上（槽位从空变满） | **自动收掉二维码窗口**，会话继续；顶栏入口改成「遥控中 · 手机已连接」 |
| 电视端关掉二维码窗口 | 只收二维码这层 UI，**会话继续挂着**；Hub 由 Activity 启动即常驻（见 5.6a） |

关窗口不再销毁会话：服务是「永久遥控」的一部分，销毁等于把手机里的旧地址作废。

### 5.6 密钥与端口的生命周期（永久有效）

密钥（token）和端口都**落盘**（`SharedPreferences "remote_access"`），首次使用时生成，之后所有冷启动复用，语义是「**一次扫码、永久直连**」：

| 事件 | token | 端口 | 手机端 |
|---|---|---|---|
| 关闭二维码窗口、重新打开 | 不变 | 不变 | 原地址继续可用，不用重扫 |
| 手机刷新页面 / 锁屏后自动重连 | 不变 | 不变 | 自动重连 |
| 手机直接打开旧地址（电视没开二维码窗） | 不变 | 不变 | **直接连上**，电视无感知 |
| 手机页面关闭 | 不变 | 不变 | SSE 断开、连接数归零，电视自动停保持常亮 |
| 电视重启 / 应用重装（清数据） | 不变 | 尽量不变 | 依然能连；仅端口被抢时才换新并回写 |

端口复用是必要条件：只固定 token 而端口每次随机，`ip:port/token` 里的主机部分照样变，地址一样作废。端口被其他进程占用时自动退回随机并回写落盘，不阻塞启动。

### 5.6a 服务常驻

- Hub 由 `MainActivity.onResume → autoStartRemote()` 拉起，不弹任何窗口；网络未就绪（DHCP 未完成）时静默重试（最多 5 次、间隔 3 秒），用户手动点「手机遥控」时仍有完整报错兜底。
- 空闲回收停用（`idleTimeoutMs` 设为一年）：默认 30 分钟没人用就关服会让旧地址失效，与「永久」矛盾。断连由手机页面关闭（SSE 断开）自然完成，电视端 `onClientsChanged(0)` 释放保持常亮。
- 安全权衡：token 落盘等于口令长期留存在本机。门禁本身靠 token 不可枚举 + 内网监听 + Origin 校验，用户明确选择「扫码一次、永久直连」的便利性，接受该留存；应用清除数据即重新生成。

---

## 6. 界面设计

> 配套可交互原型：**`remote-ui.html`**（同目录，浏览器直接打开）。顶部按钮可切换全部形态，也可用 `#panel` / `#episodes` / `#browse` / `#browse-play` / `#paused` / `#lost` / `#evicted` 直达。其中 `#browse-play` 演示「播放中手动切到浏览」——只有这个状态下，分段切换器里的「遥控」才可点。配色取自 `TvStyle` 的 NIGHT 主题。

界面按层级分三类：**主形态**（遥控面板 / 浏览模式，互斥）、**覆盖层**（选集抽屉）、**临时态**（异常）。

| 电视状态 | 基础主形态 |
|---|---|
| 播放中 | 遥控面板（主界面） |
| 浏览页 / 详情页 / 三方源列表 | 浏览模式（触摸板 + 继续观看） |
| 无内容 / 加载失败 | 提示态（只给返回与重试） |

### 6.0 电视端入口

入口不求「每个页面一个按钮」，而是「**任何常用页面都能就地打开**」，因为一次扫码后手机就能控制全 App（导航、搜索、播放、选集），不需要在每页重复进入。

| 位置 | 形态 | 说明 |
|---|---|---|
| 列表页顶栏（首页/搜索/排行榜/收藏/历史/片源/本地库/设置） | 顶栏按钮，与「手机快传」「设置」同排 | `showCatalog` 两处 `top.addView` 挂载，覆盖 8 个 tab |
| 详情页 | 标题行右侧按钮 | `base(title, trailing)` 新增的可选项，标题行右侧本来就是空的 |
| 播放页 | 播放控制行「手机遥控」按钮 | 原来只能「菜单 → 播放设置 → 最后一项」，手上没实物遥控器时这几步走不到 |
| 设置页左栏「常用」 | 文本入口 | 保留，作为兜底 |

顶栏按钮的文案随会话状态变化：

| 状态 | 文案 |
|---|---|
| 未开会话 | `手机遥控` |
| 已开启、无人连接 | `遥控 · 192.168.1.5:34567`（顺带把地址摆出来，换 IP 或手输时能看） |
| 已连接 | `遥控中 · 手机已连接` |

`tab==6`（片源）与 `tab==7`（本地库）在顶栏挂工具之前就 `return` 了，那两页保持原样：用户只要在任一入口开过会话，切过去手机仍可控。

### 6.1 形态切换规则

切换由「电视有没有在播」决定基础形态，用户可在播放中临时离开，但**两个方向不对称**：

| 触发 | 切换 | 是否强制 |
|---|---|---|
| 电视从无播放变为播放 | → 遥控面板 | 自动，强制 |
| 电视停止播放（回详情页 / 首页 / 退出播放） | → 浏览模式 | 自动，强制，且**不自动切回** |
| 播放中，用户点顶部分段器的「浏览」 | → 浏览模式 | 手动，**粘性**：此后电视切集、暂停、换线路都不弹回 |
| 浏览中，用户点「继续观看」某部 | 电视开播后 → 遥控面板 | 自动（这是用户主动发起的播放，必须跟过去） |
| 电视无播放时 | 只能浏览，「遥控」按钮禁用 | — |
| 选集抽屉 | 覆盖层，不是形态 | 切形态或进异常态时自动关闭 |
| 异常态（断连 / 被顶替 / 电视退后台） | 覆盖一切 | 恢复后回到切走前的形态（记 `lastForm`） |

为什么不对称：**用户正在手机列表里翻下一部片时，电视还在播；如果这时强制弹回遥控面板，等于打断他翻列表。**所以「播放中 → 浏览」必须粘性。

反过来「停止播放 → 浏览」必须强制，因为遥控面板上每个控件都依赖 `activeTarget`，`target` 为 `null` 时面板是个空壳，留在上面只会显示「无内容」。

一处实现细节：要区分「电视自己开始播」和「用户在手机上点播」，否则粘性规则会误伤后者。`resume` 命令发出时置一个 `expectPlay = true`，接下来收到的播放快照命中该标志就强制切回面板并清位。没这个标志，用户点了继续观看，电视播起来了，手机却还停在列表上。

### 6.2 遥控面板

```
┌─────────────────────────────┐
│ ● 已连接 192.168.1.23    ⋯  │  ← 点 ⋯ 出「退出播放 / 断开遥控 / 电视地址」
├─────────────────────────────┤
│      [ 遥控 ] [ 浏览 ]       │  ← 分段切换，两形态常驻
├─────────────────────────────┤
│  剧名                       │
│  第 12 集  [正片]  剩余 18:28│
│                             │
│  ──────●──────────────────  │  ← 主交互，宽度 100%
│  03:12             21:40    │
│                             │
│    ⏮     ↺10    ▶    ↻10   ⏭ │  ← 顺序对齐电视按键提示
│                             │
│  1.0×    有声     开        │  ← 参数行，点击循环切换
│  倍速    音量     弹幕       │     橙色 = 处于非默认状态
│                             │
│  ┌───────────────────────┐  │
│  │ 搜索  说出或输入剧名 麦克│  │  ← 输入法自带麦克风，零开发
│  └───────────────────────┘  │
│                             │
│   (11)     ( 12 )    (13)   │  ← 当前集 ±1 快捷条
│  选集 · 共 88 集        全部剧集 ›│  ← 完整列表（含线路组切换）
└─────────────────────────────┘
```

几个刻意的决定：

- **主控区顺序对齐电视按键提示**（`MainActivity.kt:2776` 的「确认 暂停/播放　左右 快退/快进」），两套肌肉记忆能互通。
- **进度条是核心**：手机相对电视遥控器最大的优势就是精确 seek，所以给它完整宽度和 12px 拖动手柄。
- **搜索框文案写「说出或输入剧名」**，就是在提示用户按键盘上的麦克风键。
- **选集两级**：圆形横条做 ±1，胶囊进完整列表（88 集需要分组分页）。
- **「音量」按钮是静音开关，不是 ±**：电视遥控器的音量键是系统级，`dispatchKeyEvent` 收不到；手机侧键调的又是手机音量。所以这个按钮走 `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE / ADJUST_UNMUTE)`，直接控制**电视的**系统音量。± 需要再加两个键，P0 不做。

### 6.3 选集抽屉

```
┌─────────────────────────────┐
│ 选集                     ✕  │
│ [正片] [线路2] [线路3]       │  ← 只有多线路时出现
│                             │
│  1  2  3  4  5  6           │  ← 5 列网格，当前集高亮
│  7  8  9 10 11 12           │
│ 13 14 15 16 17 18           │
│              ⋯ 上滑加载      │
└─────────────────────────────┘
```

线路组切换放在抽屉顶部，而不是单独页面——它是「选集」的一个维度，不该独立成一层导航。

**两个入口，同一个抽屉**：遥控面板底部有「选集 · 共 N 集」整行按钮；浏览模式的按键行里也有一个「选集」（电视没在播放时置灰不隐藏，位置固定，手指不用重新找）。两侧都调 `openDrawer()`，不复制逻辑。

一条实现约束：**抽屉不能被快照刷新关掉**。播放中位置变化每 400ms 推一帧快照，每帧都会重跑 `renderMode()`；早期版本在那里无条件 `closeDrawer()`，结果抽屉刚打开就被下一帧关掉，用户看到的是「点了没反应」。现在只在**形态真的切换**（面板 ↔ 浏览）时收抽屉。

### 6.4 浏览模式

```
┌─────────────────────────────┐
│ ● 已连接 192.168.1.23    ⋯  │
├─────────────────────────────┤
│      [ 遥控 ] [ 浏览 ]       │  ← 分段切换；两个形态常驻，电视未播放时「遥控」禁用
├─────────────────────────────┤
│      ┌───────────────┐      │
│      │       ▲       │      │
│      │   ◀   ●   ▶   │      │  ← 拖动即滑动焦点，轻点即确认
│      │       ▼       │      │
│      └───────────────┘      │
│  [ 返回 ] [ 选集 ] [ 菜单 ]   │  ← 菜单单击换面板动作、长按 500ms 呼出快捷面板；未播放时「选集」「菜单」置灰
├─────────────────────────────┤
│  [ 搜索  说出或输入剧名  搜索 ]│  ← 与遥控面板同一个 search 命令，共用提交逻辑
├─────────────────────────────┤
│  继续观看                    │
│  ┌────┐ ┌────┐ ┌────┐      │
│  │封面│ │封面│ │封面│      │
│  └────┘ └────┘ └────┘      │
│  剧名    剧名    剧名        │
└─────────────────────────────┘
```

「继续观看」的封面由电视代理输出（见 4.1 封面代取），手机不直连 CDN，所以第一屏就能出图，不会先灰一排占位块。

分段切换器**两个形态都常驻**（挂在两个视图之外，不随形态显示/隐藏）。最初只把它放在浏览模式里，结果是死循环：播放中手机停在面板，面板没有分段器 → 切不到浏览 → 也就没有方向键可用。现在播放中随时能切到浏览用方向键操作电视，再切回来控播放。

**浏览模式里也放一个搜索框**：用户正在浏览/翻列表时想搜下一部剧，不该被迫先切回遥控面板。两个形态各有一个输入框，但共用同一个 `submitSearch()`，发同一条 `search` 命令，服务端无感知。

**播放中浏览模式的触摸板方向键不是焦点搜索**：播放屏只有 surface + HUD，没有可聚焦的 View，`focusSearch` 必然落空——真机表现就是「按了没反应」。所以 `remoteDpad` 在 `screen=="player" && !panel` 时直接映射播放屏语义，与实体遥控器完全一致：

| 触摸板 | 播放屏行为 |
|---|---|
| 确认 | `togglePlayback()` 暂停/继续 |
| 上 | `showPanel()` 播放控制条 |
| 下 | `showEpisodePanel(hud)` 选集面板 |
| 左 / 右 | `seek(∓1,0)` 快退 / 快进 10s |

控制条（panel）已经弹出时再恢复焦点搜索路径——那套 HUD 视图在 Activity 窗口里，`focusSearch` 是可用的。

触摸板实现要点：**全部走方法直调，不做 `KeyEvent` 注入。**

我最初写的是「注入 `dispatchKeyEvent`」，核实代码后这是次优方案，因为注入会撞上三件麻烦事：

| 注入方式的问题 | 代码依据 |
|---|---|
| `BACK` 只在 `ACTION_UP` 触发 `goBack()`，只发 DOWN 会静默无效 | `MainActivity.kt:3846` |
| 长按右键走 `startFastForward()`，靠 `ACTION_UP` 才 `stopFastForward()`；漏发 UP 会一直 3× 快进停不下来 | 3872 / 3879 |
| 播放屏的方向键有专用语义（↓选集、↑更多），注入会被 `screen`/`panel` 分支拦截，产生非预期副作用 | 3877-3889 |

直调映射，每个都有现成入口：

| 手机操作 | 直调 |
|---|---|
| 上 / 下 / 左 / 右 | `focusSearch(FOCUS_*)` + `requestFocus()`，复用现有 `FocusGrid` 与 `nextFocusXxxId` 链 |
| 确认 | 当前焦点视图 `performClick()` |
| 返回 | `goBack()`（与 BACK 的 UP 分支同一入口，语义完全一致） |
| 菜单（短按） | 按 `extras.menuKeyAction` 分流：`showEpisodePanel(hud)` / `toggleSeriesDanmaku(id)` / `showPanel()`；电视未播放时返回 false，页面按钮相应置灰 |
| 菜单（长按） | `showQuickPanel()` 四列快捷面板，与实体遥控的 `menuLongRunnable` 同一入口 |

**注意「菜单」不是固定行为**：`extras.menuKeyAction` 是用户在播放设置里可配的（选集 / 弹幕 / 快捷面板），手机端不能假设「菜单 = 快捷面板」。单击与长按是两条通道：单击走用户配置的动作，长按 500ms 固定呼出快捷面板——与电视底部按键提示条「长按菜单 快捷面板」一致。页面实现：触摸端 `touchstart` 起计时器、`touchend` 未到 500ms 补发短按（`touchstart` 同时 `preventDefault` 阻止浏览器补发 click 造成双发），桌面端无 touch 事件、click 兜底短按。

### 6.5 异常态

这五种是实际使用中最容易让人以为「坏了」的地方，必须有明确文案与出口：

| 状态 | 文案 | 可操作 |
|---|---|---|
| 连接中断 | 正在重新连接…（第 2 次） | 自动；3 次失败后转「连接失败」 |
| 连接失败 | 无法连接电视。请确认手机与电视在同一 WiFi，且电视上的红果TV 正在运行；如果电视上的遥控窗口已经关掉，回去重开并扫码。 | 「重试」+ 显示电视 IP |
| 被顶替 | 控制权已转移到其他设备。 | 「重新连接」（不再自动重连） |
| 电视退到后台 | 电视已暂停。请在电视上重新打开红果TV。 | 只读展示最后状态，控件置灰 |
| 会话结束（空闲回收 / 防拉锯） | 归入「连接失败」：对端直接关闭和对端不可达在浏览器里表现为同一件事，页面无法区分，硬拆两套文案反而会猜错 | 「重试」 |

### 6.6 弹窗与二级浮层

App 里所有弹窗（退出挽留、更新提示、确认框、选择列表…）都是 `TvDialog` 的独立 `AlertDialog` 窗口。这带来一个只有真机能撞上的问题：

**`Activity.currentFocus` 只认 Activity 自己的窗口。** 弹窗一盖上来它就是 `null`，遥控那套「手动 `focusSearch` + `performClick`」全部落空——表现正是「弹窗弹出来，遥控器就点不动了」，连返回键都关不掉。

处理分两步：

1. `TvDialog.styleWindow` 把所有弹窗登记进一个 `live` 列表，关掉时自动摘除（decorView 的 detach 监听 + `isShowing` 惰性清理双保险），`topmostDialog()` 取最上层那个。
2. 遥控命令先问 `topmostDialog()`：有弹窗就作用在**弹窗自己的视图树**上，没有才走主界面。

作用方式**不用 `dispatchKeyEvent` 注入**：弹窗是独立窗口，注入的按键会先经过 `Dialog` 的 callback，实测按下去毫无反应。改成和主界面完全一致的「`decorView.findFocus()` → `focusSearch(dir)` → `requestFocus()`」，确认键走 `performClick()`，返回键直接 `dismiss()`，行为确定。

一处改动覆盖全部弹窗，不必为每种弹窗再写一套遥控逻辑。也顺带绕开了播放屏的方向键专用语义（↓选集、↑更多）——弹窗盖着的时候本来就不该触发它们。

**弹窗内的列表也必须是按钮行，不能是 ListView。** `performClick()` 只会触发 View 自己的 OnClickListener，ListView 的 `performClick` 不会派发 `OnItemClickListener`——真机上「红果账号」这类选择弹窗弹出来，手机遥控确认键照样点不动。全局统一改法（与清晰度选择器同源）：

- `TvDialog.choose` 重写：选项从 ListView 换成全宽按钮列（左对齐、46dp 行高、`nextFocusUp/Down` 显式串链），选项多时套 `ScrollView` + `capHeight`，焦点进新行自动滚到位。登录菜单、本地备份、搜索历史、播放设置、片源跳过片头片尾等约 30 处调用一次修完。
- 弹幕屏蔽词（多选）同步改：每行一个自绘勾选态的按钮（✓ 前缀 + accent 描边），点按切换，`清空/保存` 读本地布尔数组；ListView 的 `choiceAdapter/RadioAdapter/listAdapter` 因此全部删除。
- 不走 `styleWindow` 的独立窗口必须显式登记：`TvDialog.register(dialog)` 新增公开入口，NAS 全屏播放器和选集面板（`Dialog` 而非 `AlertDialog`）都已补登记；`live` 列表与 `dispatchRemoteKey` 相应放宽到 `Dialog`。
- NAS 里的 `alert()` 原来直接弹系统 `AlertDialog`（没登记、没主题），换成 `TvDialog.info`。

**档位选择器另有一套尺寸规则**：倍速 / 清晰度这类选择器都收窄到 `maxWidth 380dp`、选项行文字左对齐，但**窗口位置按场景分**——播放中的「本剧 ××」用 `gravity=START` 靠左留 48dp 边距（真机反馈居中半屏的卡片把播放画面整个挡住）；设置页的「全局默认 ××」仍居中，那边没有播放画面要护，居中更像正式对话框。焦点导航照旧走弹窗自己的视图树。

**片源是独立模块，遥控跟着走**：电视停在片源页（`tab==6`）时，手机的搜索、「继续观看」、菜单键都作用于片源自己，不再把人拽回主站——

- `search` → `third.remoteSearch(word)`：正在播先收播放器，再走片源自己的聚合搜索（没配源时返回 false，回落主站搜索）；
- `/resume` → 列片源的观看历史（`ThirdStore`，`id` 为 `源接口|影片号`），点卡片 → `third.remoteResume(entry)` 按记录位置直接起播；
- `menu`（单击与长按）→ `third.remoteMenu()`：呼出片源播放器自己的底部操作条。

手机端靠快照里的 `screen=="third"` 识别上下文：标题从「继续观看」换成「片源 · 继续观看」，上下文变化时自动重拉 `/resume`。

**主站页的继续观看含红果云端记录**：已登录时，本机记录在前，云端记录补在后（最多 6 条，同剧只留本机那条——本机进度总比云端新）。云端条目的 `id` 带 `cloud:` 前缀，角标文案「· 云端」直接拼在 caption 里，页面无需感知。点云端卡片 → `remoteResumePlay` 认出前缀 → 走最近观看页云端卡片同一条 `openDetail(cloud=)` 路径：按云端 `episodeId` 起播、对不上时退回集号、不写本机记录。云端历史若还没拉过（登录后只拉一次），手机第一次要 `/resume` 时顺手触发一次 `refreshHistory`（每会话只踢一次，会话结束重置），当次返回本机记录，下次浏览进来云端就齐了；片源页不掺云端记录，两个模块各自独立。

**手机搜索会先停播放**：`search` 命令直接把电视切到搜索结果页，若电视正在播，`RemoteCommand.Search` 先按 `returnToDetail` 的同一套收尾处理（`resetPlaybackSession` → `saveProgress` → `releasePlayer`），否则画面走了、声音还在后台放——这是真机抓到的实际缺陷。片源页走上面的独立通道，同样会先收播放器。

### 6.7 键位映射对照

| 手机操作 | 电视实物遥控器 | 收敛到的方法 |
|---|---|---|
| 播放/暂停 | `DPAD_CENTER` / `MEDIA_PLAY_PAUSE` | `togglePlayback()`(3069) |
| ±10s | `DPAD_LEFT/RIGHT` | `seekTo()`(3070) |
| 上一集/下一集 | 选集面板 / 自动连播 | `skipEpisode()`(3075) |
| 进度条拖动 | 长按右键 3× 快进 | `seekTo()`(3070) |
| 选集 | `↓ 选集` | `playEpisode()`(2724)（**直接调，不弹面板**） |
| 方向键 | 方向键 | 焦点搜索（浏览模式） |
| 返回 | `BACK`（在 `ACTION_UP` 触发） | `goBack()`(3846) |
| 菜单 短按 | `MENU` | `extras.menuKeyAction` 分流：选集 / 弹幕 / 快捷面板(3856)；手机端单击即发短按 |
| 菜单 长按 | `MENU` 按住 500ms | `showQuickPanel()` 四列快捷面板（`menuLongRunnable` 同一入口）；手机端按住 500ms 发 `long:true` |
| 返回上一级 | `BACK`（在 `ACTION_UP` 触发） | `goBack()`；手机上对应 `⋯` 菜单里的「返回上一级（退回详情或收起菜单）」 |

**手机端的选集绝不能调 `showEpisodePanel`**：那会弹出可聚焦面板，把电视端用户正在操作的焦点抢走。必须直接 `playEpisode(index)`。

---

## 7. 异常兜底矩阵

### 7.1 连接层

| 场景 | 兜底 |
|---|---|
| 手机锁屏 / 浏览器标签冻结 | `EventSource` 自动重连（浏览器内建退避），服务端允许同 token 重连 |
| TCP 半开 | 15s 心跳 + 写看门狗强杀，不依赖 TCP 自身超时 |
| 手机切 4G / 离开局域网 | 重连必然失败 → 3 次后提示「请确认与电视在同一 WiFi」+ 重扫入口 |
| 电视 IP 变化（DHCP 续租） | 同上只能重扫；电视端窗口显示当前 IP 便于核对 |
| 路由器 AP 隔离 / 访客网络 | 首次连接即失败，文案直说「访客网络或 AP 隔离会导致连不上」 |

### 7.2 会话层

| 场景 | 兜底 |
|---|---|
| Activity 重建（内存压力） | **实现收敛为 Activity 级持有**：`MainActivity` 已声明 `configChanges`（含 orientation/screenSize/uiMode），旋转与主题切换都不会重建，所以不需要 `object` 单例 + `WeakReference` 那一套。代价是进程被杀或显式 finish 时必须重扫——这本来就是 token 不落盘的既定行为 |
| 按 Home 离开前台 | 不销毁会话，状态转 `TV_PAUSED` 推给手机，页面置灰 |
| 回到前台 | `remotePublish()` 里消费 Hub 缓存的最后一条意图（不再只靠 `onResume` 这一个时机） |
| 用户从播放器退回详情页 | 会话保留，状态转 `NONE`，手机自动切浏览模式 |
| 电视进程被杀 / 重启 | 只能重扫。token 不落盘换来的是不承担泄露风险，不做妥协 |
| 电视端关遥控窗口 | 已有手机连接时**只关窗口不销毁会话**（用户常只是想关掉挡画面的二维码），手机继续可控；没有连接才 `close()`。重开窗口沿用同一 token，不用重扫 |
| 空闲 / 防拉锯到极限 | 会话销毁并回调 `onSessionEnded`，电视端收起入口，手机下一次重连失败后进入「无法连接电视」 |

### 7.3 指令层

| 场景 | 兜底 |
|---|---|
| 缓冲中拖进度条 | `seekTo` 内部 `if (!playbackReady || duration <= 0) return` 会静默失败 → `MainActivity` 暂存 `remotePendingSeek`，`READY` 后补一次（`step` 同理） |
| 电视遥控器连点快进与手机拖动互撞 | 「最后操作者赢」：`seekTo` 里已有 `main.removeCallbacks(seekRunnable); pendingSeek = null`(3070)，无需额外处理 |
| 连点快进 5 次 | 命令为绝对语义，天然幂等；另有 20 req/s 限速 |
| 断线期间的操作 | **只缓存最后一条意图**，且只保留幂等类（play / seek / episode / speed / muted / danmaku / resume），`step` / `dpad` / `back` 这类有时效性的直接丢弃。`remotePublish()` 每次发布时探一次（`peekPending` + 有 target 才取走），所以「电视在首页时发的命令」也能在播放器真的出现后兑现 |
| 乱序 / 重放 | 每条命令带 `seq`，服务端只接受 `seq > lastSeq`；**新页面（新 `sid`）接入时复位水位**，同一页面的重连不复位，避免重发被执行两次。来自旧 `sid` 的命令直接 409 |
| 播放错误态点播放 | 走 `requestPlayback(true)`，内部已有 `playEpisode(index, retryPosition, true)` 重试 |
| 正在切集时收到 seek | `playEpisode` 执行中暂存，`READY` 后应用 |
| 三方源 45s 卡死自动换线路 | `STALL_SWITCH_SECONDS`(63) 触发后 `playGroup` 会变 → 必须主动推一次快照，否则手机高亮的集数错位 |
| 三方 `seek` 的 120ms 限流 | 拖动结束**必须补发一次最终位置**，否则最后 120ms 的手势会被吞，松手后位置对不上 |
| 三方 `jumpEpisode` 到边界弹 Toast | 遥控侧先查边界再发，避免电视上莫名弹「已是第一集」 |
| 手机拖动进度条期间收到快照 | 进入 `localGesture` 状态暂停应用快照，松手补齐最终位置后恢复（否则会被旧位置弹回去） |

### 7.4 安全层

| 场景 | 兜底 |
|---|---|
| 畸形 JSON / 超长 body | 400 / 413 并断连，不断服务 |
| 刷命令 | 20 req/s 限速，超出 429 |
| 非内网来源 | 握手阶段校验 `isSiteLocalAddress`，直接 close |
| 跨站请求伪造 | token 在路径 + Origin 校验 + 拒绝 `transfer-encoding` |
| SSE 的 GET 也要校验 Origin | 现有 `foreignOrigin()` 只在 POST 分支调用（`PhoneSearchServer.kt:124`），长连接不校验等于开了个裸读口 |
| 同网段任何人拿到 token | token 不落盘、只出现在二维码里，进程结束即作废；16 字节随机（128 bit），不可枚举 |
| 二手手机残留 | 页面不持久化任何状态，刷新即失效 |

---

## 8. 需要改动的现有代码（已落地）

| # | 位置 | 改动 | 状态 |
|---|---|---|---|
| 1 | `MainActivity.kt` 起播 / `requestPlayback` / `stopForSleep` / `releasePlayer`<br>`ThirdSourceScreen.kt:keepScreenOn` | 全部 `FLAG_KEEP_SCREEN_ON` 的 add/clear 收敛到 `refreshScreenOn()`，引入显式持有位 `playerScreenOn` / `thirdWantsScreenOn` / `remoteHolding` | 已完成 |
| 2 | `MainActivity.syncMediaSession()` / `updatePlaybackText()` 开头 | 调 `remotePublish()`（节流 500ms） | 已完成 |
| 3 | `ThirdSourceScreen.remoteSnapshot()` | 由 `MainActivity.remotePublish()` 主动拉，**不在 `updatePlayUi()` 里挂钩** | 已完成（改法有变，见下） |
| 4 | `onResume` / `onPause` / `onStop` | 翻转 `foreground`，`remotePublish(true)`；`onResume` 末尾 `takePending()` 消费最后意图 | 已完成 |
| 5 | 设置页 rail「常用」区 | 新增第三个入口「手机遥控 · 手机当遥控器」 | 已完成 |
| 6 | 播放器 `⋯ 播放设置` 列表 | 末尾追加「手机遥控 · 扫码后用手机控制」，按 `lastIndex` 判断 | 已完成 |
| 7 | `AndroidManifest.xml` | 无需新权限（INTERNET 已有） | 无需改动 |

**第 3 项的改法与初稿不同**：初稿想在 `updatePlayUi()` 尾部 publish。但 `updatePlayUi()` 是三方播放器的 UI 刷新循环，只在播放态跑；而遥控需要知道「三方退出了播放」（回列表），那个时机它不会被调用。改成由 `remotePublish()` 拉 `third.remoteSnapshot()`（`mode != PLAY` 时返回 `null`），一份逻辑同时覆盖进出播放态，也不用在 88 行外再插一个挂点。

常亮收敛的最终形态：

```kotlin
private var playerScreenOn = false      // 播放器自己要求（起播 / 播放 / 暂停 / 释放 四处维护）
private var thirdWantsScreenOn = false  // 三方播放器，由 keepScreenOn() 转发过来
private var remoteHolding = false       // 手机遥控槽位是否有人

private fun refreshScreenOn() {
    if (remoteHolding || thirdWantsScreenOn || playerScreenOn)
        window.addFlags(FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(FLAG_KEEP_SCREEN_ON)
}
```

**不用 `screen == "player" && requestedAutoplay` 反推**：起播时 `player` 可能还没创建，`releasePlayer()` 之后 `screen` 仍是 `"player"`，反推会在两个边界上都判错。三个显式持有位各自维护、互不干扰，任一来源退出都不会误关别人的常亮。

---

## 9. 体积与性能

### 9.1 实测基线

| 项 | 数值 |
|---|---|
| release APK | 1.90 MB（debug 11.7 MB，不作参考） |
| `classes.dex` | 2353 KB |
| `resources.arsc` | 243 KB |
| `PhoneInputServer.class`（未混淆，含调试信息） | 20.3 KB |
| `res/p-.png` 单张 | 108 KB |
| `res/zs.jpg` | 102 KB |
| `bcprov` jar（R8 裁剪前） | 8.18 MB |

### 9.2 增量估算

| 项 | 估算 |
|---|---|
| `LanRemoteHub` + `LanRemote` class | 50–60 KB（R8 后 25–35 KB） |
| 内嵌页面（400–500 行 HTML/CSS/JS，deflate 后） | 6–8 KB |
| **合计** | **约 30–45 KB，占 release APK 的 1.6%–2.3%** |

**零新增 Gradle 依赖**，ZXing 已在包内。作为参照，这个增量比 APK 里任意一张背景图都小。

### 9.3 运行时开销

| 状态 | 开销 |
|---|---|
| 空闲（已开启、无手机连接） | 1 个 daemon 线程阻塞在 `accept`（`soTimeout=500ms` 轮询），CPU ≈ 0% |
| 连接中 | 2 个线程（1 SSE + 1 POST），快照约 200 B |
| 带宽 | 500 ms 一次 ≈ 400 B/s，播 1 小时约 1.4 MB |

对比：`QuickTransferServer` 处理 2 GB 文件上传、`RemoteVideo` 做 8 MB 预读并解密，遥控的数据量比它们小三个数量级。

**按需启动**：遥控服务不在 App 启动时常驻，只在用户打开「手机遥控」入口时创建，关闭窗口时销毁。无人使用时开销为 0。

---

## 10. 分期与工作量

| 阶段 | 内容 | 估时 | 状态 |
|---|---|---|---|
| 1 | `core/LanRemote.kt` 模型 + `LanRemoteHub`（token / SSE / 心跳 / 写看门狗 / 顶替 / 限速）+ 单测 | 1.5 天 | 已完成 |
| 2 | 内嵌页面（遥控面板 / 选集抽屉 / 浏览模式 / 异常态）+ 自动重连 | 1 天 | 已完成 |
| 3 | 官方适配器 + 常亮收敛 + 设置页与播放器入口 | 0.5 天 | 已完成 |
| 4 | 三方适配器 + 三方播放器补 `snapshot` | 0.5 天 | 已完成 |
| 5 | 真机联调（含锁屏 5 分钟、切网、顶替、断线恢复） | 0.5 天 | 待在电视/手机真机上跑 |

比早前估的 2.5–3 天多，多出来的是「浏览模式 + 继续观看」与「三方共用」两块，都是后续确认要做的。

### 10.1 代码索引

| 文件 | 内容 |
|---|---|
| `core/…/LanRemote.kt` | `PlaybackSnapshot` / `RemoteCommand` 白名单解析 / `RemoteTarget` / `RemoteController` / JSON 编解码 |
| `core/…/LanRemoteHub.kt` | 监听、token 门禁、SSE、心跳、写看门狗、单槽位顶替、限速、空闲回收 |
| `core/…/LanRemotePage.kt` | 读取 `resources/remote.html`，缺文件时给可读的降级页 |
| `core/src/main/resources/remote.html` | 手机页面：面板 / 选集抽屉 / 浏览模式 / 四种异常态 |
| `app/…/RemoteTargets.kt` | `LambdaTarget`：用函数类型接住 Activity 私有状态 |
| `app/…/PhoneRemoteSession.kt` | 二维码窗口 + Hub 生命周期（**不挂在 TvTools 上**） |
| `app/…/MainActivity.kt` | `refreshScreenOn()` / `remotePublish()` / `remoteTarget()` / `remoteController` / `openRemote()` |
| `app/…/ThirdSourceScreen.kt` | `remoteSnapshot()` / `remoteApply()` / `remoteEpisodes()`；`keepScreenOn` 改为转发 |

测试：`RemoteProtocolTest`（8 个）+ `LanRemoteHubTest`（18 个），真实 Socket 打，覆盖 token 门禁、Origin、命令白名单、乱序丢弃、顶替、限速、超大包、detach、客户端数回调、页面资源打包。`core` 全量 **281 个测试全通过**。

---

## 11. 验收标准

1. 扫码到可操作 < 2 秒
2. 指令往返 < 300 ms（同局域网）
3. 连续操作 2 小时不断连
4. 手机遥控与电视实物遥控同时操作，状态 0.5 秒内互相同步
5. 手机锁屏 5 分钟解锁，自动恢复且进度正确
6. 三方片源播放时，遥控功能与官方片源表现一致
7. 第二台手机扫码后，第一台在 1 秒内显示「已被接管」且不再自动重连
8. 电视按 Home 后，手机在 0.5 秒内显示「电视已暂停」；回前台后自动恢复
9. 遥控连接期间暂停，电视不息屏
10. `:core:test` 全绿（现有 255 个用例不回归）

---

## 12. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 前台限制（退后台即失效） | **高**，用户体验预期落差 | UI 明示；P0 不做唤醒；电视端入口文案写清「播放时使用」 |
| 常亮改造波及 10 处现有逻辑 | **中高**，可能引入「息屏 / 长亮不灭」回归 | 单独一个 commit，逐处对照验证 4 个场景（起播/暂停/退出/三方） |
| 路由器 AP 隔离 / 访客网络 | 中，手机连不上 | 沿用「需同一局域网」文案，显示电视 IP 便于排查 |
| `maxConnections=1` 顶替误伤 | 中，锁屏重连时可能互踢 | 顶替 + `evicted` 后停止自动重连 + 60s 内 3 次即销毁会话 |
| 同网段任何人拿到 token 即可控制 | 低 | token 不落盘、只在二维码、退出即销毁 |
| 手机浏览器后台挂起 `EventSource` | 低 | 服务端允许重连 + 15s 心跳 |
| 明文 HTTP | 低 | `network_security_config` 的 `base-config` 已允许明文；仅内网 |
| 内嵌页面难维护 | 低 | 放 `core/src/main/resources/`，不写成 Kotlin 字符串常量 |

---

## 13. 后续可选项（不进 P0）

- **发弹幕**：需先补发散弹签名接口，独立评估
- **多设备并发**：`maxConnections > 1` + 状态广播给全部连接
- **后台遥控**：需将播放器整体迁入前台 Service，属架构级重构
- **遥控页 PWA**：加 `manifest` 可「添加到主屏幕」，但需 HTTPS 或 localhost，局域网 HTTP 下不可行
- **电视端遥控状态浮层**：在播放 HUD 上显示「已连接 1 台手机」，让用户知道有人在控

---

## 附：与既有模块的关系

| 模块 | 关系 |
|---|---|
| 手机输入（`PhoneInputServer`） | 并列的第七个局域网服务；浏览模式的搜索可直接复用它，但 P0 统一走遥控页 |
| 手机快传（`QuickTransferServer`） | 并列；快传是落盘，遥控是控制，无交集 |
| 三方片源（`ThirdSourceScreen`） | 通过 `ThirdTarget` 适配器接入，不改其内部播放逻辑 |
| 本地库 / NAS | 无直接关系；`continueWatching` 数据可作浏览模式的「继续观看」来源 |
| `TvMediaSession` | 同为输入源；两者可同时工作，全部收敛到同一批控制方法 |
