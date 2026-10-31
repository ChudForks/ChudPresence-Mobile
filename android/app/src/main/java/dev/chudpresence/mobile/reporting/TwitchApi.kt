package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.TwitchSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class TwitchDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtEpochMs: Long,
    val pollingIntervalSeconds: Long
)

internal data class ResolvedTwitchStream(
    val id: String,
    val userLogin: String,
    val userName: String,
    val title: String,
    val gameName: String?,
    val startedAt: String?,
    val artworkUrl: String?
)

internal data class TwitchResolution(val stream: ResolvedTwitchStream?, val session: TwitchSession)

/** Official Twitch OAuth device flow and Helix stream lookup for a public mobile client. */
internal class TwitchApi(private val clientId: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private data class CachedStream(val value: ResolvedTwitchStream?, val expiresAtMs: Long)
    private val cache = mutableMapOf<String, CachedStream>()
    private data class CachedVideo(val value: ResolvedTwitchVideo?, val expiresAtMs: Long)
    private val videoCache = mutableMapOf<String, CachedVideo>()
    private val refreshedSessions = linkedMapOf<TwitchSession, TwitchSession>()

    val configured: Boolean get() = clientId.isNotBlank()

    suspend fun startDeviceAuthorization(): TwitchDeviceAuthorization {
        requireConfigured()
        val response = postForm(
            DEVICE_URL,
            FormBody.Builder().add("client_id", clientId).add("scopes", "").build()
        )
        if (response.first !in 200..299) throw twitchFailure("Twitch connection", response)
        return parseTwitchDeviceAuthorization(json.parseToJsonElement(response.second), System.currentTimeMillis())
            ?: throw IOException("Twitch returned an invalid device authorization")
    }

    suspend fun awaitDeviceAuthorization(authorization: TwitchDeviceAuthorization): TwitchSession {
        requireConfigured()
        var interval = authorization.pollingIntervalSeconds.coerceAtLeast(1)
        while (System.currentTimeMillis() < authorization.expiresAtEpochMs) {
            delay(interval * 1_000)
            val response = postForm(
                TOKEN_URL,
                FormBody.Builder()
                    .add("client_id", clientId)
                    .add("scopes", "")
                    .add("device_code", authorization.deviceCode)
                    .add("grant_type", DEVICE_GRANT)
                    .build()
            )
            if (response.first in 200..299) {
                return parseTwitchSession(json.parseToJsonElement(response.second), System.currentTimeMillis())
                    ?: throw IOException("Twitch returned an invalid token response")
            }
            val message = twitchMessage(response.second)
            when {
                message.equals("authorization_pending", ignoreCase = true) -> Unit
                message.equals("slow_down", ignoreCase = true) -> interval += 5
                else -> throw twitchFailure("Twitch connection", response)
            }
        }
        throw IOException("Twitch connection code expired. Try connecting again.")
    }

    suspend fun resolveStream(session: TwitchSession, channelLogin: String): TwitchResolution = mutex.withLock {
        requireConfigured()
        val login = channelLogin.trim().lowercase(Locale.ROOT)
        require(TWITCH_LOGIN.matches(login)) { "Twitch did not expose a valid channel login" }
        val active = validSession(session)
        cache[login]?.takeIf { System.currentTimeMillis() < it.expiresAtMs }?.let {
            return TwitchResolution(it.value, active)
        }

        var resolvedSession = active
        var response = getStream(login, resolvedSession.accessToken)
        if (response.first == 401) {
            resolvedSession = refresh(resolvedSession)
            response = getStream(login, resolvedSession.accessToken)
        }
        if (response.first !in 200..299) throw twitchFailure("Twitch stream lookup", response)
        val stream = parseTwitchStream(json.parseToJsonElement(response.second))
        if (cache.size >= 128) cache.clear()
        cache[login] = CachedStream(stream, System.currentTimeMillis() + CACHE_MS)
        TwitchResolution(stream, resolvedSession)
    }

    suspend fun resolveVideo(session: TwitchSession, channelLogin: String, durationMs: Long?, videoId: String?): TwitchVideoResolution = mutex.withLock {
        requireConfigured()
        val login = channelLogin.trim().lowercase(Locale.ROOT)
        require(videoId == null || TWITCH_VIDEO_ID.matches(videoId)) { "Invalid Twitch video ID" }
        require(videoId?.let(TWITCH_VIDEO_ID::matches) == true || (TWITCH_LOGIN.matches(login) && durationMs != null && durationMs > 0)) {
            "Twitch did not expose enough metadata to identify this VOD"
        }
        var active = validSession(session)
        val key = videoId?.let { "id:$it" } ?: "$login:$durationMs"
        videoCache[key]?.takeIf { System.currentTimeMillis() < it.expiresAtMs }?.let {
            return TwitchVideoResolution(it.value, active)
        }
        suspend fun get(url: String): JsonElement {
            var response = getMetadata(url, active.accessToken)
            if (response.first == 401) {
                active = refresh(active)
                response = getMetadata(url, active.accessToken)
            }
            if (response.first !in 200..299) throw twitchFailure("Twitch VOD lookup", response)
            return json.parseToJsonElement(response.second)
        }
        val video = if (videoId != null) {
            parseTwitchVideos(get("https://api.twitch.tv/helix/videos?id=$videoId")).singleOrNull { it.id == videoId }
        } else {
            val usersUrl = "https://api.twitch.tv/helix/users".toHttpUrl().newBuilder().addQueryParameter("login", login).build()
            val user = (((get(usersUrl.toString()) as? JsonObject)?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject)
            val userId = user?.string("id")?.takeIf(TWITCH_VIDEO_ID::matches)
            val videos = mutableListOf<ResolvedTwitchVideo>()
            var cursor: String? = null
            if (userId != null) {
                // Exhaust the listing before accepting a duration match. An incomplete or ambiguous
                // listing must never turn the currently-live stream or latest VOD into a guessed match.
                for (page in 0 until 3) {
                    val url = "https://api.twitch.tv/helix/videos".toHttpUrl().newBuilder()
                        .addQueryParameter("user_id", userId).addQueryParameter("first", "100")
                    cursor?.let { url.addQueryParameter("after", it) }
                    val root = get(url.build().toString()) as? JsonObject ?: break
                    videos += parseTwitchVideos(root)
                    cursor = (root["pagination"] as? JsonObject)?.string("cursor")?.takeIf { it.isNotBlank() }
                    if (cursor == null) break
                }
            }
            if (cursor == null) selectTwitchVideo(videos, login, durationMs!!) else null
        }
        if (videoCache.size >= 128) videoCache.clear()
        videoCache[key] = CachedVideo(video, System.currentTimeMillis() + if (video == null) CACHE_MS else 5 * 60_000L)
        TwitchVideoResolution(video, active)
    }

    suspend fun revoke(session: TwitchSession) {
        if (!configured) return
        val response = postForm(
            REVOKE_URL,
            FormBody.Builder().add("client_id", clientId).add("token", session.accessToken).build()
        )
        if (response.first !in 200..299) throw twitchFailure("Twitch disconnect", response)
    }

    suspend fun clear() = mutex.withLock {
        cache.clear()
        videoCache.clear()
        synchronized(refreshedSessions) { refreshedSessions.clear() }
    }

    fun refreshedSession(original: TwitchSession): TwitchSession? = synchronized(refreshedSessions) {
        refreshedSessions[original]?.takeIf { it != original }
    }

    private suspend fun validSession(session: TwitchSession): TwitchSession {
        val active = refreshedSession(session) ?: session
        if (System.currentTimeMillis() + EXPIRY_MARGIN_MS < active.expiresAtEpochMs) return active
        return refresh(active)
    }

    private suspend fun refresh(session: TwitchSession): TwitchSession {
        val response = postForm(
            TOKEN_URL,
            FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", session.refreshToken)
                .build()
        )
        if (response.first !in 200..299) throw twitchFailure("Twitch token refresh", response)
        val refreshed = parseTwitchSession(json.parseToJsonElement(response.second), System.currentTimeMillis(), session.refreshToken)
            ?: throw IOException("Twitch returned an invalid refresh response")
        // Keep a refresh even if the following Helix request fails or is cancelled. Update
        // older aliases too when a 401 requires a second rotation during the same lookup.
        synchronized(refreshedSessions) {
            refreshedSessions.entries.filter { it.value == session }.forEach { it.setValue(refreshed) }
            if (refreshedSessions.size >= 16) refreshedSessions.remove(refreshedSessions.keys.first())
            refreshedSessions[session] = refreshed
        }
        return refreshed
    }

    private suspend fun getStream(login: String, accessToken: String): Pair<Int, String> {
        val url = "https://api.twitch.tv/helix/streams".toHttpUrl().newBuilder()
            .addQueryParameter("user_login", login).build()
        return getMetadata(url.toString(), accessToken)
    }

    private suspend fun getMetadata(url: String, accessToken: String): Pair<Int, String> =
        http.newCall(Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Client-Id", clientId)
            .build()).awaitTextResponse()

    private suspend fun postForm(url: String, body: FormBody): Pair<Int, String> =
        http.newCall(Request.Builder().url(url).post(body).build()).awaitTextResponse()

    private fun requireConfigured() {
        check(configured) { "Set TWITCH_CLIENT_ID in android/gradle.properties before connecting Twitch." }
    }

    private fun twitchFailure(operation: String, response: Pair<Int, String>): IOException {
        val detail = twitchMessage(response.second)?.takeIf { it.isNotBlank() }
        return IOException("$operation failed with HTTP ${response.first}${detail?.let { ": $it" }.orEmpty()}")
    }

    private fun twitchMessage(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.string("message")
    }.getOrNull()

    companion object {
        private const val DEVICE_URL = "https://id.twitch.tv/oauth2/device"
        private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        private const val REVOKE_URL = "https://id.twitch.tv/oauth2/revoke"
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val CACHE_MS = 30_000L
        private const val EXPIRY_MARGIN_MS = 60_000L
        private val TWITCH_LOGIN = Regex("[a-z0-9_]{3,25}")
    }
}

internal fun parseTwitchDeviceAuthorization(root: JsonElement, nowMs: Long): TwitchDeviceAuthorization? {
    val value = root as? JsonObject ?: return null
    val deviceCode = value.string("device_code") ?: return null
    val userCode = value.string("user_code") ?: return null
    val verificationUri = value.string("verification_uri")?.takeIf { it.startsWith("https://") } ?: return null
    val expiresIn = value.long("expires_in")?.coerceAtLeast(1) ?: return null
    val interval = value.long("interval")?.coerceAtLeast(1) ?: 5
    return TwitchDeviceAuthorization(deviceCode, userCode, verificationUri, nowMs + expiresIn * 1_000, interval)
}

internal fun parseTwitchSession(root: JsonElement, nowMs: Long, fallbackRefreshToken: String? = null): TwitchSession? {
    val value = root as? JsonObject ?: return null
    val access = value.string("access_token") ?: return null
    val refresh = value.string("refresh_token") ?: fallbackRefreshToken ?: return null
    val expiresIn = value.long("expires_in")?.coerceAtLeast(60) ?: return null
    return TwitchSession(access, refresh, nowMs + expiresIn * 1_000)
}

internal fun parseTwitchStream(root: JsonElement): ResolvedTwitchStream? {
    val value = ((root as? JsonObject)?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val id = value.string("id") ?: return null
    val login = value.string("user_login") ?: return null
    val name = value.string("user_name") ?: login
    val title = value.string("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val game = value.string("game_name")?.trim()?.takeIf { it.isNotEmpty() }
    val thumbnail = value.string("thumbnail_url")?.replace("{width}", "640")?.replace("{height}", "360")
        ?.takeIf { it.startsWith("https://") }
    return ResolvedTwitchStream(id, login, name, title, game, value.string("started_at"), thumbnail)
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(key: String): Long? = string(key)?.toLongOrNull()
