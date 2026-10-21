// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ThirdCms 纯函数解析测试：分页容错、编码兜底、播放地址三层解析、TVBox 配置提取。 */
class ThirdCmsTest {

    // ---------- parseList ----------

    @Test
    fun `parseList 常规响应含分类与条目`() {
        val body = """
            {"code":1,"page":1,"pagecount":3,"limit":"20","total":48,
             "class":[{"type_id":1,"type_name":"电影"},{"type_id":2,"type_name":"剧集"}],
             "list":[
               {"vod_id":101,"vod_name":"测试剧","vod_pic":"http://p/1.jpg","vod_remarks":"HD"},
               {"vod_id":102,"vod_name":"第二部","vod_pic":"","vod_remarks":"更新至24集"}]}
        """.trimIndent()
        val page = ThirdCms.parseList(body)
        assertEquals(listOf("1" to "电影", "2" to "剧集"), page.categories.map { it.id to it.name })
        assertEquals(2, page.videos.size)
        assertEquals("测试剧", page.videos[0].name)
        assertEquals("HD", page.videos[0].remarks)
        assertTrue(page.hasMore)
        assertEquals(48, page.total)
    }

    @Test
    fun `parseList 缺 page 与 pagecount 时按条目数判尾页`() {
        val few = ThirdCms.parseList("""{"list":[${(1..5).joinToString(",") { """{"vod_id":$it,"vod_name":"剧$it"}""" }}]}""")
        assertFalse(few.hasMore)
        val full = ThirdCms.parseList("""{"list":[${(1..20).joinToString(",") { """{"vod_id":$it,"vod_name":"剧$it"}""" }}]}""")
        assertTrue(full.hasMore)
    }

    @Test
    fun `parseList 字段缺失的条目跳过且空列表判尾页`() {
        val page = ThirdCms.parseList("""{"list":[{"vod_pic":"http://p.jpg"},{"vod_id":"","vod_name":"无名"},{"vod_id":9,"vod_name":"有效"}]}""")
        assertEquals(1, page.videos.size)
        assertEquals("有效", page.videos[0].name)
        assertFalse(page.hasMore)
    }

    @Test
    fun `parseList 最后一页 page 等于 pagecount 时无更多`() {
        val page = ThirdCms.parseList("""{"page":3,"pagecount":3,"list":[{"vod_id":1,"vod_name":"剧"}]}""")
        assertFalse(page.hasMore)
    }

    // ---------- parsePlayGroups ----------

    @Test
    fun `parsePlayGroups 按$$$分组按#分集按名称$地址拆分`() {
        val groups = ThirdCms.parsePlayGroups(
            "liangzi\$\$\$lzm3u8",
            "第01集\$https://v.a/1.m3u8#第02集\$https://v.a/2.m3u8\$\$\$第01集\$https://v.b/1.mp4")
        assertEquals(2, groups.size)
        assertEquals("liangzi", groups[0].first)
        assertEquals(2, groups[0].second.size)
        assertEquals("第01集" to "https://v.a/1.m3u8", groups[0].second[0].label to groups[0].second[0].url)
        assertEquals("第02集" to "https://v.a/2.m3u8", groups[0].second[1].label to groups[0].second[1].url)
        assertEquals("lzm3u8", groups[1].first)
        assertEquals(listOf("第01集" to "https://v.b/1.mp4"), groups[1].second.map { it.label to it.url })
    }

    @Test
    fun `parsePlayGroups 无$的整段视为地址`() {
        val groups = ThirdCms.parsePlayGroups("测试", "https://v.a/1.m3u8#https://v.a/2.m3u8")
        assertEquals(2, groups[0].second.size)
        assertEquals("第1集", groups[0].second[0].label)
        assertEquals("https://v.a/2.m3u8", groups[0].second[1].url)
    }

    @Test
    fun `parsePlayGroups 空段跳过且保留源标签`() {
        val groups = ThirdCms.parsePlayGroups("源", "#第01集\$https://v.a/1##第03集\$https://v.a/3#")
        assertEquals(2, groups[0].second.size)
        assertEquals(listOf("第01集", "第03集"), groups[0].second.map { it.label })
        assertEquals("https://v.a/3", groups[0].second[1].url)
    }

    @Test
    fun `parsePlayGroups 组名缺失兜底线路N`() {
        val groups = ThirdCms.parsePlayGroups("量子", "第01集\$https://a/1\$\$\$第01集\$https://b/1\$\$\$第01集\$https://c/1")
        assertEquals("量子", groups[0].first)
        assertEquals("线路2", groups[1].first)
        assertEquals("线路3", groups[2].first)
    }

    @Test
    fun `parsePlayGroups 全空或地址为空返回空组`() {
        assertTrue(ThirdCms.parsePlayGroups("", "").isEmpty())
        assertTrue(ThirdCms.parsePlayGroups("源", "#\$#\$\$\$  ").isEmpty())
    }

    // ---------- defaultGroupIndex ----------

    @Test
    fun `defaultGroupIndex m3u8组优先`() {
        val groups = ThirdCms.parsePlayGroups(
            "下载\$\$\$lzm3u8",
            "第01集\$https://a/1.mp4\$\$\$第01集\$https://a/1.m3u8")
        assertEquals(1, ThirdCms.defaultGroupIndex(groups))
        assertEquals(0, ThirdCms.defaultGroupIndex(ThirdCms.parsePlayGroups("a\$\$\$b", "01\$https://x/1.mp4\$\$\$01\$https://y/2.mp4")))
    }

    // ---------- parseDetail ----------

    @Test
    fun `parseDetail 完整字段含简介去HTML与播放组`() {
        val body = """
            {"code":1,"list":[{
               "vod_id":55,"vod_name":"末日重生","vod_pic":"http://p/5.jpg","vod_remarks":"更新至08集",
               "type_name":"科幻片","vod_year":"2025",
               "vod_content":"<p>末日之下&nbsp;重获新生。</p>",
               "vod_play_from":"量子${'$'}${'$'}${'$'}lz",
               "vod_play_url":"第01集${'$'}https://v/1.m3u8#第02集${'$'}https://v/2.m3u8${'$'}${'$'}${'$'}第01集${'$'}https://v/1.mp4"}]}
        """.trimIndent()
        val detail = ThirdCms.parseDetail(body)!!
        assertEquals("55", detail.video.id)
        assertEquals("末日重生", detail.video.name)
        assertEquals("更新至08集", detail.video.remarks)
        assertEquals("科幻片", detail.typeName)
        assertEquals("2025", detail.year)
        assertEquals("末日之下 重获新生。", detail.synopsis)
        assertEquals(2, detail.groups.size)
        assertEquals(2, detail.groups[0].second.size)
    }

    @Test
    fun `parseDetail 空列表返回null`() {
        assertNull(ThirdCms.parseDetail("""{"list":[]}"""))
        assertNull(ThirdCms.parseDetail("""{"list":[{"vod_name":"无id"}]}"""))
    }

    @Test(expected = org.json.JSONException::class)
    fun `parseDetail 非法体抛异常由客户端层兜底`() {
        ThirdCms.parseDetail("not json")
    }

    // ---------- decode ----------

    @Test
    fun `decode UTF-8正常不降级`() {
        val text = "量子资源测试English123"
        assertEquals(text, ThirdCms.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decode GBK字节自动降级`() {
        val text = "末日重生第二季"
        val gbkBytes = text.toByteArray(charset("GBK"))
        // UTF-8 解码 GBK 字节会出现替换符，触发 GBK 兜底
        assertTrue(String(gbkBytes, Charsets.UTF_8).contains('\uFFFD'))
        assertEquals(text, ThirdCms.decode(gbkBytes))
    }

    // ---------- probeTotal ----------

    @Test
    fun `probeTotal 含list与total返回总数`() {
        assertEquals(165000, ThirdCms.probeTotal("""{"code":1,"total":165000,"list":[]}"""))
    }

    @Test
    fun `probeTotal total缺失取list长度`() {
        assertEquals(2, ThirdCms.probeTotal("""{"list":[{},{}]}"""))
        assertEquals(0, ThirdCms.probeTotal("""{"list":[]}"""))
    }

    @Test
    fun `probeTotal 缺list字段返回null`() {
        assertNull(ThirdCms.probeTotal("""{"code":404,"msg":"接口不存在"}"""))
    }

    // ---------- parseTvboxConfig ----------

    @Test
    fun `parseTvboxConfig 只保留type1的http源其余跳过计数`() {
        val body = """
            {"spider":"https://x/jar",
             "sites":[
               {"key":"a","name":"量子","type":1,"api":"https://cj.lziapi.com/api.php/provide/vod"},
               {"key":"b","name":"spider源","type":0,"api":"csp_XPath"},
               {"key":"c","name":"加密app源","type":3,"api":"https://app/enc"},
               {"key":"d","name":"类型缺失","api":"https://cms/vod"},
               {"key":"e","name":"本地源","type":1,"api":"clan://localhost/tvbox"},
               {"key":"f","name":"","type":1,"api":"https://ok/vod"}]}
        """.trimIndent()
        val parse = ThirdCms.parseTvboxConfig(body)
        assertEquals(2, parse.candidates.size)
        assertEquals("量子", parse.candidates[0].name)
        assertEquals("https://cj.lziapi.com/api.php/provide/vod", parse.candidates[0].api)
        assertEquals("未命名源", parse.candidates[1].name)
        assertEquals(4, parse.skipped)
    }

    @Test
    fun `parseTvboxConfig 缺sites返回空`() {
        val parse = ThirdCms.parseTvboxConfig("""{"spider":"jar"}""")
        assertTrue(parse.candidates.isEmpty())
        assertEquals(0, parse.skipped)
    }

    // ---------- apiUrl ----------

    @Test
    fun `apiUrl 按源地址是否自带query正确衔接并编码`() {
        assertEquals("https://a/vod?ac=list&pg=1", ThirdCms.apiUrl("https://a/vod", listOf("ac" to "list", "pg" to "1")))
        assertEquals("https://a/vod?at=json&ac=detail&wd=%E6%9C%AB%E6%97%A5", ThirdCms.apiUrl("https://a/vod?at=json", listOf("ac" to "detail", "wd" to "末日")))
    }

    // ---------- searchKey / dedupeHits ----------

    private fun detail(id: String, name: String, year: String?, episodes: Int): ThirdDetail {
        val list = (1..episodes).map { ThirdEpisode("第${it}集", "https://v/$id/$it.m3u8") }
        return ThirdDetail(ThirdVideo(id, name, "", ""), null, year, null, null, listOf("线路1" to list))
    }

    @Test
    fun `searchKey 去除书名号空白并统一大小写拼接年份`() {
        assertEquals("末日重生|2025", ThirdCms.searchKey("《末日 重生》", "2025"))
        assertEquals("abc|", ThirdCms.searchKey("ABC", null))
        assertEquals("abc|2024", ThirdCms.searchKey("  abc ", " 2024 "))
    }

    @Test
    fun `dedupeHits 同片名年份合并并优先当前源`() {
        val a = ThirdSource("a", "A源", "https://a/vod")
        val b = ThirdSource("b", "B源", "https://b/vod")
        val hits = listOf(
            ThirdSearchHit(b, detail("1", "末日重生", "2025", 30)),
            ThirdSearchHit(a, detail("2", "《末日重生》", "2025", 10)),
            ThirdSearchHit(a, detail("3", "另一部剧", "2024", 5))
        )
        val preferred = ThirdCms.dedupeHits(hits, a.api)
        assertEquals(2, preferred.size)
        assertEquals("2", preferred[0].detail.video.id)
        assertEquals("3", preferred[1].detail.video.id)
        // 无偏好时保留集数更多的 B 源
        assertEquals("1", ThirdCms.dedupeHits(hits, null)[0].detail.video.id)
    }

    @Test
    fun `dedupeHits 年份不同视为不同作品`() {
        val a = ThirdSource("a", "A源", "https://a/vod")
        val hits = listOf(
            ThirdSearchHit(a, detail("1", "同名剧", "2024", 10)),
            ThirdSearchHit(a, detail("2", "同名剧", "2025", 10))
        )
        assertEquals(2, ThirdCms.dedupeHits(hits, null).size)
    }
}
