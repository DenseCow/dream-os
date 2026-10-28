package com.firehome.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Continue Watching for the official apps (Prime Video, Netflix, Hulu, ...).
 *
 * Progress comes from each app's media session (read by [AppWatchService], a notification listener
 * granted over adb). What's playing comes from the session metadata when the app fills it in, and
 * otherwise from what was selected on screen when OK was pressed to start it: Prime Video's
 * session only says "PrimeVideo", but its detail page / home card expose "[American Horror Story, ...]
 * Resume S1 E2" to accessibility, which HomeRedirectService hands to [onOkPressed].
 */
object AppWatch {
    private const val TAG = "FireHome"

    /** Apps whose content is on TMDB (YouTube/Twitch would only add noise). */
    val PACKAGES: Set<String> = Tmdb.SERVICES.map { it.pkg }.toSet() +
        setOf("com.amazon.firebat", "com.netflix.ninja", "com.hulu.plus", "com.hulu.livingroomplus", "com.disney.disneyplus")

    /** Names apps put in their metadata instead of the title. */
    private val JUNK = setOf("primevideo", "amazonprimevideo", "prime", "netflix", "hulu", "peacock", "disney", "disneyplus",
        "max", "hbomax", "paramount", "paramountplus", "tubi", "plutotv", "appletv", "video", "unknown", "advertisement", "ad")

    private val SE = Regex("(?i)\\bS(?:eason)?\\s*(\\d{1,2})\\s*[:,.·]?\\s*E(?:p(?:isode)?)?\\s*(\\d{1,3})\\b")
    private val SE_WORDS = Regex("(?i)\\bSeason\\s*(\\d{1,2}),?\\s*Episode\\s*(\\d{1,3})\\b")
    private val BUTTON = Regex("(?i)^(resume|play|watch|start|restart|continue|episode|trailer|more|season|\\d+% watched)\\b")

    /** What was on screen when the user pressed OK in a tracked app. */
    class Pick(val pkg: String, val title: String?, val season: Int, val episode: Int, val at: Long)

    @Volatile private var pick: Pick? = null

    fun isJunk(s: String?) = s.isNullOrBlank() || s.lowercase().replace(Regex("[^a-z0-9]"), "") in JUNK

    /** Season/episode found in any of [texts], or null. */
    fun seasonEpisode(vararg texts: String?): Pair<Int, Int>? {
        for (t in texts) {
            if (t == null) continue
            (SE.find(t) ?: SE_WORDS.find(t))?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        }
        return null
    }

    /** Called (posted, off the key path) when OK goes down; only looks at tracked apps. */
    fun onOkPressed(service: AccessibilityService) {
        val root = service.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return
        // The selected item: real input focus, or the virtual "selected" node Prime's renderer reports.
        val picked = ArrayList<Pair<String, String>>() // text, description
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 400) {
            val n = queue.removeFirst()
            val text = n.text?.toString().orEmpty().trim()
            val desc = n.contentDescription?.toString().orEmpty().trim()
            if ((n.isSelected || n.isFocused || n.isAccessibilityFocused) && (text.isNotEmpty() || desc.isNotEmpty())) picked += text to desc
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (picked.isEmpty()) return
        var title: String? = null
        var se: Pair<Int, Int>? = null
        for ((text, desc) in picked) {
            if (se == null) se = seasonEpisode(text, desc)
            if (title != null) continue
            title = when {
                desc.startsWith("[") && desc.contains("]") -> {
                    val inner = desc.substringAfter("[").substringBefore("]")
                    val after = desc.substringAfter("]").trim()
                    if (after.isNotEmpty() && !BUTTON.containsMatchIn(after)) after else inner.split(", ").first()
                }
                text.isNotEmpty() && text.length < 80 && !BUTTON.containsMatchIn(text) -> text
                desc.isNotEmpty() && desc.length < 80 && !BUTTON.containsMatchIn(desc) -> desc
                else -> null
            }?.let { WatchHistory.cleanTitle(it) }?.takeIf { it.isNotEmpty() && !isJunk(it) }
        }
        if (title == null && se == null) return
        val old = pick
        // Pressing "Resume S1 E2" on the detail page keeps the title picked from the card before it.
        val keepTitle = title ?: old?.takeIf { it.pkg == pkg && SystemClock.elapsedRealtime() - it.at < 5 * 60_000 }?.title
        pick = Pick(pkg, keepTitle, se?.first ?: 0, se?.second ?: 0, SystemClock.elapsedRealtime())
        Log.w(TAG, "watch pick in $pkg: ${if (keepTitle != null) "a title" else "no title"} ${se ?: ""}") // not what's being watched
    }

    /** The on-screen pick for [pkg] if it was made in the last few minutes; consumed. */
    fun takePick(pkg: String): Pick? {
        val p = pick ?: return null
        if (p.pkg != pkg || SystemClock.elapsedRealtime() - p.at > 5 * 60_000) return null
        pick = null
        return p
    }

    /** A link that opens [pkg] on the exact title, from the session's media id (when the app sets a usable one). */
    fun deepLink(pkg: String, mediaId: String?): String? {
        val id = mediaId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            pkg == "com.netflix.ninja" && id.all { it.isDigit() } -> "https://www.netflix.com/watch/$id"
            pkg == "com.amazon.firebat" && id.startsWith("amzn1.dv.gti.") -> "https://app.primevideo.com/detail?gti=$id"
            pkg.startsWith("com.hulu.") && Regex("^[0-9a-f-]{36}$").matches(id) -> "https://www.hulu.com/watch/$id"
            else -> null
        }
    }

    /** Opens the app for [e]: exact deep link, else the app's search for the title, else just the app. */
    fun resume(ctx: Context, e: WatchHistory.Entry): Boolean {
        val pkg = e.source
        val links = listOfNotNull(e.deepLink, Tmdb.SERVICES.firstOrNull { it.pkg == pkg }?.search?.format(Uri.encode(e.title)))
        for (link in links) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (ex: Exception) {
            }
        }
        val launch = Apps.launchIntent(ctx, pkg) ?: return false
        return try { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (ex: Exception) { false }
    }
}

/**
 * Notification listener, only so Dream OS may read other apps' media sessions (Android requires one
 * for MediaSessionManager.getActiveSessions). Granted by tools/deploy.sh with
 * `cmd notification allow_listener`. Event-driven: no timers except a once-a-minute save while
 * something is actually playing.
 */
class AppWatchService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val trackers = HashMap<MediaSession.Token, Tracker>()
    private var msm: MediaSessionManager? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty(), prune = true) }
    /** Last now-playing notification text per app, a fallback when the session has no title. */
    private val notifText = HashMap<String, Pair<String?, String?>>()

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val me = ComponentName(this, AppWatchService::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, me, handler)
            sync(m.getActiveSessions(me), prune = true)
        } catch (e: SecurityException) {
            Log.w(TAG, "media sessions not readable: $e")
        }
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (e: Exception) {}
        trackers.values.forEach { it.detach() }
        trackers.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in AppWatch.PACKAGES) return
        val extras = sbn.notification?.extras ?: return
        @Suppress("DEPRECATION")
        val token = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (token == null && sbn.notification.category != Notification.CATEGORY_TRANSPORT) return
        notifText[pkg] = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() to extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (token != null && token !in trackers) sync(listOf(MediaController(this, token)), prune = false)
    }

    private fun sync(list: List<MediaController>, prune: Boolean) {
        val live = HashSet<MediaSession.Token>()
        for (c in list) {
            val pkg = c.packageName
            if (pkg !in AppWatch.PACKAGES) continue
            live += c.sessionToken
            if (c.sessionToken !in trackers) trackers[c.sessionToken] = Tracker(c).also { it.attach() }
        }
        if (prune) {
            val gone = trackers.keys.filter { it !in live }
            for (t in gone) trackers.remove(t)?.detach()
        }
    }

    private inner class Tracker(val c: MediaController) : MediaController.Callback() {
        val pkg: String = c.packageName
        var state: PlaybackState? = null
        var meta: MediaMetadata? = null
        var pick: AppWatch.Pick? = null
        var lastProgress = 0f

        val periodic = object : Runnable {
            override fun run() {
                if (!playing(state)) return
                save(position(state))
                handler.postDelayed(this, 60_000)
            }
        }

        fun attach() {
            c.registerCallback(this, handler)
            meta = c.metadata
            logMeta()
            onPlaybackStateChanged(c.playbackState)
        }

        fun detach() {
            if (playing(state)) save(position(state))
            handler.removeCallbacks(periodic)
            try { c.unregisterCallback(this) } catch (e: Exception) {}
        }

        fun playing(s: PlaybackState?) = s?.state == PlaybackState.STATE_PLAYING

        /** Where playback is now, extrapolated from the last report (Prime resets to 0 when it stops). */
        fun position(s: PlaybackState?): Long {
            s ?: return 0
            if (s.state != PlaybackState.STATE_PLAYING || s.lastPositionUpdateTime <= 0) return s.position
            return s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }

        override fun onPlaybackStateChanged(s: PlaybackState?) {
            val was = playing(state)
            val now = playing(s)
            if (now && !was) {
                AppWatch.takePick(pkg)?.let { pick = it }
                // Autoplay rolled into the next episode: same session, position back near the start.
                val p = pick
                if (p != null && p.episode > 0 && lastProgress >= WatchHistory.FINISHED && (s?.position ?: 0) < 120_000) {
                    pick = AppWatch.Pick(pkg, p.title, p.season, p.episode + 1, p.at)
                }
                handler.removeCallbacks(periodic)
                handler.postDelayed(periodic, 60_000)
            } else if (was && !now) {
                save(position(state)) // the old state still holds the last real position
                handler.removeCallbacks(periodic)
            }
            state = s
        }

        override fun onMetadataChanged(m: MediaMetadata?) {
            val oldTitle = titleOf(meta)
            if (playing(state) && oldTitle != null && oldTitle != titleOf(m)) save(position(state))
            meta = m
            logMeta()
        }

        override fun onSessionDestroyed() {
            detach()
            trackers.remove(c.sessionToken)
        }

        fun text(key: String) = meta?.getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        fun titleOf(m: MediaMetadata?): String? = m?.let {
            listOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                .mapNotNull { k -> it.getText(k)?.toString() }.firstOrNull { t -> !AppWatch.isJunk(t) }
        }

        fun logMeta() {
            val m = meta ?: return
            // Which fields the app fills in, not their values (titles of what's being watched stay out of logcat).
            Log.w(TAG, "media session $pkg: ${m.keySet().map { it.removePrefix("android.media.metadata.") }}")
        }

        fun save(positionMs: Long) {
            val n = notifText[pkg]
            val strings = listOf(
                MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
            ).map { text(it) } + listOf(n?.first, n?.second)
            val valid = strings.filterNotNull().filter { !AppWatch.isJunk(it) }
            val se = AppWatch.seasonEpisode(*valid.toTypedArray())
            val p = pick
            // The show/movie name is the first string that isn't the "S1 E2 ..." line.
            val metaTitle = valid.firstOrNull { AppWatch.seasonEpisode(it) == null }?.let { WatchHistory.cleanTitle(it) }?.takeIf { it.isNotEmpty() }
            val title = metaTitle ?: p?.title ?: return
            val season = se?.first ?: p?.season ?: 0
            val episode = se?.second ?: p?.episode ?: 0
            val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0L
            val mediaId = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: meta?.description?.mediaId
            if (duration > 0) lastProgress = positionMs.toFloat() / duration
            WatchHistory.record(this@AppWatchService, WatchHistory.Sighting(
                source = pkg,
                title = title,
                isEpisode = if (episode > 0) true else null,
                season = season,
                episode = episode,
                positionMs = positionMs,
                durationMs = duration,
                deepLink = AppWatch.deepLink(pkg, mediaId),
            ))
        }
    }

    private companion object {
        const val TAG = "FireHome"
    }
}
