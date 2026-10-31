package dev.chudpresence.mobile.media

import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Unknown breaking versions still retain their ordinary Android metadata. */
internal fun supportsMetadataVersion(version: Long): Boolean = version == 1L

internal fun supportedContentType(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { it in setOf("song", "video", "short", "episode", "movie", "live") }

internal fun supportedMediaKind(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
    ?.takeIf { it in setOf("music", "video") }

internal fun metadataText(value: String?): String? = value?.take(1_024)
    ?.replace(Regex("[\\p{Cc}]"), " ")?.trim()?.takeIf { it.isNotEmpty() }

/** Public, credential-free destinations only. No host resolution or network calls occur here. */
internal fun publicHttpsUrl(maxLength: Int, vararg candidates: String?): String? =
    candidates.firstNotNullOfOrNull { candidate ->
        val value = candidate?.trim()?.takeIf { it.length in 1..maxLength } ?: return@firstNotNullOfOrNull null
        val uri = runCatching { URI(value) }.getOrNull() ?: return@firstNotNullOfOrNull null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return@firstNotNullOfOrNull null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
            uri.port !in setOf(-1, 443) || !publicHost(host) || uri.rawFragment != null ||
            credentialQuery(uri.rawQuery)) return@firstNotNullOfOrNull null
        value
    }

private fun publicHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") ||
        listOf(".local", ".localdomain", ".internal", ".lan", ".home", ".test", ".invalid", ".example", ".onion")
            .any(host::endsWith)) return false
    if (host.contains(':')) {
        // Only literal IPv6 reaches this branch, so InetAddress never performs DNS.
        val address = runCatching { InetAddress.getByName(host.removePrefix("[").removeSuffix("]")) }.getOrNull() ?: return false
        val bytes = address.address
        if (bytes.size == 4) return publicHost(address.hostAddress ?: return false)
        return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress &&
            (bytes.size != 16 || (bytes[0].toInt() and 0xfe) != 0xfc)
    }
    if (host.all { it.isDigit() || it == '.' }) {
        val parts = host.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.length > 1 && it.startsWith('0') }) return false
        val numbers = parts.map { it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return false }
        val (a, b, c) = numbers
        return a !in setOf(0, 10, 127) && a < 224 &&
            !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
            !(a == 192 && b == 0 && c in setOf(0, 2)) &&
            !(a == 198 && b in 18..19) && !(a == 198 && b == 51 && c == 100) &&
            !(a == 203 && b == 0 && c == 113)
    }
    if (host.split('.').all { it.matches(Regex("(?:0x[0-9a-f]+|[0-9]+)")) }) return false
    return host.contains('.') && host.length <= 253 && host.split('.').all {
        it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
            it.all { character -> character in 'a'..'z' || character.isDigit() || character == '-' }
    }
}

private fun credentialQuery(rawQuery: String?): Boolean = rawQuery?.split('&')?.any { pair ->
    var encodedName = pair.substringBefore('=')
    repeat(4) { depth ->
        val name = encodedName.lowercase(Locale.ROOT).replace('-', '_')
        if (name in setOf("token", "__token__", "access_token", "refresh_token", "id_token", "auth", "authz", "authorization", "jwt",
                "key", "api_key", "apikey", "client_secret", "secret", "password", "session", "session_id", "sessionid",
                "signature", "sig", "policy", "key_pair_id", "credential", "expires", "expiry", "hdnea", "hdnts") ||
            name.startsWith("x_amz_") || name.startsWith("x_goog_") || name.endsWith("_token")) return@any true
        if (depth == 3) return@any '%' in encodedName
        val decoded = runCatching { URLDecoder.decode(encodedName, "UTF-8") }.getOrElse { return@any true }
        if (decoded == encodedName) return@any false
        encodedName = decoded
    }
    false
} == true
