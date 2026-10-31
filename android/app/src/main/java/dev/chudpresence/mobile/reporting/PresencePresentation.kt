package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.services.ServiceDefinition
import java.net.URI
import java.net.URLEncoder

internal data class PresencePresentation(
    val state: String?, val detailsUrl: String? = null, val stateUrl: String? = null,
    val artworkClickUrl: String? = null, val button1Label: String? = null, val button1Url: String? = null,
    val button2Label: String? = null, val button2Url: String? = null
)

internal fun presencePresentation(
    snapshot: MediaSnapshot,
    definition: ServiceDefinition = defaultServiceDefinition(snapshot)
): PresencePresentation {
    val source = definition.id
    if (source == "youtube" || source == "youtube_music") {
        val music = source == "youtube_music"
        val id = youtubeVideoId(snapshot)
        val isShort = !music && snapshot.contentType == "short"
        val content = id?.let {
            if (music) "https://music.youtube.com/watch?v=$it"
            else if (isShort) "https://www.youtube.com/shorts/$it"
            else "https://www.youtube.com/watch?v=$it"
        }
        val artist = youtubePageUrl(snapshot.artistUrl, music)
        val album = if (music) youtubePageUrl(snapshot.albumUrl, true) else null
        val search = if (music) snapshot.artist?.takeIf { it.isNotBlank() }?.let {
            "https://music.youtube.com/search?q=" + URLEncoder.encode(it, "UTF-8")
        }?.takeIf { it.length <= 256 } else artist
        return PresencePresentation(
            state = snapshot.artist,
            detailsUrl = content, stateUrl = artist,
            artworkClickUrl = if (music) album ?: artist else null,
            button1Label = content?.let { if (music) "Play on YouTube Music" else if (isShort) "Watch Short" else "Watch on YouTube" },
            button1Url = content, button2Label = search?.let { if (music) "Search artist" else "View channel" }, button2Url = search
        )
    }
    if (source == "crunchyroll") {
        val content = crunchyrollPageUrl(snapshot.contentUrl, "watch")
            ?: crunchyrollWatchUrl(snapshot.packageName, snapshot.mediaId)
        val series = crunchyrollPageUrl(snapshot.artistUrl, "series")
        return PresencePresentation(
            state = snapshot.artist,
            detailsUrl = content, stateUrl = series?.takeIf { !snapshot.artist.isNullOrBlank() },
            artworkClickUrl = series,
            button1Label = content?.let { "Watch on Crunchyroll" }, button1Url = content,
            button2Label = series?.let { "View series" }, button2Url = series
        )
    }
    if (source == "twitch") {
        val vod = isTwitchVod(snapshot)
        val channel = twitchChannelPageUrl(snapshot.artistUrl) ?: twitchChannelPageUrl(snapshot.contentUrl)
        val content = if (vod) twitchVideoPageUrl(snapshot.contentUrl) else channel
        val watch = content ?: if (vod) channel?.let { "$it/videos" } else null
        val state = snapshot.artist?.let { if (snapshot.contentType == "live") "$it • Live" else it }
        return PresencePresentation(
            state = state,
            detailsUrl = content, stateUrl = channel?.takeIf { !snapshot.artist.isNullOrBlank() },
            artworkClickUrl = content ?: channel,
            button1Label = watch?.let { "Watch on Twitch" }, button1Url = watch
        )
    }
    if (source == "hulu") {
        val content = huluWatchUrl(snapshot.contentUrl)
        return PresencePresentation(
            state = snapshot.artist,
            detailsUrl = content, artworkClickUrl = content,
            button1Label = content?.let { "Watch on Hulu" }, button1Url = content
        )
    }
    return genericPresencePresentation(snapshot, definition)
}

internal fun youtubeVideoId(snapshot: MediaSnapshot): String? =
    if (classifyMediaSource(snapshot.packageName, snapshot.appLabel) in setOf("youtube", "youtube_music"))
        youtubeVideoId(snapshot.mediaId) ?: youtubeVideoId(snapshot.contentUrl)
    else null

internal fun youtubeVideoId(value: String?): String? {
    if (value == null) return null
    if (VIDEO_ID.matches(value)) return value
    return runCatching {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
        when (uri.host?.lowercase()) {
            "youtu.be" -> uri.path.trim('/').takeIf(VIDEO_ID::matches)
            "youtube.com", "www.youtube.com", "music.youtube.com" -> {
                if (uri.path in listOf("/watch", "/watch/")) uri.rawQuery?.split('&')
                    ?.firstOrNull { it.startsWith("v=") }?.substring(2)?.takeIf(VIDEO_ID::matches)
                else Regex("^/(?:shorts|live)/([A-Za-z0-9_-]{11})/?$").matchEntire(uri.path)?.groupValues?.get(1)
            }
            else -> null
        }
    }.getOrNull()
}

private fun youtubePageUrl(value: String?, music: Boolean): String? = value?.takeIf { it.length <= 256 }?.let {
    runCatching {
        val uri = URI(it)
        val hosts = if (music) setOf("music.youtube.com") else setOf("www.youtube.com", "youtube.com")
        it.takeIf { uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null
            && uri.port == -1 && uri.path?.startsWith('/') == true }
    }.getOrNull()
}

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/** A Hulu content/asset ID alone is not a verified public watch destination. */
internal fun huluWatchUrl(value: String?): String? {
    val exposed = value?.trim()?.takeIf { it.length <= 256 }?.let {
        runCatching {
            val uri = URI(it)
            if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("hulu.com", "www.hulu.com") ||
                uri.userInfo != null || uri.port != -1) return@runCatching null
            val path = uri.rawPath?.takeIf { path -> Regex("/watch/[A-Za-z0-9-]{1,128}/?").matches(path) }
                ?: return@runCatching null
            "https://www.hulu.com" + path.trimEnd('/')
        }.getOrNull()
    }
    return exposed
}
