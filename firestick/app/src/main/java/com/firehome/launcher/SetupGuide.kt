package com.firehome.launcher

/**
 * Beginner-friendly setup help (owner, 2026-10-28 night; the iPhone's SetupGuide.swift in TV words): every account
 * step has a "Show Me How" screen of numbered steps and an "If Something Goes Wrong" screen of fixes. The websites are
 * used on a phone or computer; keys and addresses are typed here with Phone Remote or the on-screen keyboard. Each
 * line is a focusable row so the list scrolls with the remote. Text only: no setup logic here.
 */
internal object SetupGuide {
    /** One instruction (or one problem): its short name and what to do / what the viewer will see. */
    class Step(val title: String, val text: String)

    val TMDB_STEPS = listOf(
        Step("Make a Free TMDB Account", "On your phone or computer, sign up at themoviedb.org/signup, then choose Activate My Account in TMDB's email."),
        Step("Open the API Page", "On TMDB, your profile picture at the top right, then Settings, then API. Or go to themoviedb.org/settings/api."),
        Step("Ask for a Key", "Choose Create, then Developer, and accept the terms. For the app's name type Dream; any website and a short summary are fine."),
        Step("Copy the Key Labeled API Key", "It's 32 letters and numbers. Leave the much longer API Read Access Token."),
        Step("Add It Here", "Choose TMDB Key (Movie & Show Info in setup) and type it with Phone Remote or the keyboard, then save it."),
    )

    val TMDB_TROUBLE = listOf(
        Step("That Key Didn't Work", "Use API Key, the short one, not the long API Read Access Token, with nothing before or after it."),
        Step("TMDB Won't Let You Ask for a Key", "Activate your account first: open TMDB's email, choose Activate My Account, then log in again."),
        Step("There's No Key on the API Page", "Finish the form under Create first. If TMDB is reviewing it, wait a few minutes and look again."),
        Step("The Key Won't Save", "Check the TV's internet connection, then save the key again."),
    )

    val DEBRID_STEPS = listOf(
        Step("Choose a Service", "Premiumize starts Play fastest (about \$12 a month); TorBox works the same way. AllDebrid and Real-Debrid cost less."),
        Step("Sign Up and Buy a Plan", "On your phone, open the service's website (its code is under What's a Debrid Service?), make an account and buy a plan."),
        Step("Connect It Here", "Choose your service in the list. Scan its code or go to the address shown, sign in and enter the code."),
        Step("Wait for Connected", "Dream OS connects by itself once you approve. You can connect more than one; they're asked in the order you choose."),
    )

    val DEBRID_TROUBLE = listOf(
        Step("The Code Expired", "Codes work for about 10 minutes. Choose the service again for a new code and enter it right away."),
        Step("It Says Expired or No Premium Plan", "The account needs an active paid plan. Renew it on the service's website, then check the service again here."),
        Step("It Says Sign In Again", "Choose the service, then Link Again, and enter the new code."),
        Step("Your Key Wasn't Accepted", "Copy the key again from the API Key line on your account page, with nothing before or after it."),
        Step("It Still Says Waiting", "Wait a few seconds after you approve. If nothing changes, go back, choose the service again and use the new code."),
    )

    val SCRAPER_STEPS = listOf(
        Step("Get Your Repository's Address", "Repositories are shared by the people who make them, on a web page or on GitHub. The address starts with https://."),
        Step("Add It Here", "Choose Add Scraper Repository and type the address with Phone Remote or the keyboard. Dream OS checks and installs it."),
        Step("Use Recommended Settings", "Turns on the fastest, most reliable providers, so most titles start in about a second."),
        Step("Continue", "The next step checks that your debrid service and scrapers work together."),
    )

    val SCRAPER_TROUBLE = listOf(
        Step("That Doesn't Look Like a Web Address", "Enter the whole address and only that, with no spaces or extra words."),
        Step("No Scrapers Were Found", "The address may lead to a page about the repository. Use the exact address its maker gives, or ask them for it."),
        Step("Couldn't Reach That Address", "Check the TV's internet connection. If it's fine, the website may be down for a while: try again later."),
        Step("It Isn't in a Format Dream OS Supports", "That repository won't work with Dream OS. Try another one."),
        Step("Providers Shows 0 On", "Choose Use Recommended Settings, or turn on at least one provider."),
    )

    /** A guide screen: [steps] as rows ([numbered]: "1.  …"), then Done. */
    fun screen(ui: SetupUi, parts: SetupParts, panel: SetupPanel, title: String, steps: List<Step>, numbered: Boolean): SetupScreen {
        val s = SetupScreen(ui, title, null)
        var first: BrandRow? = null
        for ((i, st) in steps.withIndex()) {
            val r = parts.row(null) {}
            r.set(if (numbered) "${i + 1}.  ${st.title}" else st.title, st.text)
            if (first == null) first = r
            s.add(r, if (i == 0) 0 else 8)
        }
        val done = parts.pill("Done", true) { panel.pop() }
        s.add(parts.pills(done), 22)
        s.focus = { first ?: done }
        return s
    }
}
