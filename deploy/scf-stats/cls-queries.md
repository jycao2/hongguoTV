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

## 备注

- 日志只含随机 ID 与版本号，不含 IP、机型、内容等信息；SCF 不解析、不额外落库请求来源 IP。
- 2026-10-17 已用 4 条测试上报（其中同一 ID 重复 2 次）验证：累计去重 4（含早期 1 条手工测试）、版本分布 0.17.1=3 / 0.17.0=1，去重行为正确。
