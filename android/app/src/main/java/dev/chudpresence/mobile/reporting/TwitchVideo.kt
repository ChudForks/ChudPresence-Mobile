package dev.chudpresence.mobile.reporting

import dev.chudpresence.mobile.data.TwitchSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class ResolvedTwitchVideo(
    val id: String, val userLogin: String, val userName: String, val title: String,
    val durationMs: Long, val artworkUrl: String?
)

internal data class TwitchVideoResolution(val video: ResolvedTwitchVideo?, val session: TwitchSession)

internal fun parseTwitchVideos(root: JsonElement): List<ResolvedTwitchVideo> =
    ((root as? JsonObject)?.get("data") as? JsonArray)?.mapNotNull { item ->
        val value = item as? JsonObject ?: return@mapNotNull null
        val id = value.videoString("id")?.takeIf { TWITCH_VIDEO_ID.matches(it) } ?: return@mapNotNull null
        val login = value.videoString("user_login")?.takeIf { twitchChannelUrl(it) != null } ?: return@mapNotNull null
        val title = value.videoString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: "Twitch VOD"
        val duration = parseTwitchVideoDuration(value.videoString("duration")) ?: return@mapNotNull null
        val artwork = value.videoString("thumbnail_url")
            ?.replace("%{width}", "320")?.replace("%{height}", "180")
            ?.replace("{width}", "320")?.replace("{height}", "180")
            ?.takeIf { it.startsWith("https://") }
        ResolvedTwitchVideo(id, login, value.videoString("user_name")?.takeIf { it.isNotBlank() } ?: login,
            title, duration, artwork)
    }.orEmpty()

/** Stock Twitch exposes no VOD ID: accept only a unique creator + exact whole-second duration match. */
internal fun selectTwitchVideo(videos: List<ResolvedTwitchVideo>, login: String, durationMs: Long): ResolvedTwitchVideo? =
    videos.filter { it.userLogin.equals(login, ignoreCase = true) && it.durationMs / 1000 == durationMs / 1000 }.singleOrNull()

internal fun parseTwitchVideoDuration(value: String?): Long? {
    val match = value?.takeIf { it.isNotBlank() }?.let {
        Regex("^(?:(\\d{1,6})h)?(?:(\\d{1,6})m)?(?:(\\d{1,6})s)?$").matchEntire(it)
    } ?: return null
    val hours = match.groupValues[1].toLongOrNull() ?: 0
    val minutes = match.groupValues[2].toLongOrNull() ?: 0
    val seconds = match.groupValues[3].toLongOrNull() ?: 0
    return ((hours * 3600 + minutes * 60 + seconds) * 1000).takeIf { it > 0 }
}

internal val TWITCH_VIDEO_ID = Regex("[0-9]{1,20}")

private fun JsonObject.videoString(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
