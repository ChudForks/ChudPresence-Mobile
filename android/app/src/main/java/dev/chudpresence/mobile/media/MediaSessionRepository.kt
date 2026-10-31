package dev.chudpresence.mobile.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import dev.chudpresence.mobile.ChudPresenceApplication
import dev.chudpresence.mobile.services.installedAppLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MediaSnapshot(
    val sessionId: String, val packageName: String, val appLabel: String, val title: String,
    val artist: String?, val album: String?, val playbackState: String, val positionMs: Long?,
    val durationMs: Long?, val artwork: Bitmap?, val positionUpdatedElapsedMs: Long? = null,
    val playbackSpeed: Float = 0f, val playingSince: Long = 0, val artworkUrl: String? = null,
    val mediaId: String? = null, val crunchyrollEnriched: Boolean = false,
    val twitchEnriched: Boolean = false, val contentUrl: String? = null,
    val artistUrl: String? = null, val albumUrl: String? = null,
    val contentType: String? = null, val metadataEnriched: Boolean = false,
    val seasonNumber: Int? = null, val episodeNumber: String? = null,
    val rawTitle: String? = null, val displayTitle: String? = null,
    val displaySubtitle: String? = null, val displayDescription: String? = null,
    val mediaKind: String? = null, val metadataVersion: Long = 0,
    val isFallbackTitle: Boolean = false
) {
    fun positionAt(elapsedRealtimeMs: Long): Long? {
        val observed = positionMs?.takeIf { it >= 0 } ?: return null
        val duration = durationMs?.takeIf { it > 0 }
        val base = duration?.let { observed.coerceAtMost(it) } ?: observed
        val updateTime = positionUpdatedElapsedMs ?: return base
        if (playbackState != "playing" || !playbackSpeed.isFinite() || playbackSpeed <= 0f || elapsedRealtimeMs <= updateTime) return base
        // Saturate instead of allowing a malformed position/speed or long uptime to wrap negative.
        val elapsed = elapsedRealtimeMs.toDouble() - updateTime.toDouble()
        val advanced = (base.toDouble() + elapsed * playbackSpeed).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        return duration?.let { advanced.coerceAtMost(it) } ?: advanced
    }
}

class MediaSessionRepository(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private data class Registration(val controller: MediaController, val callback: MediaController.Callback)
    private val registrations = mutableMapOf<String, Registration>()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val _sessions = MutableStateFlow<List<MediaSnapshot>>(emptyList())
    val sessions: StateFlow<List<MediaSnapshot>> = _sessions
    private val lastPlaying = mutableMapOf<String, Long>()
    private val previousStates = mutableMapOf<String, String>()
    private var activeSessionsListenerRegistered = false
    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    @Synchronized fun refresh(): Boolean {
        val component = ComponentName(context, MediaNotificationListener::class.java)
        val controllers = runCatching { manager.getActiveSessions(component) }.getOrElse {
            if (activeSessionsListenerRegistered) runCatching {
                manager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            }
            activeSessionsListenerRegistered = false
            registrations.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }
            registrations.clear()
            previousStates.clear()
            lastPlaying.clear()
            _sessions.value = emptyList()
            return false
        }
        // Some video apps have a session without a playback notification. Discover those
        // sessions as soon as Android announces them, including packages we have never seen.
        if (!activeSessionsListenerRegistered) runCatching {
            manager.addOnActiveSessionsChangedListener(activeSessionsListener, component, callbackHandler)
        }.onSuccess { activeSessionsListenerRegistered = true }
        val activeIds = controllers.map { key(it) }.toSet()
        registrations.filterKeys { it !in activeIds }.toMap().forEach { (id, registration) ->
            runCatching { registration.controller.unregisterCallback(registration.callback) }
            registrations.remove(id)
            previousStates.remove(id)
            lastPlaying.remove(id)
        }
        controllers.forEach { controller ->
            // A media process can die after enumeration but before the callback is registered.
            // Its dead Binder must not crash notification delivery or hide the other sessions.
            runCatching {
                registrations.getOrPut(key(controller)) {
                    object : MediaController.Callback() {
                        override fun onMetadataChanged(metadata: MediaMetadata?) { refresh() }
                        override fun onPlaybackStateChanged(state: PlaybackState?) { refresh() }
                        override fun onSessionDestroyed() { refresh() }
                    }.let { callback ->
                        controller.registerCallback(callback, callbackHandler)
                        Registration(controller, callback)
                    }
                }
            }
        }
        _sessions.value = controllers.mapNotNull { controller -> runCatching { toSnapshot(controller) }.getOrNull() }
        return true
    }

    internal fun toSnapshot(controller: MediaController): MediaSnapshot {
        val metadata = controller.metadata
        val playback = controller.playbackState
        val state = when (playback?.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> "stopped"
        }
        val id = key(controller)
        if (state == "playing" && previousStates[id] != "playing") lastPlaying[id] = System.nanoTime()
        previousStates[id] = state
        val capturedAt = SystemClock.elapsedRealtime()
        val position = playback?.position?.takeIf { it >= 0 }
        val previous = _sessions.value.firstOrNull { it.sessionId == id }
        val label = installedAppLabel(context, controller.packageName) ?: previous?.appLabel ?: controller.packageName
        val rawTitle = metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
        val displayTitle = metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        val version = metadata?.getLong(METADATA_PREFIX + "VERSION") ?: 0
        val supported = supportsMetadataVersion(version)
        val snapshot = MediaSnapshot(id, controller.packageName, label,
            mediaSessionTitle(controller.packageName, rawTitle, displayTitle, label),
            metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)),
            metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)), state,
            position, metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it in 1..MAX_DURATION_MS },
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            // Some players expose a stale lastPositionUpdateTime while returning a current position.
            // Anchor the reported position to when we read it to avoid double-advancing it to the end.
            positionUpdatedElapsedMs = capturedAt,
            playbackSpeed = playback?.playbackSpeed?.takeIf { it.isFinite() && it >= 0f } ?: 0f,
            playingSince = lastPlaying[id] ?: 0,
            artworkUrl = publicArtworkUrl(
                metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI),
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ),
            mediaId = (if (supported) metadataText(metadata?.getString(METADATA_PREFIX + "VIDEO_ID")) else null)
                ?: metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)),
            contentUrl = publicMetadataUrl(
                if (supported) metadata?.getString(METADATA_PREFIX + "CONTENT_URL") else null,
                metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)),
            artistUrl = if (supported) publicMetadataUrl(metadata?.getString(METADATA_PREFIX + "ARTIST_URL")) else null,
            albumUrl = if (supported) publicMetadataUrl(metadata?.getString(METADATA_PREFIX + "ALBUM_URL")) else null,
            contentType = if (supported) supportedContentType(metadata?.getString(METADATA_PREFIX + "CONTENT_TYPE")) else null,
            metadataEnriched = supported,
            rawTitle = rawTitle,
            displayTitle = displayTitle,
            displaySubtitle = metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)),
            displayDescription = metadataText(metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)),
            mediaKind = if (supported) supportedMediaKind(metadata?.getString(METADATA_PREFIX + "MEDIA_KIND")) else null,
            metadataVersion = version,
            isFallbackTitle = rawTitle == null && displayTitle == null)
            .withEpisodeMetadata(metadata)
        // The early-next-track workaround is only for the legacy unpatched Music player.
        // Supported patches own the current item's values, including fields they removed.
        return if (previous != null && shouldRetainPreviousMetadata(previous, snapshot, capturedAt)) previous.copy(
            playbackState = state,
            positionMs = position ?: previous.positionAt(capturedAt),
            positionUpdatedElapsedMs = capturedAt,
            playbackSpeed = snapshot.playbackSpeed,
            playingSince = lastPlaying[id] ?: previous.playingSince
        ) else snapshot
    }

    private fun key(controller: MediaController) = "${controller.packageName}:${controller.sessionToken}"
}

internal const val METADATA_PREFIX = "dev.chudpresence.metadata."
internal const val HULU_PACKAGE = "com.hulu.plus"

internal fun MediaSnapshot.withHuluEpisodeMetadata(metadata: MediaMetadata?): MediaSnapshot {
    return withEpisodeMetadata(metadata)
}

internal fun MediaSnapshot.withEpisodeMetadata(metadata: MediaMetadata?): MediaSnapshot {
    if (!metadataEnriched) return this
    return withEpisodeMetadata(
        metadata?.takeIf { it.containsKey(METADATA_PREFIX + "SEASON_NUMBER") }
            ?.getLong(METADATA_PREFIX + "SEASON_NUMBER"),
        metadata?.getString(METADATA_PREFIX + "EPISODE_NUMBER")
    )
}

/** Missing keys stay absent: MediaMetadata.getLong otherwise turns a missing season into zero. */
internal fun MediaSnapshot.withHuluEpisodeMetadata(season: Long?, episode: String?): MediaSnapshot {
    return withEpisodeMetadata(season, episode)
}

internal fun MediaSnapshot.withEpisodeMetadata(season: Long?, episode: String?): MediaSnapshot {
    if (!metadataEnriched) return this
    return copy(
        seasonNumber = season?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        episodeNumber = episode?.trim()?.takeIf {
            it.length in 1..32 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(it) &&
                it.toBigDecimalOrNull()?.signum() == 1
        }
    )
}

internal fun mediaSessionTitle(packageName: String, title: String?, displayTitle: String? = null,
    appLabel: String? = null): String =
    metadataText(title) ?: metadataText(displayTitle) ?: metadataText(appLabel)
        ?: if (packageName == HULU_PACKAGE) "Hulu" else packageName

internal fun shouldRetainPreviousMetadata(previous: MediaSnapshot, next: MediaSnapshot, capturedAtElapsedMs: Long): Boolean {
    if (!next.packageName.endsWith(".android.apps.youtube.music") ||
        previous.metadataEnriched || next.metadataEnriched || next.isFallbackTitle ||
        previous.mediaId != next.mediaId) return false
    return shouldHoldPreviousTrack(previous, next.title, next.artist, next.playbackState, next.positionMs, capturedAtElapsedMs)
}

internal fun shouldHoldPreviousTrack(
    previous: MediaSnapshot,
    newTitle: String,
    newArtist: String?,
    newPlaybackState: String,
    newPositionMs: Long?,
    capturedAtElapsedMs: Long
): Boolean {
    val metadataChanged = previous.title != newTitle || previous.artist != newArtist
    if (!metadataChanged || previous.playbackState != "playing" || newPlaybackState != "playing") return false
    val position = newPositionMs ?: return true
    if (position <= TRACK_START_WINDOW_MS) return false
    val expectedPreviousPosition = previous.positionAt(capturedAtElapsedMs) ?: return false
    return position >= expectedPreviousPosition - POSITION_RESET_TOLERANCE_MS
}

internal fun publicArtworkUrl(vararg candidates: String?): String? = publicHttpsUrl(300, *candidates)
internal fun publicMetadataUrl(vararg candidates: String?): String? = publicHttpsUrl(256, *candidates)

private const val TRACK_START_WINDOW_MS = 5_000L
private const val POSITION_RESET_TOLERANCE_MS = 5_000L
private const val MAX_DURATION_MS = 31_536_000_000L

object SessionSelector {
    fun select(sessions: List<MediaSnapshot>, currentId: String?): MediaSnapshot? {
        val current = sessions.firstOrNull { it.sessionId == currentId }
        if (current?.playbackState == "playing") return current
        return sessions.filter { it.playbackState == "playing" }.maxByOrNull { it.playingSince }
            ?: current?.takeIf { it.playbackState == "paused" }
            ?: sessions.firstOrNull { it.playbackState == "paused" }
    }
}

class MediaNotificationListener : NotificationListenerService() {
    private val repository get() = (application as ChudPresenceApplication).sessions
    override fun onListenerConnected() { super.onListenerConnected(); repository.refresh() }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification) { repository.refresh() }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification) { repository.refresh() }
}
