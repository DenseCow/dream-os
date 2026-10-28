package com.firehome.launcher

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View

/**
 * A full-screen title page (details) as MainActivity drives it: Dream Cinema's ([CinemaDetails]). MainActivity
 * talks to it only through these calls, and the page asks MainActivity for everything through [Host].
 */
interface TitlePage {
    /** The page's root view; MainActivity adds it over the window's decor view. */
    val view: View
    /** Focus the first control (Play) once the page is attached. */
    fun focusInitial()
    /** Back pressed: true when the page handled it itself (e.g. back up to the buttons), false to close. */
    fun handleBack(): Boolean
    /** Every key goes here first while the page is open; true when consumed. */
    fun interceptKey(e: KeyEvent): Boolean
    /** Entrance; [fromRect]/[fromBitmap] are the pressed card's on-screen box and art, when known. */
    fun animateIn(fromRect: Rect?, fromBitmap: Bitmap?)
    /** Exit animation; [done] runs once it's off screen (MainActivity then removes the view). */
    fun animateOut(done: () -> Unit)
    /** Lets go of images, trailers and callbacks after the view is removed. */
    fun release()
    /** The watch history changed (a viewing ended, saved its spot or finished): Resume / Play may need redoing. */
    fun historyChanged() {}

    interface Host {
        fun installed(): Set<String>                       // installed package names
        fun canPlay(): Boolean                             // titles can be played in Dream OS (Play buttons show)
        fun nextEpisode(t: Title): WatchHistory.Entry?     // in-progress/up-next episode, or null
        fun watch(t: Title, pick: Boolean)                 // movie: plays; show: next episode, or asks (pick=true always asks)
        fun playEpisode(t: Title, season: Int, episode: Int)
        fun resume(e: WatchHistory.Entry)                   // as Continue Watching: its player, from the saved spot
        fun openOnService(svc: Tmdb.Service, t: Title)
        fun playTrailer(t: Title, startMs: Long, videoKey: String?) // full screen in Dream Player, from that spot when it's that video
        fun openDetails(t: Title)                           // e.g. from "More like this"
        fun titleMenu(t: Title)                             // MENU key on a card
    }
}
