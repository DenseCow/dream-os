package com.dreamos.desktop.core.model

import kotlinx.serialization.Serializable

// ---- Profiles ----

/** A viewer profile (fire-home Profiles.kt + KidsProfiles.kt). The PIN itself is never here: only [hasPin]. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Avatar emoji (fire-home Profiles.EMOJIS). */
    val emoji: String = "😀",
    /** The avatar's color, an index into [Profile.COLORS]. */
    val color: Int = 0,
    val isKids: Boolean = false,
    /** Kids rating level, an index into [KidsLevel.ALL]; the last one (Everyone) is no limit. */
    val kidsLevel: Int = KidsLevel.DEFAULT_KIDS,
    /** A Kids profile asks for a grown-up's PIN before switching away. */
    val exitPin: Boolean = false,
    val hasPin: Boolean = false,
) {
    companion object {
        /** The first profile's id; its settings files carry no suffix (as on the Fire Stick). */
        const val FIRST = "p1"
        val EMOJIS = listOf("😀", "😎", "🦄", "🐱", "🐶", "🦊", "👾", "🌟", "🚀", "🎮", "🌸", "🐼")
        /** Avatar colors as ARGB. */
        val COLORS: List<Long> = listOf(0xFF7C5CFF, 0xFF38BDF8, 0xFFF472B6, 0xFF4ADE80, 0xFFFBBF24, 0xFFF87171, 0xFFA78BFA, 0xFF2DD4BF)
    }
}

/** A Kids rating level: the highest movie and TV ratings it lets through (fire-home KidsProfiles.LEVELS). */
@Serializable
data class KidsLevel(val name: String, val movie: String, val tv: List<String>) {
    /** "Kids · PG, TV-PG" as Settings shows it. */
    val label: String get() = "$name · $movie, ${tv.last()}"

    companion object {
        val ALL = listOf(
            KidsLevel("Little Kids", "G", listOf("TV-Y", "TV-G")),
            KidsLevel("Kids", "PG", listOf("TV-Y", "TV-G", "TV-Y7", "TV-PG")),
            KidsLevel("Teens", "PG-13", listOf("TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14")),
            KidsLevel("Everyone", "R", listOf("TV-Y", "TV-G", "TV-Y7", "TV-PG", "TV-14", "TV-MA")),
        )
        const val DEFAULT_KIDS = 1
    }
}

/** Why a PIN try failed. */
sealed interface PinResult {
    data object Ok : PinResult
    data object Wrong : PinResult
    /** Too many wrong tries: wait this long before the next one. */
    data class Wait(val seconds: Int) : PinResult
}

// ---- Watch history ----

/**
 * One movie or show the viewer watched (fire-home WatchHistory.Entry). A show keeps only its latest episode.
 * [hidden]: off Continue Watching (finished, or removed by the viewer); still counts for Because You Watched and
 * for leaving finished titles out of rows.
 */
@Serializable
data class HistoryEntry(
    val title: Title,
    val season: Int = 0,
    val episode: Int = 0,
    val episodeName: String? = null,
    val stillPath: String? = null,
    val imdbId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The next episode is queued (the last one finished), not started yet. */
    val upNext: Boolean = false,
    /** Epoch ms. */
    val updatedAt: Long = 0,
    val hidden: Boolean = false,
    /** "dream" for Dream Player; "trakt" for a point pulled from Trakt. */
    val source: String = "dream",
) {
    val key: String get() = title.key
    val isShow: Boolean get() = title.isShow
    val progress: Double get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
    val finished: Boolean get() = progress >= FINISHED

    /** Episode still, else the backdrop, else the poster: every landscape image in order of preference. */
    fun images(): List<String> = listOfNotNull(
        TmdbImage.url(stillPath, "w300"), title.backdrop("w780"), title.poster("w342"),
    )

    companion object {
        /** Watched this far, a title counts as finished (fire-home WatchHistory.FINISHED). */
        const val FINISHED = 0.92
    }
}

/** What the viewer asked to play. Shared with playback (resolver) and the player. */
@Serializable
data class PlayRequest(
    val title: Title,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
    /** The viewer picked "Start Over" / "From the Beginning". */
    val fromStart: Boolean = false,
) {
    val key: String get() = title.key + if (season != null && episode != null) ":s${season}e$episode" else ""
}

/** A user-facing failure: [message] is a plain sentence ("Couldn't reach Trakt. Try again."), never technical. */
class DreamError(message: String, val heading: String? = null, cause: Throwable? = null) : Exception(message, cause)
