# 红果取流解决方案全网调研（2026-11-23）

背景：issue #36 对照实验证实「小果影视 guoapp 2.0.3 走官方 App 会话协议取流，1080P 不限流」，而本项目持续被 200+空 body 拒绝。本文记录全网搜索结果：这条路线有人做过，且不止一个，多数开源。

## 一、结论

**有人做过，且方案成熟。** 共找到 5 个可参考的实现/项目，其中 3 个开源、可直接读码。关键情报不是「能否签名」（我们已有 Argus/Ladon 全套），而是**服务端风控的精确判据**——真果鉴项目把这些坑全部踩过并写进了 README。

## 二、项目清单

| 项目 | 形态 | 许可 | 状态 | 价值 |
| --- | --- | --- | --- | --- |
| Adsryen/zhenguojian（真果鉴） | Go sidecar + Python GUI 下载器 | 开源 | 2026-10 活跃 | ⭐ 风控判据最全：签名头取舍、TLS 指纹、参数对齐 |
| juneix/redguo（guoapp 系 fork） | Flutter+Go 多端播放器 | fork 自 xudahua520/guoapp（80★） | 2026-09-26 | `native/core/provider_hongguo_*.go` 8 个文件全开源，即 issue #36 里那个不限流的实现谱系 |
| yinjg1997/hongguo | Frida 签名预言机 + Python | 开源 | 活跃 | 让模拟器里的真 App 自己算签名，绕开全部逆向；最稳兜底 |
| aston314/hongguo-tv | Go+Lampa TV 客户端 | 文档极全 | 2026-10 | docs/protocol-evidence.md：独立实现全套签名 + passport 短信登录 + MFA + 云同步，验证方法论可借鉴 |
| sunnysky123/hongguo-web | Node 网页版 | 开源 | 2026-10-27 起 | 「匿名设备注册 + 无签名请求」取流路线（b 域名 multi_video_model/v1），空 body = 设备身份被拒 |

## 三、真果鉴 README 给出的风控判据（对我们的 200+空 body 直接对症）

1. **播放接口只认已注册设备**：必须先 `device_register` 提交 openudid/udid 等指纹拿 device_id/install_id 并持久化复用；未注册设备请求播放接口 → HTTP 200 + 空 body。（我们已做设备注册，需核对指纹字段是否齐全）
2. **取流请求只带四个签名头**：X-Gorgon / X-Khronos / X-Ladon / X-Argus。**多带 X-Helios / X-Medusa 会触发风控空包**——实测同一设备同一集成功率 1/3 → 去掉后 3/3。详情/目录类接口才带六头。
3. **TLS 指纹必须 Chrome 级**：App 接口（api5-normal-sinfonline*.fqnovel.com）走 bogdanfinn/tls-client 的 Chrome 指纹；Go 默认 net/http 指纹同样请求成功率仅 1/7。Android 端自带的 TLS 栈可能天然没问题，但值得抓包核对。
4. **query 参数逐字对齐参考客户端**：只有设备字段 + _rticket，**不能带 ts**。多一个 `ts=` 时目录/详情照常，但取流接口稳定回 200 + 空 body。
5. **device_level 3→4 重试**：编码不兼容时把 device_level 从 3 提到 4 重试。
6. 回退顺序：multi_video_model/v1（详情页场景，不带 dr_scene）→ 换新注册设备 → device_level=4 → video_model/v1 旧接口 → 网页 cookie → 360p。
7. preload 场景（multi_video_model/preload/v1）2026-10-29 起对同样请求体稳定回 200 空包，已弃用。
8. 风控瞬拒不把网页 360P 兜底当成功，排队 45/90/180s 重试。

## 四、签名算法公开细节（GitCode 博客拆解 guoapp 源码）

- **X-Gorgon**：26 字节。URL MD5 前 4 字节 + body MD5 前 4 字节（完整 MD5 放 X-SS-STUB）+ 固定字节 {0,6,11,28} + 时间戳 → 20 字节明文 → 固定 20 字节密钥异或 → 每字节左旋 4 位 + 与下一字节异或 + 整体位反转 → 加版本头 84 04 40 1c。
- **X-Argus**：类 Protobuf 编码（appID/device_id/版本/渠道 v04.07.01-ml-android/nonce/时间戳 + URL 的 SM3 + body MD5 再 SM3）→ SM3 种子扩 72 轮轮密钥 → 自定义 16 字节块变换（非 AES）→ 掩码异或 → 字节反转 → AES-128-CBC 再包一层 → 魔数 3c cc → Base64。
- **X-Ladon / X-Helios / X-Medusa**：guoapp 用 HeliosFor/BuildMedusaFor 生成；真果鉴实测取流**不能带**后两个。
- **CENC 解密**：key_seed → SHA-512 双哈希 + AES-CBC 解出真实 URL；spade_a → 派生 AES key；视频本体 CENC AES-CTR，解析 moov/saiz/saio/stsz 拿逐样本 IV。

## 五、对本项目的行动建议（优先级排序）

1. **先做零成本排查（对齐第 2/3/4 条判据）**：抓我们现有取流请求与真果鉴参考客户端逐项 diff——签名头是不是带了多余的 X-Helios/X-Medusa、query 有没有 ts/多余字段、设备指纹字段是否齐全。这些是「密钥都对还被拒」的最可能原因，且我们此前从未核对过（一直按上游 PhoenixReadApi 的六头打法）。
2. **读 juneix/redguo 的 provider_hongguo_playback.go / provider_hongguo_app.go**：issue #36 说不限流的那条通道，源码就在这 8 个文件里，重点对齐 `hongguoAppRequest` 的 header/query 构造。
3. **Frida 预言机兜底（yinjg1997/hongguo 方案）**：如果纯复刻始终过不了，可在本机跑模拟器 + 真 App 算签名做研究用途验证，逐参数排除法定位是哪个环节被判。
4. **风险提示**：多项目 README 均注明仅供个人学习、CDN 屏蔽数据中心 IP（家庭宽带可用）；风控判据随服务端策略漂移（真果鉴 10 月还记录了 preload 场景全灭的演变），任何对齐都要考虑时效。

## 六、参考链接

- 真果鉴: https://github.com/Adsryen/zhenguojian
- guoapp 源码 fork: https://github.com/juneix/redguo （上游 xudahua520/guoapp）
- Frida 下载器: https://github.com/yinjg1997/hongguo
- 协议证据库: https://github.com/aston314/hongguo-tv （docs/protocol-evidence.md）
- 网页版: https://github.com/sunnysky123/hongguo-web
- 签名算法拆解: https://blog.gitcode.com/01ae971370f1b13669bc4002e0f9cc90.html
- 真果鉴取流路径拆解: https://blog.gitcode.com/3ef8ba8441b53806ae86208a0c954d5b.html
