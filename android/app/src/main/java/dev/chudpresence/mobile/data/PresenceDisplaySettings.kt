package dev.chudpresence.mobile.data

import dev.chudpresence.mobile.services.ServiceDefinition

enum class PresenceContent(val wireValue: String, val label: String) {
    APP_NAME("app_name", "App name"),
    TRACK("track", "Track"),
    ARTIST("artist", "Artist"),
    SEASON_EPISODE("season_episode", "Season & episode");

    companion object {
        val previewOptions = listOf(APP_NAME, TRACK, ARTIST)
        fun fromWireValue(value: String?): PresenceContent? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PresenceService(val source: String, val label: String, private val titleLabel: String, private val artistLabel: String) {
    YOUTUBE("youtube", "YouTube", "Video title", "Creator"),
    YOUTUBE_MUSIC("youtube_music", "YouTube Music", "Song title", "Artist"),
    TWITCH("twitch", "Twitch", "Stream / VOD title", "Streamer"),
    CRUNCHYROLL("crunchyroll", "Crunchyroll", "Episode title", "Series"),
    HULU("hulu", "Hulu", "Movie / episode title", "Series");

    val options: List<PresenceContent>
        get() = if (this == CRUNCHYROLL || this == HULU) PresenceContent.entries else PresenceContent.previewOptions

    fun contentLabel(content: PresenceContent): String = when (content) {
        PresenceContent.TRACK -> titleLabel
        PresenceContent.ARTIST -> artistLabel
        else -> content.label
    }

    val defaultContent: PresenceContent
        get() = if (this == HULU) PresenceContent.APP_NAME else PresenceContent.ARTIST
}

/** Legacy keys retain their storage format; additional services are keyed by stable registry ID. */
data class PresenceDisplaySettings(
    val services: Map<PresenceService, PresenceContent> = emptyMap(),
    val additionalServices: Map<String, PresenceContent> = emptyMap(),
    val extendedEpisodeServices: Set<String> = emptySet(),
    val activityOptions: Map<String, ServiceActivityOptions> = emptyMap()
) {
    fun optionsFor(service: ServiceDefinition): ServiceActivityOptions = activityOptions[service.id]
        ?: service.packages.sorted().firstNotNullOfOrNull { activityOptions[it] }
        ?: ServiceActivityOptions()

    fun contentFor(service: PresenceService): PresenceContent =
        services[service]?.takeIf { it in service.options } ?: service.defaultContent

    fun contentForSource(source: String): PresenceContent =
        PresenceService.entries.firstOrNull { it.source == source }?.let(::contentFor) ?: PresenceContent.ARTIST

    fun contentFor(service: ServiceDefinition): PresenceContent {
        val legacy = PresenceService.entries.firstOrNull { it.source == service.id }
        val saved = legacy?.let { services[it] } ?: additionalServices[service.id]
            // Keep a choice made before an unfamiliar package acquired its registry definition.
            ?: service.packages.sorted().firstNotNullOfOrNull { additionalServices[it] }
        return saved?.takeIf { it in service.options ||
            // A generic patch can omit episode numbers for its next item (for example a movie).
            // Preserve the choice so compactPresence can apply its explicit title fallback.
            ((legacy == null || service.id in extendedEpisodeServices) && it == PresenceContent.SEASON_EPISODE) } ?: service.defaultContent
    }

    fun hasAdditionalEpisodeChoice(service: ServiceDefinition): Boolean =
        additionalServices[service.id] == PresenceContent.SEASON_EPISODE ||
            service.packages.any { additionalServices[it] == PresenceContent.SEASON_EPISODE } ||
            (service.id in extendedEpisodeServices && services.any { it.key.source == service.id && it.value == PresenceContent.SEASON_EPISODE })
}
