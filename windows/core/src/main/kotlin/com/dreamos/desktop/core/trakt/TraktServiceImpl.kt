package com.dreamos.desktop.core.trakt

import com.dreamos.desktop.core.model.TraktDeviceCode
import com.dreamos.desktop.core.model.TraktStatus
import com.dreamos.desktop.core.service.TraktService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * [TraktService] for the screens, over [Trakt]. Register at launch after [Trakt.install]:
 * `Trakt.install(); Services.trakt = TraktServiceImpl()`.
 */
class TraktServiceImpl : TraktService {
    init { Trakt.changed() }

    override val status: StateFlow<TraktStatus> get() = Trakt.status

    override fun setClientId(id: String) = Trakt.setClientId(id)

    override suspend fun startSignIn(): TraktDeviceCode = withContext(Dispatchers.IO) { Trakt.startSignIn() }

    override suspend fun awaitApproval(code: TraktDeviceCode): Boolean = Trakt.awaitApproval(code)

    override suspend fun signOut() = withContext(Dispatchers.IO) { Trakt.signOut() }

    override suspend fun syncNow() = withContext(Dispatchers.IO) { Trakt.syncBlocking() }
}
