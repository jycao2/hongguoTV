// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class ContentType(val storedValue: String, val label: String, val searchTab: Int?) {
    COMIC("comic","漫剧",19),
    SHORT("short","短剧",11),
    /** AI 剧有独立的榜单与分类页，但官网搜索口径与漫剧共用，因此不提供搜索入口。 */
    AI("ai","AI剧",null);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
