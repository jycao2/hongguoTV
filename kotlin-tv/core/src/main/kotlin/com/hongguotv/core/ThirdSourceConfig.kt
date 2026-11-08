// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** 三方片源配置的导入导出格式（手机扫码下载/上传共用）。
 *  v2 携带源类型与元数据（headers/type/searchable/quickSearch/filterable/categories/timeoutSeconds），
 *  v1（仅 name+api）仍可解析；不含账号、进度或搜索记录。
 *  上限与 App 的 ThirdCms.MAX_SOURCES 保持一致。 */
object ThirdSourceConfig {
    const val MAX = 200
    const val MAX_BYTES = 256 * 1024
    private const val FORMAT = "hongguotv.third-sources"
    private const val MAX_HEADERS = 8
    private const val MAX_CATEGORIES = 40

    fun encode(sources: List<BackupThirdSource>): String {
        val array = JSONArray()
        sources.take(MAX).forEach { source ->
            val item = JSONObject().put("name", clean(source.name, 40)).put("api", clean(source.api, 500))
            if(source.type == 0) item.put("type", 0)
            if(source.headers.isNotEmpty()) item.put("headers",
                JSONObject(source.headers.entries.take(MAX_HEADERS).associate { (key,value) -> clean(key, 40) to clean(value, 300) }))
            if(!source.searchable) item.put("searchable", false)
            if(!source.quickSearch) item.put("quickSearch", false)
            if(!source.filterable) item.put("filterable", false)
            if(source.categories.isNotEmpty()) item.put("categories", JSONArray(source.categories.take(MAX_CATEGORIES).map { clean(it, 20) }))
            if(source.timeoutSeconds > 0) item.put("timeoutSeconds", source.timeoutSeconds.coerceAtMost(60))
            array.put(item)
        }
        return JSONObject().put("format", FORMAT).put("version", 2).put("sources", array).toString()
    }

    /** 解析并清洗：格式不对返回空列表；非法接口、重复接口直接跳过，绝不抛异常到调用方。
     *  version 1/2 都接受；v1 文件缺元数据字段时回落默认值。 */
    fun decode(raw: String): List<BackupThirdSource> {
        if(raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return emptyList()
        val json = runCatching { JSONObject(raw.trim().removePrefix("\uFEFF")) }.getOrNull() ?: return emptyList()
        if(json.optString("format") != FORMAT) return emptyList()
        if(json.optInt("version", 1) !in 1..2) return emptyList()
        val array = json.optJSONArray("sources") ?: return emptyList()
        val result = mutableListOf<BackupThirdSource>()
        for(index in 0 until array.length()) {
            if(result.size >= MAX) break
            val item = array.optJSONObject(index) ?: continue
            val api = clean(item.optString("api"), 500)
            if(!api.startsWith("http") || result.any { it.api == api }) continue
            val headers = runCatching {
                val obj = item.optJSONObject("headers") ?: return@runCatching emptyMap<String,String>()
                obj.keys().asSequence().take(MAX_HEADERS).mapNotNull { key ->
                    val value = obj.optString(key).trim()
                    if(key.isNotBlank() && value.isNotEmpty()) clean(key, 40) to clean(value, 300) else null
                }.toMap()
            }.getOrDefault(emptyMap())
            val categories = item.optJSONArray("categories")?.let { rows ->
                (0 until minOf(rows.length(), MAX_CATEGORIES)).mapNotNull { rows.optString(it).takeIf(String::isNotBlank)?.take(20) }
            } ?: emptyList()
            result += BackupThirdSource(
                clean(item.optString("name"), 40).ifBlank { hostOf(api) }, api,
                if(item.optInt("type", 1) == 0) 0 else 1,
                headers,
                item.optBoolean("searchable", true), item.optBoolean("quickSearch", true), item.optBoolean("filterable", true),
                categories, runCatching { item.optInt("timeoutSeconds", 0) }.getOrDefault(0).coerceIn(0, 60))
        }
        return result
    }

    private fun clean(value: String, max: Int) = value.filterNot { it.isISOControl() }.trim().take(max)
    private fun hostOf(api: String) = api.removePrefix("https://").removePrefix("http://").substringBefore('/')
}
