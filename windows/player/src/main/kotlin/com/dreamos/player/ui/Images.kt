package com.dreamos.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dreamos.player.engine.PlayerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Art for the player (logos, backdrops, stills, faces). A small LRU in memory: 48 images, about 60 MB worst case
 * (a w1280 backdrop decodes to ~3.7 MB). Downloads give up after 6 s. The app's image loader can replace this later.
 */
object PlayerImages {
    private const val MAX = 48
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX
    }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build()

    @Synchronized fun cached(url: String): ImageBitmap? = cache[url]

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(6)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                if (res.statusCode() != 200) return@runCatching null
                org.jetbrains.skia.Image.makeFromEncoded(res.body()).toComposeImageBitmap()
            }.onFailure { PlayerLog.d("Images: ${PlayerLog.host(url)} failed (${it.message})") }.getOrNull()
        }?.also { synchronized(this) { cache[url] = it } }
    }
}

/** An image from [url] that fades in over 500 ms once it's here; nothing (not a grey box) until then. */
@Composable
fun NetImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    fadeMs: Int = 500,
    description: String? = null,
    onState: (Boolean) -> Unit = {},
) {
    var bmp by remember(url) { mutableStateOf(url?.let { PlayerImages.cached(it) }) }
    LaunchedEffect(url) {
        if (url != null && bmp == null) bmp = PlayerImages.load(url)
        onState(bmp != null)
    }
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(fadeMs))
    bmp?.let { Image(it, description, modifier.alpha(a), alignment = alignment, contentScale = contentScale) }
}
