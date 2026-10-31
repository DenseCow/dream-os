package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.store.DreamStore

/**
 * Recent searches, per profile, newest first (fire-home CinemaSearch.kt RecentSearches): eight at most, two letters
 * at least, no repeats in any case. Stored in `search[_<id>]`, "recent".
 */
object RecentSearches {
    const val MAX = 8

    private fun prefs() = DreamStore.shared.profileFile("search")

    fun list(): List<String> = prefs().getStringList("recent").filter { it.isNotBlank() }

    fun add(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val l = (listOf(t) + list().filterNot { it.equals(t, ignoreCase = true) }).take(MAX)
        prefs().putStringList("recent", l)
    }

    fun clear() = prefs().remove("recent")
}

/**
 * My List's view, per profile (fire-home CinemaMyList: the chips' type and order, kept between visits): [type] "all",
 * "movie" or "tv"; [order] "recent" or "az". Stored in `cinema[_<id>]`, "mylist_view" as "type|order".
 */
object MyListView {
    val TYPES = listOf("all", "movie", "tv")
    val ORDERS = listOf("recent", "az")

    private fun prefs() = DreamStore.shared.profileFile("cinema")

    fun load(): Pair<String, String> {
        val v = prefs().getString("mylist_view")?.split('|')
        return (v?.getOrNull(0)?.takeIf { it in TYPES } ?: "all") to (v?.getOrNull(1)?.takeIf { it in ORDERS } ?: "recent")
    }

    fun save(type: String, order: String) = prefs().putString("mylist_view", "$type|$order")

    /** Sorting name: "The Office" files under O, "A Quiet Place" under Q. */
    fun sortName(n: String): String {
        val s = n.trim()
        for (a in listOf("the ", "a ", "an ")) if (s.length > a.length && s.startsWith(a, ignoreCase = true)) return s.substring(a.length)
        return s
    }
}
