// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class BackupProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val completed: Boolean,val updatedAt: Long) {
    fun json()=JSONObject().put("series",LibraryBackup.seriesJson(series)).put("episodeId",episodeId).put("episodeIndex",episodeIndex).put("position",position).put("duration",duration).put("completed",completed).put("updatedAt",updatedAt)
}
data class BackupSettings(val quality: Int=1080,val speed: Float=1f,val autoNext: Boolean=true,val type: ContentType=ContentType.COMIC,val frame: VideoFrameMode=VideoFrameMode.FIT,val autoSeason: Boolean=true,val danmaku: Boolean=true,
    val danmakuOpacity: Int=DanmakuPrefs.OPACITY_DEFAULT,val danmakuFont: Int=DanmakuPrefs.FONT_DEFAULT,val danmakuSpeed: Int=DanmakuPrefs.SPEED_DEFAULT,val danmakuRows: Int=DanmakuPrefs.ROWS_DEFAULT) {
    fun json()=JSONObject().put("quality",quality).put("speed",speed.toDouble()).put("autoNext",autoNext).put("type",type.storedValue).put("frame",frame.name).put("autoSeason",autoSeason).put("danmaku",danmaku)
        .put("danmakuOpacity",danmakuOpacity).put("danmakuFont",danmakuFont).put("danmakuSpeed",danmakuSpeed).put("danmakuRows",danmakuRows)
}
data class BackupData(val favorites: List<Series>,val history: List<BackupProgress>,val watched: Set<String>,val searches: List<RecentSearch>,val settings: BackupSettings,val later: List<Series> = emptyList(),val hidden: Set<String> = emptySet(),val favoriteGroups: Map<String,String> = emptyMap())

object LibraryBackup {
    const val MAX_BYTES=2*1024*1024
    private val idPattern=Regex("[0-9]{1,30}")
    private fun id(value: String)=value.also { require(idPattern.matches(it)) { "剧集标识无效" } }
    private fun cleanCover(value: String): String=runCatching {
        val uri=URI(value); val host=uri.host.orEmpty().lowercase()
        value.takeIf { it.length<=2048 && uri.scheme=="https" && uri.userInfo==null && host.isNotEmpty() && !host.contains(':') && !host.matches(Regex("[0-9.]+")) && host!="localhost" && !host.endsWith(".local") && !host.endsWith(".lan") && value.none(Char::isISOControl) }.orEmpty()
    }.getOrDefault("")
    private fun cleanText(value: String,max: Int)=value.map { if(it.isISOControl()) ' ' else it }.joinToString("").take(max)
    fun seriesJson(series: Series)=series.copy(title=cleanText(series.title,200),cover=cleanCover(series.cover),description="",badge=cleanText(series.badge,100),tags=cleanText(series.tags,200)).toJson()
    fun encode(data: BackupData): String {
        val groups=JSONObject()
        data.favoriteGroups.forEach { (key,name) -> val clean=cleanText(name,20); if(clean.isNotBlank()) groups.put(key,clean) }
        val result=JSONObject().put("format","hongguotv.library").put("version",1).put("createdAt",System.currentTimeMillis())
            .put("later",JSONArray(data.later.map(::seriesJson))).put("hidden",JSONArray(data.hidden.toList()))
            .put("favorites",JSONArray(data.favorites.map(::seriesJson))).put("history",JSONArray(data.history.map { it.json() }))
            .put("favoriteGroups",groups)
            .put("watched",JSONArray(data.watched.intersect((data.favorites.map { it.id }+data.history.map { it.series.id }).toSet()).take(500)))
            .put("searches",JSONArray(SearchHistory.encode(data.searches.map { it.copy(query=cleanText(it.query,80)) }))).put("settings",data.settings.json()).toString()
        require(result.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        return result
    }
    private fun JSONObject.number(key: String,max: Long): Long {
        val value=get(key); require(value is Number) { "数值字段无效" }
        val decimal=value.toDouble(); val integer=value.toLong()
        require(decimal.isFinite() && decimal==integer.toDouble() && integer in 0..max) { "数值范围无效" }; return integer
    }
    private fun JSONObject.string(key: String,max: Int): String {
        val value=get(key); require(value is String && value.length<=max && value.none(Char::isISOControl)) { "文字字段无效" }; return value
    }
    private fun JSONObject.rows(key: String,max: Int): List<JSONObject> {
        val array=getJSONArray(key); require(array.length()<=max) { "记录数量超过上限" }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    private fun series(o: JSONObject): Series = Series(id(o.string("id",30)),o.string("title",200).also { require(it.isNotBlank()) },cleanCover(o.string("cover",2048)),"",o.string("badge",100),o.string("tags",200))
    fun decode(value: String): BackupData {
        require(value.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "备份超过 2 MB 限制" }
        var depth=0; var quoted=false; var escaped=false
        value.forEach { c ->
            if(escaped) escaped=false
            else if(quoted && c=='\\') escaped=true
            else if(c=='"') quoted=!quoted
            else if(!quoted) { if(c=='{' || c=='[') { depth++; require(depth<=12) { "备份结构过深" } }; if(c=='}' || c==']') depth-- }
        }
        val o=JSONObject(value)
        require(o.getString("format")=="hongguotv.library" && o.number("version",1)==1L) { "不支持的备份格式或版本" }
        val later=if(o.has("later")) o.rows("later",100).map(::series) else emptyList()
        require(later.distinctBy { it.id }.size==later.size) { "稍后看包含重复剧集" }
        val hidden=if(o.has("hidden")) o.getJSONArray("hidden") else JSONArray()
        require(hidden.length()<=500)
        val hiddenIds=(0 until hidden.length()).map { id(hidden.getString(it)) }.toSet()
        val favorites=o.rows("favorites",500).map(::series)
        val groupsJson=o.optJSONObject("favoriteGroups")
        val favoriteGroups=mutableMapOf<String,String>()
        if(groupsJson!=null) {
            require(groupsJson.length()<=500) { "分组数量超过上限" }
            groupsJson.keys().forEach { key ->
                val name=groupsJson.get(key)
                require(name is String && name.isNotBlank() && name.length<=20 && name.none(Char::isISOControl)) { "分组名称无效" }
                favoriteGroups[id(key)]=name
            }
        }
        val history=o.rows("history",200).map { row ->
            val duration=row.number("duration",604_800_000)
            BackupProgress(series(row.getJSONObject("series")),id(row.string("episodeId",30)),row.number("episodeIndex",100_000).toInt(),row.number("position",604_800_000).let { if(duration>0) minOf(it,duration) else it },duration,row.get("completed").also { require(it is Boolean) } as Boolean,row.number("updatedAt",4_102_444_800_000))
        }
        require(favorites.distinctBy { it.id }.size==favorites.size && history.distinctBy { it.series.id }.size==history.size) { "备份包含重复剧集" }
        val watched=o.getJSONArray("watched"); require(watched.length()<=500)
        val watchedIds=(0 until watched.length()).map { id(watched.getString(it)) }.toSet()
        val searches=o.rows("searches",20).map { row -> RecentSearch(row.string("query",80).also { require(it.isNotBlank()) },ContentType.entries.firstOrNull { it.storedValue==row.getString("type") } ?: error("搜索类型无效")) }
        val settings=o.getJSONObject("settings")
        val quality=settings.number("quality",1080).toInt(); require(quality in PlaybackQuality.options)
        val speed=settings.get("speed"); require(speed is Number && speed.toFloat() in PlaybackSpeed.options)
        val autoNext=settings.get("autoNext"); require(autoNext is Boolean)
        val type=ContentType.entries.firstOrNull { it.storedValue==settings.getString("type") } ?: error("内容类型无效")
        val frame=VideoFrameMode.entries.firstOrNull { it.name==settings.getString("frame") } ?: error("画面模式无效")
        val autoSeason=settings.optBoolean("autoSeason",true)
        val danmaku=settings.optBoolean("danmaku",true)
        val danmakuOpacity=DanmakuPrefs.opacity(settings.optInt("danmakuOpacity",DanmakuPrefs.OPACITY_DEFAULT))
        val danmakuFont=DanmakuPrefs.font(settings.optInt("danmakuFont",DanmakuPrefs.FONT_DEFAULT))
        val danmakuSpeed=DanmakuPrefs.speed(settings.optInt("danmakuSpeed",DanmakuPrefs.SPEED_DEFAULT))
        val danmakuRows=DanmakuPrefs.rows(settings.optInt("danmakuRows",DanmakuPrefs.ROWS_DEFAULT))
        return BackupData(favorites,history,watchedIds,searches,BackupSettings(quality,speed.toFloat(),autoNext,type,frame,autoSeason,danmaku,danmakuOpacity,danmakuFont,danmakuSpeed,danmakuRows),later,hiddenIds,favoriteGroups)
    }
    /** Existing favorites stay first; only newer imported progress replaces a local record. */
    fun merge(local: BackupData,incoming: BackupData,restoreSettings: Boolean): BackupData {
        val favorites=(local.favorites+incoming.favorites).distinctBy { it.id }.take(500)
        val records=local.history.associateBy { it.series.id }.toMutableMap()
        val watched=local.watched.toMutableSet()
        incoming.history.forEach { candidate ->
            val id=candidate.series.id; val prior=records[id]
            if(prior==null || candidate.updatedAt>prior.updatedAt) {
                records[id]=candidate
                if(id in incoming.watched) watched.add(id) else watched.remove(id)
            }
        }
        incoming.watched.filter { id -> id !in records && local.favorites.none { it.id==id } }.forEach { watched.add(it) }
        val history=records.values.sortedByDescending { it.updatedAt }.take(200)
        val retained=(favorites.map { it.id }+history.map { it.series.id }).toSet()
        val favoriteIds=favorites.map { it.id }.toSet()
        val favoriteGroups=(local.favoriteGroups+incoming.favoriteGroups).filterKeys { it in favoriteIds }
        return BackupData(favorites,history,watched.intersect(retained).take(500).toSet(),(local.searches+incoming.searches).distinct().take(20),if(restoreSettings) incoming.settings else local.settings,(local.later+incoming.later).distinctBy { it.id }.take(100),(local.hidden+incoming.hidden).take(500).toSet(),favoriteGroups)
    }
}
