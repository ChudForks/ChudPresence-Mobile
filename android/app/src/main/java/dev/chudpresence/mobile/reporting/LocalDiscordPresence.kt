package dev.chudpresence.mobile.reporting

import android.app.Activity
import com.discord.socialsdk.DiscordSocialSdkInit
import com.discord.socialsdk.NativeCalls
import dev.chudpresence.mobile.BuildConfig
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.publicArtworkUrl
import dev.chudpresence.mobile.services.ServiceDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalDiscordPresence {
    private val youtubeVideoResolver = YouTubeVideoResolver()
    private var initialized = false
    @Volatile private var published = false
    private var pausedSinceEpochMs: Long? = null
    private val appSessionTimer = AppSessionTimer()
    val appSessionStartedEpochMs: Long? get() = appSessionTimer.startEpochMs

    suspend fun publish(snapshot: MediaSnapshot, settings: PresenceDisplaySettings,
        definition: ServiceDefinition = defaultServiceDefinition(snapshot),
        onPublished: (artworkUrl: String?) -> Unit = {}) = withContext(Dispatchers.IO) {
        val options = settings.optionsFor(definition)
        if (!options.shows(snapshot.playbackState)) {
            clear()
            return@withContext
        }
        val nowEpochMs = System.currentTimeMillis()
        val appSessionStart = appSessionTimer.startFor(snapshot.packageName, nowEpochMs)
        ensureInitialized()
        val source = definition.id
        val serviceName = definition.name
        val paused = snapshot.playbackState == "paused"
        val compact = compactPresence(snapshot, settings, definition)
        val presentation = compact.presentation
        pausedSinceEpochMs = if (paused) pausedSinceEpochMs ?: nowEpochMs else null
        val artist = if (paused) {
            val base = presentation.state?.takeIf { it.isNotBlank() } ?: serviceName
            "${base.take(119)} • Paused"
        } else presentation.state

        val timestamps = presenceTimestamps(snapshot, options, nowEpochMs,
            android.os.SystemClock.elapsedRealtime(), pausedSinceEpochMs ?: nowEpochMs, appSessionStart)
        fun update(artworkUrl: String?): String = nativeUpdate(
            discordText(serviceName), discordText(snapshot.title), artist?.let(::discordText),
            (if (options.showArtwork) snapshot.album else serviceName)?.let(::discordText),
            artworkUrl, presentation.detailsUrl, presentation.stateUrl, presentation.artworkClickUrl,
            presentation.button1Label, presentation.button1Url, presentation.button2Label, presentation.button2Url,
            presenceActivityType(snapshot, definition),
            compact.statusDisplay, timestamps.start, timestamps.end
        )

        val canResolveArtwork = options.showArtwork && (source == "youtube" || source == "youtube_music")
        val cachedArtwork = if (canResolveArtwork) youtubeVideoResolver.cached(snapshot)?.artworkUrl else null
        val initialArtworkUrl = presenceArtworkUrl(snapshot, definition, options, cachedArtwork)
        checkUpdate(update(initialArtworkUrl))
        published = true
        onPublished(initialArtworkUrl)
        if (canResolveArtwork && publicArtworkUrl(snapshot.artworkUrl) == null &&
            youtubeVideoId(snapshot) == null && cachedArtwork == null) {
            val resolved = youtubeVideoResolver.resolve(snapshot) ?: return@withContext
            checkUpdate(update(resolved.artworkUrl))
            onPublished(resolved.artworkUrl)
        }
    }

    private fun checkUpdate(result: String) {
        if (result == "published") return
        // A dead Binder cannot recover by resending to the same SDK client. The next reporting
        // attempt must create a new client, bind the service again, and handshake with Discord.
        nativeShutdown()
        initialized = false
        throw IllegalStateException(result)
    }

    suspend fun clear(activePackages: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (initialized && published) nativeClear()
        published = false
        pausedSinceEpochMs = null
        appSessionTimer.retainWhileActive(activePackages)
    }

    suspend fun forceClear() = withContext(Dispatchers.IO) {
        appSessionTimer.reset()
        // Stop must also be able to remove a presence left by an earlier process instance.
        ensureInitialized()
        nativeClear()
        published = false
        pausedSinceEpochMs = null
    }

    fun close() {
        if (initialized) {
            // Covers service destruction paths that did not pass through the explicit Stop action.
            if (published) nativeClear()
            nativeShutdown()
        }
        initialized = false
        published = false
        pausedSinceEpochMs = null
        appSessionTimer.reset()
    }

    private fun ensureInitialized() {
        check(isDiscordInstalled()) { "Install Discord and sign in on this phone to publish your media." }
        if (initialized) return
        val result = nativeInitialize(BuildConfig.DISCORD_APPLICATION_ID)
        check(result == "ready") { result }
        initialized = true
    }

    private external fun nativeInitialize(applicationId: Long): String
    private external fun nativeUpdate(
        serviceName: String,
        title: String,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        detailsUrl: String?,
        stateUrl: String?,
        artworkClickUrl: String?,
        button1Label: String?,
        button1Url: String?,
        button2Label: String?,
        button2Url: String?,
        activityType: Int,
        statusDisplay: Int,
        timestampStart: Long,
        timestampEnd: Long
    ): String
    private external fun nativeClear()
    private external fun nativeShutdown()

    companion object {
        init { System.loadLibrary("chudpresence_local") }

        fun initialize(activity: Activity) = DiscordSocialSdkInit.setEngineActivity(activity)
        fun isDiscordInstalled(): Boolean = runCatching { NativeCalls.isDiscordAppInstalled() }.getOrDefault(false)
    }
}

internal fun discordText(value: String, maxUtf8Bytes: Int = 128): String {
    val trimmed = value.trim()
    if (trimmed.encodeToByteArray().size <= maxUtf8Bytes) return trimmed
    val result = StringBuilder()
    var offset = 0
    var used = 0
    while (offset < trimmed.length) {
        val codePoint = Character.codePointAt(trimmed, offset)
        val characters = String(Character.toChars(codePoint))
        val bytes = characters.encodeToByteArray().size
        if (used + bytes > maxUtf8Bytes) break
        result.append(characters)
        used += bytes
        offset += Character.charCount(codePoint)
    }
    return result.toString().trimEnd()
}
