// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 三方片源配置的导入导出格式（手机扫码下载/上传共用）。只保存名称与接口，不含账号、进度或搜索记录。 */
object ThirdSourceConfig {
    const val MAX = 30
    const val MAX_BYTES = 256 * 1024
    private const val FORMAT = "hongguotv.third-sources"

    fun encode(sources: List<BackupThirdSource>): String {
        val array = JSONArray()
        sources.take(MAX).forEach { source ->
            array.put(JSONObject().put("name", clean(source.name, 40)).put("api", clean(source.api, 500)))
        }
        return JSONObject().put("format", FORMAT).put("version", 1).put("sources", array).toString()
    }

    /** 解析并清洗：格式不对返回空列表；非法接口、重复接口直接跳过，绝不抛异常到调用方。 */
    fun decode(raw: String): List<BackupThirdSource> {
        if(raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
        val json = runCatching { JSONObject(raw.trim().removePrefix("\uFEFF")) }.getOrNull() ?: return emptyList()
        if(json.optString("format") != FORMAT) return emptyList()
        val array = json.optJSONArray("sources") ?: return emptyList()
        val result = mutableListOf<BackupThirdSource>()
        for(index in 0 until array.length()) {
            if(result.size >= MAX) break
            val item = array.optJSONObject(index) ?: continue
            val api = clean(item.optString("api"), 500)
            if(!api.startsWith("http") || result.any { it.api == api }) continue
            result += BackupThirdSource(clean(item.optString("name"), 40).ifBlank { hostOf(api) }, api)
        }
        return result
    }

    private fun clean(value: String, max: Int) = value.filterNot { it.isISOControl() }.trim().take(max)
    private fun hostOf(api: String) = api.removePrefix("https://").removePrefix("http://").substringBefore('/')
}
