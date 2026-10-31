package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import java.net.URI

internal fun crunchyrollContentId(value: String?): String? =
    value?.trim()?.takeIf { Regex("[A-Z0-9]{9,32}").matches(it) }

internal fun crunchyrollSeriesUrl(seriesId: String?): String? =
    crunchyrollContentId(seriesId)?.let { "https://www.crunchyroll.com/series/$it" }

/** Only keep public watch/series destinations, stripping optional locale, slug and tracking data. */
internal fun crunchyrollPageUrl(value: String?, page: String): String? = value?.let {
    runCatching {
        val uri = URI(it)
        if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("crunchyroll.com", "www.crunchyroll.com") ||
            uri.userInfo != null || uri.port != -1) return null
        val match = Regex("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?$page/([A-Z0-9]{9,32})(?:/[^/]+)?/?$", RegexOption.IGNORE_CASE)
            .matchEntire(uri.rawPath) ?: return null
        val id = crunchyrollContentId(match.groupValues[1]) ?: return null
        "https://www.crunchyroll.com/$page/$id"
    }.getOrNull()
}

internal fun enrichCrunchyrollSnapshot(snapshot: MediaSnapshot, episode: ResolvedCrunchyrollEpisode): MediaSnapshot =
    snapshot.copy(
        artist = episode.seriesTitle ?: snapshot.artist,
        album = episode.seasonTitle ?: snapshot.album,
        artworkUrl = episode.artworkUrl ?: snapshot.artworkUrl,
        contentUrl = crunchyrollWatchUrl(snapshot.packageName, episode.episodeId) ?: snapshot.contentUrl,
        artistUrl = crunchyrollSeriesUrl(episode.seriesId),
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        crunchyrollEnriched = true
    )
