package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.CrunchyrollSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class CrunchyrollAuthorization(
    val refreshToken: String,
    val accessToken: String,
    val expiresInSeconds: Long
)

internal data class ResolvedCrunchyrollEpisode(
    val seriesTitle: String?,
    val seasonTitle: String?,
    val artworkUrl: String?,
    val seriesId: String? = null,
    val episodeId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: String? = null
)

/**
 * Best-effort client for Crunchyroll's undocumented Android API. All callers must preserve the
 * raw Android media-session metadata as a fallback because these endpoints can change at any time.
 */
internal class CrunchyrollApi(private val http: OkHttpClient = OkHttpClient.Builder()
    .cookieJar(MemoryCookieJar()).callTimeout(15, TimeUnit.SECONDS).build()) {
    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(this.cookies) {
                this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
                this.cookies.addAll(cookies)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) {
            cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var accessToken: String? = null
    private var accessTokenExpiresAtMs = 0L
    private var activeRefreshToken: String? = null
    private var activeDeviceId: String? = null
    private val refreshedSessions = linkedMapOf<CrunchyrollSession, CrunchyrollSession>()
    private val cache = object : LinkedHashMap<String, ResolvedCrunchyrollEpisode>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedCrunchyrollEpisode>?) = size > 128
    }

    fun authorizationUrl(codeVerifier: String, anonymousId: String): String =
        HttpUrl.Builder().scheme("https").host("sso.crunchyroll.com").addPathSegment("authorize")
            .addQueryParameter("client_id", ANDROID_PHONE_CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", "offline_access")
            .addQueryParameter("code_challenge", codeVerifier)
            .addQueryParameter("code_challenge_method", "plain")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("anonymous_id", anonymousId)
            .build().toString()

    suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String, deviceId: String): CrunchyrollSession = mutex.withLock {
        warmUp()
        val authorization = authorize(
            FormBody.Builder()
                .add("code", code)
                .add("code_verifier", codeVerifier)
                .add("grant_type", "authorization_code")
                .add("scope", "offline_access")
                .add("device_id", deviceId)
                .add("device_type", "ANDROID")
                .build()
        )
        activeRefreshToken = authorization.refreshToken
        activeDeviceId = deviceId
        accessToken = authorization.accessToken
        accessTokenExpiresAtMs = tokenExpiry(authorization.expiresInSeconds)
        synchronized(refreshedSessions) { refreshedSessions.clear() }
        CrunchyrollSession(authorization.refreshToken, deviceId)
    }

    fun refreshedSession(original: CrunchyrollSession): CrunchyrollSession? = synchronized(refreshedSessions) {
        refreshedSessions[original]?.takeIf { it != original }
    }

    suspend fun resolveEpisode(session: CrunchyrollSession, mediaId: String): ResolvedCrunchyrollEpisode? = mutex.withLock {
        cache[mediaId]?.let { return it }
        val token = validAccessToken(session)
        val locale = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US"
        val url = HttpUrl.Builder().scheme("https").host("www.crunchyroll.com")
            .addPathSegments("content/v2/cms/episodes/$mediaId")
            .addQueryParameter("locale", locale)
            .build()
        var response = getEpisode(url, token)
        if (response.first == 401) {
            accessTokenExpiresAtMs = 0
            response = getEpisode(url, validAccessToken(session))
        }
        if (response.first !in 200..299) throw IOException("Crunchyroll metadata lookup failed with HTTP ${response.first}")
        val resolved = parseCrunchyrollEpisode(json.parseToJsonElement(response.second)) ?: return null
        cache[mediaId] = resolved
        resolved
    }

    suspend fun clear() = mutex.withLock {
        accessToken = null
        activeRefreshToken = null
        activeDeviceId = null
        accessTokenExpiresAtMs = 0
        cache.clear()
        synchronized(refreshedSessions) { refreshedSessions.clear() }
    }

    private suspend fun validAccessToken(session: CrunchyrollSession): String {
        val active = refreshedSession(session) ?: session
        accessToken?.takeIf {
            activeRefreshToken == active.refreshToken && activeDeviceId == active.deviceId &&
                System.currentTimeMillis() < accessTokenExpiresAtMs
        }?.let { return it }
        warmUp()
        val authorization = authorize(
            FormBody.Builder()
                .add("refresh_token", active.refreshToken)
                .add("grant_type", "refresh_token")
                .add("scope", "offline_access")
                .add("device_id", active.deviceId)
                .add("device_type", "ANDROID")
                .build()
        )
        activeRefreshToken = authorization.refreshToken
        activeDeviceId = active.deviceId
        accessToken = authorization.accessToken
        accessTokenExpiresAtMs = tokenExpiry(authorization.expiresInSeconds)
        val refreshed = CrunchyrollSession(authorization.refreshToken, active.deviceId)
        synchronized(refreshedSessions) {
            refreshedSessions.entries.filter { it.value == active }.forEach { it.setValue(refreshed) }
            if (refreshedSessions.size >= 16) refreshedSessions.remove(refreshedSessions.keys.first())
            refreshedSessions[active] = refreshed
        }
        return authorization.accessToken
    }

    private suspend fun warmUp() {
        runCatching {
            http.newCall(Request.Builder().url("https://www.crunchyroll.com/").header("User-Agent", USER_AGENT).build()).awaitTextResponse()
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun authorize(body: FormBody): CrunchyrollAuthorization {
        val request = Request.Builder().url("https://www.crunchyroll.com/auth/v1/token")
            .header("Authorization", "Basic $ANDROID_PHONE_BASIC_AUTH")
            .header("User-Agent", USER_AGENT)
            .post(body).build()
        val response = http.newCall(request).awaitTextResponse()
        if (response.first !in 200..299) throw IOException("Crunchyroll sign-in failed with HTTP ${response.first}")
        val root = json.parseToJsonElement(response.second) as? JsonObject ?: throw IOException("Invalid Crunchyroll sign-in response")
        val access = root.string("access_token") ?: throw IOException("Crunchyroll did not return an access token")
        val refresh = root.string("refresh_token") ?: throw IOException("Crunchyroll did not return a refresh token")
        val expires = root["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300L
        return CrunchyrollAuthorization(refresh, access, expires)
    }

    private suspend fun getEpisode(url: HttpUrl, token: String): Pair<Int, String> = http.newCall(
        Request.Builder().url(url).header("Authorization", "Bearer $token").header("User-Agent", USER_AGENT).build()
    ).awaitTextResponse()

    private fun tokenExpiry(expiresInSeconds: Long) =
        System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60) - 30) * 1_000

    companion object {
        fun newLoginIdentifiers() = UUID.randomUUID().toString() to UUID.randomUUID().toString()

        // Public credentials used by Crunchyroll Android 3.120.1. They may be rotated upstream.
        private const val ANDROID_PHONE_CLIENT_ID = "ehetppeitvwgcbg7mpig"
        private const val ANDROID_PHONE_BASIC_AUTH = "ZWhldHBwZWl0dndnY2JnN21waWc6QklkSXM5Njlib0U1SWhGQ2ZyRU92VUxiVTJqcjg0QUw="
        private const val USER_AGENT = "Crunchyroll/3.120.1 Android/11 okhttp/5.3.2"
        private const val REDIRECT_URI = "sso.crunchyroll://auth"
    }
}

internal fun parseCrunchyrollEpisode(root: JsonElement): ResolvedCrunchyrollEpisode? {
    val item = ((root as? JsonObject)?.get("data") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val episode = (item["episode_metadata"] as? JsonObject) ?: item
    val series = episode.string("series_title")?.trim()?.takeIf { it.isNotEmpty() }
    val season = episode.string("season_title")?.trim()?.takeIf { it.isNotEmpty() }
    val artwork = bestCrunchyrollImage(item["images"] ?: episode["images"])
    val seriesId = crunchyrollContentId(episode.string("series_id") ?: item.string("series_id"))
    val episodeId = crunchyrollContentId(item.string("id"))
    val seasonNumber = episode.string("season_number")?.toIntOrNull()?.takeIf { it >= 0 }
    val episodeNumber = (episode.string("episode")?.trim()?.takeIf { it.isNotEmpty() }
        ?: episode.string("episode_number")?.trim()?.takeIf { it.isNotEmpty() })?.take(24)
    if (series == null && season == null && artwork == null && seriesId == null &&
        (seasonNumber == null || episodeNumber == null)) return null
    return ResolvedCrunchyrollEpisode(series, season, artwork, seriesId, episodeId, seasonNumber, episodeNumber)
}

private fun bestCrunchyrollImage(element: JsonElement?): String? {
    var best: Pair<Long, String>? = null
    fun visit(value: JsonElement?) {
        when (value) {
            is JsonObject -> {
                val source = value.string("source")?.takeIf { it.startsWith("https://") }
                if (source != null) {
                    val width = value["width"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                    val height = value["height"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                    val candidate = width * height to source
                    if (best == null || candidate.first > best!!.first) best = candidate
                }
                value.values.forEach(::visit)
            }
            is JsonArray -> value.forEach(::visit)
            else -> Unit
        }
    }
    visit(element)
    return best?.second
}

private fun JsonObject.string(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
