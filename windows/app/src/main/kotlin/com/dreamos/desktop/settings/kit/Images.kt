package com.dreamos.desktop.settings.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * The pages' art goes through the shell's one bounded loader ([com.dreamos.desktop.ui.kit.Images]: 1 GB of decoded
 * images in memory, 2 GB on disk, 16 downloads at once), so a poster seen on Home is already in memory on the title
 * page. This only counts the pages' own downloads, for the offscreen previews.
 */
object PageImages {
    /** Downloads under way (the offscreen previews wait for this to reach zero). */
    val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    fun cached(url: String): ImageBitmap? = com.dreamos.desktop.ui.kit.Images.cached(url)

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        inFlight.incrementAndGet()
        try { return com.dreamos.desktop.ui.kit.Images.load(url) } finally { inFlight.decrementAndGet() }
    }
}

/**
 * A remote picture. Shows [placeholder] (a quiet glass block) until it arrives, then the image; [fallbacks] are
 * tried in order when [url] fails.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbacks: List<String> = emptyList(),
    placeholder: Color = Color.White.copy(alpha = 0.06f),
    alignment: androidx.compose.ui.Alignment = androidx.compose.ui.Alignment.Center,
) {
    val first = url?.let { PageImages.cached(it) }
    var img by remember(url) { mutableStateOf(first) }
    LaunchedEffect(url) {
        if (img != null) return@LaunchedEffect
        for (u in listOfNotNull(url) + fallbacks) {
            val got = PageImages.load(u)
            if (got != null) { img = got; break }
        }
    }
    val bmp = img
    if (bmp != null) Image(bmp, null, modifier, contentScale = contentScale, alignment = alignment)
    else Box(modifier.background(placeholder))
}
