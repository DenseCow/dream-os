package com.firehome.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/**
 * Beginner-friendly setup help (owner, 2026-10-28 night; the iPhone's SetupGuide.swift in TV words). Every account
 * step opens with one plain sentence (what it is, why Dream OS needs it, what it costs), then "Show Me How" (numbered
 * steps with the exact names on the outside website) and "If Something Goes Wrong"; errors carry "What to Try" lines
 * right under them. The websites are used on a phone or computer: a step that names a page opens its short address
 * and a QR code here instead of a browser button. Text and presentation only: no setup logic here.
 */
internal object SetupGuide {
    /** An outside page a step names: shown as its short address and a QR code. */
    class Link(val title: String, val url: String) {
        val short: String get() = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    }

    /** One numbered instruction: what to do, what the viewer will see, the pages it names, the TMDB key picture. */
    class Step(val text: String, val see: String? = null, val links: List<Link> = emptyList(), val keyArt: Boolean = false)

    /** A problem in the viewer's words and what to do about it. */
    class Tip(val problem: String, val fix: String)

    // ---- TMDB ----

    const val TMDB_INTRO = "TMDB's sign-up is easiest on a computer, but your phone works too. Each step has a code to " +
        "scan with your phone."

    val TMDB_STEPS = listOf(
        Step("Make a free TMDB account. Open Join TMDB, fill in the form and choose Sign Up.",
            "TMDB sends you an email. Choose Activate My Account in it, then log in. Already have an account? Log in instead.",
            listOf(Link("Join TMDB", "https://www.themoviedb.org/signup"))),
        Step("Open the API page in your TMDB settings.",
            "On TMDB, choose your profile picture at the top right, then Settings, then API in the Settings menu. Or open the page below to go straight there.",
            listOf(Link("Open the API Page", "https://www.themoviedb.org/settings/api"))),
        Step("Choose Create, choose Developer, and accept the terms of use.",
            "TMDB shows a short form about the app you'll use the key in."),
        Step("Fill in the form, then choose Submit.",
            "If it asks how you'll use it, choose Personal. For the app's name, type Dream. For its website, any address works, such as https://www.themoviedb.org. For the summary, a short line is fine, such as \"A personal app for browsing movies and shows.\" Then add your name and contact details."),
        Step("Find the key labeled API Key.",
            "TMDB shows two. Use API Key: 32 letters and numbers. Leave the much longer API Read Access Token.",
            keyArt = true),
        Step("Come back to Dream OS and choose TMDB Key.",
            "Type the key with the on-screen keyboard, or scan the code there to type or paste it on your phone. Dream OS checks it and shows Key Added."),
    )

    val TMDB_TROUBLE = listOf(
        Tip("That Key Didn't Work", "Make sure you used API Key, the short one, not the long API Read Access Token. Enter it again with nothing before or after it."),
        Tip("TMDB Won't Let You Ask for a Key", "Your account needs to be activated first. Open the email from TMDB, choose Activate My Account, log in again, then open the API page."),
        Tip("There's No Key on the API Page", "Finish the form under Create first. If TMDB says it's reviewing your request, wait a few minutes and open the API page again."),
        Tip("The Key Won't Save", "Check the TV's internet connection, then save the key again."),
    )

    /** What to try when a TMDB key didn't pass its check. */
    val TMDB_KEY_HELP = listOf(
        "Enter API Key again: the short one with 32 letters and numbers, not the long API Read Access Token, with nothing before or after it.",
        "Still not working? Check the TV's internet connection, then try again.",
    )

    // ---- OMDb ----

    val OMDB_STEPS = listOf(
        Step("Open OMDb's key page and choose FREE! (1,000 daily limit).", links = listOf(Link("Open OMDb", "https://www.omdbapi.com/apikey.aspx"))),
        Step("Type your email, fill in anything else it asks for, and choose Generate API Key.",
            "OMDb emails you your key a few minutes later. Check your junk folder if it doesn't arrive."),
        Step("Open OMDb's email and choose the link in it to activate your key.",
            "The key only works once it's activated."),
        Step("Come back to Dream OS and choose OMDb Key.",
            "Type the key with the on-screen keyboard, or from your phone. Dream OS checks it and shows Key Added."),
    )

    val OMDB_TROUBLE = listOf(
        Tip("That Key Didn't Work", "Choose the activation link in OMDb's email first, then save the key again. Enter only the key, with nothing before or after it."),
        Tip("The Email Never Came", "Look in your junk folder. Some email services take up to an hour. You can skip ratings for now and add them later in Settings."),
    )

    val OMDB_KEY_HELP = listOf(
        "Open OMDb's email and choose the link in it to activate your key, then try again. The email can take a few minutes.",
        "Enter only the key, with nothing before or after it.",
    )

    // ---- debrid services ----

    const val PICK_LINE = "Premiumize is recommended: Play starts fastest with it. It's about \$12 a month, or about \$80 for a year. " +
        "TorBox works the same way. AllDebrid and Real-Debrid cost less, about \$4 a month, and work too: Play takes a second or two longer with them."

    private val SIGN_UP = mapOf(
        "pm" to "https://www.premiumize.me/premium", "tb" to "https://torbox.app/pricing",
        "ad" to "https://alldebrid.com/offer/", "rd" to "https://real-debrid.com/premium",
    )

    fun debridSteps(): List<Step> {
        val all = listOf("pm", "tb", "ad", "rd")
        val codes = all.filter { DebridLink.deviceSignIn(it) }.map { AccountHealth.name(it) }
        val keys = all.filter { !DebridLink.deviceSignIn(it) }.map { AccountHealth.name(it) }
        val out = arrayListOf(
            Step("Choose a service and open its website.", "Already have one? Go to step 3.",
                all.map { Link(AccountHealth.name(it), SIGN_UP.getValue(it)) }),
            Step("Choose Sign Up, make an account with your email and a password, then buy a plan.",
                "Dream OS plays only with an active paid plan. The plans page lists each length and its price."),
            Step("Come back to Dream OS and choose your service in the list.",
                listOfNotNull(
                    codes.takeIf { it.isNotEmpty() }?.let { "${names(it)} ${if (it.size == 1) "shows" else "show"} a short code." },
                    keys.takeIf { it.isNotEmpty() }?.let { "${names(it)} ${if (it.size == 1) "asks" else "ask"} for your key instead." },
                ).joinToString(" ")),
        )
        if (codes.isNotEmpty()) out += Step("For a code: scan it with your phone, or go to the address shown on a phone or computer.",
            "Sign in on the page that opens, enter the code and approve Dream OS. The code works for about 10 minutes, and Dream OS connects by itself once you approve.")
        if (keys.isNotEmpty()) out += Step("For a key: scan the code shown with your phone.",
            "Your phone opens a small Dream OS page. Choose Open Your ${keys[0]} Account, sign in, copy your API key, paste it there and choose Link.")
        out += Step("When your service says Connected, choose Continue.",
            "You can connect more than one. Dream OS asks them in the order you choose in Settings.")
        return out
    }

    val DEBRID_TROUBLE = listOf(
        Tip("The Code Expired", "Codes work for about 10 minutes. Choose Try Again for a new code, then enter it on the website right away."),
        Tip("It Says Expired or No Premium Plan", "Your account needs an active paid plan. Buy or renew one on the service's website, then choose the service here and check it again."),
        Tip("It Says Sign In Again", "Choose the service, then Link Again, and enter the new code."),
        Tip("Your Key Wasn't Accepted", "Copy the key again from the API Key line on your account page, with nothing before or after it."),
        Tip("It Still Says Waiting After You Approve", "Wait a few seconds on this screen. If nothing changes, go back, choose your service again and use the new code."),
    )

    /** "What to Try" under a failed sign-in or key for [name] ([key]: a key, not a code). */
    fun linkHelp(message: String, name: String, key: Boolean): List<String> {
        val m = message.lowercase()
        return when {
            "expired" in m -> listOf("Codes work for about 10 minutes. Choose Try Again for a new code, then enter it on the website right away.")
            "declined" in m -> listOf("Choose Try Again, and this time choose to allow Dream OS on $name's website.")
            "key" in m -> listOf("Copy the key again from the API Key line on your $name account page, with nothing before or after it.",
                "Make sure your $name plan is active, then choose Try Again.")
            "answer" in m || "network" in m || "internet" in m -> listOf("Check the TV's internet connection, then choose Try Again.",
                "If it still doesn't work, $name may be busy for a while: try again in a few minutes.")
            else -> listOf(if (key) "Check your key, then choose Try Again." else "Choose Try Again for a new code.",
                "If it keeps happening, connect another service for now and try $name again later.")
        }
    }

    // ---- Trakt ----

    fun traktSteps(): List<Step> {
        val out = arrayListOf(Step("Make a free Trakt account, if you don't have one.", links = listOf(Link("Open Trakt", "https://trakt.tv/auth/join"))))
        if (!Trakt.hasBuiltInKeys()) {
            out += Step("Choose Trakt, then Use Your Own Trakt App, and open trakt.tv/oauth/applications.",
                "Choose New Application, give it any name, such as Dream, and enter the Redirect URI Dream OS shows. Save it.")
            out += Step("Enter your app's Client ID and Client Secret in Dream OS.",
                "Trakt shows both on your new app's page. Type them with the on-screen keyboard, or from your phone.")
        }
        out += Step("Choose Sign In. Scan the code with your phone, or go to the address shown.",
            "Sign in to Trakt, enter the code and approve Dream OS. Dream OS says Connected and starts syncing your history and watchlist.")
        return out
    }

    // ---- scrapers ----

    val ADDRESS_EXAMPLES = listOf("https://someone.github.io/repo", "https://github.com/someone/repo", "https://example.com/repo.zip")

    val SCRAPER_STEPS = listOf(
        Step("Get your repository's address.",
            "Repositories are shared by the people who make them, usually on a web page or on GitHub. If someone told you about Dream OS, ask them for the address they use."),
        Step("Choose Add Scraper Repository and type the address.",
            "Use the on-screen keyboard, or scan the code there to type or paste it on your phone. Dream OS shows its progress: Checking Address, Downloading, Installing. Then it shows Added, and how many providers are ready."),
        Step("Choose Use Recommended Settings.",
            "Each provider searches one website. This turns on the fastest, most reliable ones and turns off the rest, so most titles start in about a second. You can still switch any provider on or off."),
        Step("Choose Continue.", "The next step checks that your debrid service and scrapers work together."),
    )

    val SCRAPER_TROUBLE = listOf(
        Tip("That Doesn't Look Like a Web Address", "Enter the whole address and only that, with no spaces or extra words."),
        Tip("No Scrapers Were Found", "The address may lead to a page about the repository instead of the repository itself. Use the exact address its maker gives, or ask them for it."),
        Tip("Couldn't Reach That Address", "Check the TV's internet connection. If it's fine, the website may be down for a while: try again later."),
        Tip("It Isn't in a Format Dream OS Supports", "That repository won't work with Dream OS. Try another one."),
        Tip("Providers Shows 0 On", "Choose Use Recommended Settings, or switch on at least one provider."),
    )

    /** "What to Try" under a failed Add Repository, matched to the message the scrapers gave. */
    fun repoHelp(message: String): List<String> {
        val m = message.lowercase()
        return when {
            "look like a web address" in m -> listOf("Enter the whole address and only that, with no spaces or extra words. It usually starts with https://.")
            "no scrapers were found" in m -> listOf("The address may lead to a page about the repository instead of the repository itself.",
                "Use the exact address its maker gives, or ask them for it.")
            "couldn't reach" in m -> listOf("Check the TV's internet connection.", "If it's fine, the website may be down for a while: try again later.")
            "safe" in m || "format" in m || "single add-on" in m || "too large" in m -> listOf("That repository won't work with Dream OS. Try another one.")
            "check out" in m -> listOf("The repository may be updating right now. Wait a few minutes, then try again.")
            else -> listOf("Check the address, then choose Try Again.", "If it still won't add, wait a few minutes and try again, or try another repository.")
        }
    }

    // ---- the check ----

    val CHECK_STEPS = listOf(
        Step("Dream OS checks as soon as this step opens.",
            "It searches for a popular movie with your scrapers and asks your debrid service which copies are ready to play. It usually takes under 30 seconds."),
        Step("When it says Ready to Watch, Dream OS tests your connection.",
            "If it's too slow for the best picture, choose the button it shows to use a quality that plays smoothly."),
        Step("Choose Continue."),
    )

    /** "What to Try" under a check that didn't pass, matched to the message the scrapers gave. */
    fun checkHelp(message: String): List<String> {
        val m = message.lowercase()
        return when {
            "add a source" in m -> listOf("Go back to Add Your Scrapers and add your repository.")
            "turn on at least one provider" in m -> listOf("Go back to Add Your Scrapers and choose Use Recommended Settings.")
            "link a streaming account" in m -> listOf("Your scrapers work, but no debrid service is ready to play.",
                "Go back to Connect a Debrid Service and check that your service says Connected and has an active paid plan.")
            "no sources came back" in m -> listOf("Check the Fire Stick's internet connection, then choose Check Again.",
                "If it still finds nothing, go back to Add Your Scrapers and choose Use Recommended Settings, or add another repository.")
            "none are ready" in m -> listOf("Your debrid service had none of them ready just now. Choose Check Again in a moment.",
                "If it keeps happening, check that your plan is active, or connect another service.")
            "restart" in m -> listOf("Choose Close Dream OS in Settings, open Dream OS again, then try again.")
            else -> listOf("Choose Check Again. If it still doesn't pass, go back and check that your debrid service says Connected and your scrapers have providers on.")
        }
    }

    private fun names(n: List<String>) = when (n.size) {
        0 -> ""
        1 -> n[0]
        else -> n.dropLast(1).joinToString(", ") + " and " + n.last()
    }

    // =================================================================================
    // Views
    // =================================================================================

    /**
     * Show Me How as a walkthrough on the TV: one step per page, never a list to scroll. The page's codes are right
     * on it (a QR code per website named, with its short address), so nothing opens underneath. [intro] leads the
     * first page.
     */
    fun guide(ui: SetupUi, parts: SetupParts, panel: SetupPanel, title: String, steps: List<Step>, intro: String? = null): SetupScreen =
        pager(ui, parts, panel, title, "Step", steps.mapIndexed { i, st -> Page(st.text, st.see, st.links, st.keyArt, if (i == 0) intro else null) })

    /** If Something Goes Wrong: one problem and its fix per page, the same walkthrough as [guide]. */
    fun trouble(ui: SetupUi, parts: SetupParts, panel: SetupPanel, tips: List<Tip>): SetupScreen =
        pager(ui, parts, panel, "If Something Goes Wrong", "Problem", tips.map { Page(it.problem, it.fix) })

    private class Page(val head: String, val body: String?, val links: List<Link> = emptyList(), val keyArt: Boolean = false, val lead: String? = null)

    /**
     * A walkthrough for the remote: "‹  Step 2 of 6  ›" over one page at a time, and one button. Select (or
     * Right) goes to the next page, Left to the one before; on the last page the button says Done and goes back.
     * The page is rebuilt in place under a still button, so focus never moves and nothing scrolls.
     */
    private fun pager(ui: SetupUi, parts: SetupParts, panel: SetupPanel, title: String, noun: String, pages: List<Page>): SetupScreen {
        val ctx = ui.ctx
        val k = ui.k
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val s = SetupScreen(ui, title, null)
        val counter = k.label("", 15f, Ui.alpha(Color.WHITE, 0.70f), k.medium)
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        s.add(counter, 0)
        s.add(page.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT) }, 12)
        var at = 0
        lateinit var go: (Int) -> Unit
        val button = parts.pill("Next Step", true) { if (at + 1 < pages.size) go(at + 1) else panel.pop() }
        button.setOnKeyListener { _, code, e ->
            if (e.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (at + 1 < pages.size) go(at + 1); true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { if (at > 0) go(at - 1); true }
                else -> false
            }
        }
        s.add(parts.pills(button), 24)
        go = { i ->
            val p = pages[i]
            val dir = if (i >= at) 1 else -1
            at = i
            // "‹  Step 2 of 6  ›": an arrow is bright only where Left or Right has a page to go to.
            counter.text = android.text.SpannableStringBuilder().apply {
                fun arrow(t: String, on: Boolean) {
                    val from = length
                    append(t)
                    setSpan(android.text.style.ForegroundColorSpan(Ui.alpha(Color.WHITE, if (on) 0.85f else 0.18f)), from, length, 0)
                }
                arrow("‹", i > 0)
                append("   $noun ${i + 1} of ${pages.size}   ")
                arrow("›", i + 1 < pages.size)
            }
            button.text = if (i + 1 < pages.size) "Next $noun" else "Done"
            page.removeAllViews()
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            p.lead?.let { texts.addView(k.label(it, 16f, SetupBrand.DIM, k.font, lines = 0).apply { setLineSpacing(dp(3).toFloat(), 1f) },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }) }
            texts.addView(k.label(p.head, 23f, SetupBrand.TEXT, k.semibold, lines = 0).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            })
            if (!p.body.isNullOrEmpty()) texts.addView(k.label(p.body, 17f, SetupBrand.DIM, k.font, lines = 0).apply {
                setLineSpacing(dp(4).toFloat(), 1f)
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // One website: its code beside the words. Several: a row of codes under them.
            if (p.links.size == 1) row.addView(code(ui, p.links[0], 128), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(32) })
            page.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (p.links.size > 1) {
                val codes = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                for ((j, l) in p.links.withIndex()) codes.addView(code(ui, l, 96), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (j > 0) marginStart = dp(16) })
                page.addView(codes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            }
            if (p.keyArt) page.addView(TmdbKeyArt(ctx, k), LinearLayout.LayoutParams(MATCH_PARENT, dp(TmdbKeyArt.HEIGHT_DP)).apply { topMargin = dp(16) })
            page.alpha = 0f
            page.translationX = dp(18 * dir).toFloat()
            page.animate().alpha(1f).translationX(0f).setDuration(Ui.OVERLAY_IN).setInterpolator(Ui.EASE).start()
        }
        go(0)
        s.focus = { button }
        return s
    }

    /** A website as a QR code with its name and short address under it (the TV opens no browser). */
    private fun code(ui: SetupUi, l: Link, sizeDp: Int): View {
        val ctx = ui.ctx
        val dp = { v: Int -> Ui.dp(ctx, v) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            ui.qr(l.url, sizeDp)?.let { addView(it, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))) }
            addView(ui.k.label(l.title, 15f, SetupBrand.TEXT, ui.k.semibold, lines = 0).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(ui.k.label(l.short, 13f, SetupBrand.DIM, ui.k.font, lines = 0).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
        }
    }

    /** A page on the viewer's phone: its QR code and short address (the TV opens no browser). */
    fun linkScreen(ui: SetupUi, parts: SetupParts, panel: SetupPanel, l: Link): SetupScreen {
        val ctx = ui.ctx
        val dp = { v: Int -> Ui.dp(ctx, v) }
        val s = SetupScreen(ui, l.title, null)
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.k.label("Scan the code with your phone's camera, or go to", 15f, SetupBrand.DIM, ui.k.font, lines = 0))
        col.addView(ui.k.label(l.short, 19f, SetupBrand.TEXT, ui.k.semibold, lines = 0), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        col.addView(ui.k.label("on a phone or computer.", 15f, SetupBrand.DIM, ui.k.font, lines = 0), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        wrap.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        ui.qr(l.url, 160)?.let { wrap.addView(it, LinearLayout.LayoutParams(dp(160), dp(160)).apply { marginStart = dp(24) }) }
        s.add(wrap, 0)
        val done = parts.pill("Done", true) { panel.pop() }
        s.add(parts.pills(done), 22)
        s.focus = { done }
        return s
    }

    /** The two rows every account page carries: Show Me How and If Something Goes Wrong. */
    fun helpRows(rows: ScreenRows, keyPrefix: String, parts: SetupParts, panel: SetupPanel, ui: SetupUi,
                 howCaption: String, troubleCaption: String, steps: () -> List<Step>, tips: List<Tip>, intro: String? = null, top: Int = 8) {
        rows.row("$keyPrefix:how", top) { parts.row(null, chevron = true) { panel.push(guide(ui, parts, panel, "Show Me How", steps(), intro)) } }
            .set("Show Me How", howCaption)
        rows.row("$keyPrefix:trouble", 8) { parts.row(null, chevron = true) { panel.push(trouble(ui, parts, panel, tips)) } }
            .set("If Something Goes Wrong", troubleCaption)
    }

    /** "What to Try", right under an error message. */
    fun whatToTry(ctx: Context, k: PremiumKit, lines: List<String>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val dp = { v: Int -> Ui.dp(ctx, v) }
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.04f), Ui.R_MD, 1f, Ui.alpha(Color.WHITE, 0.10f))
        addView(k.label("What to Try", 15f, SetupBrand.TEXT, k.semibold))
        for (l in lines) addView(k.label(l, 14f, SetupBrand.DIM, k.font, lines = 0).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    /** What a repository address looks like, in a monospaced face. */
    fun addressExamples(ctx: Context, k: PremiumKit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val dp = { v: Int -> Ui.dp(ctx, v) }
        setPadding(dp(16), dp(12), dp(16), dp(14))
        background = Ui.rounded(ctx, Ui.alpha(Color.WHITE, 0.045f), Ui.R_MD, 1f, Ui.alpha(Color.WHITE, 0.10f))
        addView(k.label("A Repository Address Looks Like This", 15f, SetupBrand.TEXT, k.semibold))
        // Plain lines, one per example (no capsule around each: they're text to read, not things to choose).
        for (a in ADDRESS_EXAMPLES) addView(k.label(a, 15f, Ui.alpha(SetupBrand.TEXT, 0.90f), Typeface.MONOSPACE),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(k.label("Enter it exactly as you got it. It usually starts with https:// and often has github in it.", 14f, SetupBrand.DIM, k.font, lines = 0),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(9) })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
}

/**
 * A drawing of TMDB's API page (Dream Cinema look, no screenshot): the short API Key lit with a still rainbow border
 * and "Use This One", the long API Read Access Token dimmed with "Not This One". Drawn once into a bitmap when it is
 * sized, then only that bitmap is drawn: nothing animates and no offscreen layer is used.
 */
internal class TmdbKeyArt(ctx: Context, private val k: PremiumKit) : View(ctx) {
    private var bmp: Bitmap? = null
    private val dp = ctx.resources.displayMetrics.density

    init { isFocusable = false; contentDescription = "On TMDB's API page, use the short API Key, not the long API Read Access Token." }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bmp?.recycle()
        bmp = if (w > 0 && h > 0) try { render(w, h) } catch (e: Throwable) { null } else null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bmp == null && width > 0 && height > 0) bmp = try { render(width, height) } catch (e: Throwable) { null }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); bmp?.recycle(); bmp = null }

    override fun onDraw(c: Canvas) { bmp?.let { c.drawBitmap(it, 0f, 0f, null) } }

    private fun px(v: Float) = v * dp

    private fun render(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF0E0E10.toInt()
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), px(14f), px(14f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = px(1f); p.color = Ui.alpha(Color.WHITE, 0.10f)
        c.drawRoundRect(RectF(px(0.5f), px(0.5f), w - px(0.5f), h - px(0.5f)), px(14f), px(14f), p)
        p.style = Paint.Style.FILL
        // The browser dots and the page name.
        p.color = Ui.alpha(Color.WHITE, 0.18f)
        for (i in 0..2) c.drawCircle(px(18f + i * 12f), px(18f), px(3.5f), p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = k.medium; textSize = px(11.5f); color = SetupBrand.DIM }
        c.drawText("themoviedb.org  ·  API", px(62f), px(22f), t)
        val fieldH = px(52f)
        val top = px(36f)
        field(c, RectF(px(12f), top, w - px(12f), top + fieldH), "API Key", "8f3a1c9e2b7d4f60a5e1c3b9d2f7a4e8", "Use This One", true)
        field(c, RectF(px(12f), top + fieldH + px(10f), w - px(12f), top + 2 * fieldH + px(10f)),
            "API Read Access Token", "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI4ZjNhMWM5ZTJiN2Q0ZjYwYTVlMWMzYjlkMmY3YTRlOCIs", "Not This One", false)
        return b
    }

    private fun field(c: Canvas, r: RectF, label: String, value: String, tag: String, lit: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Ui.alpha(Color.WHITE, if (lit) 0.07f else 0.03f)
        c.drawRoundRect(r, px(10f), px(10f), p)
        if (lit) {
            // A still rainbow edge (the brand's only accent), drawn once.
            p.style = Paint.Style.STROKE; p.strokeWidth = px(1.5f)
            p.shader = Rainbow.gradient(r.width())
            c.drawRoundRect(RectF(r.left + px(0.75f), r.top + px(0.75f), r.right - px(0.75f), r.bottom - px(0.75f)), px(10f), px(10f), p)
            p.shader = null; p.style = Paint.Style.FILL
        }
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = k.semibold; textSize = px(13f); color = if (lit) SetupBrand.TEXT else SetupBrand.DIM }
        c.drawText(label, r.left + px(12f), r.top + px(20f), name)
        // The tag capsule at the right.
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = k.semibold; textSize = px(11f); color = if (lit) PearlFocus.INK else SetupBrand.DIM }
        val tw = tp.measureText(tag)
        val cap = RectF(r.right - px(12f) - tw - px(16f), r.top + px(7f), r.right - px(12f), r.top + px(26f))
        p.color = if (lit) PearlFocus.PEARL else Ui.alpha(Color.WHITE, 0.06f)
        c.drawRoundRect(cap, cap.height() / 2, cap.height() / 2, p)
        c.drawText(tag, cap.left + px(8f), cap.bottom - px(5.5f), tp)
        // The value, cut at the field's edge.
        val v = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = px(12f); color = if (lit) SetupBrand.TEXT else Ui.alpha(SetupBrand.TEXT, 0.35f) }
        val room = r.width() - px(24f)
        val shown = if (v.measureText(value) <= room) value else {
            var n = value.length
            while (n > 1 && v.measureText(value.substring(0, n) + "…") > room) n--
            value.substring(0, n) + "…"
        }
        c.drawText(shown, r.left + px(12f), r.top + px(42f), v)
    }

    companion object { const val HEIGHT_DP = 160 }
}
