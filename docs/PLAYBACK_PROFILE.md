# 取址协议档案（playback-profile）

播放取址里「站方会改的那部分数据」抽成一份 JSON，端上启动时从远端拉取并覆盖内置默认。
站方下次改字段集（签名常量、版本指纹、UA、门地址），改这份档案推一次仓库即可，不用发版。

- 客户端实现：`kotlin-tv/core/.../PlaybackProfile.kt`（模型与校验）、`PlaybackProfileEnvelope.kt`（验签）、
  `PlaybackProfileStore.kt`（缓存）、`PlaybackProfileRepository.kt`（拉取）、`PlaybackProfiles.kt`（生效值）、
  `kotlin-tv/app/.../PlaybackProfileUpdater.kt`（节流刷新）
- 仓库文件：`playback-profile.src.json`（可读源码，导出器生成）、`playback-profile.json`（实际下发，带签名）

## 边界：只外置「数据」，不外置「算法」

| 外置（会变） | 留在代码（三个月没变过） |
|---|---|
| `v5.magic` / `license` / `signKey` / `sbox` / `field21` | AesV3 / sm3 / xmxor / hashF13 的表与位序 |
| `v5.envVersion` / `messageVersion` / `numericVersion` | 随机量的取法与顺序 |
| `v5.aid` / `perm` / `tz` / `lang` / `deviceMake` / `metricsJson` / `mediaTag` | 路由降级顺序、换号与冷却 |
| `ua.app` / `ua.video` / `ua.playback` / `ua.tab` | 报文字段号与顺序 |
| `device.*`（27 项查询串设备档案，`{iid}` 等占位符取运行时身份） | protobuf varint 编码规则 |
| `gates.*` 的 host / path / UA | `playbackValues/appQuery/playerRequest` 等各调用点自己覆盖的版本口径 |

外置算法等于给签名多加一个出错的地方：出错时的表现是 HTTP 200 + 0 字节，端上没有任何报错可看。

## 站方又改字段集时怎么操作

1. 改 `PlaybackProfile.builtin()`（`kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PlaybackProfile.kt`）里的数据 —— 那是唯一事实来源；
2. 在 `kotlin-tv/` 下跑导出器（会同时写 `playback-profile.src.json` 与带签名的 `playback-profile.json`）：

   ```bash
   PROFILE_SRC=../playback-profile.src.json \
   PROFILE_OUT=../playback-profile.json \
   PROFILE_KEY=tools/profile-key.pem \
   ./gradlew :core:test --tests '*PlaybackProfileExportTool*' --rerun-tasks
   ```
3. **`revision` 加一**再提交 —— 不加端上不会采纳（只认严格变大的版本号）；
4. 推 main。端上重启后 6 小时内（或手动点「立即检查更新」）就会换上。

回滚同理：把内容改回旧值、`revision` 再加一，推上去。不要靠删缓存回滚（端上缓存是每台设备自己的）。

## 私钥在哪

`kotlin-tv/tools/profile-key.pem`（PKCS8，P-256），**不入库**，已在 `.gitignore` 里。
公钥编译在 `PlaybackProfileEnvelope.PUBLIC_KEY`，是 DER/SubjectPublicKeyInfo 的 base64。

私钥丢了不影响已有设备（它们只验签、不签名），但**没法再发新档案**——只能发一版 APK 换新公钥。
所以换机器时要把这个文件一起拷走（和 `keystore.properties`、`release.jks` 同级重要）。

## 端上行为

1. 启动先读 `filesDir/playback-profile.json`（上次缓存），验签 + 校验通过才换上；不通过当没有；
2. 距上次尝试超过 6 小时才后台拉一次：原始地址 → `gh-proxy.org` → `gh-proxy.com` → `ghproxy.imciel.com` → `api.gitproxy.dev`，第一个成功的胜出（2026-11-27 换掉已失效的 ghfast.top 与只返回 JS 挑战页的 gh-proxy.net）；
3. 只接受 `revision` 严格大于当前的内置/生效档案；
4. 任何失败都静默：继续用当前生效的那份，不弹窗、不重试、不阻塞播放；
5. 设置 → 关于红果 → 「取址协议档案」：查看当前档案、手动检查、恢复内置默认。

失败模式与「没有这个功能」等价，这是整套设计的前提。

## 为什么带签名

档案里装的就是签名主密钥与替换盒。只靠 HTTPS + 域白名单挡不住仓库/CDN 侧被替换，
所以下发用信封格式：`payload` 是档案原文的 base64，`signature` 是对 payload **原始字节** 的
`SHA256withECDSA`（P-256）。选 P-256 而不是 Ed25519 是因为 Android 从 API 11 起就有前者，
后者要到 API 33 才进 Conscrypt，会把老电视挡在门外。

## 测试守着什么

| 测试 | 守的内容 |
|---|---|
| `SignerGoldenTest` | 改造前后的 x-medusa / x-helios / 旧六头**逐字节一致**（固定时钟与随机序列） |
| `PlaybackProfileConsistencyTest` | 仓库两份档案与 `builtin()` 一致；签名档案能验签；篡改 payload 必被拒收 |
| `PlaybackProfileTest` | schema/revision/十六进制长度/host 形态/path/UA/缺门/Medusa 明文/设备表键集合与占位符 等拒收边界；revision 只增；总开关 |
| `PlaybackProfileStoreTest` | 原子写入、坏档与截断当没有缓存、不残留 `.part` |
| `PlaybackProfileRepositoryTest` | 镜像链顺序与全失败的异常信息 |
| `PlaybackProfileWiringTest` | 改 Medusa 明文 → 报文变；改设备表 → `VendorConstants.device` 变；占位符按身份解析；重置回内置 |

改档案格式时，这五个测试必须先过，再谈上真机。

## 已知限制

- 「说什么」已覆盖 v5 取址门、Medusa 明文与旧六头链路共用的设备档案表；但**各调用点自己覆盖的
  版本口径**（`playbackValues` 的 73932、`appQuery/playerRequest` 的 74532、`TAB_UA` 等）仍在代码里。
  这些口径都是抓包逐端点验证的产物，改一个必须重测对应端点，暂不外置。
- 外网 SEO 页与官网 `fallback-play-info` 链路不在协议档案覆盖范围。
- 档案缓存不随本地备份迁移（它是缓存，不是用户数据）；换设备后由远端重新拉取。
