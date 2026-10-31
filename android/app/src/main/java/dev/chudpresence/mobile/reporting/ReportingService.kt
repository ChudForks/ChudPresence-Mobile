package dev.chudpresence.mobile.reporting

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.chudpresence.mobile.ChudPresenceApplication
import dev.chudpresence.mobile.MainActivity
import dev.chudpresence.mobile.R
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.HULU_PACKAGE
import dev.chudpresence.mobile.services.ServiceDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull

data class ReportingStatus(
    val running: Boolean = false,
    val source: String? = null,
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val enrichedYouTubeMusic: Boolean = false,
    val enrichedCrunchyroll: Boolean = false,
    val enrichedTwitch: Boolean = false,
    val enrichedHulu: Boolean = false,
    val publishingState: String? = null,
    val error: String? = null,
    val media: MediaSnapshot? = null,
    val mediaDefinition: ServiceDefinition? = null,
    val appSessionStartedEpochMs: Long? = null
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class ReportingService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as ChudPresenceApplication
    private var selectedId: String? = null
    private var notificationSource: String? = null
    private val crunchyrollRetryAfter = mutableMapOf<String, Long>()
    private val twitchRetryAfter = mutableMapOf<String, Long>()
    private var started = false
    @Volatile private var stopping = false
    private val localPresence = LocalDiscordPresence()
    private val syncRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:media-reporting")
            .apply { setReferenceCounted(false) }
    }
    private val interactiveStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (app.sessions.refresh()) syncRequests.tryEmit(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, interactiveStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (stopping) return START_NOT_STICKY
            stopping = true
            app.linkSync.setReportingActive(false)
            // Prevent an in-flight retry or queued media update from restoring presence after
            // the clear. A separate scope must perform shutdown because this cancels scope.
            serviceJob.cancel()
            shutdownScope.launch {
                runCatching { localPresence.forceClear() }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (started) return START_STICKY
        started = true
        startForeground(NOTIFICATION_ID, notification(null))
        app.linkSync.setReportingActive(true)
        app.serviceRegistry.beginReportingSession()
        app.sessions.refresh()
        // Discovery runs independently: a slow or missing definition must not delay basic presence.
        scope.launch {
            app.sessions.sessions.collect { snapshots ->
                snapshots.filter { it.playbackState == "playing" || it.playbackState == "paused" }.forEach {
                    app.serviceRegistry.discover(it.packageName, it.appLabel)
                }
            }
        }
        scope.launch {
            combine(app.sessions.sessions, app.settingsStore.displaySettings,
                app.settingsStore.crunchyrollSession, app.settingsStore.twitchSession,
                app.serviceRegistry.services) { _, _, _, _, _ -> Unit }
                .debounce(500)
                .collect { syncRequests.emit(Unit) }
        }
        scope.launch {
            syncRequests.collectLatest { publishCurrentState() }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                if (app.sessions.refresh()) syncRequests.emit(Unit)
            }
        }
        syncRequests.tryEmit(Unit)
        return START_STICKY
    }

    private suspend fun publishCurrentState() {
        if (stopping) return
        val displaySettings = app.settingsStore.displaySettings.firstOrNull() ?: PresenceDisplaySettings()
        val raw = selectPresenceSession(app.sessions.sessions.value.filter {
            app.serviceRegistry.isEnabled(it.packageName)
        }, selectedId, displaySettings) { app.serviceRegistry.resolve(it.packageName, it.appLabel) }
        val definition = raw?.let { effectiveServiceDefinition(it, app.serviceRegistry.resolve(it.packageName, it.appLabel)) }
        updateNotification(definition?.name)
        val selected = raw?.let { enrichTwitch(enrichCrunchyroll(prepareServiceSnapshot(it, definition!!))) }
        selectedId = selected?.sessionId
        updateWakeLock(selected?.playbackState == "playing")
        if (selected == null) clear(displaySettings)
        else sendLocalWithRetry(selected, displaySettings, definition!!)
    }

    private suspend fun enrichCrunchyroll(snapshot: MediaSnapshot): MediaSnapshot {
        if (snapshot.metadataEnriched) return snapshot
        if (classifyMediaSource(snapshot.packageName, snapshot.appLabel) != "crunchyroll") return snapshot
        val mediaId = snapshot.mediaId ?: return snapshot
        val session = app.settingsStore.crunchyrollSession.firstOrNull() ?: return snapshot
        if ((crunchyrollRetryAfter[mediaId] ?: 0) > System.currentTimeMillis()) return snapshot
        val resolved = try {
            app.crunchyrollApi.resolveEpisode(session, mediaId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            crunchyrollRetryAfter[mediaId] = System.currentTimeMillis() + 5 * 60_000L
            return snapshot
        } finally {
            app.crunchyrollApi.refreshedSession(session)?.let { refreshed ->
                withContext(NonCancellable) {
                    try { app.settingsStore.saveRefreshedCrunchyrollSession(session, refreshed) }
                    catch (_: java.io.IOException) { /* Keep basic presence; retry the retained rotation on the next lookup. */ }
                }
            }
        }
        if (resolved == null) return snapshot
        crunchyrollRetryAfter.remove(mediaId)
        return enrichCrunchyrollSnapshot(snapshot, resolved)
    }

    private suspend fun enrichTwitch(snapshot: MediaSnapshot): MediaSnapshot {
        if (snapshot.metadataEnriched) return snapshot
        if (classifyMediaSource(snapshot.packageName, snapshot.appLabel) != "twitch") return snapshot
        if (isTwitchVod(snapshot)) return enrichTwitchVideo(snapshot)
        val login = snapshot.title.trim().lowercase(java.util.Locale.ROOT)
        val session = app.settingsStore.twitchSession.firstOrNull() ?: return snapshot
        if ((twitchRetryAfter[login] ?: 0) > System.currentTimeMillis()) return snapshot
        val resolution = try {
            app.twitchApi.resolveStream(session, login)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            twitchRetryAfter[login] = System.currentTimeMillis() + 60_000L
            return snapshot
        } finally {
            app.twitchApi.refreshedSession(session)?.let { refreshed ->
                withContext(NonCancellable) {
                    try { app.settingsStore.saveRefreshedTwitchSession(session, refreshed) }
                    catch (_: java.io.IOException) { /* Keep basic presence; retry the retained rotation on the next lookup. */ }
                }
            }
        }
        val stream = resolution.stream ?: return snapshot
        twitchRetryAfter.remove(login)
        return enrichTwitchSnapshot(snapshot, stream)
    }

    private suspend fun enrichTwitchVideo(snapshot: MediaSnapshot): MediaSnapshot {
        val fallback = twitchVodFallback(snapshot)
        val login = snapshot.title.trim().lowercase(java.util.Locale.ROOT)
        val videoId = twitchVodId(snapshot)
        val retryKey = "vod:$login:${snapshot.durationMs}:$videoId"
        val session = app.settingsStore.twitchSession.firstOrNull() ?: return fallback
        if ((twitchRetryAfter[retryKey] ?: 0) > System.currentTimeMillis()) return fallback
        val resolution = try {
            app.twitchApi.resolveVideo(session, login, snapshot.durationMs, videoId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            twitchRetryAfter[retryKey] = System.currentTimeMillis() + 60_000L
            return fallback
        } finally {
            app.twitchApi.refreshedSession(session)?.let { refreshed ->
                withContext(NonCancellable) {
                    try { app.settingsStore.saveRefreshedTwitchSession(session, refreshed) }
                    catch (_: java.io.IOException) { /* Keep basic presence; retry the retained rotation on the next lookup. */ }
                }
            }
        }
        val video = resolution.video ?: return fallback
        twitchRetryAfter.remove(retryKey)
        return enrichTwitchVideoSnapshot(snapshot, video)
    }

    // Reporting is user-controlled; the lock is held only during playback and released below and in onDestroy.
    @SuppressLint("Wakelock", "WakelockTimeout")
    private fun updateWakeLock(playing: Boolean) {
        if (playing && !wakeLock.isHeld) wakeLock.acquire()
        else if (!playing && wakeLock.isHeld) wakeLock.release()
    }

    private suspend fun sendLocalWithRetry(snapshot: MediaSnapshot, displaySettings: PresenceDisplaySettings,
        definition: ServiceDefinition) {
        var retryMs = 1_000L
        while (true) {
            try {
                val source = definition.id
                localPresence.publish(snapshot, displaySettings, definition) { artworkUrl ->
                    status.value = ReportingStatus(
                        running = true,
                        source = definition.name,
                        track = snapshot.title,
                        artist = snapshot.artist,
                        album = snapshot.album,
                        media = snapshot.copy(artworkUrl = artworkUrl),
                        mediaDefinition = definition,
                        appSessionStartedEpochMs = localPresence.appSessionStartedEpochMs,
                        enrichedYouTubeMusic = source == "youtube_music" && !snapshot.album.isNullOrBlank(),
                        enrichedCrunchyroll = snapshot.crunchyrollEnriched,
                        enrichedTwitch = snapshot.twitchEnriched,
                        enrichedHulu = source == "hulu" && snapshot.metadataEnriched,
                        publishingState = "Local • published through Discord"
                    )
                }
                return
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                status.value = ReportingStatus(
                    running = true,
                    source = snapshot.appLabel,
                    track = snapshot.title,
                    artist = snapshot.artist,
                    album = snapshot.album,
                    media = snapshot,
                    mediaDefinition = definition,
                    appSessionStartedEpochMs = localPresence.appSessionStartedEpochMs,
                    enrichedYouTubeMusic = classifyMediaSource(snapshot.packageName, snapshot.appLabel) == "youtube_music" &&
                        !snapshot.album.isNullOrBlank(),
                    enrichedCrunchyroll = snapshot.crunchyrollEnriched,
                    enrichedTwitch = snapshot.twitchEnriched,
                    enrichedHulu = snapshot.packageName == HULU_PACKAGE && snapshot.metadataEnriched,
                    publishingState = "Local unavailable — retrying",
                    error = error.message ?: "Local Discord RPC failed"
                )
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(30_000)
            }
        }
    }

    private suspend fun clear(settings: PresenceDisplaySettings) {
        val activePackages = app.sessions.sessions.value.filter {
            app.serviceRegistry.isEnabled(it.packageName) &&
                settings.optionsFor(app.serviceRegistry.resolve(it.packageName, it.appLabel)).enabled
        }.map { it.packageName }.toSet()
        localPresence.clear(activePackages)
        status.value = ReportingStatus(running = true,
            publishingState = "Local • waiting for media")
    }

    override fun onDestroy() {
        app.linkSync.setReportingActive(false)
        unregisterReceiver(interactiveStateReceiver)
        if (wakeLock.isHeld) wakeLock.release()
        scope.cancel()
        localPresence.close()
        shutdownScope.cancel()
        status.value = ReportingStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(source: String?) {
        // Media access also observes our notifications; avoid reposting on every playback update.
        if (notificationSource == source) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(source))
        notificationSource = source
    }

    private fun notification(source: String?): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Presence reporting", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0,
            Intent.makeMainActivity(ComponentName(this, MainActivity::class.java)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_presence)
            .setContentTitle(source ?: "Waiting for media").setContentIntent(openApp)
            .setOnlyAlertOnce(true).setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL = "reporting"
        private const val NOTIFICATION_ID = 7
        val status = MutableStateFlow(ReportingStatus())
        fun start(context: Context) = context.startForegroundService(Intent(context, ReportingService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ReportingService::class.java).setAction(ACTION_STOP))
        private const val ACTION_STOP = "dev.chudpresence.mobile.STOP_REPORTING"
    }
}

internal fun classifyMediaSource(packageName: String, appLabel: String): String = when {
    packageName.endsWith(".android.apps.youtube.music") ||
        appLabel.equals("YouTube Music", ignoreCase = true) ||
        appLabel.equals("YT Music ReVanced", ignoreCase = true) -> "youtube_music"
    packageName in setOf("com.google.android.youtube", "app.revanced.android.youtube") ||
        appLabel.equals("YouTube", ignoreCase = true) || appLabel.equals("YouTube ReVanced", ignoreCase = true) -> "youtube"
    packageName == CRUNCHYROLL_PACKAGE || appLabel.equals("Crunchyroll", ignoreCase = true) -> "crunchyroll"
    packageName == TWITCH_PACKAGE || appLabel.equals("Twitch", ignoreCase = true) -> "twitch"
    packageName == HULU_PACKAGE -> "hulu"
    else -> "unknown"
}

internal fun crunchyrollWatchUrl(packageName: String, mediaId: String?): String? {
    if (packageName != CRUNCHYROLL_PACKAGE) return null
    val id = crunchyrollContentId(mediaId) ?: return null
    return "https://www.crunchyroll.com/watch/$id"
}

private const val CRUNCHYROLL_PACKAGE = "com.crunchyroll.crunchyroid"
private const val TWITCH_PACKAGE = "tv.twitch.android.app"
