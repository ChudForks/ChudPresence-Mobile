package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import java.net.URI
import java.util.Locale

internal fun twitchChannelUrl(login: String?): String? = login?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { Regex("[a-z0-9_]{3,25}").matches(it) }
    ?.let { "https://www.twitch.tv/$it" }

internal fun twitchChannelPageUrl(value: String?): String? = value?.let {
    runCatching {
        val uri = URI(it)
        if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in setOf("twitch.tv", "www.twitch.tv", "m.twitch.tv") ||
            uri.userInfo != null || uri.port != -1) return null
        val login = Regex("^/([A-Za-z0-9_]{3,25})/?$").matchEntire(uri.rawPath)?.groupValues?.get(1)
            ?: return null
        twitchChannelUrl(login)
    }.getOrNull()
}

internal fun twitchVideoPageUrl(value: String?): String? = value?.let {
    runCatching {
        val uri = URI(it)
        if (uri.scheme != "https" || uri.host?.lowercase(Locale.ROOT) !in setOf("twitch.tv", "www.twitch.tv", "m.twitch.tv") ||
            uri.userInfo != null || uri.port != -1) return null
        val id = Regex("^/videos/([0-9]{1,20})/?$").matchEntire(uri.rawPath)?.groupValues?.get(1) ?: return null
        "https://www.twitch.tv/videos/$id"
    }.getOrNull()
}

internal fun isTwitchVod(snapshot: MediaSnapshot): Boolean = snapshot.contentType == "vod" ||
    twitchVideoPageUrl(snapshot.contentUrl) != null || (snapshot.durationMs ?: 0) > 0

internal fun twitchVodId(snapshot: MediaSnapshot): String? =
    twitchVideoPageUrl(snapshot.contentUrl)?.substringAfterLast('/')
        ?: snapshot.mediaId?.removePrefix("v")?.takeIf { isTwitchVod(snapshot) && TWITCH_VIDEO_ID.matches(it) }

internal fun twitchVodFallback(snapshot: MediaSnapshot): MediaSnapshot {
    val loginUrl = twitchChannelUrl(snapshot.title)
    val videoId = twitchVodId(snapshot)
    return snapshot.copy(
        title = if (loginUrl != null) "Twitch VOD" else snapshot.title,
        artist = if (loginUrl != null) snapshot.title else snapshot.artist,
        album = snapshot.album ?: snapshot.artist?.removePrefix("Streaming "),
        contentType = "vod", artistUrl = twitchChannelPageUrl(snapshot.artistUrl) ?: loginUrl,
        contentUrl = videoId?.let { "https://www.twitch.tv/videos/$it" }
    )
}

internal fun enrichTwitchSnapshot(snapshot: MediaSnapshot, stream: ResolvedTwitchStream): MediaSnapshot {
    val channel = twitchChannelUrl(stream.userLogin)
    return snapshot.copy(
        title = stream.title,
        artist = stream.userName,
        album = stream.gameName ?: snapshot.artist?.removePrefix("Streaming "),
        artwork = null,
        artworkUrl = stream.artworkUrl ?: snapshot.artworkUrl,
        mediaId = stream.id,
        twitchEnriched = true,
        contentUrl = channel,
        artistUrl = channel,
        contentType = "live", durationMs = null
    )
}

internal fun enrichTwitchVideoSnapshot(snapshot: MediaSnapshot, video: ResolvedTwitchVideo): MediaSnapshot = snapshot.copy(
    title = video.title, artist = video.userName,
    album = snapshot.album ?: snapshot.artist?.removePrefix("Streaming "),
    artwork = null, artworkUrl = video.artworkUrl ?: snapshot.artworkUrl,
    mediaId = video.id, contentUrl = "https://www.twitch.tv/videos/${video.id}",
    artistUrl = twitchChannelUrl(video.userLogin), contentType = "vod", twitchEnriched = true,
    durationMs = snapshot.durationMs ?: video.durationMs
)
