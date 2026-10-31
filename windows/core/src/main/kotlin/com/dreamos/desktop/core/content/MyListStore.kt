package com.dreamos.desktop.core.content

import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.store.DreamStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

/**
 * My List, per profile (fire-home Tmdb.myList / toggleMyList), newest first, stored in `mylist[_<id>]`. Titles keep
 * their full date, genres and language (pills and Taste read them). The Trakt watchlist follows ([onChanged]).
 * Worst case: a few hundred titles (~1 KB each).
 */
object MyListStore {
    private val ser = ListSerializer(Title.serializer())
    private val lock = Any()
    private val _items = MutableStateFlow<List<Title>>(emptyList())
    @Volatile private var loadedFor: String? = null

    /** Told after a toggle (title, on the list now); Trakt's watchlist follows. Runs on the caller's thread. */
    @Volatile var onChanged: ((Title, Boolean) -> Unit)? = null

    private fun prefs() = DreamStore.shared.profileFile("mylist")

    /** The current profile's list, kept current across profile switches ([reload]). */
    val flow: StateFlow<List<Title>>
        get() { items(); return _items }

    fun items(): List<Title> = synchronized(lock) {
        if (loadedFor != DreamStore.currentProfile) {
            _items.value = prefs().get("items", ser) ?: emptyList()
            loadedFor = DreamStore.currentProfile
        }
        _items.value
    }

    fun contains(key: String) = items().any { it.key == key }

    /** Adds [t] at the top or removes it; true when it's on the list afterwards. */
    fun toggle(t: Title): Boolean {
        val on = synchronized(lock) {
            val list = items().toMutableList()
            val removed = list.removeAll { it.key == t.key }
            if (!removed) list.add(0, t)
            prefs().put("items", ser, list)
            _items.value = list
            !removed
        }
        try { onChanged?.invoke(t, on) } catch (e: Exception) {}
        return on
    }

    /** Sets whether [t] is on the list without telling Trakt (Trakt's own watchlist pull). */
    fun set(t: Title, on: Boolean) = synchronized(lock) {
        val list = items().toMutableList()
        val has = list.any { it.key == t.key }
        if (has == on) return@synchronized
        if (on) list.add(0, t) else list.removeAll { it.key == t.key }
        prefs().put("items", ser, list)
        _items.value = list
    }

    /** The profile switched: the next read loads its list. */
    fun reload() = synchronized(lock) { loadedFor = null; items() }
}
