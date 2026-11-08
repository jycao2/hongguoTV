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
{"evt": "ping", "u": "a1b2c3...", "v": "0.18.0", "c": 10126, "ts": 1792203419}
```

- `u` 随机安装 ID（每台设备首次启动生成、本地持久化）
- `v` 版本名、`c` versionCode、`ts` 函数端秒级时间戳
- 同一次调用的 Flask 访问日志行不匹配下面的正则，提取结果为 NULL，`count(DISTINCT ...)` 自动忽略，无需额外过滤。

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

每日活跃趋势（`SCF_StartTime` 为秒级 epoch；仅保留 7 天日志，故最长看近 7 日）：

```sql
SELECT date_format(from_unixtime(SCF_StartTime), '%Y-%m-%d') AS day,
       count(DISTINCT regexp_extract(SCF_Message, '"u"\s*:\s*"([^"]+)"', 1)) AS active_users
GROUP BY day ORDER BY day
```

总上报次数（不去重，反映调用/启动次数；仅统计 ping 行）：

```sql
SELECT count(*) AS pings
WHERE SCF_Message LIKE '%"evt": "ping"%'
```

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

实测基线（2026-11-08，近 7 天）：全局去重 **17,161 台**，分档相加 16,075 + 9 + 944 + 36 ≈ 17,161（对得上）。真用户（剔除 dead 与无法识别）**16,120 台**：`old`（0.23–0.30.8）**16,084（99.8%）**、`bridge`（≥0.30.9）**96（0.6%）** —— **离切轨差一个数量级，不可切轨**。

真用户按设备最高次版本：0.23=4,674 / 0.24=2,004 / 0.25=2,290 / 0.26=1,735 / 0.27=2,920 / 0.28=2,452 / 0.30=105。0.23 仍是最大单档（比第二名多 60%），说明这批设备从 0.23 之后就没再升过级，怀疑其更新链路早已静默失效，待查。

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
