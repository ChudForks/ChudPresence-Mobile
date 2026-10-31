package dev.chudpresence.mobile.services

import dev.chudpresence.mobile.data.PresenceContent

/** A bounded presentation definition, never executable application integration code. */
data class ServiceDefinition(
    val id: String,
    val name: String,
    val packages: Set<String>,
    val mediaKind: String = "unknown",
    val titleField: String = "title",
    val artistField: String = "artist",
    val titleLabel: String = "Title",
    val artistLabel: String = "Artist / creator",
    val defaultContent: PresenceContent = PresenceContent.ARTIST,
    val supportsEpisodes: Boolean = false,
    val fallbackArtworkUrl: String? = null,
    val allowedHosts: Set<String> = emptySet(),
    val contentButtonLabel: String? = null,
    val artistButtonLabel: String? = null
) {
    // Generic apps without validated link hosts cannot publish action buttons.
    val supportsActionButtons: Boolean get() = allowedHosts.isNotEmpty()

    val options: List<PresenceContent>
        get() = if (supportsEpisodes) PresenceContent.entries else PresenceContent.previewOptions

    fun contentLabel(content: PresenceContent): String = when (content) {
        PresenceContent.TRACK -> titleLabel
        PresenceContent.ARTIST -> artistLabel
        else -> content.label
    }
}

/** Defaults also ship as a human-editable manifest in the app and patch repository. */
object ServiceDefinitions {
    val builtIns: List<ServiceDefinition> = listOf(
        ServiceDefinition("youtube", "YouTube", setOf("com.google.android.youtube", "app.revanced.android.youtube"),
            "video", titleLabel = "Video title", artistLabel = "Creator",
            allowedHosts = setOf("youtube.com", "www.youtube.com", "youtu.be"),
            contentButtonLabel = "Watch on YouTube", artistButtonLabel = "View channel"),
        ServiceDefinition("youtube_music", "YouTube Music", setOf("com.google.android.apps.youtube.music", "app.revanced.android.apps.youtube.music"),
            "music", titleLabel = "Song title", artistLabel = "Artist", allowedHosts = setOf("music.youtube.com"),
            contentButtonLabel = "Play on YouTube Music", artistButtonLabel = "Search artist"),
        ServiceDefinition("twitch", "Twitch", setOf("tv.twitch.android.app"), "video",
            titleLabel = "Stream / VOD title", artistLabel = "Streamer", allowedHosts = setOf("twitch.tv", "www.twitch.tv"),
            contentButtonLabel = "Watch on Twitch"),
        ServiceDefinition("crunchyroll", "Crunchyroll", setOf("com.crunchyroll.crunchyroid"), "video",
            titleLabel = "Episode title", artistLabel = "Series", supportsEpisodes = true,
            allowedHosts = setOf("crunchyroll.com", "www.crunchyroll.com"),
            contentButtonLabel = "Watch on Crunchyroll", artistButtonLabel = "View series"),
        ServiceDefinition("hulu", "Hulu", setOf("com.hulu.plus"), "video", titleLabel = "Movie / episode title",
            artistLabel = "Series", defaultContent = PresenceContent.APP_NAME, supportsEpisodes = true,
            fallbackArtworkUrl = "https://play-lh.googleusercontent.com/" +
                "Nvn3JLQ-9URuX94b1TtJyloIili61q_mQTxoCvMMs7xIhfQKRX1UyS7KyjDtlkmgOZa2uUA74671jVm0O8UWOvg=w512-h512",
            allowedHosts = setOf("hulu.com", "www.hulu.com"), contentButtonLabel = "Watch on Hulu"),
        ServiceDefinition("disney_plus", "Disney+", setOf("com.disney.disneyplus"), "video",
            titleField = "display_subtitle", artistField = "title", titleLabel = "Episode title", artistLabel = "Series",
            allowedHosts = setOf("disneyplus.com", "www.disneyplus.com"), contentButtonLabel = "Watch on Disney+")
    )
}
