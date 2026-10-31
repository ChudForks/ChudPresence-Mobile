package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.publicArtworkUrl
import dev.chudpresence.mobile.services.ServiceDefinition
import dev.chudpresence.mobile.services.ServiceDefinitions
import java.net.URI

/** Pure presentation boundary shared by the live publisher and device/unit checks. */
internal fun defaultServiceDefinition(snapshot: MediaSnapshot): ServiceDefinition =
    ServiceDefinitions.builtIns.firstOrNull { snapshot.packageName in it.packages }
        ?: ServiceDefinitions.builtIns.firstOrNull { it.id == classifyMediaSource(snapshot.packageName, snapshot.appLabel) }
        ?: ServiceDefinition(id = snapshot.packageName, name = snapshot.appLabel,
            packages = setOf(snapshot.packageName), defaultContent = PresenceContent.ARTIST)

internal fun effectiveServiceDefinition(snapshot: MediaSnapshot, definition: ServiceDefinition): ServiceDefinition =
    if (snapshot.seasonNumber != null && snapshot.episodeNumber != null)
        definition.copy(supportsEpisodes = true) else definition

/** A compatible patch already published its chosen canonical values; stock mappings must not override them. */
internal fun prepareServiceSnapshot(snapshot: MediaSnapshot, definition: ServiceDefinition): MediaSnapshot {
    if (snapshot.metadataEnriched) return snapshot
    fun field(name: String): String? = when (name) {
        "title" -> snapshot.rawTitle ?: snapshot.title.takeUnless { snapshot.isFallbackTitle }
        "display_title" -> snapshot.displayTitle
        "display_subtitle" -> snapshot.displaySubtitle
        "display_description" -> snapshot.displayDescription
        "artist" -> snapshot.artist
        "album" -> snapshot.album
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
    val title = field(definition.titleField) ?: snapshot.title
    val state = field(definition.artistField)
        ?: if (definition.artistField == "artist") snapshot.displaySubtitle ?: snapshot.displayDescription else null
    return snapshot.copy(title = title, artist = state?.takeUnless { it == title },
        isFallbackTitle = snapshot.isFallbackTitle && field(definition.titleField) == null)
}

internal fun presenceMediaKind(snapshot: MediaSnapshot, definition: ServiceDefinition): String =
    snapshot.mediaKind?.takeIf { it == "music" || it == "video" }
        ?: when (snapshot.contentType) {
            "song" -> "music"
            "video", "short", "episode", "movie" -> "video"
            else -> definition.mediaKind
        }

/** 0=Listening, 1=Watching, 2=Playing (the neutral fallback for an unidentified media kind). */
internal fun presenceActivityType(snapshot: MediaSnapshot, definition: ServiceDefinition): Int =
    when (presenceMediaKind(snapshot, definition)) { "music" -> 0; "video" -> 1; else -> 2 }

internal fun servicePageUrl(value: String?, definition: ServiceDefinition): String? {
    val public = publicArtworkUrl(value) ?: return null
    val uri = runCatching { URI(public) }.getOrNull() ?: return null
    return public.takeIf { uri.host.lowercase() in definition.allowedHosts }
}

internal fun genericPresencePresentation(snapshot: MediaSnapshot, definition: ServiceDefinition): PresencePresentation {
    val content = servicePageUrl(snapshot.contentUrl, definition)
    val artist = servicePageUrl(snapshot.artistUrl, definition)?.takeIf { !snapshot.artist.isNullOrBlank() }
    val album = servicePageUrl(snapshot.albumUrl, definition)
    return PresencePresentation(state = snapshot.artist, detailsUrl = content, stateUrl = artist,
        artworkClickUrl = album ?: content,
        button1Label = content?.let { definition.contentButtonLabel ?: "Open media" }, button1Url = content,
        button2Label = artist?.let { definition.artistButtonLabel ?: "View creator" }, button2Url = artist)
}
