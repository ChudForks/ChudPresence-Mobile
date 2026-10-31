package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.media.MediaSnapshot
import dev.chudpresence.mobile.media.publicArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal data class ResolvedYouTubeVideo(
    val videoId: String,
    val artworkUrl: String = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
)

internal class YouTubeVideoResolver {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = object : LinkedHashMap<String, ResolvedYouTubeVideo>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedYouTubeVideo>?) = size > 128
    }
    private data class AttemptState(var attempts: Int = 0, val startedAtNanos: Long = System.nanoTime())
    private val attempts = object : LinkedHashMap<String, AttemptState>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AttemptState>?) = size > 128
    }
    private val lookupMutex = Mutex()

    fun cached(snapshot: MediaSnapshot): ResolvedYouTubeVideo? {
        val key = cacheKey(snapshot) ?: return null
        return synchronized(cache) { cache[key] }
    }

    suspend fun resolve(snapshot: MediaSnapshot): ResolvedYouTubeVideo? = lookupMutex.withLock {
        val channel = snapshot.artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = cacheKey(snapshot) ?: return null
        val music = classifyMediaSource(snapshot.packageName, snapshot.appLabel) == "youtube_music"
        synchronized(cache) { cache[key]?.let { return it } }

        val state = synchronized(attempts) { attempts.getOrPut(key) { AttemptState() } }
        while (state.attempts < MAX_ATTEMPTS) {
            val targetNanos = state.startedAtNanos + state.attempts * RETRY_INTERVAL_NANOS
            val remainingNanos = targetNanos - System.nanoTime()
            if (remainingNanos > 0) delay((remainingNanos + 999_999) / 1_000_000)
            state.attempts++

            val response = try {
                search(listOfNotNull(snapshot.title, channel, snapshot.album?.takeIf { music }).joinToString(" "), music)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val match = if (music) selectYouTubeMusicVideo(response, snapshot.title, channel, snapshot.album, snapshot.durationMs)
                else selectYouTubeVideo(response, snapshot.title, channel, snapshot.durationMs)
            if (match == null) continue
            synchronized(cache) { cache[key] = match }
            synchronized(attempts) { attempts.remove(key) }
            return match
        }
        null
    }

    private fun cacheKey(snapshot: MediaSnapshot): String? {
        val channel = snapshot.artist?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val source = classifyMediaSource(snapshot.packageName, snapshot.appLabel)
        if (source != "youtube" && source != "youtube_music") return null
        val album = if (source == "youtube_music") normalize(snapshot.album.orEmpty()) else ""
        return "$source\n${normalize(snapshot.title)}\n${normalize(channel)}\n$album\n${snapshot.durationMs ?: -1}"
    }

    private suspend fun search(query: String, music: Boolean): JsonElement {
        val version = clientVersion(music)
        val origin = if (music) "https://music.youtube.com" else "https://www.youtube.com"
        val body = buildJsonObject {
            put("query", query)
            if (music) put("params", "EgWKAQIIAWoMEA4QChADEAQQCRAF")
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", if (music) "WEB_REMIX" else "WEB")
                    put("clientVersion", version)
                    put("hl", "en")
                    put("gl", Locale.getDefault().country.ifBlank { "US" })
                })
            })
        }.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("$origin/youtubei/v1/search?prettyPrint=false&key=$INNERTUBE_KEY")
            .header("Content-Type", "application/json")
            .header("Origin", origin)
            .header("User-Agent", "Mozilla/5.0")
            .header("X-YouTube-Client-Name", if (music) "67" else "1")
            .header("X-YouTube-Client-Version", version)
            .post(body)
            .build()
        return json.parseToJsonElement(await(http.newCall(request)))
    }

    private suspend fun await(call: Call): String {
        val (status, body) = call.awaitTextResponse()
        if (status !in 200..299) throw IOException("YouTube lookup failed with HTTP $status")
        return body
    }

    private fun clientVersion(music: Boolean): String = SimpleDateFormat(
        if (music) "'1.'yyyyMMdd'.01.00'" else "'2.'yyyyMMdd'.00.00'", Locale.US).run {
        timeZone = TimeZone.getTimeZone("UTC")
        format(Date())
    }

    companion object {
        // Public Innertube client key used by YouTube's web clients; it does not identify a user.
        private const val INNERTUBE_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_INTERVAL_NANOS = 15_000_000_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun selectYouTubeVideo(
    response: JsonElement,
    expectedTitle: String,
    expectedChannel: String,
    expectedDurationMs: Long?
): ResolvedYouTubeVideo? {
    val title = normalize(expectedTitle)
    val channel = normalize(expectedChannel)
    return renderers(response, "videoRenderer")
        .mapNotNull { renderer ->
            val videoId = renderer.text("videoId")?.takeIf { VIDEO_ID.matches(it) } ?: return@mapNotNull null
            if (normalize(renderer.displayText("title")) != title) return@mapNotNull null
            val actualChannel = renderer.displayText("ownerText").ifBlank { renderer.displayText("longBylineText") }
                .ifBlank { renderer.displayText("shortBylineText") }
            if (normalize(actualChannel) != channel) return@mapNotNull null
            val durationMs = parseDurationMs(renderer.displayText("lengthText"))
            val difference = if (expectedDurationMs != null && durationMs != null) {
                kotlin.math.abs(expectedDurationMs - durationMs)
            } else 0L
            if (expectedDurationMs != null && durationMs != null && difference > 15_000) return@mapNotNull null
            difference to ResolvedYouTubeVideo(videoId)
        }
        .minByOrNull { it.first }
        ?.second
}

/** Song results provide album covers, rather than a music video's unrelated thumbnail. */
internal fun selectYouTubeMusicVideo(
    response: JsonElement,
    expectedTitle: String,
    expectedArtist: String,
    expectedAlbum: String?,
    expectedDurationMs: Long?
): ResolvedYouTubeVideo? {
    val title = normalize(expectedTitle)
    val artist = normalize(expectedArtist)
    val album = expectedAlbum?.takeIf { it.isNotBlank() }?.let(::normalize)
    return renderers(response, "musicResponsiveListItemRenderer").mapNotNull { renderer ->
        val columns = renderer.musicColumns("flexColumns", "musicResponsiveListItemFlexColumnRenderer")
        if (normalize(columns.firstOrNull()?.displayText("text").orEmpty()) != title) return@mapNotNull null
        val runs = columns.drop(1).flatMap { column ->
            ((column["text"] as? JsonObject)?.get("runs") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }
        fun pageType(run: JsonObject): String? {
            val browse = (run["navigationEndpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject
            val configs = browse?.get("browseEndpointContextSupportedConfigs") as? JsonObject
            return (configs?.get("browseEndpointContextMusicConfig") as? JsonObject)?.text("pageType")
        }
        val artists = runs.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }.mapNotNull { it.text("text") }
        if (artists.none { normalize(it) == artist } && normalize(artists.joinToString(" ")) != artist) return@mapNotNull null
        if (album != null && runs.none { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" && normalize(it.text("text").orEmpty()) == album }) {
            return@mapNotNull null
        }
        val fixed = renderer.musicColumns("fixedColumns", "musicResponsiveListItemFixedColumnRenderer")
        val duration = fixed.firstNotNullOfOrNull { parseDurationMs(it.displayText("text")) }
            ?: runs.firstNotNullOfOrNull { parseDurationMs(it.text("text").orEmpty()) }
        if (expectedDurationMs != null && duration == null) return@mapNotNull null
        val difference = if (expectedDurationMs != null && duration != null) kotlin.math.abs(expectedDurationMs - duration) else 0L
        if (difference > 2_000) return@mapNotNull null
        val watch = (renderer["navigationEndpoint"] as? JsonObject)?.get("watchEndpoint") as? JsonObject
        val videoId = ((renderer["playlistItemData"] as? JsonObject)?.text("videoId")
            ?: renderer.text("videoId") ?: watch?.text("videoId"))?.takeIf { VIDEO_ID.matches(it) } ?: return@mapNotNull null
        val thumbnail = ((renderer["thumbnail"] as? JsonObject)?.get("musicThumbnailRenderer") as? JsonObject)
            ?.get("thumbnail") as? JsonObject
        val artwork = (thumbnail?.get("thumbnails") as? JsonArray)?.asReversed()?.firstNotNullOfOrNull {
            publicArtworkUrl((it as? JsonObject)?.text("url"))
        } ?: return@mapNotNull null
        difference to ResolvedYouTubeVideo(videoId, artwork)
    }.minByOrNull { it.first }?.second
}

private fun JsonObject.musicColumns(key: String, renderer: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get(renderer) as? JsonObject }

private fun renderers(element: JsonElement, name: String): Sequence<JsonObject> = sequence {
    when (element) {
        is JsonObject -> {
            element[name]?.let { if (it is JsonObject) yield(it) }
            element.values.forEach { yieldAll(renderers(it, name)) }
        }
        is JsonArray -> element.forEach { yieldAll(renderers(it, name)) }
        else -> Unit
    }
}

private fun JsonObject.text(key: String): String? = this[key]?.let { value ->
    runCatching { value.jsonPrimitive.contentOrNull }.getOrNull()
}

private fun JsonObject.displayText(key: String): String {
    val value = this[key] as? JsonObject ?: return ""
    value.text("simpleText")?.let { return it }
    return (value["runs"] as? JsonArray)?.mapNotNull { run ->
        (run as? JsonObject)?.text("text")
    }?.joinToString("").orEmpty()
}

private fun parseDurationMs(value: String): Long? {
    val parts = value.trim().split(':').map { it.toLongOrNull() ?: return null }
    if (parts.size !in 2..3) return null
    return parts.fold(0L) { total, part -> total * 60 + part } * 1_000
}

private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
    .lowercase(Locale.ROOT)
    .replace(Regex("\\p{M}+"), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), "")

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
