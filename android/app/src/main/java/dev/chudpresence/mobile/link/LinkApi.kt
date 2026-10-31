package dev.chudpresence.mobile.link

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class LinkFailure(
    val status: Int = 0, val code: String = "", message: String, val retryAfterMs: Long = 60_000L
) : IOException(message) {
    var verifiedPairing: LinkPairing? = null
        internal set
    val verifiedDeviceId: String? get() = verifiedPairing?.deviceId
    var authenticationIsCurrent: () -> Boolean = { false }
        internal set
}

/** No redirects, DNS names, public addresses, cookies, Origin header, or presence endpoints. */
internal class LinkApi(socketFactory: SocketFactory? = null, private val networkIsCurrent: () -> Boolean = { true }) {
    @Volatile var supportsEvents: Boolean = false
        private set
    @Volatile var supportsSettingsSync: Boolean = false
        private set
    private val http = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .apply { if (socketFactory != null) socketFactory(socketFactory) }.build()
    // Only the notification request is long-lived. Ordinary calls keep their bounded timeouts.
    private val eventsHttp = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(150, TimeUnit.SECONDS).build()

    suspend fun listen(pairing: LinkPairing, onRevision: suspend (Long) -> Unit): Nothing = withContext(Dispatchers.IO) {
        val server = verifiedInfo(pairing)
        if (server["capabilities"]?.jsonArray?.none { it.jsonPrimitive.content == "settings-change-events" } != false) {
            throw LinkFailure(code = "events_unsupported", message = "Update the desktop Link app to receive setting changes immediately.")
        }
        ensureNetwork()
        val request = Request.Builder().url(normalizeLinkEndpoint(pairing.endpoint) + "/v1/events")
            .header("Accept", "text/event-stream").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${pairing.token}").build()
        val call = eventsHttp.newCall(request)
        val response = withTimeoutOrNull(8_000L) { call.await() }
            ?: throw IOException("Link did not open setting notifications in time.")
        try { coroutineScope {
            // A body read blocks in Okio after await() completes. Cancel the socket as well as
            // the coroutine on network loss/inactivity; otherwise the old session could linger.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
            try {
                response.use {
                    ensureNetwork()
                    if (!response.isSuccessful) throw authenticated(response.failure(), pairing)
                    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream") {
                        throw IOException("Link returned an invalid notification stream.")
                    }
                    val source = response.body?.source() ?: throw IOException("Link returned an empty notification stream.")
                    val decoder = LinkEventDecoder()
                    var lastRevision: Long? = null
                    while (currentCoroutineContext().isActive) {
                        val event = decoder.accept(source.readUtf8LineStrict(64 * 1024L))
                        ensureNetwork()
                        when (event) {
                            is LinkEvent.Revision -> {
                                if (event.serverId != pairing.serverId) throw identityChanged()
                                if (event.revision != lastRevision) {
                                    lastRevision = event.revision
                                    onRevision(event.revision)
                                }
                            }
                            LinkEvent.Revoked -> {
                                if (lastRevision == null) throw IOException("Link did not identify its notification stream.")
                                throw authenticated(LinkFailure(401, "revoked", "This phone is no longer paired. Create a new phone code in Link."), pairing)
                            }
                            null -> Unit
                        }
                    }
                    throw CancellationException("Link notification session stopped")
                }
            } catch (failure: IOException) {
                // Socket cancellation unblocks the synchronous read with an IOException.
                // Preserve structured cancellation instead of failing the parent worker.
                currentCoroutineContext().ensureActive()
                throw failure
            } finally {
                call.cancel()
                cancellation.cancel()
            }
        } } finally { call.cancel(); response.close() }
    }

    suspend fun pair(endpoint: String, code: String, phoneName: String): LinkPairing {
        val serverId = info(endpoint)
        val result = request(endpoint, "/v1/pair", "POST", buildJsonObject {
            put("code", code); put("name", phoneName); put("kind", "mobile")
        })
        if (result["serverId"]?.jsonPrimitive?.content != serverId ||
            result["protocolVersion"]?.jsonPrimitive?.intOrNull != 1) {
            throw LinkFailure(code = "identity_changed", message = "Link changed during pairing. Create a new phone code and try again.")
        }
        val token = result["token"]?.jsonPrimitive?.content.orEmpty()
        val deviceId = result["deviceId"]?.jsonPrimitive?.content.orEmpty()
        if (!Regex("[a-f0-9]{64}").matches(token) || deviceId.isBlank()) {
            throw IOException("Link returned an invalid pairing response.")
        }
        return LinkPairing(endpoint, serverId, deviceId, phoneName, token)
    }

    suspend fun services(pairing: LinkPairing, inventory: List<LinkInventoryItem>? = null): List<LinkService> {
        val root = protected(pairing, "/v1/services", if (inventory == null) "GET" else "PUT",
            inventory?.let { items -> { buildJsonObject {
                put("services", JsonArray(items.filter { supportsSettingsSync || LinkField.DISPLAY in it.values }
                    .map { it.json(supportsSettingsSync) }))
            } } })
        return root.getValue("services").jsonArray.map { raw ->
            val service = raw.jsonObject
            val choices = service["displayChoices"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val legacyFields = if (choices.isEmpty()) emptySet() else linkDisplayFields
            fun fields(key: String, fallback: Set<LinkField>): Set<LinkField> =
                service[key]?.jsonArray?.mapNotNull { LinkField.fromWire(it.jsonPrimitive.content) }?.toSet() ?: fallback
            LinkService(service.getValue("id").jsonPrimitive.content, service.getValue("name").jsonPrimitive.content,
                service.getValue("shared").jsonPrimitive.boolean, service.getValue("syncEnabled").jsonPrimitive.boolean,
                service.getValue("syncVersion").jsonPrimitive.long,
                choices,
                service.getValue("participants").jsonArray.map { participant ->
                    val item = participant.jsonObject
                    LinkParticipant(item.getValue("deviceId").jsonPrimitive.content, item.getValue("name").jsonPrimitive.content,
                        item.getValue("kind").jsonPrimitive.content, readLinkValues(item),
                        item.getValue("reportedAt").jsonPrimitive.content)
                }, fields("supportedFields", legacyFields), fields("syncFields", linkDisplayFields),
                service["syncRequested"]?.jsonPrimitive?.booleanOrNull ?: service.getValue("syncEnabled").jsonPrimitive.boolean)
        }
    }

    suspend fun selectSync(pairing: LinkPairing, service: LinkService, enabled: Boolean, sourceDeviceId: String?,
        fields: Set<LinkField> = service.supportedFields) {
        require(linkServiceId(service.id) == service.id)
        protected(pairing, "/v1/services/${service.id}/sync", "PUT") {
            require(!enabled || supportsSettingsSync || fields == linkDisplayFields) { "Update Link to sync activity settings." }
            buildJsonObject {
                put("enabled", enabled); put("baseVersion", service.syncVersion)
                if (enabled) {
                    require(fields.isNotEmpty() && service.supportedFields.containsAll(fields))
                    put("sourceDeviceId", requireNotNull(sourceDeviceId))
                    if (supportsSettingsSync) put("fields", JsonArray(fields.map { JsonPrimitive(it.wireName) }))
                }
            }
        }
    }

    suspend fun settings(pairing: LinkPairing, changes: Map<String, LinkEdit>): LinkSettingsSnapshot {
        val root = protected(pairing, "/v1/settings", if (changes.isEmpty()) "GET" else "PATCH",
            changes.takeIf { it.isNotEmpty() }?.let { pending -> { buildJsonObject {
                put("changes", buildJsonObject {
                    pending.forEach { (id, edit) -> put(id, buildJsonObject {
                        require(edit.fields.isNotEmpty() && (supportsSettingsSync || edit.fields.keys == linkDisplayFields))
                        edit.fields.forEach { (field, change) -> put(field.wireName, change.value) }
                        put("baseVersion", edit.baseVersion)
                    }) }
                })
            } } })
        return LinkSettingsSnapshot(root.getValue("services").jsonObject.mapValues { (_, raw) ->
            LinkPreference(readLinkValues(raw.jsonObject),
                raw.jsonObject.getValue("version").jsonPrimitive.long).also {
                if (it.values.isEmpty() || it.version <= 0) throw IOException("Link returned an invalid shared preference.")
            }
        }, root["conflicts"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty())
    }

    suspend fun unpair(pairing: LinkPairing) { protected(pairing, "/v1/device", "DELETE") }

    private suspend fun protected(pairing: LinkPairing, path: String, method: String, body: (() -> JsonObject)? = null): JsonObject {
        verifiedInfo(pairing)
        return try { request(pairing.endpoint, path, method, body?.invoke(), pairing.token) }
        catch (failure: LinkFailure) { throw authenticated(failure, pairing) }
    }

    private fun authenticated(failure: LinkFailure, pairing: LinkPairing) = failure.apply {
        verifiedPairing = pairing
        authenticationIsCurrent = networkIsCurrent
    }

    private suspend fun verifiedInfo(pairing: LinkPairing): JsonObject {
        val result = serverInfo(pairing.endpoint)
        if (result.getValue("serverId").jsonPrimitive.content != pairing.serverId) throw identityChanged()
        return result
    }

    private fun identityChanged() = LinkFailure(code = "identity_changed",
        message = "Your paired Link isn’t at this address on the current network. The saved pairing is kept for when you return.")

    private suspend fun info(endpoint: String): String = serverInfo(endpoint).getValue("serverId").jsonPrimitive.content

    private suspend fun serverInfo(endpoint: String): JsonObject {
        val info = request(endpoint, "/v1/info", "GET")
        if (info["name"]?.jsonPrimitive?.content != "ChudPresence Link" ||
            info["protocolVersion"]?.jsonPrimitive?.intOrNull != 1 ||
            info["serverId"]?.jsonPrimitive?.content.isNullOrBlank()) {
            throw LinkFailure(code = "unsupported_server", message = "This address is not a compatible ChudPresence Link server.")
        }
        supportsEvents = info["capabilities"]?.jsonArray?.any { it.jsonPrimitive.content == "settings-change-events" } == true
        val capabilities = info["capabilities"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        supportsSettingsSync = "service-settings-sync" in capabilities && "selective-settings-sync" in capabilities
        return info
    }

    private fun ensureNetwork() {
        if (!networkIsCurrent()) throw LinkFailure(code = "network_changed", message = "Link will reconnect when a local network is available.")
    }

    private fun Response.failure(): LinkFailure {
        val retry = header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400)?.times(1_000) ?: 60_000L
        return when (code) {
            401 -> LinkFailure(401, "unauthorized", "This phone is no longer paired. Create a new phone code in Link.")
            429 -> LinkFailure(429, "rate_limited", "Link is receiving too many requests. Try again in a minute.", retry)
            else -> LinkFailure(code, message = "Link could not open setting notifications (HTTP $code).")
        }
    }

    private suspend fun request(endpoint: String, path: String, method: String,
        payload: JsonObject? = null, token: String? = null): JsonObject = withContext(Dispatchers.IO) {
        // Validate again when restoring an endpoint from storage.
        ensureNetwork()
        val origin = normalizeLinkEndpoint(endpoint)
        val encoded = payload?.toString()
        require(encoded == null || encoded.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "Link request exceeds the supported size." }
        val request = Request.Builder().url(origin + path).header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (token != null) request.header("Authorization", "Bearer $token")
        request.method(method, encoded?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        http.newCall(request.build()).await().use { response ->
            ensureNetwork()
            val raw = response.body?.source()?.let { source ->
                source.request(1024 * 1024L + 1)
                if (source.buffer.size > 1024 * 1024) throw IOException("Link returned too much data.")
                source.readUtf8()
            }.orEmpty()
            val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (!response.isSuccessful) {
                val code = root?.get("error")?.jsonPrimitive?.content.orEmpty()
                val message = when {
                    code == "invalid_code" -> "That phone code is invalid or expired. Create a new Pair phone code in Link."
                    response.code == 401 -> "This phone is no longer paired. Create a new Pair phone code in Link."
                    response.code == 429 -> "Link is receiving too many requests. Try again in a minute."
                    code == "sync_conflict" -> "This sync selection changed on another device. Review the refreshed choices and try again."
                    code in setOf("sync_not_enabled", "field_not_synced", "service_not_shared", "unsupported_display", "service_not_reported") ->
                        "This service’s sync availability changed. Refresh Link and review its current choices."
                    response.code in 300..399 -> "Link redirected the request. Enter the desktop’s local address directly."
                    else -> "Link could not complete the request (HTTP ${response.code}). Try again."
                }
                val retry = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 86_400)?.times(1_000) ?: 60_000L
                throw LinkFailure(response.code, code, message, retry)
            }
            if (response.code == 204) JsonObject(emptyMap())
            else root ?: throw IOException("Link returned an invalid response.")
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, result, _ -> result.close() }
        }
    })
}

internal fun normalizeLinkEndpoint(value: String): String {
    val input = value.trim().let { if ("://" in it) it else "http://$it" }
    val url = input.toHttpUrlOrNull()
    require(url != null && url.scheme == "http" && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "Enter the desktop’s local IP address, with an optional port (for example 192.168.1.10:17879)."
    }
    val parts = url.host.split('.')
    val ipv4 = parts.takeIf { it.size == 4 }?.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    val privateV4 = ipv4?.size == 4 && (ipv4[0] == 10 || ipv4[0] == 192 && ipv4[1] == 168 ||
        ipv4[0] == 172 && ipv4[1] in 16..31 || ipv4[0] == 169 && ipv4[1] == 254)
    val privateV6 = if (url.host.contains(':') && Regex("[0-9a-fA-F:]+").matches(url.host)) {
        val address = InetAddress.getByName(url.host)
        address is Inet6Address && (address.isLinkLocalAddress || address.isSiteLocalAddress ||
            (address.address[0].toInt() and 0xfe) == 0xfc)
    } else false
    require(privateV4 || privateV6) { "Use the private LAN IP shown in Link. Connect your phone and PC to the same network." }
    return url.newBuilder().apply { if (URI(input).port == -1) port(17879) }.build().toString().removeSuffix("/")
}
