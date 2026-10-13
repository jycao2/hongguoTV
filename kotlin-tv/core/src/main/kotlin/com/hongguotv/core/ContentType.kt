// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

enum class ContentType(val storedValue: String, val label: String, val searchTab: Int) {
    COMIC("comic","漫剧",19),
    SHORT("short","短剧",11);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: COMIC
    }
}
