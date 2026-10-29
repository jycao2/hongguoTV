# 三方片源模块需求与详细设计

> 状态：设计定稿（2026-10-20），待实施
> 设计稿：对话内 6 屏 UI 稿（① 首启预填态 ② 浏览态 ③ 详情视图 ④ 播放页 ⑤ 源管理弹窗 ⑥ 一键导入弹窗）

## 一、需求背景

用户需要在红果TV 内新增「片源」模块：类似饭太硬/TVBox 的能力，支持添加第三方资源站接口地址，浏览并播放该站的全部视频资源。功能定位为红果TV 导航内的独立入口，但业务与红果现有内容体系完全隔离——红果的剧、搜索、收藏、历史、推荐不涉及三方内容，三方数据也不进入红果的任何体系。

## 二、定位与隔离原则

同一个 App（红果TV），导航栏最右新增「片源」入口，进去之后是自成体系的功能，像「店中店」：同一个门牌，独立柜台。

### 四层隔离

| 层 | 隔离方式 |
|---|---|
| 代码 | 新建 3 个文件（ThirdSourceScreen.kt 界面、ThirdCms.kt 协议与解析、ThirdStore.kt 持久化），MainActivity 仅加三处：nav 一项、showCatalog 一行分发、release 清理一行。红果现有逻辑零改动 |
| 数据 | 独立 SharedPreferences 存源列表与播放进度；不进 Library、不进收藏/历史/继续观看、不参与备份格式 |
| 网络 | 独立 OkHttpClient（超时 10 秒），不走红果签名/拦截器/UA |
| 播放 | 独立 ExoPlayer 实例 + 独立播放控制层，不复用红果播放页（无弹幕/倍速/清晰度）；与红果播放器不同时存活，切 tab 时现有清理逻辑自然释放 |

## 三、界面设计（6 屏）

### ① 首启空态
- 首次进入片源 tab：源列表为空，不预置任何内置源，展示空态与「添加源 / 导入配置 / 导入导出配置（扫码）」入口
- 用户添加或导入源后，源条为**源胶囊组**：每个源带探活状态点（绿=就绪 / 转圈=探活中 / 灰=不可用），当前源 accent 边框高亮，左右键直接切换源
- 空态常驻免责声明：三方接口由用户自行添加，应用不提供、不存储、不传播任何视频内容
- 删除全部源后回到同一空态，可通过导入配置或扫码导入找回

### ② 有源浏览态
- 源条：源胶囊组（左右切换）+「管理」按钮 + 片源内搜索行
- 分类条：ac=list 拉取的分类胶囊（全部/电影/剧集/动漫/综艺/短剧…），选中 accent
- 结果网格：6 列 3:4 海报卡（海报 + 名称 + 右上备注角标如 HD/更新至24集），焦点卡白框放大 + 名称变 accent
- 分页：移到底部自动加载下一页（ac=list&t=分类&pg=N）

### ③ 片源详情视图（页内切换，不进红果详情体系）
- 左：大海报 + 来源名
- 右：标题 + 备注角标 + 元信息行（类型·年份·地区·集数）+ 简介（两行截断）
- 播放组 tabs：vod_play_from 按 $$$ 拆分，m3u8 组默认优先
- 分集网格（7 列）：上次看到的那集 accent 高亮
- 主按钮「继续播放 第 N 集」（有进度时默认焦点）/「从第01集开始」
- 底部同分类推荐 5 张横图（ac=videolist&t=同分类）
- BACK 返回片源列表

### ④ 独立播放页
- 全屏画面，OK 呼出底部控制层（上一集 / 下一集 / 返回详情 + accent 进度条 + 当前时间），10 秒无操作自动隐藏
- 顶部渐隐条：剧名 · 第 N 集 + 源名 · 播放组
- BACK 一步回详情
- 进度每 5 秒防抖保存（键：源id:vod_id → 集号/位置/组号），再进详情自动定位

### ⑤ 源管理弹窗
- 源列表：名称 + 域名 + 条目数 + 标签（使用中），当前源 accent 左边条
- 按钮组：`[＋ 添加源] [＋ 导入配置] [导入/导出配置（扫码）] [批量删除]`
- 底部小字：删除需二次确认 · 删除后可通过导入配置找回 · 上限 30 个
- 添加源：输入名称 + 接口地址 → 保存时探活（HTTP 200 + JSON 含 list 字段即通过），失败报明确原因（超时/非 CMS 接口/HTTP 错误）

### ⑥ 一键导入 TVBox 配置
- 入口：源管理弹窗「＋ 导入配置」
- 流程：粘贴配置链接 → OK 解析 → 解析 sites[] → 只取 type=1 且 api 为 http 链接的 CMS 源 → 勾选清单（每项实时探活：可连接·条目数 / 超时）→ 导入所选
- 自动跳过：type=0（spider jar）、type=3/4（加密 app 源），底部注明「已自动跳过 N 个不支持源」
- 加密混淆配置提示「该配置不支持导入」（仅支持明文 JSON）
- 导入完成显示成功 N 个 / 失败 N 个，与手动添加的源同列表管理

## 四、数据模型

```kotlin
data class ThirdSource(val id:String, val name:String, val api:String)
data class ThirdCategory(val id:String, val name:String)
data class ThirdVideo(val id:String, val name:String, val cover:String, val remarks:String)
data class ThirdEpisode(val label:String, val url:String)
data class ThirdDetail(
    val video:ThirdVideo, val typeName:String?, val year:String?,
    val synopsis:String?, val groups:List<Pair<String,List<ThirdEpisode>>>
)
data class ThirdProgress(val episode:Int, val positionMs:Long, val group:Int)
```

- ThirdStore：源列表与进度持久化到独立 SharedPreferences（JSON 序列化），上限 30 个源
- 列表/分类缓存：内存级 10 分钟 TTL，不落盘

## 五、CMS 协议适配（ThirdCms.kt）

- 列表 `?ac=list&t={分类}&pg={页}` → class[] 分类 + list[]（vod_id/vod_name/vod_pic/vod_remarks）
- 详情 `?ac=detail&ids={id}` → 完整字段 + vod_play_from / vod_play_url
- 搜索 `?ac=detail&wd={词}`（直接含播放地址，点击即可播）
- 播放地址三层解析：`vod_play_from` 组名按 `$$$` 分组 → 组内按 `#` 分集 → 每集按 `名称$地址` 拆分；容错：空段跳过、无 `$` 的整段视为地址
- 编码兜底：先按 UTF-8 解析 JSON，抛错则按 GBK 重试
- 分页容错：page/pagecount 缺失时用「返回条数 < limit」判断尾页
- 探活规则：HTTP 200 + JSON 含 `list` 字段

### 默认空源策略

- 应用不内置、不预置任何第三方 CMS 接口地址，首次启动源列表为空
- 源完全由用户通过「添加源」「导入配置」「扫码导入」三种方式自行添加
- 不提供「恢复内置源」，源列表删除后只能通过导入配置或扫码导入找回
- 应用不对源内容做任何存储或转码，播放为直连源站的实时流

**硬性约束（违反即返工，后续任何功能都适用）**

- 任何新增能力——包括源健康度统计、单源失效自动降级、播放地址失效换组/换源重试、批量导入优化——**一律不得引入内置源、默认源、示例源、推荐源，也不得提供「一键恢复默认源」入口**。这些能力只能作用于用户自己添加的源。
- 生产代码中不得出现任何 http(s) 源地址字面量。允许的例外只有三类：输入框占位符、域名解析逻辑、单元测试里的假地址。
- 源码审查与发布前检查须确认：`ThirdStore` 在无持久化数据时返回空列表；`Third*.kt` 生产代码中不出现形如 `api.php/provide/vod` 的真实接口路径。
- 变更此策略等同于改变项目的合规性质，必须单独评审，不得夹带在其他功能改动中提交。

## 六、播放地址格式实测样例

```
vod_play_from: liangzi$$$lzm3u8          （$$$ 分组）
vod_play_url:  第01集$https://v.xxx.com/share/xxx#第02集$https://…   （# 分集，$ 分名称地址）
```

## 七、焦点链

源胶囊 ↔ 分类条 ↔ 网格首行 ↔ 网格翻页；源条「管理」与搜索行左右互达；详情页：播放组 ↔ 分集网格 ↔ 推荐 ↔ 主按钮；弹窗沿用 tvTools 弹窗体系。焦点显式接线沿用红果已验证范式（空态兜底、焦点记忆、焦点丢失自动落输入框/首卡）。

## 八、错误处理

- 源超时 10 秒：列表页显示错误 + 重试，不阻塞其他源
- 单条数据字段缺失：跳过该条不崩溃
- 海报加载失败：显示名称占位
- 集地址不可用：播放页提示并可换集
- 所有请求独立 OkHttpClient（独立连接池，不占红果接口资源）

## 九、改动清单

| 文件 | 内容 |
|---|---|
| kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/ThirdCms.kt | 新建：CMS 协议客户端 + 播放地址解析 + TVBox 配置解析（纯函数可测），不含任何内置源 |
| kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/ThirdStore.kt | 新建：源列表与进度持久化（独立 prefs） |
| kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/ThirdSourceScreen.kt | 新建：片源页全部界面逻辑（空态/浏览/详情/播放控制） |
| kotlin-tv/app/src/test/…/ThirdCmsTest.kt | 新建：解析容错/GBK/分页/$#$$ 解析/TVBox 配置提取用例 |
| kotlin-tv/app/src/main/kotlin/com/hongguotv/nativeapp/MainActivity.kt | 仅 +1 nav 项、+1 行分发、+release 清理一行 |
| README.md | 免责声明追加一句（见第十一节） |

红果 core/Library/备份格式零改动。工作量约 1000-1200 行新代码 + 测试。

## 十、分期

- **一期（本设计范围）**：默认空源 + 源管理（添加/导入/删除/切换/探活/扫码导入导出）+ 分类浏览 + 分页网格 + 详情 + 独立播放（组/集切换 + 续播）+ 片源内搜索 + 一键导入 TVBox 配置
- **二期（可选）**：源测速排序、跨源聚合搜索
- **永久不做**：spider jar 动态加载（安全风险与维护地狱）

## 十一、合规说明

README 免责声明在保留现有核心表述（仅做技术交流与学习用途，不提供、不存储、不传播任何视频内容）基础上追加一句：

> 三方片源接口需由用户自行添加，应用不内置任何接口地址，其内容的合法性由对应源站自行负责。

默认空源说明：应用不提供、不预置任何源，用户添加的源可删除、可通过导入配置恢复，应用不对源内容做任何存储或转码。
