package dev.chudpresence.mobile.reporting

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HttpResponseTest {
    @Test fun `a disconnected body resumes the caller with an IO failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(4096))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())

            try {
                withTimeout(3_000) { call.awaitTextResponse() }
                fail("An incomplete body must fail")
            } catch (_: IOException) {
                // A timeout here would mean the callback abandoned its continuation.
            }
        }
    }

    @Test fun `HTTP errors preserve their status and response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("slow_down"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())

            assertEquals(429 to "slow_down", withTimeout(3_000) { call.awaitTextResponse() })
        }
    }

    @Test fun `cancelling the caller cancels its HTTP call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitTextResponse() }

            result.cancel()
            result.join()

            assertTrue(call.isCanceled())
        }
    }
}
