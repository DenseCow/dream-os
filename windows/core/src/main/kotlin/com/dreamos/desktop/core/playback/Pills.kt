package com.dreamos.desktop.core.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/*
 * Availability and language pills: the answers the UI draws (fire-home Availability.kt / LangPills.kt, iPhone
 * Models.swift Availability + ContentContract.swift AvailabilityDetail + LanguagePills.swift). The UI draws the pill
 * (one shared component, never its own); this file says what it says and in which tone.
 * Rules: fire-home docs/STANDARDS.md §5 ("one fixed card label per state", "a source decides, not the date").
 */

/** The status a pill shows; [tone] is its status color (green / amber / red, STANDARDS §2). */
@Serializable
enum class AvailabilityStatus(val label: String, val tone: PillTone) {
    AVAILABLE("Available Now", PillTone.GOOD),
    CAM("CAM Only", PillTone.WARN),
    NONE("Not Available Yet", PillTone.BAD),
}

/** Status colors (STANDARDS §2): GOOD green #30D158 (text #6BEB8F), WARN amber #FFD60A (#FFE266), BAD red #FF453A (#FF8178); PLAIN neutral; WATCHED gold. */
@Serializable
enum class PillTone { GOOD, WARN, BAD, PLAIN, WATCHED }

/** The drawn mark at a pill's start (never a text character, STANDARDS §2). */
@Serializable
enum class PillGlyph { NONE, CHECK, CAM, CLOCK, SPEAKER, SUBTITLES }

/**
 * One title's availability answer (fire-home Availability.Entry). [best]: the best real release found ("2160p WEB-DL
 * HDR"), or for CAM the best CAM ("HDTS 1080p"); [note]: where it is / when it comes ("In Theaters Now", "Coming
 * Friday at 9:00 PM"); [releaseAt]: a known release instant ahead (epoch ms, 0 none); [exact]: [releaseAt] is a real
 * air time, not a day's start; [checkedAt]: epoch ms of the answer.
 */
@Serializable
data class AvailabilityAnswer(
    val status: AvailabilityStatus,
    val best: String? = null,
    val note: String? = null,
    val checkedAt: Long = 0,
    val releaseAt: Long = 0,
    val exact: Boolean = false,
    /** Set by a developer override; never replaced by a check. */
    val forced: Boolean = false,
)

/**
 * What a pill says: [labels] longest first (a narrow card takes the first that fits, never an icon-only pill), its
 * [tone] and its [glyph]. Informational: never focusable.
 */
data class Pill(val labels: List<String>, val tone: PillTone, val glyph: PillGlyph = PillGlyph.NONE) {
    val label get() = labels.first()

    companion object {
        /** The Watched pill (STANDARDS §2/§5): replaces the availability pill on anything the viewer finished. */
        val WATCHED = Pill(listOf("Watched"), PillTone.WATCHED, PillGlyph.CHECK)
    }
}

/**
 * A language pill on the title page (STANDARDS §5 "Language pills"): "English Dub Available" (GOOD, CHECK),
 * "Japanese Audio Only" (WARN, SPEAKER), "English Subtitles" (PLAIN, CHECK), "No English Subtitles" (PLAIN, NONE).
 */
data class LanguagePill(val text: String, val tone: PillTone, val glyph: PillGlyph)

/** The title page's language row: [audio] first, then [subtitles]; empty until the release names are known. */
data class LanguagePills(val audio: LanguagePill?, val subtitles: LanguagePill?) {
    val isEmpty get() = audio == null && subtitles == null
}

/**
 * What the UI calls for pills. Every read is instant (memory, kept on disk between launches) so cards and title pages
 * show their pill on the first frame; checks run in the background and arrive through [updates].
 */
interface AvailabilityApi {
    /** The kept answer's card pill for [target], no network; null when it gets none (old title, nothing known yet). */
    fun cardPill(target: PlayTarget, narrow: Boolean = false): Pill?

    /** The title page's large pill ("Available Now · 4K HDR"); null as for [cardPill]. */
    fun pagePill(target: PlayTarget): Pill?

    /** The raw kept answer, no network. */
    fun peek(key: String): AvailabilityAnswer?

    /** The kept answer found a source (Available Now or CAM Only), whatever the listed date: out early. No network. */
    fun outEarly(key: String): Boolean = peek(key)?.status.let { it == AvailabilityStatus.AVAILABLE || it == AvailabilityStatus.CAM }

    /**
     * An episode still ahead by its listed date: one quick, bounded check of the index (not repeated for a while), true
     * when a source is already up. "A source decides, not the date." Never asks while Dream Player plays.
     */
    suspend fun earlyRelease(target: PlayTarget): Boolean = outEarly(target.key)

    /**
     * Cards about to show (a row landed, a page is looked ahead): queue their checks, top rows first. Returns at once.
     * [priority] true: on screen now or a title page (goes before the look-ahead queue).
     */
    fun ask(targets: List<PlayTarget>, priority: Boolean = false)

    /** Answer now (a title page opening). Bounded; returns the kept answer when the check can't finish in time. */
    suspend fun check(target: PlayTarget): AvailabilityAnswer?

    /** Every new answer, keyed by [PlayTarget.key]. */
    val updates: Flow<Pair<String, AvailabilityAnswer>>

    /** The title page's language pills for [target], from kept answers (no network); refreshed behind. */
    fun languagePills(target: PlayTarget): LanguagePills
}
