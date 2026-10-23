# MainActivity 渐进式拆分计划（遗留需求，暂不实施）

> 状态：**已评审，暂缓实施**。2026-10-24 整理归档。
> 未来动工时直接按本文档执行，无需重新评审。
> 原则：稳定优先，每步独立可编译、可提交、可回滚；任何一步真机验证不过即停止并回滚该步。

## 背景

`MainActivity.kt` 已膨胀至约 2724 行 / 180KB，承载全部页面、弹窗、播放器逻辑。
本计划只做**渐进式抽离**，不追求彻底重构。预期减行约 15%（2724 → 2350），
状态密集的「不可拆分核」保持现状。

## 一、文件结构摸底（2026-10-24 时点行号）

| 区段 | 行号 | 性质 | 处置 |
|---|---|---|---|
| UI 工具函数（dp/lp/text/column/row/rounded/focusStyle/button/addButton） | L260-301 | 纯视图工厂，无业务状态 | **步骤 1 抽出** |
| 本地备份/恢复 | L303-424 | 半独立，依赖少量 Activity 状态 | 步骤 5（可选） |
| base 骨架/时钟/loadCover | L425-510 | 页面骨架核心 | 不动 |
| 片库/首页/收藏（含 inner class CatalogAdapter/CardHolder） | L511-1208 | 状态密集 | **不动** |
| 设置页 settings() | L1209-1413 | 200 行巨型函数，焦点链手工接线 | **不动** |
| 搜索/联想/随机荐剧 | L1414-1610 | 状态密集 | **不动** |
| 退出确认/详情页/播放器 | L1611-2105 | 状态最密集 | **不动** |
| 弹幕设置弹窗组（弹幕偏好/屏蔽词/观看统计/接口自检/按键设置） | L2106-2304 | 半独立弹窗 | 步骤 3 抽出 |
| 播放控制/面板/背景/进度/弹幕生命周期 | L2305-2724 | 状态最密集 | **不动** |

## 二、风险点与对策

1. **调用点几百处，怕遗漏** → 对策：工具函数改写为 `Context` 扩展函数
   （`fun Context.dp(...)`、`fun Context.text(...)`）搬到新文件，MainActivity 内
   `dp(12)`、`text("...")` 等调用**零改动自动解析到扩展**；编译通过即引用全对。
2. **颜色默认值行为等价**：`text()` 默认参数 `color=white` 引用 Activity `lazy val white`
   （首次访问冻结主题色）。抽离后改为 `TvStyle.text` 直读。已确认主题切换走
   `recreate()`（MainActivity L1305），重建后 lazy 重取，两写法等价。
   **步骤 1 完成后必须真机切一次主题验证**——这是唯一逻辑变化点。
3. **扩展函数访问不到跨文件 private 成员**：第二梯队弹窗引用 `library`/`extras`/`danmakuView`
   等私有字段，抽到别的文件需把被访问字段最小化放宽为 internal，或改为传参。
   逐函数评估，风险升为中。
4. **inner class 不能动**：CatalogAdapter/CardHolder 隐式持有 Activity 引用，留在原文件。
5. **diff 大、review 难**：小步提交，每步独立可编译可回滚；不把本计划与其他功能改动混在同一提交。

## 三、拆分步骤

### 步骤 0：基线
- 打 debug 包，`adb install -r` 覆盖安装（先确认 /sdcard/hongguotvTV-backup.json 存在）
- 真机跑核心路径：首页加载 → 搜索 → 详情 → 播放+弹幕 → 设置各弹窗 → 片源进出，记录行为

### 步骤 1：抽 UiKit.kt（风险低，减 ~50 行）
- 新建 `app/src/main/kotlin/com/hongguotv/nativeapp/UiKit.kt`
- L260-301 的 dp/lp/text/column/row/rounded/focusStyle/button/addButton 改写为
  Context 扩展函数搬出；默认参数 `white` → `TvStyle.text` 直读
- MainActivity 调用点零改动；删除原成员函数
- 注意 ThirdSourceScreen.kt 有自己的同名工具函数，**本步不动它**，避免扩大爆炸半径

### 步骤 2：验证 + 提交
- 见「四、验证步骤」；通过后中文提交（不推送）

### 步骤 3：抽 SettingsDialogs.kt（风险中，减 ~200 行）
- 新建 `SettingsDialogs.kt`，搬出 5 个弹窗：showDanmakuSpeedPicker、showDanmakuRowsPicker、
  showDanmakuPrefsDialog、showBlockedWordDialog、showWatchStats、showInterfaceCheck、showKeySettings
- 被访问的 Activity 私有字段（library/extras/danmakuView 等）最小化放宽为 internal，或改传参
- 弹窗视觉规范（深色玻璃卡片/圆角/胶囊按钮）保持不变，只搬逻辑不改样式

### 步骤 4：验证 + 提交
- 5 个弹窗逐一真机打开、修改、确认持久化生效

### 步骤 5（可选）：抽备份恢复组（风险中，减 ~120 行）
- L303-424 的本地备份/恢复函数搬出为 BackupSection.kt
- 涉及权限请求回调 continuation，仔细核对

## 四、验证步骤（每个改动步骤都完整跑一遍）

1. `.\gradlew.bat :core:test :app:assembleDebug`（本地无 release 签名，用 debug）
2. `adb install -r` 覆盖安装
3. 核心路径回归：首页加载 → 搜索出结果 → 详情页 → 播放+弹幕 → 设置页各弹窗 → 片源页进出
4. **步骤 1 专属**：切一次主题（深夜 → 暖玉白 → 切回），确认全部文字/背景颜色正常
5. 焦点抽查：搜索页键盘 → 联想 → 结果区；设置页从上到下穿越不跳项
6. 真机观察 10 分钟无崩溃、无卡顿回退后，中文提交（不推送）

## 五、明确不做

- 不拆 showCatalog / settings() / 播放器：状态密集、焦点链手工接线，收益/风险比为负
- 不引入 ViewBinding / Compose / 依赖注入框架：纯搬函数，不改架构
- 不动 ThirdSourceScreen.kt 的同名工具函数（留待 MainActivity 稳定后再评估复用）
- 每步独立提交，不与功能开发混合；验证不过即 `git checkout -- .` 回滚该步
