package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.ServiceActivityOptions
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.SessionSelector
import dev.chudpresence.mobile.media.publicArtworkUrl
import dev.chudpresence.mobile.services.ServiceDefinition

/** Filter before selection so a hidden session cannot block another eligible app. */
internal fun selectPresenceSession(
    sessions: List<MediaSnapshot>, currentId: String?, settings: PresenceDisplaySettings,
    definitionFor: (MediaSnapshot) -> ServiceDefinition = ::defaultServiceDefinition
): MediaSnapshot? = SessionSelector.select(sessions.filter {
    settings.optionsFor(definitionFor(it)).shows(it.playbackState)
}, currentId)

internal fun PresencePresentation.withOptions(options: ServiceActivityOptions): PresencePresentation = copy(
    artworkClickUrl = artworkClickUrl.takeIf { options.showArtwork },
    button1Label = button1Label.takeIf { options.showActionButtons },
    button1Url = button1Url.takeIf { options.showActionButtons },
    button2Label = button2Label.takeIf { options.showActionButtons },
    button2Url = button2Url.takeIf { options.showActionButtons }
)

internal fun presenceArtworkUrl(snapshot: MediaSnapshot, definition: ServiceDefinition,
    options: ServiceActivityOptions, resolvedArtworkUrl: String? = null): String? {
    val mediaArtwork = if (options.showArtwork) publicArtworkUrl(snapshot.artworkUrl)
        ?: youtubeVideoId(snapshot)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
        ?: publicArtworkUrl(resolvedArtworkUrl) else null
    return mediaArtwork ?: publicArtworkUrl(definition.fallbackArtworkUrl)
}

internal data class PresenceTimestamps(val start: Long = -1L, val end: Long = -1L)

internal fun presenceTimestamps(snapshot: MediaSnapshot, options: ServiceActivityOptions,
    nowEpochMs: Long, elapsedRealtimeMs: Long, pausedSinceEpochMs: Long, appSessionStartedEpochMs: Long): PresenceTimestamps {
    // Start-only uses Discord's elapsed counter; omitting End removes item progress.
    if (!options.showPlaybackProgress) return PresenceTimestamps(start = appSessionStartedEpochMs)
    val paused = snapshot.playbackState == "paused"
    val position = snapshot.positionAt(elapsedRealtimeMs)
    val start = when {
        paused -> pausedSinceEpochMs
        position != null -> nowEpochMs - position
        else -> -1L
    }
    val end = if (!paused && start >= 0 && snapshot.durationMs != null) start + snapshot.durationMs else -1L
    return PresenceTimestamps(start, end)
}
