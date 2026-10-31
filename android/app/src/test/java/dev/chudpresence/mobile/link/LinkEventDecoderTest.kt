package dev.chudpresence.mobile.link

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LinkEventDecoderTest {
    @Test fun `initial and later revision frames dispatch only at blank lines`() {
        val decoder = LinkEventDecoder()
        assertNull(decoder.accept("id: 0"))
        assertNull(decoder.accept("event: revision"))
        assertNull(decoder.accept("data: {\"serverId\":\"desktop\",\"revision\":0}"))
        assertEquals(LinkEvent.Revision("desktop", 0), decoder.accept(""))

        assertNull(decoder.accept("id: 42\r"))
        assertNull(decoder.accept("event: revision\r"))
        assertNull(decoder.accept("data: {\"serverId\":\"desktop\",\"revision\":42,\"extra\":true}\r"))
        assertEquals(LinkEvent.Revision("desktop", 42), decoder.accept("\r"))
    }

    @Test fun `multiline data is joined and heartbeat comments do not dispatch`() {
        val decoder = LinkEventDecoder()
        assertNull(decoder.accept(": heartbeat"))
        assertNull(decoder.accept(""))
        assertNull(decoder.accept("event: revision"))
        assertNull(decoder.accept("data: {"))
        assertNull(decoder.accept("data: \"serverId\": \"desktop\","))
        assertNull(decoder.accept(": heartbeat inside a frame"))
        assertNull(decoder.accept("data: \"revision\": 7}"))
        assertEquals(LinkEvent.Revision("desktop", 7), decoder.accept(""))
    }

    @Test fun `revocation dispatches and unknown or data-less events are ignored`() {
        val decoder = LinkEventDecoder()
        assertNull(decoder.accept("event: future-event"))
        assertNull(decoder.accept("data: not necessarily JSON"))
        assertNull(decoder.accept(""))
        assertNull(decoder.accept("event: revision"))
        assertNull(decoder.accept(""))
        assertNull(decoder.accept("event: revoked"))
        assertNull(decoder.accept("data: {}"))
        assertEquals(LinkEvent.Revoked, decoder.accept(""))
    }

    @Test fun `invalid revision payloads fail without producing an event`() {
        listOf(
            "not JSON",
            "[]",
            "{}",
            "{\"serverId\":\"\",\"revision\":0}",
            "{\"serverId\":\"   \",\"revision\":0}",
            "{\"serverId\":12,\"revision\":0}",
            "{\"serverId\":\"desktop\",\"revision\":-1}",
            "{\"serverId\":\"desktop\",\"revision\":1.5}",
            "{\"serverId\":\"desktop\",\"revision\":\"1\"}",
            "{\"serverId\":\"desktop\",\"revision\":9223372036854775808}"
        ).forEach { payload ->
            val decoder = LinkEventDecoder()
            decoder.accept("event: revision")
            decoder.accept("data: $payload")
            assertThrows(IOException::class.java) { decoder.accept("") }
        }
    }

    @Test fun `invalid revocation payload fails`() {
        val decoder = LinkEventDecoder()
        decoder.accept("event: revoked")
        decoder.accept("data: invalid")
        assertThrows(IOException::class.java) { decoder.accept("") }
    }

    @Test fun `line and accumulated frame limits count utf8 bytes`() {
        assertThrows(IOException::class.java) {
            LinkEventDecoder().accept("data: " + "é".repeat(32_768))
        }
        val decoder = LinkEventDecoder()
        decoder.accept("data: " + "a".repeat(40_000))
        assertThrows(IOException::class.java) { decoder.accept("data: " + "b".repeat(30_000)) }
    }

    @Test fun `incomplete frame is discarded at end of input`() {
        val decoder = LinkEventDecoder()
        decoder.accept("event: revision")
        decoder.accept("data: {\"serverId\":\"desktop\",\"revision\":1}")
        assertNull(decoder.endOfInput())
        assertNull(decoder.accept(""))
        decoder.accept("event: revision")
        decoder.accept("data: {\"serverId\":\"desktop\",\"revision\":2}")
        assertEquals(LinkEvent.Revision("desktop", 2), decoder.accept(""))
    }
}
