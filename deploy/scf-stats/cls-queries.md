# 红果TV 装机统计 — CLS 查询手册

## 资源位置

- 地域：广州（ap-guangzhou）
- 函数：SCF `hongguotv-stats`
- 日志集：`SCF_logset_wswonCho`
- 日志主题：`SCF_logtopic_zifwZlqr`（topic ID `54038a8e-4355-4706-8436-648636fde31e`）
- 日志保留：7 天（标准存储）
- 检索页：`https://console.cloud.tencent.com/cls/search?region=ap-guangzhou&topic_id=54038a8e-4355-4706-8436-648636fde31e&topicType=log`

## 字段结构

SCF Web 函数每次调用会写多行日志，App 上报行的 `SCF_Message` 形如：

```json
{"evt": "ping", "u": "a1b2c3...", "v": "0.18.0", "c": 10126, "a": "9f2c1d...", "s": 28, "ts": 1792203419}
```

- `u` 随机安装 ID（每台设备首次启动生成、存在 SharedPreferences，**卸载重装会换新的**）
- `v` 版本名、`c` versionCode、`ts` 函数端秒级时间戳
- `a` **设备去重键**：`ANDROID_ID` 的 SHA-256 前 16 位（小写 hex），重装不变。**0.30.11 起才有**，之前的行为空
- `s` 系统 API 级别（`Build.VERSION.SDK_INT`），**0.30.11 起才有**，之前的行为空
- 同一次调用的 Flask 访问日志行不匹配下面的正则，提取结果为 NULL，`count(DISTINCT ...)` 自动忽略，无需额外过滤。

`a` / `s` 在服务端是**可选字段**：格式不合法就丢弃该字段、照常入库，绝不因为新字段把整条 ping 拒掉，
否则老客户端或异常机型会直接把统计打空。

无需配置 JSON 键值索引：直接对已索引的 `SCF_Message` 文本字段用 `regexp_extract` 提取，历史数据也能查。
（在「统计图表」SQL 框执行；时间范围在页面顶部时间选择器切换。）

## 常用 SQL

真实客户端的 `u` 恒为 32 位小写 hex；上线前手工测试用的是 `test-...` / `demo-...` 形态的 ID，
累计/活跃指标统一只统计 hex-32 的 ID，测试数据自动排除：

累计安装量（时间窗选「全部日志」或任意区间，去重设备数）：

```sql
SELECT count(DISTINCT CASE WHEN regexp_like(regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1), '^[0-9a-f]{32}$')
                           THEN regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1) END) AS installs
```

DAU / WAU / MAU：时间选择器分别选「近 1 天 / 近 7 天 / 近 30 天」，执行同一条：

```sql
SELECT count(DISTINCT CASE WHEN regexp_like(regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1), '^[0-9a-f]{32}$')
                           THEN regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1) END) AS active_users
```

版本分布（区间内每个版本的去重设备数）：

```sql
SELECT regexp_extract(SCF_Message, '"v"\s*:\s*"([^"]+)"', 1) AS ver,
       count(DISTINCT regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1)) AS installs
GROUP BY ver ORDER BY installs DESC
```

每日活跃趋势（**`SCF_StartTime` 是毫秒级 epoch，必须 `/1000`**；不除的话 `from_unixtime` 会算出 58805 年这种鬼日期，按天分桶全错）：

```sql
SELECT date_format(from_unixtime(CAST(SCF_StartTime AS BIGINT) / 1000), '%Y-%m-%d') AS day,
       count(DISTINCT regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1)) AS active_users
GROUP BY day ORDER BY day
```

总上报次数（不去重，反映调用/启动次数；仅统计 ping 行）：

```sql
SELECT count(*) AS pings
WHERE SCF_Message LIKE '%"evt": "ping"%'
```

## 真实设备数（跨重装去重，0.30.11 起可用）

`u`（install_id）卸载重装就换新的，`a`（ANDROID_ID 哈希）重装不变。两者一起看就能算出重装虚高了多少：

```sql
* | SELECT count(DISTINCT a) AS real_devices,
       count(DISTINCT u) AS install_ids,
       round(count(DISTINCT u) * 1.0 / count(DISTINCT a), 2) AS id_per_device
FROM (
  SELECT nullif(regexp_extract(SCF_Message, '"a"\s*:\s*"([0-9a-f]{16})"', 1), '') AS a,
         nullif(regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1), '') AS u
  FROM log WHERE SCF_Message LIKE '%"evt": "ping"%'
)
```

`id_per_device` 就是「平均每台设备产生几个安装 ID」：接近 1 说明几乎没人重装，17,157 那类数字可信；
明显大于 1 说明同一台电视在反复重装，真实设备数要按 `real_devices` 算。
**注意：`a` 为空的都是 0.30.11 之前的行，不参与计算，所以这两个数只覆盖新版本设备，不能拿去跟老口径的总量直接比。**

Android ／ API 级别分布（回答「到底还有多少 Android 5–7 的设备」）：

```sql
* | SELECT s AS api_level, count(DISTINCT a) AS devices
FROM (
  SELECT nullif(regexp_extract(SCF_Message, '"a"\s*:\s*"([0-9a-f]{16})"', 1), '') AS a,
         CAST(nullif(regexp_extract(SCF_Message, '"s"\s*:\s*([0-9]+)', 1), '') AS INT) AS s
  FROM log WHERE SCF_Message LIKE '%"evt": "ping"%'
)
WHERE s IS NOT NULL GROUP BY s ORDER BY api_level DESC
```

对应关系：21–22 = Android 5.0/5.1，23 = 6.0，24–25 = 7.0/7.1，26–27 = 8.0/8.1，28 = 9，29 = 10，30 = 11，31–32 = 12，33 = 13，34 = 14。
`update_fail` 行里也有 `"s"` 字段（那里是失败阶段），所以正则只取纯数字，两类行不会串。

## 发版门禁：版本分布与更新失败

切换更新通道（例如把清单 minSdk 由 26 降到 21、让存量设备改吃 5.0 包）前，必须先看这两组数据。

**口径前提（重要）**：应用版本 **< 0.23 的客户端已无法播放**，属于无效用户，统计时整档剔除，不参与门禁计算。

```sql
* | SELECT CASE WHEN k IS NULL THEN 'unknown'
                WHEN k < 2300 THEN 'dead(<0.23)'
                WHEN k >= 3009 THEN 'bridge(>=0.30.9)'
                ELSE 'old(0.23-0.30.8)' END AS tier,
       count(*) AS devices
FROM (
  -- 每台设备取窗口内最高版本，再分档：逐版本 count(DISTINCT u) 后相加会把
  -- 「一周内从 0.27 升到 0.28」的设备算两次（实测虚高 61%）
  SELECT uid, max(k) AS k FROM (
    SELECT regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1) AS uid,
           CAST(regexp_extract(regexp_extract(SCF_Message, '"v"\s*:\s*"([^"]+)"', 1), '^0\.([0-9]+)\.([0-9]+)', 1) AS INT) * 100
         + CAST(regexp_extract(regexp_extract(SCF_Message, '"v"\s*:\s*"([^"]+)"', 1), '^0\.([0-9]+)\.([0-9]+)', 2) AS INT) AS k
    FROM log WHERE SCF_Message LIKE '%"evt": "ping"%'
  ) GROUP BY uid
) GROUP BY tier ORDER BY devices DESC
```

校验方式：四个档位的 `devices` 相加必须**等于** `count(DISTINCT u)` 的全局总数；不等就说明口径写错了。
更省事的写法是让 SQL 自己保证闭合（一次性输出总数和四档，不做手工相加）：

```sql
* | SELECT count(*) AS total_devices,
       sum(CASE WHEN k IS NULL THEN 1 ELSE 0 END) AS unknown_,
       sum(CASE WHEN k < 2300 THEN 1 ELSE 0 END) AS dead,
       sum(CASE WHEN k >= 2300 AND k < 3009 THEN 1 ELSE 0 END) AS old,
       sum(CASE WHEN k >= 3009 THEN 1 ELSE 0 END) AS bridge
FROM (
  SELECT uid, max(k) AS k FROM (
    SELECT nullif(regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1), '') AS uid,
           CAST(nullif(regexp_extract(regexp_extract(SCF_Message, '"v"\s*:\s*"([^"]+)"', 1), '^0\.([0-9]+)\.([0-9]+)', 1), '') AS INT) * 100
         + CAST(nullif(regexp_extract(regexp_extract(SCF_Message, '"v"\s*:\s*"([^"]+)"', 1), '^0\.([0-9]+)\.([0-9]+)', 2), '') AS INT) AS k
    FROM log WHERE SCF_Message LIKE '%"evt": "ping"%'
  ) GROUP BY uid
)
```

实测基线（2026-11-08 23:50，近 7 天，日志保留上限）：全局去重 **17,157 台**。

| 档位 | 设备数 | 说明 |
|---|---|---|
| dead（最高版本 < 0.23） | **942** | 剔除对象 |
| old（0.23–0.30.8） | **16,082** | 99.8% |
| bridge（≥ 0.30.9） | **97** | 0.6% |
| unknown（版本名非法） | 36 | 剔除对象，见下 |

闭合校验：942 + 16,082 + 97 + 36 = **17,157** ✓；与独立口径 `count(DISTINCT uid)` = 17,157 一致 ✓。
真用户（剔除 dead 与 unknown）= **16,179**。`bridge : old ≈ 1 : 166` —— **离切轨差两个数量级，不可切轨**。

三点必须注意的口径细节：

1. **dead 是「窗口内最高版本仍 < 0.23」**，不是「出现过 < 0.23」。出现过 < 0.23 的设备实际有 1,462 台，其中 **520 台已升上来**，这 520 台不属于 dead，不能剔。
2. **unknown 36 台是垃圾数据**，版本名为 `1.0.0`(16) / `3.1`(16) / `1.0.1`(4) / `v13`(1) / `6.24.1`(1)，不是本项目的版本号（疑似外部调用方复用同一接口），必须剔除，不进分母。
3. 版本名普遍带 `+NN` 后缀（如 `0.23.1+42`），正则只取前三段，后缀不影响分档；`0.30.9`→3009、`0.30.10`→3010 不会倒挂。

**这个数不能当"用户数"用**，误差来源已实测：

- **窗口只有 7 天**（日志保留上限），是「近 7 天启动过的安装实例」，不是存量。电视是低频设备，7 天没开机的那部分完全不可见，可能是大头。
- **`install_id` 存在 SharedPreferences**，卸载重装 / 清数据 / 恢复出厂都会换新 ID，同一台电视可能被计多次。实测 7 天内：设备日均约 4,200，但每天新增 1,500–3,400 个 ID、**62.7% 的实例只活跃 1 天**。不过 11-02 那批新出现设备的回访率是 **52%**（2,213/4,244），说明 ID 总体稳定，重复计数不占主导。真实物理设备数应略低于 17,157，但**不影响切轨判断**（1:166 的量级差，打七折也不变结论）。
- **右截断假象**：最近几天才出现的设备观察期短，会把平均活跃天数压低。0.28 档平均只有 1.06 天不是留存差，是它最新、观察窗口最短。

新装设备装的是什么版本（判断渠道在发哪个包，2026-11-06 后首次出现的 8,495 台）：

| 版本 | 新增设备 |
|---|---|
| 0.27.0+49 | 2,723 |
| 0.28.0+50 | 1,589 |
| 0.26.1+48 | 1,249 |
| 0.23.0+41 / 0.23.1+42 | 1,015 / 691 |
| 0.25.0+45 | 249 |
| **0.30.10+57** | **30** |
| **0.30.9+54** | **16** |

**结论：新装用户装的几乎全是 0.23–0.28 的旧包，最新版 3 天只进来 46 台。** 两个可能：渠道分发的是旧 APK；或存量设备更新链路对 0.23–0.28 早已静默失效、新 ID 只是重装产物。两者都指向「先把更新链路修好」，而不是切轨。

查询语句：

```sql
* | SELECT v AS ver, count(DISTINCT uid) AS new_devices
FROM (
  SELECT nullif(regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1), '') AS uid,
         regexp_extract(SCF_Message, '"v"\s*:\s*"([^"]+)"', 1) AS v,
         date_format(from_unixtime(CAST(SCF_StartTime AS BIGINT) / 1000), '%Y-%m-%d') AS d
  FROM log WHERE SCF_Message LIKE '%"evt": "ping"%'
) WHERE uid IN (
  SELECT uid FROM (
    SELECT nullif(regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1), '') AS uid,
           min(date_format(from_unixtime(CAST(SCF_StartTime AS BIGINT) / 1000), '%Y-%m-%d')) AS fd
    FROM log WHERE SCF_Message LIKE '%"evt": "ping"%'
    GROUP BY uid
  ) WHERE fd >= '<起始日，如 2026-11-06>'
)
GROUP BY v ORDER BY new_devices DESC LIMIT 25
```

更新失败分布（按阶段 + 目标版本，定位「某个版本在某批机型装不上」）：

```sql
SELECT regexp_extract(SCF_Message, '"s"\s*:\s*"([^"]+)"', 1) AS stage,
       regexp_extract(SCF_Message, '"t"\s*:\s*"([^"]+)"', 1) AS target,
       count(*) AS fails,
       count(DISTINCT regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1)) AS devices
FROM log
WHERE SCF_Message LIKE '%"evt": "update_fail"%'
GROUP BY stage, target ORDER BY fails DESC
```

阶段含义：`parse` 清单解析失败（多为旧客户端遇到低 minSdk 声明）、`download` 下载失败、`verify` 包校验/元信息不符、`install` 系统安装入口被拒。

## 备注

- 日志只含随机 ID 与版本号，不含 IP、机型、内容等信息；SCF 不解析、不额外落库请求来源 IP。
- 2026-10-17 已用 4 条测试上报（其中同一 ID 重复 2 次）验证：累计去重 4（含早期 1 条手工测试）、版本分布 0.17.1=3 / 0.17.0=1，去重行为正确。
