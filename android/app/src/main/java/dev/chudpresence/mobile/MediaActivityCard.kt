package dev.chudpresence.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.data.ServiceActivityOptions
import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.publicArtworkUrl
import dev.chudpresence.mobile.reporting.ReportingStatus
import dev.chudpresence.mobile.reporting.compactPresence
import dev.chudpresence.mobile.reporting.defaultServiceDefinition
import dev.chudpresence.mobile.reporting.presenceActivityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
internal fun MediaActivityCard(status: ReportingStatus, settings: PresenceDisplaySettings = PresenceDisplaySettings()) {
    val media = status.media
    val definition = status.mediaDefinition ?: media?.let(::defaultServiceDefinition)
    val options = definition?.let(settings::optionsFor) ?: ServiceActivityOptions()
    val presentation = if (media != null && definition != null) compactPresence(media, settings, definition).presentation else null
    val hasMedia = (media != null || status.track != null) && options.shows(media?.playbackState ?: "playing")
    val source = definition?.name ?: status.source ?: "Media"
    val verb = if (media != null && definition != null) when (presenceActivityType(media, definition)) {
        0 -> "Listening to"
        1 -> "Watching"
        else -> "Playing"
    } else "Playing"
    val paused = media?.playbackState == "paused"
    val state = (presentation?.state ?: status.artist)?.takeIf { it.isNotBlank() }
    val stateLine = if (paused) "${state ?: source} • Paused" else state
    val uriHandler = LocalUriHandler.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CURRENT MEDIA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth().testTag("media-activity-card"), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (hasMedia) {
                    Text("$verb $source".uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActivityArtwork(media, definition?.fallbackArtworkUrl, options.showArtwork)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(media?.title ?: status.track.orEmpty(), style = MaterialTheme.typography.titleMedium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            stateLine?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            (media?.album ?: status.album)?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (options.showPlaybackProgress && media?.durationMs != null && media.durationMs > 0 && media.positionMs != null) ActivityTimeline(media)
                    if (!options.showPlaybackProgress) status.appSessionStartedEpochMs?.let { ActivitySessionElapsed(it) }
                    listOf(presentation?.button1Label to presentation?.button1Url,
                        presentation?.button2Label to presentation?.button2Url).forEach { (label, url) ->
                        if (label != null && url != null) {
                            OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Text(label) }
                        }
                    }
                } else {
                    Text(if (status.running) "Waiting for media" else "No active media", style = MaterialTheme.typography.titleMedium)
                    Text(if (status.running) "Play something to see your Discord activity here." else "Start reporting to share your media on Discord.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(status.error ?: status.publishingState ?: if (status.running) "Connecting…" else "Idle",
            modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActivityArtwork(media: MediaSnapshot?, fallbackUrl: String?, showArtwork: Boolean) {
    val context = LocalContext.current
    val local = media?.artwork?.takeIf { showArtwork && !it.isRecycled }
    val publishedUrl = publicArtworkUrl(media?.artworkUrl).takeIf { showArtwork }
    val url = publishedUrl ?: if (local == null) publicArtworkUrl(fallbackUrl) else null
    // A new item gets its own state immediately, including when neither item has a URL.
    // Prefer the same remote image sent to Discord over a player's possibly stale bitmap.
    val item = listOf(media?.packageName, media?.sessionId, media?.mediaId, media?.title, media?.artist, media?.album)
    var remoteArtwork by remember(item, url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item, url) {
        remoteArtwork = url?.let { ActivityArtworkLoader.load(it) }
    }
    val appIcon = remember(context, media?.packageName) {
        media?.packageName?.let { name -> runCatching {
            context.packageManager.getApplicationIcon(name).toBitmap(128, 128)
        }.getOrNull() }
    }
    val image = remoteArtwork ?: local ?: appIcon
    Surface(Modifier.size(80.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop)
        } else {
            Image(painterResource(R.drawable.presence_brand), contentDescription = null, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun ActivitySessionElapsed(startEpochMs: Long) {
    var now by remember(startEpochMs) { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(startEpochMs, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    Text(activityTime(now - startEpochMs) + " elapsed", modifier = Modifier.testTag("media-app-elapsed"),
        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFF43B581))
}

@Composable
private fun ActivityTimeline(media: MediaSnapshot) {
    var now by remember(media) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(media, lifecycleOwner) {
        if (media.playbackState == "playing") lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    val duration = media.durationMs ?: return
    val position = media.positionAt(now) ?: return
    val progress = (position.toFloat() / duration).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val progressColor = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(4.dp).progressSemantics(progress)) {
            val y = size.height / 2
            drawLine(trackColor, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
            if (progress > 0) drawLine(progressColor, Offset(0f, y), Offset(size.width * progress, y),
                strokeWidth = size.height, cap = StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(activityTime(position), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("−" + activityTime(duration - position), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun activityTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

/** Bounded, cached artwork reads run only while a card needs a remote image. */
private object ActivityArtworkLoader {
    private const val MAX_BYTES = 4L * 1024 * 1024
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(url: String): Bitmap? = try {
        withContext(Dispatchers.IO) {
            cache.get(url)?.let { return@withContext it }
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body ?: return@use null
                if (!response.isSuccessful || body.contentLength() > MAX_BYTES) return@use null
                val source = body.source()
                if (source.request(MAX_BYTES + 1)) return@use null
                val bytes = source.readByteArray()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                val options = BitmapFactory.Options()
                while (bounds.outWidth / options.inSampleSize.coerceAtLeast(1) > 512 ||
                    bounds.outHeight / options.inSampleSize.coerceAtLeast(1) > 512) {
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { cache.put(url, it) }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
