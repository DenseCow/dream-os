package com.dreamos.desktop.core.playback

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/*
 * The viewer's debrid accounts as Settings and Setup see them (fire-home DebridLink / DreamAccounts / AccountHealth,
 * iPhone DebridLink.swift / DebridAccountsStatus.swift). Accounts are the viewer's own; Dream OS signs in under its
 * own name ("DreamOS") or Real-Debrid's published client id for open-source apps, never another app's id.
 */

/** A device sign-in waiting for the viewer: show [code], [url] (and a QR code of [qrUrl]); on a PC also "Open in Browser" with [qrUrl]. */
data class SignInCode(
    val service: DebridServiceId,
    val code: String,
    val url: String,
    val qrUrl: String,
    val intervalS: Int,
    /** Epoch ms. */
    val expiresAt: Long,
    internal val state: Map<String, String>,
)

/** One poll of a sign-in. */
sealed class SignInPoll {
    data object Waiting : SignInPoll()
    data class Done(val user: String?) : SignInPoll()
    /** [message]: a plain sentence for the viewer ("The code expired. Try again."). */
    data class Failed(val message: String) : SignInPoll()
}

/** An account's health after a read-only account check (fire-home AccountHealth.Health). */
@Serializable
enum class AccountHealthState(val label: String, val tone: PillTone) {
    OK("Connection OK", PillTone.PLAIN),
    LIMIT("Magnet Limit Reached", PillTone.WARN),
    INVALID("Sign-In Invalid", PillTone.BAD),
    OFFLINE("No Answer Right Now", PillTone.WARN),
    /** AllDebrid's AUTH_BLOCKED: a sign-in from a new network waiting for the owner's approval by email. */
    BLOCKED("Approve This Network", PillTone.BAD),
}

/**
 * The last check of one account. [premium] true/false when the service said, null unknown; [until] plan end
 * (epoch ms, 0 unknown or none); [at] when checked (epoch ms).
 */
@Serializable
data class AccountHealth(
    val service: DebridServiceId,
    val health: AccountHealthState,
    val at: Long,
    val premium: Boolean? = null,
    val until: Long = 0,
    val user: String? = null,
    /** The plan renews by itself (AllDebrid isSubscribed, TorBox is_subscribed): no countdown notices. */
    val subscribed: Boolean = false,
)

/** A row of Settings › Accounts: the summary on the right and its tone ("Premium · 214 Days Left", "Expires in 3 Days"). */
data class AccountSummary(val service: DebridServiceId, val linked: Boolean, val text: String, val tone: PillTone, val user: String?)

interface AccountsApi {
    /** Linked services in the viewer's ask order. Changes on link / unlink / reorder. */
    val linked: StateFlow<List<DebridServiceId>>

    /** Starts a device sign-in; null when the service didn't answer (show "Couldn't reach <Service>. Try again."). */
    suspend fun startSignIn(service: DebridServiceId): SignInCode?

    /** One check whether the viewer approved [code]; call every [SignInCode.intervalS] seconds until not Waiting. */
    suspend fun poll(code: SignInCode): SignInPoll

    /** Premiumize without a registered device sign-in: link with the account's API key. */
    suspend fun linkPremiumizeKey(key: String): SignInPoll

    /** True when [service] can do the code sign-in in this build (Premiumize needs a client id registered to Dream OS). */
    fun deviceSignIn(service: DebridServiceId): Boolean

    fun unlink(service: DebridServiceId)

    /** The viewer's ask order (Settings); services left out follow in the default order. */
    fun setOrder(order: List<DebridServiceId>)

    /** One read-only account call (Test Connection / Check Again). Null when [service] isn't linked. */
    suspend fun checkHealth(service: DebridServiceId): AccountHealth?

    /** The last kept check, no network. */
    fun lastHealth(service: DebridServiceId): AccountHealth?

    /** The row's summary, no network. */
    fun summary(service: DebridServiceId): AccountSummary

    /** The words for AUTH_BLOCKED, the same on setup's check, Accounts and Settings. */
    fun blockedLine(service: DebridServiceId): String =
        "${service.displayName} blocked this new network. Approve it from the email ${service.displayName} sent you " +
            "(or the security page of your ${service.displayName} account), then choose Check Again."
}
