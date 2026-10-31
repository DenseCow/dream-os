package com.dreamos.desktop.core

import com.dreamos.desktop.core.content.ProfileStore
import com.dreamos.desktop.core.model.MediaKind
import com.dreamos.desktop.core.model.Title
import com.dreamos.desktop.core.platform.Platform
import com.dreamos.desktop.core.store.DreamStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/** Shared test setup: a fresh settings store in a temp folder (never the viewer's), and title builders. */
object TestSupport {
    fun freshStore(): DreamStore {
        val root = Files.createTempDirectory("dream-test")
        // Caches (TMDB answers on disk) go to the temp folder too, never the viewer's.
        Platform.current = Platform.forTests(root)
        val s = DreamStore(root.resolve("settings"))
        DreamStore.use(s)
        DreamStore.currentProfile = DreamStore.FIRST_PROFILE
        ProfileStore.init()
        return s
    }

    fun movie(id: Int, name: String = "Movie $id", date: String = "2020-01-01", votes: Int = 1000, rating: Double = 7.0,
              genres: List<Int> = listOf(18), lang: String = "en", poster: Boolean = true, backdrop: Boolean = true) =
        Title(id, MediaKind.MOVIE, name, posterPath = if (poster) "/p$id.jpg" else null, backdropPath = if (backdrop) "/b$id.jpg" else null,
            year = date.take(4), rating = rating, date = date, votes = votes, genreIds = genres, lang = lang)

    fun show(id: Int, name: String = "Show $id", date: String = "2020-01-01", votes: Int = 500, rating: Double = 7.5,
             genres: List<Int> = listOf(18), lang: String = "en") =
        Title(id, MediaKind.TV, name, posterPath = "/p$id.jpg", backdropPath = "/b$id.jpg", year = date.take(4), rating = rating,
            date = date, votes = votes, genreIds = genres, lang = lang)

    /** local.properties' value for [key] (the worktree root or fire-home's), read at run time; null when absent. */
    fun localProperty(key: String): String? {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val f = dir.resolve("local.properties")
            if (Files.exists(f)) {
                val p = Properties().apply { Files.newBufferedReader(f).use { load(it) } }
                p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            dir = dir.parent
        }
        return null
    }
}
