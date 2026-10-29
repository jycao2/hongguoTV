// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

object PlaybackQuality {
    val options=listOf(480,720,1080)
    fun normalize(value: Int)=value.takeIf { it in options } ?: 1080
}

/** 播放失败后的自动降档：以「实际播到的档位」为基准，只往更低的档退。 */
object QualityDowngrade {
    /** 从播放信息文本解析实际档位，如 "1080P"、"720P（兼容资源）"；"自动" 或空文本返回 0。 */
    fun parse(text: String): Int = Regex("(\\d{3,4})P").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /**
     * 返回严格低于当前实际档位的最高可选档，已到最低档返回 null。
     * [actual] 为 0（无法判定）时以用户配置为基准，避免降档后又拿回同一档反复失败。
     */
    fun next(actual: Int,configured: Int): Int? {
        val configuredQuality=PlaybackQuality.normalize(configured)
        val ceiling=if(actual>0) minOf(actual,configuredQuality) else configuredQuality
        return PlaybackQuality.options.filter { it<ceiling }.maxOrNull()
    }
}

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts=0; private set
    val remaining get()=attempts<3
    val delayMillis get()=listOf(1000L,2500L,5000L).getOrNull(attempts)
    fun consume(): Boolean { if(!remaining) return false; attempts++; return true }
    fun reset() { attempts=0 }
}

data class ResumeTarget(val index: Int,val position: Long)
object ResumePlayback {
    fun target(episodes: List<String>,episodeId: String?,position: Long,completed: Boolean): ResumeTarget {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId)
        if(index<0) return ResumeTarget(0,0)
        return if(completed) ResumeTarget((index+1).coerceAtMost(episodes.lastIndex),0)
            else ResumeTarget(index,position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int,val seen: Int,val checkedAt: Long,
    val newSeasonId: String? = null,val newSeasonTitle: String? = null,val ackedSeasonId: String? = null) {
    val added get()=(total-seen).coerceAtLeast(0)
    val hasNewSeason get()=!newSeasonId.isNullOrBlank()
    val hasUpdate get()=added>0 || hasNewSeason
    fun observe(count: Int,now: Long,acknowledge: Boolean=false): FavoriteUpdate {
        require(count>0)
        if(now<checkedAt) return this
        return FavoriteUpdate(count,if(acknowledge) maxOf(seen,count) else seen,now,
            if(acknowledge) null else newSeasonId,if(acknowledge) null else newSeasonTitle,
            if(acknowledge) newSeasonId ?: ackedSeasonId else ackedSeasonId)
    }
    fun observeNewSeason(seasonId: String?,seasonTitle: String?,now: Long): FavoriteUpdate {
        if(now<checkedAt) return this
        if(seasonId.isNullOrBlank() || !seasonId.matches(Regex("[0-9]{1,30}"))) return this
        // A season the user already saw must not re-surface on the next periodic check.
        if(seasonId==newSeasonId || seasonId==ackedSeasonId) return this
        return FavoriteUpdate(total,seen,now,seasonId,seasonTitle?.ifBlank { null },ackedSeasonId)
    }
    fun json()=JSONObject().put("total",total).put("seen",seen).put("checkedAt",checkedAt)
        .put("newSeasonId",newSeasonId).put("newSeasonTitle",newSeasonTitle).put("ackedSeasonId",ackedSeasonId)
    companion object {
        fun first(count: Int,now: Long): FavoriteUpdate { require(count>0); return FavoriteUpdate(count,count,now) }
        fun decode(value: JSONObject?)=runCatching {
            require(value!=null)
            val total=value.getInt("total"); val seen=value.getInt("seen"); val time=value.getLong("checkedAt")
            require(total>0 && seen>0 && time>=0)
            val seasonId=value.optString("newSeasonId").ifBlank { null }
            val seasonTitle=value.optString("newSeasonTitle").ifBlank { null }
            val ackedId=value.optString("ackedSeasonId").ifBlank { null }
            require(seasonId==null || seasonId.matches(Regex("[0-9]{1,30}")))
            require(ackedId==null || ackedId.matches(Regex("[0-9]{1,30}")))
            FavoriteUpdate(total,seen,time,seasonId,seasonTitle,ackedId)
        }.getOrNull()
    }
}
