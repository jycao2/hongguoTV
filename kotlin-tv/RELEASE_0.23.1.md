# 0.23.1+42（versionCode 10242）更新日志

## 更新要点

- 修复部分时段整站「无法播放」：播放地址接口偶发返回空响应时，自动延长退避重试，并在持续失败后重新注册设备身份再试，容错窗口由约 4 秒放宽到约 10 秒
- 播放拉流分块提升到 2MB，并启用 8MB 后台预读与内存分块缓存，减少起播与中途缓冲等待
- 播放失败改为按实际播放档位自动降级重试，手动重试入口显示当前档位
- 搜索结果中同系列多季按详情自动聚合补齐，分组详情缓存 24 小时
- 首页卡片标题固定两行高度，修复集数与「热门」标签的对齐偏差；货架焦点规则抽成 FocusGrid，遥控焦点移动更稳定
- 修复 Android 10+ 本地自动备份写入失败：临时文件名与 MIME 扩展名不符，被系统补后缀后每次备份都新插一条重复条目，最终报 `Failed to build unique file` 且主备份文件从未更新

## 详细说明

### 修复

**播放地址接口偶发空响应导致无法播放**

现象：部分时段点击任意剧集都提示「已自动重试 3 次，仍无法播放」，重启 App、换剧集均无效，十几分钟后自行恢复。

定位：失败发生在取播放地址的接口（`multi_video_model`），返回 HTTP 200 但响应体为空，尚未进入播放器。用相同设备身份与参数在电脑上连发 12 次请求全部成功并拿到 1080P 地址，说明是平台侧间歇抖动，而非参数或播放器问题。

处置：
- 重试从「同一身份退避 0/0.7/1.5 秒」改为「0.7/1.5/3 秒退避」；
- 一轮仍为空则丢弃进程内身份、重新注册一台匿名设备再走一轮；
- 最坏情况约 10 秒后才报错，覆盖平台短时抖动与长时间播放后身份失效两种情况。

涉及文件：`ContentRepository.kt`、`PlaybackDeviceSession.kt`。

**Android 10+ 本地自动备份写入失败**

现象：播放期间 MediaProvider 每约 5 秒报一次 `Failed to build unique file: /storage/emulated/0/Download/hongguotv/hongguotv-backup.json.tmp.json`，电视上累积了 62 条 `hongguotv-backup.json.tmp (N).json` 孤儿条目，而主备份 `hongguotv-backup.json` 从未生成，本地自动备份实际一直失效。

定位：临时文件名为 `hongguotv-backup.json.tmp`，MediaStore 按 MIME（`application/json`）判断扩展名不符，插入时自动补成 `.json`；此后按 `DISPLAY_NAME` 查回原名的记录永远查不到，于是每次备份都新插一行，重名时被 uniquify 成 `... (N).json`，条目耗尽后写入直接抛异常。

处置：临时文件名改为 `hongguotv-backup.tmp.json`，与 MIME 一致，保存时按名复用同一条记录；`delete()` 改为按精确名与 `名字 (N).ext` 变体批量删除，启动时清理旧版残留条目（设备重装过的旧条目 MediaProvider 已置空归属，系统侧不允许删除，仅留在公共下载目录中）。

涉及文件：`LocalBackupStore.kt`、`LocalBackupStorage.kt`、`Library.kt`。

### 优化

- 拉流分块 512KB→2MB，后台预读 8MB，新增预读命中与卡顿耗时埋点及验证脚本（`6e7dfef`）。
- 货架焦点规则抽成 `FocusGrid` 并补单测，新增焦点轨迹回归脚本（`3ad82ef`）。

### 其他修复

- 播放失败按实际播放档位自动降级重试，手动入口改为动态档位（`64bfa0b`）。
- 搜索结果同系列多季按详情聚合原位补齐，分组只加一次详情请求并缓存 24 小时（`92b8a96`）。
- 卡片标题固定为两行实测高度，修复集数与「热门」标签对齐偏差（`27419e0`）。

### 清理

- 删除 `RemoteVideo.read()` 末尾重复的 `return`（不可达代码）。

## 验证

- core JVM 单测全绿（含预读命中、分块缓存、档位降级、焦点规则用例）。
- 电视实测：`Playback ready 2018ms`、首帧 2001ms，画面与弹幕正常。
- 抖动窗口内连续 3 次自动重试失败的场景已被新逻辑覆盖（实测同环境下恢复正常播放）。
- 电视实测本地备份：启动后 `hongguotv-backup.json` 正常生成（可解析，含 11 条观看记录）；手动「立即备份」后主文件更新时间刷新并生成 `hongguotv-backup.prev.json`，日志中不再出现 `Failed to build unique file`，目录也不再新增重复的 `.tmp` 条目。

## 发版注意

- 本次把 `versionCode` 基值由 10100 提到 **10200**：电视上装过本地调试包 10156，低于它的正式包无法覆盖安装。
- `versionName = 0.23.1 + run_number`，`tag = kotlin-v0.23.1-build.<run_number>`；本文件按预期的 42 号运行编写。
