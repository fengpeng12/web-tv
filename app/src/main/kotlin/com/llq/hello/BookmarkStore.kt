package com.llq.hello

import android.content.Context

/** 书签存储：SharedPreferences 持久化，格式为「名称\t网址」，一行一条。 */
class BookmarkStore(context: Context) {

    private val sp = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    data class Bookmark(val name: String, val url: String)

    fun load(): MutableList<Bookmark> {
        val raw = sp.getString(KEY, "").orEmpty()
        val list = mutableListOf<Bookmark>()
        if (raw.isEmpty()) return list
        for (line in raw.split("\n")) {
            if (line.isEmpty()) continue
            val idx = line.indexOf('\t')
            if (idx <= 0) continue
            val name = line.substring(0, idx)
            val url = line.substring(idx + 1)
            if (url.isNotEmpty()) list.add(Bookmark(name, url))
        }
        return list
    }

    private fun save(list: List<Bookmark>) {
        val sb = StringBuilder()
        for (b in list) {
            sb.append(b.name.replace("\t", " ").replace("\n", " "))
                .append('\t')
                .append(b.url.replace("\n", ""))
                .append('\n')
        }
        sp.edit().putString(KEY, sb.toString()).apply()
    }

    /** 添加书签；同网址已存在时更新名称。 */
    fun add(name: String, url: String) {
        val list = load()
        val existed = list.indexOfFirst { it.url == url }
        if (existed >= 0) list[existed] = Bookmark(name, url) else list.add(Bookmark(name, url))
        save(list)
    }

    fun remove(url: String) {
        val list = load()
        list.removeAll { it.url == url }
        save(list)
    }

    /** 判断该网址是否已收藏。 */
    fun contains(url: String): Boolean = load().any { it.url == url }

    private companion object {
        const val KEY = "items"
    }
}
