// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

class L10nTest {

    @Test fun `简体默认原文输出`() {
        L10n.configure(L10n.ZH)
        assertEquals("设置", L10n.tr("设置"))
        assertEquals("正在加载剧集", L10n.tr("正在加载剧集"))
    }

    @Test fun `繁中走 opencc s2twp 词汇转换`() {
        L10n.configure(L10n.TW)
        assertEquals("設定", L10n.tr("设置"))
        assertEquals("網路", L10n.tr("网络"))
        assertEquals("影片", L10n.tr("视频")) // s2twp 词汇级：视频→影片
        assertEquals("資訊", L10n.tr("信息"))
    }

    @Test fun `繁中未命中回退原文`() {
        L10n.configure(L10n.TW)
        val rare = "一个绝对不会在字典里的测试文案XYZ"
        assertEquals(rare, L10n.tr(rare))
    }

    @Test fun `英文走词条字典`() {
        L10n.configure(L10n.EN)
        assertEquals("Settings", L10n.tr("设置"))
        assertEquals("Next Episode", L10n.tr("下一集"))
        assertEquals("Back", L10n.tr("返回"))
    }

    @Test fun `英文未覆盖回退中文`() {
        L10n.configure(L10n.EN)
        val rare = "一个绝对不会在字典里的测试文案XYZ"
        assertEquals(rare, L10n.tr(rare))
    }

    /** 词条里的换行与人工译文未必一一对上，空白差异不能导致整段回退中文（2026-11-27 实踩）。 */
    @Test fun `换行与空白差异仍能命中 - 英文`() {
        L10n.configure(L10n.EN)
        assertEquals("Play videos from a NAS on your LAN.\nConnection details stay on this device; re-enter them after reinstalling.",
            L10n.tr("接入同一局域网下的 NAS 即可直接播放影片。\n连接信息只保存在本机，卸载重装后需重新填写。"))
        assertEquals("Scan to download the current source config, or upload one to restore.\nExisting sources are kept; only missing ones are added.",
            L10n.tr("扫码打开：可下载当前源配置，或上传配置恢复到电视。\n已有源不覆盖，仅追加缺失。"))
        assertEquals("Scan to download the current source config, or upload one to restore.\nExisting sources are kept; only missing ones are added.",
            L10n.tr("扫码打开：可下载当前源配置，或上传配置恢复到电视。  已有源不覆盖，仅追加缺失。"))
    }

    /** 弹窗正文常是 appendLine 拼的整块说明：整块没词条，但每一行都是独立词条。 */
    @Test fun `多行说明逐行翻译 - 英文`() {
        L10n.configure(L10n.EN)
        val block = buildString {
            appendLine("小爱只会说「打开 / 关闭某个设备」，不会把整句传出来，所以约定：")
            appendLine("巴法云里一个主题就是一个动作，主题昵称就是小爱要喊的名字。")
            appendLine()
            appendLine("① cloud.bemfa.com 新建主题，如 hghs001、hgmod1001")
        }
        val translated = L10n.tr(block)
        assertEquals(true, translated.contains("Xiao Ai only says open/close, so:"))
        assertEquals(true, translated.contains("cloud.bemfa.com"))
        assertEquals(false, translated.contains("新建主题"))
        // 空行必须留着，否则整段挤成一团
        assertEquals(true, translated.contains("\n\n"))
        assertEquals(false, translated.contains("巴法云里一个主题"))
    }

    /** 带插值的整句：片段表按「句子骨架」替换，剧名等数据保持原样。 */
    @Test fun `带插值的整句按片段翻译 - 英文`() {
        L10n.configure(L10n.EN)
        assertEquals("Saved 《黄石第五季》", L10n.tr("已收藏《黄石第五季》"))
        assertEquals("Switched to 「红果片源」", L10n.tr("已切换到「红果片源」"))
        assertEquals("Checking 首页…", L10n.tr("正在检测 首页…"))
        assertEquals("Already up to date 0.35.0", L10n.tr("已是最新版本 0.35.0"))
        assertEquals("Remove from history 《黄石》? Watch progress will be cleared too.",
            L10n.tr("从观看历史中移除《黄石》？播放进度也会一起清除。"))
    }

    /** 片段表只动句子骨架，接口下发的剧名/题材名不能被啃掉。 */
    @Test fun `剧名不含骨架时保持原样`() {
        L10n.configure(L10n.EN)
        assertEquals("我的前半生", L10n.tr("我的前半生"))
        assertEquals("大生意人", L10n.tr("大生意人"))
    }

    @Test fun `换行与空白差异仍能命中 - 繁中`() {
        L10n.configure(L10n.TW)
        assertEquals("設定", L10n.tr("  设置  "))
    }

    @Test fun `带插值模板回填 - 命中字典`() {
        L10n.configure(L10n.EN)
        assertEquals("12 cloud watch records", L10n.trFmt("已读取 {0} 部云端观看记录", 12))
    }

    @Test fun `带插值模板回填 - 未命中回退后仍回填`() {
        L10n.configure(L10n.EN)
        assertEquals("一个测试 12", L10n.trFmt("一个测试 {0}", 12))
    }

    @Test fun `云端同步模板 - 整条命中不含中文残留`() {
        // trFmt 模板含 {0}，字典整条收录后按整串命中，不再靠片段替换拼出半中半英
        L10n.configure(L10n.EN)
        assertEquals("Read 3 cloud favorites", L10n.trFmt("已读取 {0} 部云端收藏", 3))
        assertEquals("Favorites synced: uploaded 5, failed 1", L10n.trFmt("收藏同步完成：上传 {0} 部，失败 {1} 部", 5, 1))
        assertEquals("Signed in as +8613800138000", L10n.trFmt("已登录 {0}", "+8613800138000"))
        assertFalse(L10n.trFmt("云端历史读取失败：{0}", "boom").contains("云端"))
        L10n.configure(L10n.TW)
        assertEquals("已讀取 3 部雲端收藏", L10n.trFmt("已读取 {0} 部云端收藏", 3))
        assertEquals("雲端歷史讀取失敗：boom", L10n.trFmt("云端历史读取失败：{0}", "boom"))
    }

    @Test fun `跟随系统 - 繁中地区`() {
        L10n.configure(L10n.FOLLOW_SYSTEM) { L10n.localeToLanguage(Locale("zh", "TW")) }
        assertEquals(L10n.TW, L10n.current())
        assertEquals("設定", L10n.tr("设置"))
    }

    @Test fun `跟随系统 - 港澳也走繁中`() {
        assertEquals(L10n.TW, L10n.localeToLanguage(Locale("zh", "HK")))
        assertEquals(L10n.TW, L10n.localeToLanguage(Locale("zh", "MO")))
    }

    @Test fun `跟随系统 - 简中与英文与未知`() {
        assertEquals(L10n.ZH, L10n.localeToLanguage(Locale("zh", "CN")))
        assertEquals(L10n.EN, L10n.localeToLanguage(Locale.ENGLISH))
        assertEquals(L10n.ZH, L10n.localeToLanguage(Locale.JAPANESE))
    }

    @Test fun `手动选择优先于系统`() {
        L10n.configure(L10n.EN) { L10n.TW }
        assertEquals(L10n.EN, L10n.current())
        assertEquals("Settings", L10n.tr("设置"))
    }

    @Test fun `非法语言代码回退跟随系统`() {
        L10n.configure("fr") { L10n.EN }
        assertEquals(L10n.EN, L10n.current())
    }

    @Test fun `字典导出供遥控页使用`() {
        assertEquals("設定", L10n.dictionary(L10n.TW)["设置"])
        assertEquals("Settings", L10n.dictionary(L10n.EN)["设置"])
        assertEquals(true, L10n.dictionary(L10n.ZH).isEmpty())
    }
}

class VoiceIntentEnglishTest {
    private fun plan(s: String) = VoiceIntent.parse(s)

    @Test fun `英文下一集`() {
        val p = plan("next episode") ?: throw AssertionError("null")
        assertEquals(RemoteCommand.Act("play.next"), p.command)
    }
    @Test fun `英文上一集`() {
        val p = plan("previous") ?: throw AssertionError("null")
        assertEquals(RemoteCommand.Act("play.prev"), p.command)
    }
    @Test fun `英文暂停`() {
        val p = plan("pause") ?: throw AssertionError("null")
        assertEquals(RemoteCommand.Act("play.toggle"), p.command)
    }
    @Test fun `英文导航`() {
        val p = plan("settings") ?: throw AssertionError("null")
        assertEquals(RemoteCommand.Open("settings"), p.command)
    }
    @Test fun `中文路径不受影响`() {
        val p = plan("下一集") ?: throw AssertionError("null")
        assertEquals(RemoteCommand.Act("play.next"), p.command)
    }
}
