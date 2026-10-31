package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.PresenceContent
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.services.ServiceDefinition

internal data class CompactPresence(val content: PresenceContent, val presentation: PresencePresentation) {
    val statusDisplay: Int
        get() = when (content) {
            PresenceContent.APP_NAME -> 0
            PresenceContent.TRACK -> 1
            PresenceContent.ARTIST -> 2
            PresenceContent.SEASON_EPISODE -> error("Season & episode must resolve to a Discord field")
        }
}

/** Resolve the active service's choice to Discord's name, details, or state field. */
internal fun compactPresence(
    snapshot: MediaSnapshot,
    settings: PresenceDisplaySettings,
    definition: ServiceDefinition = defaultServiceDefinition(snapshot)
): CompactPresence {
    val content = settings.contentFor(effectiveServiceDefinition(snapshot, definition))
    val presentation = presencePresentation(snapshot, definition).withOptions(settings.optionsFor(definition))
    if (content == PresenceContent.SEASON_EPISODE) {
        val season = snapshot.seasonNumber?.takeIf { it >= 0 }
        val episode = snapshot.episodeNumber?.trim()?.takeIf { it.isNotEmpty() }
        return if (season != null && episode != null) {
            CompactPresence(PresenceContent.ARTIST, presentation.copy(
                state = "Season $season · Episode $episode", stateUrl = presentation.detailsUrl))
        } else CompactPresence(PresenceContent.TRACK, presentation)
    }
    // Stock Crunchyroll sessions often omit the series. Keep the compact activity useful.
    val resolved = when {
        snapshot.isFallbackTitle && snapshot.artist.isNullOrBlank() -> PresenceContent.APP_NAME
        content == PresenceContent.ARTIST && snapshot.artist.isNullOrBlank() -> PresenceContent.TRACK
        else -> content
    }
    return CompactPresence(resolved, presentation)
}
