package dev.chudpresence.mobile.data

enum class ServiceActivityOption(val storageKey: String) {
    ENABLED("enabled"),
    SHOW_WHILE_PAUSED("show_while_paused"),
    ARTWORK("artwork"),
    PLAYBACK_PROGRESS("playback_progress"),
    ACTION_BUTTONS("action_buttons")
}

/** Defaults preserve the activity published before per-app controls were available. */
data class ServiceActivityOptions(
    val enabled: Boolean = true,
    val showWhilePaused: Boolean = true,
    val showArtwork: Boolean = true,
    val showPlaybackProgress: Boolean = true,
    val showActionButtons: Boolean = true
) {
    fun shows(playbackState: String): Boolean = enabled && (showWhilePaused || playbackState != "paused")

    operator fun get(option: ServiceActivityOption): Boolean = when (option) {
        ServiceActivityOption.ENABLED -> enabled
        ServiceActivityOption.SHOW_WHILE_PAUSED -> showWhilePaused
        ServiceActivityOption.ARTWORK -> showArtwork
        ServiceActivityOption.PLAYBACK_PROGRESS -> showPlaybackProgress
        ServiceActivityOption.ACTION_BUTTONS -> showActionButtons
    }

    fun with(option: ServiceActivityOption, value: Boolean): ServiceActivityOptions = when (option) {
        ServiceActivityOption.ENABLED -> copy(enabled = value)
        ServiceActivityOption.SHOW_WHILE_PAUSED -> copy(showWhilePaused = value)
        ServiceActivityOption.ARTWORK -> copy(showArtwork = value)
        ServiceActivityOption.PLAYBACK_PROGRESS -> copy(showPlaybackProgress = value)
        ServiceActivityOption.ACTION_BUTTONS -> copy(showActionButtons = value)
    }
}
