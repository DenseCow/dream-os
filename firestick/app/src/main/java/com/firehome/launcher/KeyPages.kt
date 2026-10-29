package com.firehome.launcher

import android.content.Context
import android.util.Log
import java.net.URLEncoder

/**
 * The TMDB and OMDb key pages (the iPhone's KeyEntryView in TV terms), shared by setup's Connect Movie & Show Info
 * step, Accounts and Settings. Each key: its row (Added / Not Added), Show Me How with the exact steps (the websites
 * open on the viewer's phone by QR code), If Something Goes Wrong, and the entry screen, whose failed check carries
 * "What to Try". A key that fails its check is never saved. TMDB is the viewer's own key only (no built-in option);
 * OMDb offers Use the Built-In Key only when this build carries one.
 */
internal class KeyPages(
    private val ctx: Context,
    private val ui: SetupUi,
    private val panel: SetupPanel,
    private val onChange: () -> Unit = {},
    /** Offer Use the Built-In Key for OMDb (Settings); first-run setup never shows it. */
    private val builtIn: Boolean = true,
) {
    enum class Kind { TMDB, OMDB }

    private val k = ui.k
    private val parts = SetupParts(ctx, k)
    private fun app() = ctx.applicationContext
    private fun prefs() = app().getSharedPreferences("tmdb", Context.MODE_PRIVATE)

    private fun title(kind: Kind) = if (kind == Kind.TMDB) "TMDB Key" else "OMDb Key"
    private fun site(kind: Kind) = if (kind == Kind.TMDB) "https://www.themoviedb.org/settings/api" else "https://www.omdbapi.com/apikey.aspx"

    /** The viewer typed their own OMDb key (not the build's). */
    private fun ownOmdb() = !prefs().getString("omdbKey", null).isNullOrBlank()

    /** The row's value: "Added", "Included" (OMDb's built-in key in use) or "Not Added". */
    private fun value(kind: Kind): Pair<String, Boolean> = when (kind) {
        Kind.TMDB -> if (Tmdb.hasKey(app())) "Added" to true else "Not Added" to false
        Kind.OMDB -> when {
            ownOmdb() -> "Added" to true
            Ratings.omdbEnabled(app()) -> "Included" to true
            else -> "Not Added" to false
        }
    }

    /**
     * [kind]'s rows on a page: the key row, Show Me How and If Something Goes Wrong (and, for OMDb with a key of the
     * viewer's own in a build that has one, Use the Built-In Key). Call again from the page's onShow / onResume to
     * repaint the value.
     */
    fun addRows(s: SetupScreen, rows: ScreenRows, kind: Kind, top: Int) {
        val (v, ok) = value(kind)
        rows.row("key:$kind", top) { parts.row(null, chevron = true) { panel.push(entry(kind)) } }
            .set(title(kind), if (kind == Kind.TMDB) "Posters, details and search" else "Rotten Tomatoes and IMDb scores",
                v, if (ok) SetupBrand.GREEN else null, if (ok) BrandRow.Mark.OK else BrandRow.Mark.NONE)
        if (kind == Kind.TMDB) SetupGuide.helpRows(rows, "tmdb", parts, panel, ui, "Get Your Free Key From TMDB", "Keys That Don't Work",
            { SetupGuide.TMDB_STEPS }, SetupGuide.TMDB_TROUBLE, SetupGuide.TMDB_INTRO)
        else SetupGuide.helpRows(rows, "omdb", parts, panel, ui, "Get Your Free Key From OMDb", "Keys and the Activation Email",
            { SetupGuide.OMDB_STEPS }, SetupGuide.OMDB_TROUBLE)
        if (kind == Kind.OMDB) {
            if (builtIn && BuildConfig.OMDB_KEY.isNotBlank() && ownOmdb()) rows.row("omdb:builtin", 8) {
                parts.row(null) {
                    Ratings.setOmdbKey(app(), "")
                    Log.w(SetupBrand.TAG, "Setup: OMDb back on the built-in key")
                    onChange()
                    panel.top?.let { if (it.alive) it.onShow() }
                }
            }.set("Use the Built-In Key", "Removes your own key from this Fire Stick")
            else s.hide("omdb:builtin")
        }
    }

    /** A whole page for one key (Settings › TMDB Key / OMDb Key). */
    fun overview(kind: Kind): SetupScreen {
        val s = SetupScreen(ui, null, null)
        val rows = ScreenRows(s)
        s.view("note", 0) { parts.note(note(kind), 15f) }
        fun paint() { addRows(s, rows, kind, 16) }
        paint()
        s.onShow = { paint() }
        s.onResume = { paint() }
        return s
    }

    fun note(kind: Kind) = if (kind == Kind.TMDB)
        "Dream OS gets posters, details and search from TMDB. Your key is free: on TMDB's API page, use the key labeled API Key (32 letters and numbers), not the much longer API Read Access Token."
    else "Rotten Tomatoes and IMDb scores come from OMDb. Your key is free and arrives by email: choose the link in that email to activate it, then enter the key here."

    /** Typing the key (keyboard, Phone Remote, or the QR code to the site's key page), then its check. */
    fun entry(kind: Kind): SetupScreen = setupEntry(ctx, ui, parts, title(kind), null, "Your ${title(kind)}", "", emptyList(),
        if (kind == Kind.TMDB) "Scan the code to open TMDB's API page on your phone, then type API Key here, or with Phone Remote."
        else "Scan the code to get a free OMDb key on your phone, then type it here, or with Phone Remote.",
        site(kind)) { typed -> panel.replace(checking(kind, typed.filterNot { it.isWhitespace() })) }

    private fun checking(kind: Kind, key: String): SetupScreen {
        val s = SetupScreen(ui, title(kind), null)
        val rows = ScreenRows(s)
        rows.row("check", 0) { parts.row() }.set("Checking Your Key", "One moment…", mark = BrandRow.Mark.BUSY)
        // Saved as soon as it passes, in the background: Back during "Checking Your Key" drops only the page.
        s.bg({ test(kind, key).also { ok -> if (ok) { if (kind == Kind.TMDB) Tmdb.setKey(app(), key) else Ratings.setOmdbKey(app(), key) } } }) { ok ->
            if (ok) {
                Log.w(SetupBrand.TAG, "Setup: ${title(kind)} added")
                onChange()
                rows.get("check")?.set("Key Added", if (kind == Kind.TMDB) "Posters and details are ready." else "Rotten Tomatoes and IMDb scores are on.",
                    mark = BrandRow.Mark.OK)
                // The result shows briefly, then the page goes back by itself: no Done to press.
                ui.main.postDelayed({ if (s.alive && panel.top === s) panel.pop() }, 1400)
            } else {
                Log.w(SetupBrand.TAG, "Setup: ${title(kind)} didn't pass its check")
                rows.get("check")?.set("That Key Didn't Work", "Check it and try again.", mark = BrandRow.Mark.WARN)
                s.view("help", 14) { SetupGuide.whatToTry(ctx, k, if (kind == Kind.TMDB) SetupGuide.TMDB_KEY_HELP else SetupGuide.OMDB_KEY_HELP) }
                val again = parts.pill("Try Again", true) { panel.replace(entry(kind)) }
                val how = parts.pill("Show Me How", false) {
                    panel.push(SetupGuide.guide(ui, parts, panel, "Show Me How", if (kind == Kind.TMDB) SetupGuide.TMDB_STEPS else SetupGuide.OMDB_STEPS,
                        if (kind == Kind.TMDB) SetupGuide.TMDB_INTRO else null))
                }
                s.view("again", 18) { parts.pills(again, how) }
                again.requestFocus()
            }
        }
        return s
    }

    /** One request with [key] alone (the saved key is untouched until it passes). Blocking. */
    private fun test(kind: Kind, key: String): Boolean = key.isNotEmpty() && try {
        when (kind) {
            Kind.TMDB -> { Net.getJson("https://api.themoviedb.org/3/configuration?api_key=${URLEncoder.encode(key, "UTF-8")}"); true }
            Kind.OMDB -> Ratings.testKey(key)
        }
    } catch (e: Exception) { false }
}
