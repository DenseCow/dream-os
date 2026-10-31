package com.dreamos.desktop.core.model

import kotlinx.serialization.Serializable

// ---- Notices (fire-home DreamNotices.kt) ----

@Serializable
enum class NoticeSeverity { INFO, WARNING, CRITICAL }

/** What activating a notice does: [label] names it ("Renew"), [target] says what to open ("accounts", "update", "renew:rd"). */
@Serializable
data class NoticeAction(val label: String, val target: String)

/**
 * One notice in the notification center. [key] dedupes: a key is posted once, ever (a dismissed notice never comes back),
 * so whatever makes an event new goes into the key (a date, a version). [emoji] is the banner icon (allowed by the owner).
 */
@Serializable
data class Notice(
    val key: String,
    /** "expiry", "limit", "episodes", "update", "info", "summary". */
    val type: String,
    val title: String,
    val body: String,
    val severity: NoticeSeverity = NoticeSeverity.INFO,
    /** Epoch ms. */
    val at: Long = 0,
    val read: Boolean = false,
    val action: NoticeAction? = null,
    val emoji: String? = null,
) {
    companion object {
        const val TYPE_EXPIRY = "expiry"
        const val TYPE_LIMIT = "limit"
        const val TYPE_EPISODES = "episodes"
        const val TYPE_UPDATE = "update"
        const val TYPE_INFO = "info"
        const val TYPE_SUMMARY = "summary"
    }
}

// ---- Trakt ----

/** The viewer's Trakt link. */
@Serializable
data class TraktStatus(
    val signedIn: Boolean = false,
    /** The Trakt username, "" when signed out. */
    val user: String = "",
    /** Epoch ms of the last finished sync, 0 = never. */
    val lastSync: Long = 0,
    /** A plain sentence when the last sync failed ("Couldn't reach Trakt. Try again."), else null. */
    val problem: String? = null,
    /** The viewer's own Trakt app Client ID is saved (Dream OS ships none). */
    val hasClientId: Boolean = false,
)

/** Trakt's device sign-in: show [userCode] and [verifyUrl] (a QR code on a TV, a link on a PC) until it is approved. */
@Serializable
data class TraktDeviceCode(
    val userCode: String,
    val verifyUrl: String,
    /** Epoch ms when the code stops working. */
    val expiresAt: Long,
    /** Seconds between approval checks. */
    val interval: Int,
    val deviceCode: String,
)

// ---- Updates (fire-home DreamUpdate.kt) ----

/** The newest public release on GitHub (DenseCow/dream-os). */
@Serializable
data class UpdateInfo(
    /** "4.3.4". */
    val version: String,
    /** Release notes as plain text (Markdown cleaned). */
    val notes: String = "",
    /** The Windows installer's download URL (asset DreamOS-Windows-Setup.exe), "" when this release has none. */
    val downloadUrl: String = "",
    val sizeBytes: Long = 0,
    /** Epoch ms. */
    val publishedAt: Long = 0,
    /** SHA-256 of the installer when the release states it, else null. */
    val sha256: String? = null,
)

/** What the About page shows about updates. */
@Serializable
data class UpdateStatus(
    /** The version this build is. */
    val current: String,
    /** The newest release seen, null before the first check. */
    val latest: UpdateInfo? = null,
    /** [latest] is newer than [current]. */
    val available: Boolean = false,
    /** Epoch ms of the last check, 0 = never. */
    val checkedAt: Long = 0,
    /** The last check failed. */
    val failed: Boolean = false,
)
