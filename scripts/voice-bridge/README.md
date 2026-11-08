# 语音桥：一句话开播某剧 / 跳到某个模块

电视端从 `0.28.13-voice` 起，遥控命令口多了两条命令：

| 命令 | 作用 |
|---|---|
| `{"cmd":"watch","query":"黄石第五季"}` | 按片名搜一次，命中就直接起播（有进度就续播） |
| `{"cmd":"open","module":"source"}` | 直接跳到某个一级模块 |

`module` 可选：`home`（首页）、`search`、`rank`（排行榜）、`favorite`（收藏）、`history`（最近观看）、`settings`、`source`（片源）、`library`（本地库）。

命令口本来就是给手机遥控用的那个：`http://<电视IP>:40749/<token>/cmd`，地址在电视「设置 → 设备与数据 → 遥控地址」里。**同一个局域网内，任何能发 HTTP 的东西都能直接调它**——米家/小爱之所以还要一个桥，只是因为它们自己不会发 HTTP。

## 这个桥做什么

把一句中文翻成上面那两条命令之一：

```
"小爱同学，打开片源"        → {"cmd":"open","module":"source"}
"我想看黄石第五季"          → {"cmd":"watch","query":"黄石第五季"}
"回到首页"                  → {"cmd":"open","module":"home"}
```

只用标准库，Python 3.8+ 就能跑，NAS / 树莓派 / 常开的电脑都行。

```bash
cp bridge.json.example bridge.json     # 把 tv 填成电视上那条地址
python bridge.py --selftest            # 先看话术能不能听懂（不联网）
python bridge.py                       # 起服务，默认 0.0.0.0:8817
```

验证：

```bash
curl "http://127.0.0.1:8817/health"                       # {"ok":true,"tv_online":true}
curl "http://127.0.0.1:8817/say?text=打开片源"
curl "http://127.0.0.1:8817/say?text=我要看黄石第五季"
```

接口：

| 路径 | 说明 |
|---|---|
| `GET /say?text=…` | 自然语句，桥自己判断是开播还是跳模块 |
| `GET /open?module=source` | 直接指定模块 |
| `GET /watch?q=黄石` | 直接指定片名 |
| `GET /health` | 探活：电视地址与 token 对不对 |
| `POST /say` `{"text":"…"}` | 同上，给只会 POST 的平台用 |

配置里填了 `key` 的话，每个请求都要带 `?key=…`（POST 带 `"key"` 字段），只放在局域网里就够了。

## 三种接线方式

### A. Home Assistant（推荐，能接米家设备和小爱）

`configuration.yaml`：

```yaml
rest_command:
  hongguo_say:
    url: "http://192.168.31.44:8817/say?text={{ text | urlencode }}"
    method: GET
```

米家无线开关（或任意米家设备）通过官方 `xiaomi_home` 集成进 HA 后，写自动化：

```yaml
alias: 按一下开关就开播
trigger:
  - platform: state
    entity_id: event.mi_wireless_switch_button_1   # 换成你那个开关的实体
action:
  - service: rest_command.hongguo_say
    data:
      text: "我要看黄石第五季"
```

语音入口：米家 App →「我的」→「AI 训练计划」里新增一条，说「我要看黄石」时让那个开关执行一次开/关。
这样小爱负责听，开关负责触发，HA 负责转成 HTTP，全程不需要任何平台审核。

**不想跑桥**也行，HA 可以直接 POST 到电视（适合固定几句话）：

```yaml
rest_command:
  hongguo_watch:
    url: "http://192.168.31.38:40749/<token>/cmd"
    method: POST
    content_type: "application/json; charset=utf-8"
    payload: '{"cmd":"watch","query":"{{ query }}"}'
```

### B. 没有 HA，只有米家

米家场景的动作里没有「发 HTTP」这一项，只能二选一：

- 上米家万能遥控器（红外），学 TCL 遥控的方向键/确认键，做按键级操作（能开机、进 App、按几下，做不到"打开某部剧"）；
- 或者让桥跑在一台常开的机器上，用巴法云这类虚拟设备云把小爱的话变成本地 HTTP 请求再转发给桥。

### C. 手机快捷指令 / Tasker / Node-RED

一个 GET 就行：`http://<桥地址>:8817/say?text=我要看黄石第五季`。

## 限制

- 电视必须开机、和桥在同一网段。电视换 IP 或重装 App 后 token 不变，但**换网络**后地址会变，改一下 `tv` 即可。
- 桥只做"一句话翻译"，不替电视做搜索兜底：片源页搜不到就停在搜索结果页，不会乱播别的片。
- 片源模块里开播走的是聚合搜索，第一个返回的源结果就起播，所以同一部剧多点几次可能换源（各源清晰度不同）。
- 只认「片名关键词」：剧名里有季号就带上（`黄石第五季`），只说 `黄石` 会命中搜索首条。
