// 开发期探针：分页列出搜索结果 id/标题；`<video_id> model` 打印该集模型的路由/直链有效期/fallback URL。
// 与 Probe/DumpSpade 同族，PC 侧验证取址行为用（注意：PC 探针结论受出口环境影响，最终以真机为准）。
package com.hongguotv.core

fun main(args: Array<String>) {
    val repository = ContentRepository()
    if (args.getOrNull(1) == "model") {
        val model = repository.videoModel(args[0])
        println("route=${model.optString("__route")}")
        println("url_expire=${model.optLong("url_expire")}")
        println("inline_array=${model.optJSONArray("video_list")?.length() ?: -1}")
        val raw = model.opt("fallback_api")
        val url = when (raw) {
            is org.json.JSONObject -> raw.str("fallback_api")
            is org.json.JSONArray -> raw.optString(0)
            else -> raw.toString()
        }
        println("fallback_url=$url")
        return
    }
    val keyword = args.getOrNull(0) ?: return
    var page = 1
    while (true) {
        val search = repository.search(keyword, page)
        search.items.forEach { println("id=${it.id} title=${it.title}") }
        if (!search.hasMore) break
        page++
        if (page > 4) break
    }
}
