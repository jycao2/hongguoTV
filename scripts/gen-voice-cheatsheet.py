# -*- coding: utf-8 -*-
"""从 VoiceIntent.kt / LanRemote.kt 导出语音指令速查表（docs/voice-cheatsheet.md）。

用法：python scripts/gen-voice-cheatsheet.py
改了词表或动作白名单就跑一遍，别手改文档——两边各说一套最常见。
"""
import re
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VI = os.path.join(ROOT, 'kotlin-tv', 'core', 'src', 'main', 'kotlin', 'com', 'hongguotv', 'core', 'VoiceIntent.kt')
LR = os.path.join(ROOT, 'kotlin-tv', 'core', 'src', 'main', 'kotlin', 'com', 'hongguotv', 'core', 'LanRemote.kt')
OUT = os.path.join(ROOT, 'docs', 'voice-cheatsheet.md')

vi = io.open(VI, encoding='utf-8').read()
lr = io.open(LR, encoding='utf-8').read()

rules_body = vi[vi.index('private val RULES'):vi.index('private val MODULES')]
mods_body = vi[vi.index('private val MODULES'):vi.index('/** 列表词')]

RULES = []
for m in re.finditer(r'Rule\(listOf\((.*?)\),\s*RemoteCommand\.Act\("([^"]+)"(?:,\s*"([^"]*)")?\),\s*"([^"]*)"\)',
                     rules_body, re.S):
    RULES.append(dict(words=re.findall(r'"([^"]*)"', m.group(1)),
                      key=m.group(2), arg=m.group(3) or '', echo=m.group(4)))

MODULES = []
for m in re.finditer(r'ModuleRule\(listOf\((.*?)\),\s*"([a-z]+)",\s*"([^"]*)"\)', mods_body, re.S):
    MODULES.append(dict(words=re.findall(r'"([^"]*)"', m.group(1)), key=m.group(2), label=m.group(3)))

white = lr[lr.index('val REMOTE_ACTIONS'):lr.index('/** `list.play` 的列表键')]
ACTIONS = [(k, c.strip()) for k, c in
           re.findall(r'"([a-z]+\.[a-z]+)"[ \t]*,?[ \t]*(?://[ \t]*(.*))?', white)]


def esc(text):
    """表格里不能出现裸竖线。"""
    return text.replace('|', '\\|')


def cell(words):
    return ' · '.join('「%s」' % w for w in words)


# 词表里不体现、只看 arg 也读不懂的键，以及注释写在上一行抓不到的那几个
ARG_NOTES = {
    'theme.set': 'dark / light',
    'volume.mute': 'on / off',
    'type.set': 'comic / short / ai',
    'random.pick': '空 = 当前类型；comic / short / ai',
    'sleep.set': 'min:<分钟> / ep:<集数>',
    'list.play': '<列表>:<序号>',
}
EXTRA_MEANING = {
    'volume.up': '系统媒体音量 +1 档',
    'volume.down': '系统媒体音量 -1 档',
    'volume.mute': '静音开关（arg 决定开 / 关）',
    'list.play': '定位列表第 N 条并播放，arg = `<列表>:<序号>`',
}


def key_cell(key, arg):
    note = arg or ARG_NOTES.get(key, '')
    return ('`%s`（%s）' % (key, esc(note))) if note else '`%s`' % key


SECTIONS = [
    ('三、播放控制', ['play.toggle', 'play.next', 'play.prev', 'play.replay', 'player.close',
                      'panel.episodes', 'skip.intro']),
    ('四、音量', ['volume.up', 'volume.down', 'volume.mute']),
    ('五、外观与弹窗', ['theme.toggle', 'theme.set', 'clock.toggle', 'backdrop.reset', 'dialog.close']),
    ('六、播放偏好', ['autonext.toggle', 'autoseason.toggle', 'danmaku.toggle', 'autoskip.toggle']),
    ('七、观看与收藏', ['resume.latest', 'favorite.toggle']),
    ('八、内容库与随机推荐', ['type.set', 'random.pick']),
    ('九、工具与弹窗', ['update.check', 'check.interface', 'cache.clear', 'stats.show', 'backup.show',
                        'quick.send', 'transfer.refresh', 'transfer.files', 'transfer.clear',
                        'history.clear', 'remote.address', 'xiaoai.show', 'account.show',
                        'hidden.restore']),
]

out = []
w = out.append
w('# 语音指令速查表')
w('')
w('> 由代码导出，**别手改**：说法来自 `core/VoiceIntent.kt` 的 `RULES`，动作键来自 `core/LanRemote.kt` 的')
w('> `REMOTE_ACTIONS`。改完词表跑 `python scripts/gen-voice-cheatsheet.py` 重新生成，省得两边各说一套。')
w('')
w('规模：**%d 组说法 / %d 个动作键 / %d 个模块**；另有 5 条走专用解析（剧名+集数、序数定位、快进快退、倍速、返回），'
  '因为它们的参数不是固定值，放进词表反而说不清。' % (len(RULES), len(ACTIONS), len(MODULES)))
w('')
w('## 三种入口，同一套命令')
w('')
w('| 入口 | 怎么用 |')
w('|---|---|')
w('| 手机遥控页 | 电视上说「遥控地址」，手机浏览器打开，输入框打字或键盘语音 |')
w('| 局域网 | `POST {遥控地址}/cmd`，body `{"cmd":"say","text":"检查更新"}` |')
w('| 小爱同学 | 巴法云主题把原话转发到电视，同样走 `VoiceIntent`（自建技能见 `xiaoai-voice.md`） |')
w('')
w('回执是电视上的短提示（Toast），不打断画面。所有说法都可以加「帮我 / 我要 / 我想 / 小爱同学」开头，')
w('句尾「一下 / 吧 / 呢」可省。')
w('')

w('## 一、搜片与选集')
w('')
w('| 说法 | 执行 | 说明 |')
w('|---|---|---|')
w('| 我要看文盲修仙 / 搜一下末世日常 / 播放斗罗大陆 | `watch(query)` | **只把结果摆出来**，不自动播 |')
w('| 我要看文盲修仙的第七集 / 第 12 话 / 第 3 期 | `watch(query, episode=7)` | 说清集号才直接落到那一集 |')
w('| 第七集 / 第5集（已经在看某剧） | `list.play episodes:7` | 当前这部剧的第 7 集 |')
w('| 历史记录第二个 / 收藏第 3 个 / 选集第五个 | `list.play <列表>:N` | 定位列表第 N 条并播放 |')
w('| 第二个（单独说，不带列表） | `list.play current:N` | 只在整句几乎只有序号时才认，防片名误判 |')
w('')
w('列表键：`history` 最近观看 / `favorite` 收藏 / `episodes` 选集 / `catalog` 当前列表 / `current` 当前页。')
w('搜不到就停在结果页提示换说法；搜到的剧没有正片（空壳、预告）同样只列结果、不进播放器；')
w('当前内容库搜不到会自动换库（漫剧 ⇄ 短剧）再搜一次。')
w('')

w('## 二、进度、倍速与定时')
w('')
w('| 说法 | 执行 | 说明 |')
w('|---|---|---|')
w('| 快进 30 秒 / 前进一分钟 / 往后 15 秒 | `step(+30000)` | 只说「快进」默认 15 秒，上限 10 分钟 |')
w('| 快退一分钟 / 后退 15 秒 / 倒回去 2 分钟 | `step(-60000)` | 同上 |')
w('| 1.5 倍速 / 两倍 / 半倍 | `speed(1.5)` | 落最近档位 |')
w('| 半小时后关掉 / 定时 30 分钟 / 看完这集就关 | `sleep.set`（`min:30` / `ep:1`） | 只开界面已有的档位 |')
w('')
w('片名保护：「黄石**第五季**」不会被当成第五个；「八倍镜」「三倍人生」不会被当成倍速；')
w('不带「倍速」的句子要求「倍」正好落在句尾。')
w('')

for title, keys in SECTIONS:
    w('## %s' % title)
    w('')
    w('| 说法（说中任意一个即可） | 动作键 | 回执 |')
    w('|---|---|---|')
    for key in keys:
        for r in [x for x in RULES if x['key'] == key]:
            w('| %s | %s | %s |' % (cell(r['words']), key_cell(r['key'], r['arg']), esc(r['echo'])))
    w('')

w('## 十、页面导航')
w('')
w('| 说法 | 模块键 | 回执 |')
w('|---|---|---|')
for m in MODULES:
    w('| %s | `%s` | 打开%s |' % (cell(m['words']), m['key'], m['label']))
w('| 返回上一级 / 返回上级 / 退一步 | `back` | 等价返回键（有弹窗先关弹窗） |')
w('| 返回首页 | `open home` | 走模块导航，不是返回键 |')
w('')

w('## 附录 A：动作键总表（%d 个）' % len(ACTIONS))
w('')
w('`act` 命令的 id 白名单。命名 `<对象>.<动词>`，键名一旦发布就当接口用——改文案不改键。')
w('')
w('| 动作键 | 含义 |')
w('|---|---|')
for k, c in ACTIONS:
    w('| `%s` | %s |' % (k, esc(c or EXTRA_MEANING.get(k, '—'))))
w('')

w('## 附录 B：协议命令')
w('')
w('| cmd | 参数 | 作用 |')
w('|---|---|---|')
w('| `open` | `module` | 跳模块，键见上表 |')
w('| `watch` | `query`，可选 `episode` | 搜片；带集号才直接开播 |')
w('| `say` | `text` | 整句话交给 `VoiceIntent` 解析（小爱走这条） |')
w('| `act` | `id`，可选 `arg` | 执行动作键 |')
w('| `search` | `query` | 搜索页填入关键词 |')
w('| `resume` | `id` | 续播指定剧 |')
w('| `play` | `on` | 播放 / 暂停 |')
w('| `seek` | `to`（毫秒） | 绝对定位 |')
w('| `step` | `delta`（毫秒，±10 分钟） | 相对跳转 |')
w('| `episode` | 集号或 `delta` | 切集 |')
w('| `speed` | `value` | 倍速 |')
w('| `muted` | `on` | 静音 |')
w('| `danmaku` | `on` | 弹幕开关 |')
w('| `dpad` | `key`（up/down/left/right/center），可选 `hold` | 方向键，hold 用于长按快进 |')
w('| `back` | — | 返回 |')
w('| `menu` | `long` | 菜单 / 长按快捷面板 |')
w('| `detach` | — | 断开遥控会话 |')
w('')

w('## 附录 C：容错边界（认不出时为什么不动）')
w('')
w('- 解析顺序：剧名+集数 → 列表序数 → 快进快退 → 倍速 → 定时 → 动作词表 → 模块 → 返回 → 模糊 → 当剧名搜索。')
w('- 模糊兜底要求**别名的每个字都出现**，两个动作得分并列就放弃。曾经放过 0.75 的宽松档，')
w('  「关闭弹窗」被吃成「关闭弹幕」——四个字错一个，语义可能整个拧了，所以宁可不猜。')
w('- 认不出任何动作就退化成「当剧名搜」；搜不到只提示，不硬播。')
w('')
w('## 附录 D：新增一条指令')
w('')
w('1. `core/LanRemote.kt` 的 `REMOTE_ACTIONS` 登记键。')
w('2. `app/MainActivity.kt` 的 `remoteAct` 加分支：成功返回 `true`，并给一句回执。')
w('3. `core/VoiceIntent.kt` 的 `RULES` 加说法（长词写前面）。')
w('4. 重新生成本文档，补一条单测。')
w('')

io.open(OUT, 'w', encoding='utf-8', newline='').write('\n'.join(out) + '\n')
print('写入', OUT)
print('规则组', len(RULES), '动作键', len(ACTIONS), '模块', len(MODULES))
print('词表未覆盖的键:', sorted(set(k for k, _ in ACTIONS) - set(r['key'] for r in RULES)))
print('词表里有、白名单没有:', sorted(set(r['key'] for r in RULES) - set(k for k, _ in ACTIONS)))
