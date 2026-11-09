# 片源模块功能示例与真机验证清单

> 适用版本：0.30.11 起的片源模块（本地未发版提交 0c7856f / 4e99c40 / ca6e456）。
> 对应能力：每源自定义请求头、TVBox site 元数据、CMS v1 XML 接口（type 0）。

## 1. 每源自定义请求头（site.header）

来源：TVBox 配置里每个 site 的 `header` 字段。导入配置或探活时自动携带；播放取流（m3u8/mp4）同样带上。

### JSON 对象形态

```json
{
  "sites": [
    {
      "name": "某防盗链站",
      "api": "https://example1.com/api.php/provide/vod",
      "type": 1,
      "header": {
        "User-Agent": "okhttp/4.9.0",
        "Referer": "https://example1.com/"
      }
    }
  ]
}
```

### 字符串形态（分号/逗号分隔的 k=v）

```json
{
  "sites": [
    {
      "name": "某UA校验站",
      "api": "https://example2.com/vod",
      "type": 1,
      "header": "User-Agent=okhttp/4.9.0;Referer=https://example2.com/"
    }
  ]
}
```

**规则**：
- 键值都做 trim，空键/空值丢弃；
- 自定义头逐条覆盖默认浏览器 UA（OkHttp 头名不区分大小写）；
- 只写 `User-Agent` 时只覆盖 UA，其余请求头仍用默认。

## 2. site 元数据字段

| 字段 | 默认 | 作用 |
|---|---|---|
| `searchable` | true | `false` 时聚合搜索跳过该源（该源单源搜索仍可用，失败会给明确原因） |
| `quickSearch` | true | `false` 时输入联想（边输边搜）不请求该源 |
| `filterable` | true | 仅解析并存储，暂无 UI 行为（保留以兼容 TVBox 配置） |
| `categories` | 全部 | 首页只显示列出的分类 id，按响应顺序保留；空/缺省不裁剪 |
| `timeout` | 10 秒 | 该源的请求超时（**秒**，0 用默认，上限 60，超出钳制） |

布尔字段兼容 `true/false`、`0/1`（数字与字符串）三种写法。

### 完整示例

```json
{
  "sites": [
    {
      "name": "慢速大站",
      "api": "https://slow.example.com/api.php/provide/vod",
      "type": 1,
      "searchable": 1,
      "quickSearch": 0,
      "filterable": 1,
      "categories": ["1", "2", 6],
      "timeout": 30,
      "header": {"User-Agent": "okhttp/4.9.0"}
    }
  ]
}
```

效果：聚合搜索参与但联想不参与；首页只显示分类 1/2/6；单源超时 30 秒；请求带自定义 UA。

## 3. CMS v1 XML 接口（type 0）

TVBox 配置里 `"type": 0` 且 api 是 http(s) 的源现在会被导入（此前直接跳过），按老苹果 CMS v1 协议请求：

| 操作 | 请求 | 响应 |
|---|---|---|
| 分类+列表 | `?ac=videolist&pg=1`（可选 `&t=分类id`） | `<rss>` XML |
| 详情 | `?ac=videolist&ids=123` | `<rss>` XML（含 `<dl><dd flag="线路">`） |
| 搜索 | `?ac=videolist&wd=片名` | `<rss>` XML |
| 探活 | `?ac=videolist&pg=1` | 解出列表结构即通过 |

```json
{
  "sites": [
    {"name": "老站XML源", "api": "https://old.example.com/api.php/provide/vod", "type": 0}
  ]
}
```

**说明**：
- XML 可能是 GBK 编码——沿用现有编码兜底（UTF-8 失败按 GBK 重解）；
- 播放地址格式与 JSON 版一致（`第01集$url#第02集$url`，`$$$` 分线路），线路切换、倍速、片头跳过等功能全部通用；
- `type: 3`（spider jar）与 `type: 4`（JS 源）仍跳过并计入「跳过数」。

## 4. 真机手动验证清单

前置：设置 → 片源管理。

**每源 header**
- [ ] 用含 `header` 的配置导入 → 导入预览里该源「可连接」（探活带自定义头）
- [ ] 进入该源首页/详情/播放 → 均正常（此前不带头连不上的站应能接通）
- [ ] 不带 header 的普通源回归：浏览、搜索、播放不受影响

**site 元数据**
- [ ] `searchable: 0` 的源 → 聚合搜索结果里没有它，进度条 N/M 也不计入
- [ ] `quickSearch: 0` 的源 → 输入联想无该源结果，整词搜索仍可用
- [ ] `categories: ["1","2"]` 的源 → 首页分类栏只剩指定分类
- [ ] `timeout: 30` 的慢源 → 不再在 10 秒报「连接超时」（可在无头站验证）

**type 0 XML**
- [ ] 导入含 type 0 的配置 → 「导入所选」数量包含 XML 源（之前被计入跳过数）
- [ ] XML 源首页分类与封面正常，分页下滑正常
- [ ] XML 源详情 → 选集 → 播放 → 线路切换（多 `dd flag` 的源）
- [ ] XML 源搜索出结果并可播放
- [ ] 源管理里对 XML 源点「测试」→ 显示可连接 · 片量

**持久化回归**
- [ ] 杀进程重开 → 源还在，带 header/type/timeout 的源行为不回退（设置落在 third-sources-v1）
- [ ] 备份导出/恢复 → 源列表恢复（注意：备份只迁名称与接口，header/type/超时不随备份，恢复后回落默认值）

## 5. 已知限制

- 备份恢复（BackupThirdSource）只含名称与接口地址；headers/type/元数据不随备份迁移，恢复后为默认值。重新导入一次原配置即可补齐。
- `filterable` 目前只解析存储，未接 UI（与官方 TVBox 的筛选参数联动属于后续增强）。
- spider jar / JS 源（type 3/4）不支持，配置里会跳过。
- XML 源的 `<dl><dd>` 若用 `$$$` 嵌套多线路的内容格式（罕见），按单一内容解析，可能少线路。

## 6. 网络代理（DNS 污染 / SNI 阻断场景）

部分片源域名会被运营商上游链路拦截：域名被解析到假 IP（DNS 污染），或 TLS 握手阶段被掐断（SNI 阻断）。**换 DNS、DoH 均无效**——污染发生在更上游，加密查询返回的也是假地址。唯一应用侧出路是让流量走局域网内一台不被拦截的代理（电脑上运行 Clash/mihomo 等即可）。

### TV 端用法

设置 → 实验室功能 → **网络代理**：

- 填电脑的局域网 IP 和代理端口（Clash 默认混合端口 `7890`，需开启「允许局域网连接 Allow LAN」）；
- 「测试连接」验证电脑代理可达；「扫码填写」可用手机回填，免遥控输入；
- 启用后全部网络流量走代理；**局域网地址（快传 / 遥控 / WebDAV）始终直连**，不受影响；
- 配置持久化，重启保持；「停用」随时关闭，运行中切换立即全量生效。

### PC 端要点（Clash / mihomo）

```yaml
mixed-port: 7890
allow-lan: true
bind-address: "*"
```

- Windows 防火墙默认拦截局域网入站，需为代理内核程序放行入站规则（程序级比端口级可靠）；
- 若电脑上是 FlClash：核心必须经 GUI 启动，**代理开关未打开时核心会静默拒绝所有代理流量**（连接被 RST、无任何日志），务必在 GUI 里确认代理已开启；
- 电视已保存的端口与 PC 实际监听端口要一致，改端口可在 mihomo 运行时通过 REST API `PATCH /configs` 调整，无需重启。

### 真机验证记录（2026-11-10）

- 电视 → PC mihomo（0.0.0.0:7890）→ 香港节点：被污染域名的 API 返回真实数据（片量 125138），列表与海报全部经代理加载成功；视频 CDN 域名确认进入代理隧道；
- 防火墙恢复启用 + 程序级放行规则后，连接保持 ESTABLISHED。
