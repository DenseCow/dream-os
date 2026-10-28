package com.firehome.launcher

/**
 * A Browse by Service card: the service's name (as in [Tmdb.SERVICES], which holds its TMDB provider
 * ids), the TMDB networks its originals air on (0 = none listed), and its brand color, used only as
 * a soft tint on the card.
 */
class ServiceSpec(val name: String, val networks: String, val color: Int) {
    companion object {
        /** The Browse by Service cards, in order. Names match [Tmdb.SERVICES]. */
        val SHORTCUTS = listOf(
            ServiceSpec("Netflix", "213", 0xFFE50914.toInt()),
            ServiceSpec("Hulu", "453", 0xFF1CE783.toInt()),
            ServiceSpec("Prime Video", "1024", 0xFF00A8E1.toInt()),
            ServiceSpec("Apple TV", "2552", 0xFF8E8E93.toInt()),
            ServiceSpec("Disney+", "2739", 0xFF113CCF.toInt()),
            ServiceSpec("HBO Max", "3186|49", 0xFF002BE7.toInt()),
            ServiceSpec("Peacock", "3353", 0xFFF6B700.toInt()),
            ServiceSpec("Paramount+", "4330", 0xFF0064FF.toInt()),
            ServiceSpec("AMC+", "174|4661", 0xFF2F80ED.toInt()),
            ServiceSpec("Crunchyroll", "1112", 0xFFF47521.toInt()),
            ServiceSpec("Tubi", "", 0xFF7B3FE4.toInt()),
        )
    }
}
