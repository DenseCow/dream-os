package com.firehome.launcher

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout

/**
 * The home screen as MainActivity drives it: Dream Cinema's shell ([CinemaShell]). It takes a [Host] (every
 * action goes through MainActivity), so the shell is only layout, focus and motion.
 */
interface HomeShell {
    val root: FrameLayout
    /** Where pages live (MainActivity's focus helpers search here). */
    val content: FrameLayout
    val currentTab: Tab
    /** The Search page's field, for typing from the phone remote. */
    val searchInput: EditText?
    /** [instant]: no fade (a fade starts from a black page: the voice hand-off switches on screen, at once). */
    fun show(requested: Tab, force: Boolean = false, focusContent: Boolean = false, instant: Boolean = false)
    /**
     * Gets [tab]'s art back (it lets go of it while hidden) without showing it; [ready] says when every
     * picture of its first screen is in, so a switch to it draws whole from its first frame.
     */
    fun warmArt(tab: Tab): () -> Boolean
    fun homePressed()
    fun focusContent()
    fun focusBar()
    fun handleBack(): Boolean
    fun focusAirPods(): Boolean
    fun updateAirPods()
    fun onTick()
    fun onResume()
    fun onPause()
    fun releaseGraphics()
    fun destroy()
    fun appsChanged()
    fun historyChanged()
    fun continueRemoved()
    fun applyInsets(h: Int, v: Int)

    /** What the shell asks of MainActivity. */
    interface Host {
        fun apps(): List<AppEntry>
        fun launch(pkg: String)
        fun appMenu(app: AppEntry)
        fun titleMenu(t: Title)
        /** [from] is the card that was pressed, for the details page's shared-element move. */
        fun openDetails(t: Title, from: View?)
        fun resume(e: WatchHistory.Entry)
        fun continueMenu(e: WatchHistory.Entry, t: Title, card: View)
        fun episodeChoice(item: NewEpisodes.Item)
        fun watch(t: Title, pick: Boolean)
        fun canPlay(): Boolean
        fun playTrailerFor(t: Title)
        fun settings()
        fun profileMenu()
        fun airPods()
        /** Text for the AirPods control ("AirPods · 80%"), or null to hide it. */
        fun airPodsLabel(): String?
        fun systemView(): View
        fun resumed(): Boolean
        /** No sheet, panel or details page is open over the home screen. */
        fun idle(): Boolean
        /** Focus rests on [t] (null: on nothing playable): Instant Play may look for its stream (MainActivity). */
        fun speculate(t: Title?)
        /** Opens the Notifications panel (the bell in the bar). */
        fun notices() {}
        /** Home's first row is on screen (the boot intro ends on it). */
        fun homeReady() {}
    }

    /**
     * Pages. MOVIES, TV, GENRE and SERVICE open from Browse (they're not in the bar; Browse stays marked
     * while one shows). SYSTEM opens from Settings.
     */
    enum class Tab(val label: String) { HOME("Home"), BROWSE("Browse"), MOVIES("Movies"), TV("TV Shows"), APPS("Apps"), MYLIST("My List"), SEARCH("Search"), SYSTEM("System"), GENRE("Genre"), SERVICE("Service") }

    companion object {
        fun isInside(v: View, parent: View): Boolean {
            var p: Any? = v
            while (p is View) {
                if (p === parent) return true
                p = p.parent
            }
            return false
        }

        /** The child of [parent] that contains [v], if any. */
        fun directChild(v: View, parent: ViewGroup): View? {
            var cur: View = v
            while (true) {
                val p = cur.parent as? View ?: return null
                if (p === parent) return cur
                cur = p
            }
        }
    }
}
