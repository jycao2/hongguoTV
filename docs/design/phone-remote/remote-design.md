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

CSP 与现有 `PhoneInputServer` 不同，必须放开两项：

```
default-src 'none';
style-src 'unsafe-inline';
script-src 'unsafe-inline';
connect-src 'self';
```

`EventSource` 受 `connect-src` 约束，漏了会静默失败。`img-src` 若要用封面则需放开到 `'self'` 或 `data:`。

### 4.2 上行命令：绝对语义为主

```jsonc
{"cmd":"play","on":true}                              // 播放/暂停
{"cmd":"seek","to":12345}                             // 毫秒绝对位置
{"cmd":"step","by":10000}                             // ±10s，服务端基于快照换算成绝对 seek
{"cmd":"episode","group":0,"index":12}                // 选集（二维）
{"cmd":"speed","value":1.5}
{"cmd":"muted","on":true}
{"cmd":"danmaku","on":false}
{"cmd":"dpad","key":"up|down|left|right|center","hold":0}  // hold>0 = 按住连发，单位 ms
{"cmd":"back"}                                            // BACK 的 DOWN+UP
{"cmd":"menu","long":false}                               // long=true 走长按（快捷面板）
{"cmd":"search","query":"剧名"}
{"cmd":"resume","id":"..."}                           // 继续观看点播
{"cmd":"detach"}                                       // 释放槽位，会话与 token 保留
```

**为什么是绝对语义**：连点 5 次快进、断线重连重复投递、多端乱序，这三类问题在绝对语义下自动消失。唯一保留的相对命令是 `step`，因为它是用户每次点击的显式意图；代价是它**不进重放队列**（见 7.3）。

### 4.3 下行快照

```jsonc
{
  "epoch": 1,                  // 会话代次，Activity 重建自增
  "seq": 1234,
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
idleTimeout     = 30 * 60_000   // 30 分钟无任何 HTTP 请求才回收；无绝对 TTL
serverHeartbeat = 15_000        // SSE 注释行 ":hb\n\n"，兼作半开连接探测
writeWatchdog   = 15_000        // 超过 15s 未成功写入 → close()
snapshotThrottle= 500           // 快照下发节流
cmdRateLimit    = 20 / sec      // 按客户端 IP
maxConnections  = 1             // 抢占式
```

**Java 没有写超时**：阻塞式 `Socket` 只能设 `setSoTimeout`（读），`SO_SNDTIMEO` 未暴露。手机锁屏后 TCP 半开，服务端 `write()` 会永久阻塞，线程就漏了。唯一可靠解是看门狗：记录最后一次写入成功的时间戳，超时后直接 `close()` —— close 会让阻塞中的 `write()` 立刻抛异常退出。

现有六个 LAN 服务都没做这件事，因为它们都是短连接；遥控是长连接，必须做。

### 5.3 顶替防拉锯

被顶掉的页面若继续自动重连，会反过来顶掉新的，两台设备无限互踢。两条规则：

1. **被顶掉的客户端收到 `evicted` 后主动 `close()` 打断 `EventSource` 自动重连**，页面显示「控制权已转移到其他设备」，只留一个手动「重新连接」按钮。
2. **服务端记 `evictionCount`**：同一会话 60 秒内被顶替超过 3 次，判定异常，直接销毁会话并要求重扫。

### 5.4 `detach` 与 `bye` 的语义区分

| 命令 | 效果 |
|---|---|
| `detach`（手机端点「断开」） | 只释放槽位，**会话与 token 保留**；刷新页面可立即重连 |
| `bye`（电视端点「结束遥控」） | 销毁会话、close 所有连接、二维码失效 |

否则用户误点一下就得跑到电视前重新扫码，体验断崖。

---

## 6. 界面设计

> 配套可交互原型：**`remote-ui.html`**（同目录，浏览器直接打开）。顶部按钮可在六种形态间切换，也可用 `#panel` / `#episodes` / `#browse` / `#paused` / `#lost` / `#evicted` 直达。配色取自 `TvStyle` 的 NIGHT 主题。

界面形态不靠用户手动切换，由电视状态驱动。电视一按返回键退回详情页，手机界面在 0.5 秒内自动切成浏览模式。

| 电视状态 | 手机界面 |
|---|---|
| 播放中 | 遥控面板（主界面） |
| 浏览页 / 详情页 / 三方源列表 | 浏览模式（触摸板 + 继续观看） |
| 无内容 / 加载失败 | 提示态（只给返回与重试） |

### 6.1 遥控面板

```
┌─────────────────────────────┐
│ ● 已连接 192.168.1.23    ⋯  │  ← 点 ⋯ 出「退出播放 / 断开遥控 / 电视地址」
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

### 6.2 选集抽屉

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

### 6.3 浏览模式

```
┌─────────────────────────────┐
│ ● 已连接      浏览模式 ⇄ 遥控 │
├─────────────────────────────┤
│      ┌───────────────┐      │
│      │       ▲       │      │
│      │   ◀   ●   ▶   │      │  ← 拖动即滑动焦点，轻点即确认
│      │       ▼       │      │
│      └───────────────┘      │
│    [ 返回 ]   [ 菜单 ]       │  ← 菜单：短按=用户配置动作，长按 500ms=快捷面板
├─────────────────────────────┤
│  继续观看                    │
│  ┌────┐ ┌────┐ ┌────┐      │
│  │封面│ │封面│ │封面│      │
│  └────┘ └────┘ └────┘      │
│  剧名    剧名    剧名        │
└─────────────────────────────┘
```

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
| 菜单（短按） | 按 `extras.menuKeyAction` 分流：`showEpisodePanel(hud)` / `toggleSeriesDanmaku(id)` / `showPanel()` |
| 菜单（长按） | `showPanel()`（快捷面板） |

**注意「菜单」不是固定行为**：`extras.menuKeyAction` 是用户在播放设置里可配的（选集 / 弹幕 / 快捷面板，见 3856-3860），手机端不能假设「菜单 = 快捷面板」，只有长按才是。

### 6.4 异常态

这五种是实际使用中最容易让人以为「坏了」的地方，必须有明确文案与出口：

| 状态 | 文案 | 可操作 |
|---|---|---|
| 连接中断 | 正在重新连接…（第 2 次） | 自动；3 次失败后转「连接失败」 |
| 连接失败 | 无法连接电视。请确认手机与电视在同一 WiFi，且电视上的红果TV 正在运行。 | 「重试」+「重新扫码」+ 显示电视 IP |
| 被顶替 | 控制权已转移到其他设备。 | 「重新连接」（不再自动重连） |
| 电视退到后台 | 电视已暂停。请在电视上重新打开红果TV。 | 只读展示最后状态，控件置灰 |
| 会话结束 | 遥控会话已结束。 | 「重新扫码」 |

### 6.5 键位映射对照

| 手机操作 | 电视实物遥控器 | 收敛到的方法 |
|---|---|---|
| 播放/暂停 | `DPAD_CENTER` / `MEDIA_PLAY_PAUSE` | `togglePlayback()`(3069) |
| ±10s | `DPAD_LEFT/RIGHT` | `seekTo()`(3070) |
| 上一集/下一集 | 选集面板 / 自动连播 | `skipEpisode()`(3075) |
| 进度条拖动 | 长按右键 3× 快进 | `seekTo()`(3070) |
| 选集 | `↓ 选集` | `playEpisode()`(2724)（**直接调，不弹面板**） |
| 方向键 | 方向键 | 焦点搜索（浏览模式） |
| 返回 | `BACK`（在 `ACTION_UP` 触发） | `goBack()`(3846) |
| 菜单 短按 | `MENU` | `extras.menuKeyAction` 分流：选集 / 弹幕 / 快捷面板(3856) |
| 菜单 长按 500ms | `MENU` 长按 | `showPanel()` 快捷面板(3850) |
| 退出播放 | `MEDIA_STOP` | `returnToDetail()`(3870)，对应手机端 `⋯` 菜单里的「退出播放」 |

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
| Activity 重建（内存压力） | Hub 做成 `object` 单例，内部持 `WeakReference<Controller>`；Activity 销毁时只解绑 controller，**监听 Socket 与 token 全部保留**，重建后重新注册，手机侧无感、不用重扫 |
| 按 Home 离开前台 | 不销毁会话，状态转 `TV_PAUSED` 推给手机，页面置灰 |
| 回到前台 | `onResume` 消费 Hub 里缓存的最后一条意图，恢复正确状态 |
| 用户从播放器退回详情页 | 会话保留，状态转 `NONE`，手机自动切浏览模式 |
| 电视进程被杀 / 重启 | 只能重扫。token 不落盘换来的是不承担泄露风险，不做妥协 |
| 退出播放器 / 电视端关遥控窗口 | `bye`：销毁会话 + close 全部连接，手机页显示「会话已结束」 |

### 7.3 指令层

| 场景 | 兜底 |
|---|---|
| 缓冲中拖进度条 | `seekTo` 内部 `if (!playbackReady || duration <= 0) return` 会静默失败 → Hub 暂存 `pendingSeek`，`READY` 后再应用 |
| 电视遥控器连点快进与手机拖动互撞 | 「最后操作者赢」：`seekTo` 里已有 `main.removeCallbacks(seekRunnable); pendingSeek = null`(3070)，无需额外处理 |
| 连点快进 5 次 | 命令为绝对语义，天然幂等；另有 20 req/s 限速 |
| 断线期间的操作 | **只缓存最后一条意图，重连后先拉全量快照再应用，绝不回放历史队列**（否则重连瞬间会连跳 5 集）。且只保留幂等类（play / seek / episode / speed / muted / danmaku），`step` / `dpad` / `back` 这类有时效性的直接丢弃 |
| 乱序 / 重放 | 每条命令带 `(epoch, seq)`，服务端只接受 `seq > lastSeq` |
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
| 同网段任何人拿到 token | token 不落盘、只出现在二维码里、退出即销毁 |
| 二手手机残留 | 页面不持久化任何状态，刷新即失效 |

---

## 8. 需要改动的现有代码

| # | 位置 | 改动 | 风险 |
|---|---|---|---|
| 1 | `MainActivity.kt:2757, 3065-3066, 3097, 3815`<br>`ThirdSourceScreen.kt:232, 234, 261-263, 367, 2804` | 全部 `FLAG_KEEP_SCREEN_ON` 的 add/clear 收敛到 `refreshScreenOn()`，引入独立持有位 `remoteHolding` | **中**。现有 **10 处**直接操作该 flag，逐处改为统一决策，任一处漏改会导致「暂停后息屏 / 退出后长亮」，需回归验证 |
| 2 | `MainActivity.syncMediaSession()`(3081) | 尾部调 `remotePublish()` | 低 |
| 3 | `MainActivity.updatePlaybackText()`(3045) | `pendingSeek` 变化时 publish | 低 |
| 4 | `ThirdSourceScreen.updatePlayUi()`(2730) | 尾部 publish | 低 |
| 5 | `onResume`(3903) / `onPause`(3924) / `onStop`(3930) | 翻转 `tvForeground`，消费最后意图 | 低 |
| 6 | 设置页 rail 区（`railAction` 定义在 1773） | 新增第三个入口「手机遥控」 | 低 |
| 7 | 播放器 `↑更多` 快捷面板 | 新增入口（播放时才有意义） | 低 |
| 8 | `AndroidManifest.xml` | 无需新权限（INTERNET 已有） | 无 |

代码第 1 项是最容易被低估的地方：现有常亮逻辑分散在「起播」「requestPlayback」「releasePlayer」「onStop」和三方播放器的 `pause`/`onIsPlayingChanged` 里，各处自行 add/clear，语义互相覆盖。遥控要求「暂停 + 有连接时依然常亮」，必须先把这 10 处收敛成一个决策函数，否则会出现「手机上按暂停 → 电视息屏 → 遥控看起来失效」。

```kotlin
private var remoteHolding = false
private fun refreshScreenOn() {
    val need = remoteHolding || (screen == "player" && requestedAutoplay)
    if (need) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
```

`remoteHolding` 由 Hub 维护：槽位上有连接 → `true`，槽位为空 → `false`（单槽位，顶替时不会瞬间空出，因此不会闪一下熄灭）。

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

| 阶段 | 内容 | 估时 |
|---|---|---|
| 1 | `core/LanRemote.kt` 模型 + `LanRemoteHub`（token / SSE / 心跳 / 写看门狗 / 顶替 / 限速）+ 单测 | 1.5 天 |
| 2 | 内嵌页面（遥控面板 / 选集抽屉 / 浏览模式 / 异常态）+ 自动重连 | 1 天 |
| 3 | `OfficialTarget` + 常亮收敛 + 设置页与播放器入口 | 0.5 天 |
| 4 | `ThirdTarget` + 三方播放器补 `snapshot` | 0.5 天 |
| 5 | 真机联调（含锁屏 5 分钟、切网、顶替、断线恢复） | 0.5 天 |
| | **合计** | **4 天** |

比早前估的 2.5–3 天多，多出来的是「浏览模式 + 继续观看」与「三方共用」两块，都是后续确认要做的。

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
