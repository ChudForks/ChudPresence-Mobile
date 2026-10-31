package dev.chudpresence.mobile.services

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class GooglePlayAppInfo(val category: String? = null, val iconUrl: String? = null)

/** Gets category and icon from the exact package's public listing in a single request. */
internal class GooglePlayAppLookup {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun fetch(packageName: String): GooglePlayAppInfo? = runInterruptible(Dispatchers.IO) {
        require(ServiceManifestCodec.validPackage(packageName))
        val url = "https://play.google.com/store/apps/details".toHttpUrl().newBuilder()
            .addQueryParameter("id", packageName).addQueryParameter("hl", "en_US").addQueryParameter("gl", "US").build()
        http.newCall(Request.Builder().url(url).header("Accept", "text/html").build()).execute().use { response ->
            if (!response.isSuccessful) return@runInterruptible null
            val body = response.body ?: return@runInterruptible null
            require(body.contentLength() <= MAX_BYTES) { "Play listing is too large" }
            val bytes = body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_BYTES) { "Play listing is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            parse(bytes.decodeToString(throwOnInvalidSequence = true), packageName)
        }
    }

    companion object {
        /** Patched clients are installed under aliases that have no public Play listing. */
        fun listingPackage(packageName: String): String = when (packageName) {
            "app.revanced.android.youtube" -> "com.google.android.youtube"
            "app.revanced.android.apps.youtube.music" -> "com.google.android.apps.youtube.music"
            else -> packageName
        }

        private const val MAX_BYTES = 4 * 1024 * 1024
        private val scripts = Regex("""<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        fun validCategory(value: String): Boolean = Regex("[A-Z][A-Z_]{1,63}").matches(value)

        fun validIconUrl(value: String): Boolean = ServiceManifestCodec.publicHttpsUrl(value) &&
            URI(value).host in setOf("play-lh.googleusercontent.com", "lh3.googleusercontent.com")

        /** Use only the exact listing's structured data, never recommended apps or screenshots. */
        fun parse(html: String, packageName: String): GooglePlayAppInfo? = scripts.findAll(html).firstNotNullOfOrNull { match ->
            runCatching {
                val app = Json.parseToJsonElement(match.groupValues[1]) as? JsonObject ?: return@runCatching null
                fun string(key: String) = (app[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (string("@type") != "SoftwareApplication") return@runCatching null
                val url = URI(string("url") ?: return@runCatching null)
                val ids = url.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "id" }
                    .map { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
                if (url.scheme != "https" || url.host != "play.google.com" || url.userInfo != null || url.port != -1 ||
                    !(url.path == "/store/apps/details" || url.path.startsWith("/store/apps/details/")) ||
                    ids != listOf(packageName)) return@runCatching null
                GooglePlayAppInfo(
                    category = string("applicationCategory")?.takeIf(::validCategory),
                    iconUrl = string("image")?.takeIf(::validIconUrl)
                ).takeIf { it.category != null || it.iconUrl != null }
            }.getOrNull()
        }

        fun mediaKind(category: String?): String = when (category) {
            "MUSIC_AND_AUDIO" -> "music"
            "VIDEO_PLAYERS", "ENTERTAINMENT" -> "video"
            else -> "unknown"
        }
    }
}
