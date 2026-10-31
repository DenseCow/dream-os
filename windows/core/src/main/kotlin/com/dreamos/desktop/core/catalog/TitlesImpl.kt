package com.dreamos.desktop.core.catalog

import com.dreamos.desktop.core.content.Details
import com.dreamos.desktop.core.content.Ratings
import com.dreamos.desktop.core.model.*
import com.dreamos.desktop.core.service.Titles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The title page's data (fire-home Tmdb.details, TitleInfo, Ratings, person pages, X-Ray) as the [Titles] service. */
class TitlesImpl : Titles {
    override suspend fun details(kind: MediaKind, id: Int): TitleDetails = withContext(Dispatchers.IO) { Details.details(kind, id) }
    override fun cachedDetails(kind: MediaKind, id: Int): TitleDetails? = Details.cachedDetails(kind, id)
    override suspend fun episodes(showId: Int, season: Int): List<Episode> = withContext(Dispatchers.IO) { Details.episodes(showId, season) }
    override suspend fun episodesChecked(showId: Int, season: Int): List<Episode> = withContext(Dispatchers.IO) { Details.episodes(showId, season, askEarly = true) }
    override suspend fun scores(details: TitleDetails): Scores = withContext(Dispatchers.IO) {
        try { Ratings.scores(details) } catch (e: Exception) { Ratings.cached(details) }
    }
    override suspend fun person(id: Int, excludingKey: String?): PersonDetails? = withContext(Dispatchers.IO) {
        try { Details.person(id, excludingKey) } catch (e: Exception) { null }
    }
    override suspend fun titleLogo(kind: MediaKind, id: Int): String? = withContext(Dispatchers.IO) {
        try { Details.logo(kind, id) } catch (e: Exception) { null }
    }
    override suspend fun allowed(title: Title): Boolean = withContext(Dispatchers.IO) { Details.allowed(title) }
    override suspend fun showAirState(showId: Int): ShowAirState? = withContext(Dispatchers.IO) {
        try { Details.showAirState(showId) } catch (e: Exception) { null }
    }
    override suspend fun cast(request: PlayRequest): List<CastMember> = withContext(Dispatchers.IO) {
        try { Details.cast(request) } catch (e: Exception) { emptyList() }
    }
}
