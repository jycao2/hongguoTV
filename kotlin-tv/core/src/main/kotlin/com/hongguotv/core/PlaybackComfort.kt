// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

enum class VideoFrameMode(val label: String) {
    FIT("完整画面"), ZOOM("等比铺满"), FILL("拉伸铺满");
    companion object { fun fromStored(value: String?)=entries.firstOrNull { it.name==value } ?: FIT }
}

/** Per-series skip preference: intro seconds skipped on start, outro seconds end the episode early. */
data class SkipMark(val introSeconds: Int,val outroSeconds: Int) {
    init { require(introSeconds in 0..600 && outroSeconds in 0..600) }
    val empty get()=introSeconds==0 && outroSeconds==0
}

/** Device-local JSON store; intentionally outside the backup so each TV keeps its own marks. */
object SkipMarks {
    const val MAX_ENTRIES=200
    fun decode(raw: String?): LinkedHashMap<String,SkipMark> {
        val result=linkedMapOf<String,SkipMark>()
        val json=runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return result
        for(id in json.keys().asSequence().toList().asReversed()) {
            if(!id.matches(Regex("[0-9]{1,30}"))) continue
            val o=json.optJSONObject(id) ?: continue
            val intro=o.optInt("intro",-1); val outro=o.optInt("outro",-1)
            if(intro !in 0..600 || outro !in 0..600) continue
            val mark=SkipMark(intro,outro)
            if(!mark.empty) result[id]=mark
            if(result.size>=MAX_ENTRIES) break
        }
        return result
    }
    fun encode(marks: Map<String,SkipMark>): String {
        val json=JSONObject()
        marks.forEach { (id,mark) -> json.put(id,JSONObject().put("intro",mark.introSeconds).put("outro",mark.outroSeconds)) }
        return json.toString()
    }
    /** Returns a new map with the mark set (or removed when null/empty), trimmed to the newest entries. */
    fun set(marks: Map<String,SkipMark>,id: String,mark: SkipMark?): LinkedHashMap<String,SkipMark> {
        val next=LinkedHashMap(marks)
        if(mark==null || mark.empty) next.remove(id) else next[id]=mark
        while(next.size>MAX_ENTRIES) next.remove(next.keys.first())
        return next
    }
}

/** Session-only timer driven by a monotonic clock, independent of playback speed. */
class SleepTimer(private val now: ()->Long) {
    private var deadline: Long?=null
    var episodesLeft=0; private set
    val active get()=deadline!=null || episodesLeft>0
    fun cancel() { deadline=null; episodesLeft=0 }
    fun afterMinutes(minutes: Int) { require(minutes in 1..180); cancel(); deadline=now()+minutes*60_000L }
    fun afterEpisodes(count: Int) { require(count in 1..10); cancel(); episodesLeft=count }
    fun remainingMillis()=deadline?.let { (it-now()).coerceAtLeast(0) }
    fun poll(): Boolean { if(deadline?.let { now()>=it }!=true) return false; cancel(); return true }
    fun episodeEnded(): Boolean {
        if(poll()) return true
        if(episodesLeft<=0) return false
        episodesLeft--
        return episodesLeft==0
    }
    fun label(): String = remainingMillis()?.let { "${(it+59_999)/60_000} 分钟后" }
        ?: if(episodesLeft>0) "再播完 $episodesLeft 集" else "关闭"
}
