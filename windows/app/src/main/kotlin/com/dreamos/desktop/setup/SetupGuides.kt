package com.dreamos.desktop.setup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dreamos.desktop.settings.TmdbKeyArt

/**
 * Every word of setup's help (iPhone SetupGuide.swift / fire-home SetupGuide.kt), worded for a PC: "select" for
 * the mouse and the keyboard alike, "Dream OS" as the name, websites open in the browser and show a QR code for a
 * phone. Flow and order are the other apps'.
 */
object SetupGuides {
    val tmdbIntro = "TMDB's sign-up takes a few minutes in your browser. Each step has a button that opens the right page, and a code to scan if you'd rather use your phone."

    val tmdb = listOf(
        GuideStep("Make a free TMDB account. Select Join TMDB, fill in the form and select Sign Up.",
            "TMDB sends you an email. Select Activate My Account in it, then log in. Already have an account? Log in instead.",
            listOf("Join TMDB" to "https://www.themoviedb.org/signup")),
        GuideStep("Open the API page in your TMDB settings.",
            "On TMDB, select your profile picture at the top right, then Settings, then API in the Settings menu. Or use the button below to go straight there.",
            listOf("Open the API Page" to "https://www.themoviedb.org/settings/api")),
        GuideStep("Select Create, choose Developer, and accept the terms of use.", "TMDB shows a short form about the app you'll use the key in."),
        GuideStep("Fill in the form, then select Submit.",
            "If it asks how you'll use it, choose Personal. For the app's name, type Dream OS. For its website, any address works, such as https://www.themoviedb.org. For the summary, a short line is fine, such as \"A personal app for browsing movies and shows.\" Then add your name and contact details."),
        GuideStep("Copy the key labeled API Key.",
            "TMDB shows two. Copy API Key: 32 letters and numbers. Leave the much longer API Read Access Token.",
            art = { TmdbKeyArt(Modifier.widthIn(max = 560.dp)) }),
        GuideStep("Come back to Dream OS, select Enter My Key, then Paste.", "Dream OS checks your key, shows Key Added and moves on by itself."),
    )

    val tmdbTrouble = listOf(
        Trouble("That Key Didn't Work", "Make sure you copied API Key, the short one, not the long API Read Access Token. Copy it again with nothing before or after it, then paste it."),
        Trouble("TMDB Won't Let You Ask for a Key", "Your account needs to be activated first. Open the email from TMDB, select Activate My Account, log in again, then open the API page."),
        Trouble("There's No Key on the API Page", "Finish the form under Create first. If TMDB says it's reviewing your request, wait a few minutes and open the API page again."),
        Trouble("The Key Won't Save", "Check your PC's internet connection, then select Save Key again."),
    )

    val omdb = listOf(
        GuideStep("Open OMDb's key page and choose FREE! (1,000 daily limit).", null, listOf("Open OMDb" to "https://www.omdbapi.com/apikey.aspx")),
        GuideStep("Type your email, fill in anything else it asks for, and select Generate API Key.", "OMDb emails you your key a few minutes later. Check your junk folder if it doesn't arrive."),
        GuideStep("Open OMDb's email and select the link in it to activate your key.", "The key only works once it's activated. Then copy the key from the email."),
        GuideStep("Come back to Dream OS and select Paste.", "Dream OS checks your key and shows Key Added."),
    )

    val omdbTrouble = listOf(
        Trouble("That Key Didn't Work", "Select the activation link in OMDb's email first, then save the key again. Copy only the key, with nothing before or after it."),
        Trouble("The Email Never Came", "Look in your junk folder. Some email services take up to an hour. You can skip ratings for now and add them later in Settings."),
    )

    /** Which service to pick, right under the list of services (fire-home SetupGuide.PICK_LINE). */
    const val PICK_LINE = "Premiumize is recommended: Play starts fastest with it. It's about \$12 a month, or about \$80 for a year. " +
        "TorBox works the same way. AllDebrid and Real-Debrid cost less, about \$4 a month, and work too: Play takes a second or two longer with them."

    val debrid = listOf(
        GuideStep("Choose a service and open its website.", "Already have one? Go to step 3.", listOf(
            "Premiumize" to "https://www.premiumize.me/premium", "TorBox" to "https://torbox.app/pricing",
            "AllDebrid" to "https://alldebrid.com/offer/", "Real-Debrid" to "https://real-debrid.com/premium")),
        GuideStep("Select Sign Up, make an account with your email and a password, then buy a plan.", "Dream OS plays only with an active paid plan. The plans page lists each length and its price."),
        GuideStep("Come back to Dream OS and select your service in the list.", "AllDebrid, Real-Debrid and TorBox show a short code. Premiumize asks for your key instead."),
        GuideStep("For a code: select Open in Browser. Dream OS copies the code for you.",
            "Sign in on the page that opens, paste the code and approve Dream OS. The code works for about 10 minutes, and Dream OS connects by itself once you approve."),
        GuideStep("For a key: select Open Your Premiumize Account and sign in.", "Find API Key on your account page and copy it. Come back, paste it and select Connect."),
        // Setup stays on this step after a sign-in (owner, 2026-10-30): the viewer goes on with Continue.
        GuideStep("When your service says Connected, select Continue.", "You can connect more than one. Dream OS asks them in the order you choose in Settings."),
    )

    val debridTrouble = listOf(
        Trouble("The Code Expired", "Codes work for about 10 minutes. Select Try Again for a new code, then enter it on the website right away."),
        Trouble("It Says Expired or No Premium Plan", "Your account needs an active paid plan. Buy or renew one on the service's website, then select the service here and choose Check Again."),
        Trouble("It Says Sign In Again", "Select the service, choose Link Again, and enter the new code."),
        Trouble("Your Key Wasn't Accepted", "Copy the key again from the API Key line on your account page, with nothing before or after it."),
        Trouble("It Still Says Waiting After You Approve", "Wait a few seconds on this screen. If nothing changes, select Back, select your service again and use the new code."),
    )

    val scrapers = listOf(
        GuideStep("Copy your repository's address.", "Repositories are shared by the people who make them, usually on a web page or on GitHub. Copy the whole address. If someone told you about Dream OS, ask them for the address they use."),
        GuideStep("Come back to Dream OS, select Add Repository, then Paste.", "Dream OS shows its progress: Checking Address, Downloading, Installing. Then it shows Added."),
        GuideStep("Dream OS turns on the recommended providers.",
            "Each provider searches one website. The recommended ones are the fastest and most reliable, so most titles start in about a second. Manage Scrapers switches any provider on or off."),
        GuideStep("Select Continue.", "The next step checks that your debrid service and scrapers work together."),
    )

    val scrapersTrouble = listOf(
        Trouble("That Doesn't Look Like a Web Address", "Copy the whole address and paste only that, with no spaces or extra words."),
        Trouble("No Scrapers Were Found", "The address may lead to a page about the repository instead of the repository itself. Use the exact address its maker gives, or ask them for it."),
        Trouble("Couldn't Reach That Address", "Check your PC's internet connection. If it's on, the website may be down for a while: try again later."),
        Trouble("It Isn't in a Format Dream OS Supports", "That repository won't work with Dream OS. Try another one."),
        Trouble("Providers Shows 0 On", "Select Manage Scrapers, then Use Recommended Settings, or switch on at least one provider."),
    )

    /** Trakt's Show Me How (fire-home traktSteps without built-in keys), worded for the PC's one-field sign-in. */
    val trakt = listOf(
        GuideStep("Make a free Trakt account, if you don't have one.", null, listOf("Open Trakt" to "https://trakt.tv/auth/join")),
        GuideStep("Select Trakt, then Use Your Own Trakt App, and open trakt.tv/oauth/applications.",
            "Select New Application, give it any name, such as Dream, and enter the Redirect URI Dream OS shows. Save it."),
        GuideStep("Copy your app's Client ID into Dream OS.", "Trakt shows it on your new app's page."),
        GuideStep("Select Sign In. Open the address shown in your browser, or scan the code with your phone.",
            "Sign in to Trakt, enter the code and approve Dream OS. Dream OS says Connected and starts syncing your history and watchlist."),
    )

    /** The help topics: Show Me How's steps and If Something Goes Wrong's problems, with the rows' captions. */
    val tmdbHelp get() = HelpTopic("Get your free key from TMDB", "Keys that don't work", tmdb, tmdbIntro, tmdbTrouble)
    val omdbHelp get() = HelpTopic("Get your free key from OMDb", "Keys and the activation email", omdb, null, omdbTrouble)
    val debridHelp get() = HelpTopic("Sign up, then connect it to Dream OS", "Expired codes, plans and keys", debrid, null, debridTrouble)
    val scrapersHelp get() = HelpTopic("Add it, then choose your providers", "Addresses that won't install", scrapers, null, scrapersTrouble)

    /** "What to Try" under a failed sign-in or key for [name] ([key]: a key, not a code). fire-home SetupGuide.linkHelp. */
    fun linkHelp(message: String, name: String, key: Boolean): List<String> {
        val m = message.lowercase()
        return when {
            "expired" in m -> listOf("Codes work for about 10 minutes. Select Try Again for a new code, then enter it on the website right away.")
            "declined" in m -> listOf("Select Try Again, and this time choose to allow Dream OS on $name's website.")
            "key" in m -> listOf("Copy the key again from the API Key line on your $name account page, with nothing before or after it.",
                "Make sure your $name plan is active, then select Try Again.")
            "answer" in m || "network" in m || "internet" in m -> listOf("Check your PC's internet connection, then select Try Again.",
                "If it still doesn't work, $name may be busy for a while: try again in a few minutes.")
            else -> listOf(if (key) "Check your key, then select Try Again." else "Select Try Again for a new code.",
                "If it keeps happening, connect another service for now and try $name again later.")
        }
    }

    /** "What to Try" under a failed Add Repository, matched to the scrapers' message. fire-home SetupGuide.repoHelp. */
    fun repoHelp(message: String): List<String> {
        val m = message.lowercase()
        return when {
            "look like a web address" in m -> listOf("Paste the whole address and only that, with no spaces or extra words. It usually starts with https://.")
            "no scrapers were found" in m -> listOf("The address may lead to a page about the repository instead of the repository itself.",
                "Use the exact address its maker gives, or ask them for it.")
            "couldn't reach" in m -> listOf("Check your PC's internet connection.", "If it's on, the website may be down for a while: try again later.")
            "safe" in m || "format" in m || "single add-on" in m || "too large" in m -> listOf("That repository won't work with Dream OS. Try another one.")
            "check out" in m -> listOf("The repository may be updating right now. Wait a few minutes, then try again.")
            else -> listOf("Check the address, then select Try Again.", "If it still won't add, wait a few minutes and try again, or try another repository.")
        }
    }

    /** "What to Try" for a failed check, matched on its message (iPhone checkHelp). */
    fun checkHelp(message: String): List<String> {
        val m = message.lowercase()
        return when {
            "add a source" in m || "add a debrid account and a scraper" in m -> listOf("Select Back to return to Add Your Scrapers, then add your repository.")
            "turn on at least one provider" in m -> listOf("Select Back to return to Add Your Scrapers, select Manage Scrapers, then Use Recommended Settings.")
            "link a streaming account" in m -> listOf("Your scrapers work, but no debrid service is ready to play.", "Go back to Connect a Debrid Service, and check that your service says Connected and has an active paid plan.")
            "no sources came back" in m -> listOf("Check your PC's internet connection, then select Try Again.", "If it still finds nothing, go back to Add Your Scrapers, select Manage Scrapers and choose Use Recommended Settings, or add another repository.")
            "none are ready" in m -> listOf("Your debrid service had none of them ready just now. Select Try Again in a moment.",
                "If it keeps happening, check that your plan is active, or connect another service.")
            "taking too long" in m -> listOf("Select Try Again. If it keeps happening, go back to Add Your Scrapers, select Manage Scrapers and choose Use Recommended Settings.")
            "restart" in m -> listOf("Close Dream OS, open it again, then select Try Again.")
            else -> listOf("Select Try Again. If it still doesn't pass, go back and check that your debrid service says Connected and your scrapers have providers on.")
        }
    }
}

/** A help topic: Show Me How ([steps], [intro]) and If Something Goes Wrong ([problems]), with each row's caption. */
class HelpTopic(val howCaption: String, val troubleCaption: String, val steps: List<GuideStep>, val intro: String?, val problems: List<Trouble>)

/** The two help rows a page shows for [topic]; [open] gets true for Show Me How, false for If Something Goes Wrong. */
@androidx.compose.runtime.Composable
fun HelpRows(topic: HelpTopic, open: (Boolean) -> Unit) {
    com.dreamos.desktop.settings.kit.PageRow("Show Me How", { open(true) }, caption = topic.howCaption)
    com.dreamos.desktop.settings.kit.PageRow("If Something Goes Wrong", { open(false) }, caption = topic.troubleCaption)
}

/** The open help page for [topic]: the walkthrough ([how]) or the problems list; Done and Back call [onBack]. */
@androidx.compose.runtime.Composable
fun HelpPage(topic: HelpTopic, how: Boolean, onBack: () -> Unit) {
    if (how) com.dreamos.desktop.settings.kit.PageFrame(onBack) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 36.dp)) { Walkthrough("Show Me How", topic.steps, topic.intro, onBack) }
    } else com.dreamos.desktop.settings.kit.PageScaffold("If Something Goes Wrong", onBack) { TroubleList(topic.problems) }
}
