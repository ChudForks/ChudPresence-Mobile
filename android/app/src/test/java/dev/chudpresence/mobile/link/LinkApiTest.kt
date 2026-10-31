package dev.chudpresence.mobile.link

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import dev.chudpresence.mobile.data.PresenceDisplaySettings
import dev.chudpresence.mobile.services.ServiceDefinitions
import kotlinx.serialization.json.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkApiTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = MockWebServer()

    private val pairing: LinkPairing get() = LinkPairing(
        "http://192.168.42.2:${server.port}", "desktop", "phone", "Test phone", "a".repeat(64)
    )

    @Before fun startServer() { server.start(loopback, 0) }

    @After fun stopServer() { server.shutdown() }

    @Test fun `stream verifies identity sends bearer and dispatches distinct revisions`() = runBlocking {
        enqueueInfo()
        val frames = "id: 1\nevent: revision\ndata: {\"serverId\":\"desktop\",\"revision\":1}\n\n" +
            ": heartbeat\n\nid: 1\nevent: revision\ndata: {\"serverId\":\"desktop\",\"revision\":1}\n\n" +
            "id: 2\nevent: revision\ndata: {\"serverId\":\"desktop\",\"revision\":2}\n\n"
        // After delivering the frames, slowly send an unterminated line so the next read blocks.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(frames + "x".repeat(8_192))
            .throttleBody(frames.toByteArray(Charsets.UTF_8).size.toLong(), 1, TimeUnit.SECONDS))
        val observed = CompletableDeferred<List<Long>>()
        val revisions = mutableListOf<Long>()
        val job = launch(Dispatchers.IO) {
            api().listen(pairing) { revision ->
                revisions.add(revision)
                if (revisions.size == 2) observed.complete(revisions.toList())
            }
        }
        assertEquals(listOf(1L, 2L), withTimeout(5_000) { observed.await() })
        val info = server.takeRequest(5, TimeUnit.SECONDS)!!
        val events = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/info", info.path)
        assertNull(info.getHeader("Authorization"))
        assertEquals("/v1/events", events.path)
        assertEquals("Bearer ${pairing.token}", events.getHeader("Authorization"))
        // Cancellation must close the socket while the stream read is still blocked.
        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun `wrong server never receives the bearer token`() = runBlocking {
        enqueueInfo(id = "different-desktop")
        val failure = failure { api().listen(pairing) {} }
        assertEquals("identity_changed", failure.code)
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun `a superseded network makes no requests`() = runBlocking {
        val failure = failure { api { false }.listen(pairing) {} }
        assertEquals("network_changed", failure.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun `older servers use the compatibility path without opening an event stream`() = runBlocking {
        enqueueInfo(eventsSupported = false)
        val failure = failure { api().listen(pairing) {} }
        assertEquals("events_unsupported", failure.code)
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test fun `event endpoint preserves unauthorized and retry-after responses`() = runBlocking {
        enqueueInfo()
        server.enqueue(MockResponse().setResponseCode(401))
        enqueueInfo()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))
        val networkCurrent = AtomicBoolean(true)
        val attempted = pairing
        val unauthorized = failure { api { networkCurrent.get() }.listen(attempted) {} }
        assertEquals(401, unauthorized.status)
        assertEquals(attempted.deviceId, unauthorized.verifiedDeviceId)
        assertSame(attempted, unauthorized.verifiedPairing)
        assertTrue(unauthorized.authenticationIsCurrent())
        networkCurrent.set(false)
        assertFalse(unauthorized.authenticationIsCurrent())
        val limited = failure { api().listen(pairing) {} }
        assertEquals(429, limited.status)
        assertEquals(3_000L, limited.retryAfterMs)
    }

    @Test fun `a revocation after a verified revision reports unauthorized`() = runBlocking {
        enqueueInfo()
        enqueueStream("event: revision\ndata: {\"serverId\":\"desktop\",\"revision\":0}\n\n" +
            "event: revoked\ndata: {}\n\n")
        val revisions = mutableListOf<Long>()
        val failure = withTimeout(5_000) { failure { api().listen(pairing) { revisions.add(it) } } }
        assertEquals(listOf(0L), revisions)
        assertEquals(401, failure.status)
        assertEquals("revoked", failure.code)
    }

    @Test fun `a revision identifying a different server is rejected`() = runBlocking {
        enqueueInfo()
        enqueueStream("event: revision\ndata: {\"serverId\":\"different-desktop\",\"revision\":0}\n\n")
        val revisions = mutableListOf<Long>()
        val failure = withTimeout(5_000) { failure { api().listen(pairing) { revisions.add(it) } } }
        assertEquals("identity_changed", failure.code)
        assertTrue(revisions.isEmpty())
    }

    @Test fun `revocation before a verified revision does not invalidate the pairing`() = runBlocking {
        enqueueInfo()
        enqueueStream("event: revoked\ndata: {}\n\n")
        val failure = withTimeout(5_000) { ioFailure { api().listen(pairing) {} } }
        assertFalse(failure is LinkFailure && failure.status == 401)
    }

    @Test fun `new inventory reports disabled services and actual boolean settings`() = runBlocking {
        enqueueInfo(settingsSupported = true)
        enqueueJson("""{"services":[]}""")
        val definition = ServiceDefinitions.builtIns.single { it.id == "youtube" }
        val values = PresenceDisplaySettings().linkValues(definition) + (LinkField.ENABLED to JsonPrimitive(false))
        val client = api()
        client.services(pairing, listOf(LinkInventoryItem(definition, values)))
        assertTrue(client.supportsSettingsSync)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        val report = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", report.method)
        assertEquals("Bearer ${pairing.token}", report.getHeader("Authorization"))
        assertNull(report.getHeader("Origin"))
        val service = Json.parseToJsonElement(report.body.readUtf8()).jsonObject.getValue("services").jsonArray.single().jsonObject
        assertFalse(service.getValue("enabled").jsonPrimitive.boolean)
        assertTrue(service.getValue("artwork").jsonPrimitive.boolean)
        assertTrue(service.getValue("actionButtons").jsonPrimitive.boolean)
    }

    @Test fun `legacy servers receive display only inventory and omit unmappable services`() = runBlocking {
        enqueueInfo()
        enqueueJson("""{"services":[]}""")
        val youtube = ServiceDefinitions.builtIns.single { it.id == "youtube" }
        val hulu = ServiceDefinitions.builtIns.single { it.id == "hulu" }
        val client = api()
        client.services(pairing, listOf(LinkInventoryItem(youtube, PresenceDisplaySettings().linkValues(youtube)),
            LinkInventoryItem(hulu, mapOf(LinkField.ARTWORK to JsonPrimitive(false)))))
        assertFalse(client.supportsSettingsSync)
        server.takeRequest(5, TimeUnit.SECONDS)
        val report = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        val item = report.getValue("services").jsonArray.single().jsonObject
        assertEquals(setOf("id", "name", "display", "displayChoices"), item.keys)
        assertEquals("youtube", item.getValue("id").jsonPrimitive.content)
    }

    @Test fun `discovery handles boolean only participants and remembered paused selections`() = runBlocking {
        enqueueInfo(settingsSupported = true)
        enqueueJson("""{"services":[{"id":"hulu","name":"Hulu","shared":true,"syncEnabled":false,
            "syncRequested":true,"syncVersion":12,"supportedFields":["enabled","artwork"],"syncFields":["artwork"],
            "displayChoices":[],"participants":[{"deviceId":"phone","name":"Phone","kind":"mobile",
            "enabled":false,"artwork":false,"reportedAt":"2026-10-27T12:00:00Z"}]}]}""")
        val service = api().services(pairing).single()
        assertTrue(service.syncRequested)
        assertFalse(service.syncEnabled)
        assertEquals(setOf(LinkField.ARTWORK), service.syncFields)
        assertEquals(setOf(LinkField.ENABLED, LinkField.ARTWORK), service.supportedFields)
        assertTrue(service.participants.single().supports(service.syncFields, service.displayChoices))
    }

    @Test fun `settings patch sends only edited fields preserves false and accepts missing display`() = runBlocking {
        enqueueInfo(settingsSupported = true)
        enqueueJson("""{"revision":8,"services":{"youtube":{"artwork":false,"enabled":false,"version":8}},"conflicts":[]}""")
        val changes = LinkEdit(mapOf(LinkField.ARTWORK to LinkFieldEdit(JsonPrimitive(false), "tap")), 6)
        val result = api().settings(pairing, mapOf("youtube" to changes))
        assertEquals(JsonPrimitive(false), result.services.getValue("youtube").values[LinkField.ARTWORK])
        assertFalse(LinkField.DISPLAY in result.services.getValue("youtube").values)
        server.takeRequest(5, TimeUnit.SECONDS)
        val patch = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PATCH", patch.method)
        val body = Json.parseToJsonElement(patch.body.readUtf8()).jsonObject.getValue("changes").jsonObject.getValue("youtube").jsonObject
        assertEquals(setOf("artwork", "baseVersion"), body.keys)
        assertFalse(body.getValue("artwork").jsonPrimitive.boolean)
        assertEquals(6L, body.getValue("baseVersion").jsonPrimitive.long)
    }

    @Test fun `selection uses discovery version explicit field subset and only one starting source`() = runBlocking {
        enqueueInfo(settingsSupported = true)
        enqueueJson("{}")
        val service = LinkService("youtube", "YouTube", true, false, 23, listOf("app", "creator", "title"), emptyList(),
            supportedFields = LinkField.entries.toSet())
        api().selectSync(pairing, service, true, "phone", setOf(LinkField.ARTWORK, LinkField.ENABLED))
        server.takeRequest(5, TimeUnit.SECONDS)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/services/youtube/sync", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("enabled", "baseVersion", "sourceDeviceId", "fields"), body.keys)
        assertEquals(23L, body.getValue("baseVersion").jsonPrimitive.long)
        assertEquals(setOf("artwork", "enabled"), body.getValue("fields").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test fun `disabling sync omits initialization and fields`() = runBlocking {
        enqueueInfo(settingsSupported = true)
        enqueueJson("{}")
        val service = LinkService("youtube", "YouTube", true, true, 5, emptyList(), emptyList())
        api().selectSync(pairing, service, false, null, emptySet())
        server.takeRequest(5, TimeUnit.SECONDS)
        val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals(setOf("enabled", "baseVersion"), body.keys)
        assertFalse(body.getValue("enabled").jsonPrimitive.boolean)
    }

    @Test fun `older display selections omit the unsupported fields parameter`() = runBlocking {
        enqueueInfo()
        enqueueJson("{}")
        val service = LinkService("youtube", "YouTube", true, false, 2, listOf("creator"), emptyList())
        api().selectSync(pairing, service, true, "phone", linkDisplayFields)
        server.takeRequest(5, TimeUnit.SECONDS)
        val body = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
        assertEquals(setOf("enabled", "baseVersion", "sourceDeviceId"), body.keys)
    }

    @Test fun `new fields require both settings and selective capabilities before an authenticated write`() = runBlocking {
        enqueueInfo(settingsSupported = true, selectiveSupported = false)
        val change = LinkEdit(mapOf(LinkField.ARTWORK to LinkFieldEdit(JsonPrimitive(false), "tap")), 6)
        var rejected = false
        try { api().settings(pairing, mapOf("youtube" to change)) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun `field not synced remains a recoverable selection error`() = runBlocking {
        enqueueInfo(settingsSupported = true)
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
            .setBody("""{"error":"field_not_synced","message":"Selection changed"}"""))
        val change = LinkEdit(mapOf(LinkField.ARTWORK to LinkFieldEdit(JsonPrimitive(false), "tap")), 6)
        val failure = failure { api().settings(pairing, mapOf("youtube" to change)) }
        assertEquals("field_not_synced", failure.code)
        assertEquals(409, failure.status)
        assertSame(pairing.serverId, failure.verifiedPairing?.serverId)
    }

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun enqueueInfo(id: String = "desktop", eventsSupported: Boolean = true,
        settingsSupported: Boolean = false, selectiveSupported: Boolean = settingsSupported) {
        val capabilities = JsonArray(buildList {
            if (eventsSupported) add(JsonPrimitive("settings-change-events"))
            if (settingsSupported) add(JsonPrimitive("service-settings-sync"))
            if (selectiveSupported) add(JsonPrimitive("selective-settings-sync"))
        }).toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{\"name\":\"ChudPresence Link\",\"protocolVersion\":1," +
                "\"serverId\":\"$id\",\"capabilities\":$capabilities}"))
    }

    private fun enqueueStream(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
    }

    private fun api(networkIsCurrent: () -> Boolean = { true }) = LinkApi(RedirectToLoopback(), networkIsCurrent)

    private suspend fun failure(action: suspend () -> Unit): LinkFailure {
        try { action() } catch (failure: LinkFailure) { return failure }
        throw AssertionError("Expected a LinkFailure")
    }

    private suspend fun ioFailure(action: suspend () -> Unit): IOException {
        try { action() } catch (failure: IOException) { return failure }
        throw AssertionError("Expected an IOException")
    }

    /** Preserve production's private-IP validation while keeping the test entirely local. */
    private inner class RedirectToLoopback : SocketFactory() {
        override fun createSocket(): Socket = RedirectSocket()
        override fun createSocket(host: String, port: Int): Socket = connect(port)
        override fun createSocket(host: InetAddress, port: Int): Socket = connect(port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = connect(port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = connect(port)
        private fun connect(port: Int): Socket = createSocket().apply { connect(InetSocketAddress(loopback, port)) }
    }

    private inner class RedirectSocket : Socket() {
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val port = (endpoint as InetSocketAddress).port
            super.connect(InetSocketAddress(loopback, port), timeout)
        }
    }
}
