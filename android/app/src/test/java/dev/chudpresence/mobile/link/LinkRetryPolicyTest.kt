package dev.chudpresence.mobile.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRetryPolicyTest {
    @Test fun `failures double from thirty seconds and cap at ten minutes`() {
        val policy = LinkRetryPolicy { 0L }
        val expected = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L, 600_000L)

        expected.forEachIndexed { index, delay ->
            val now = index * 1_000L
            assertEquals(delay, policy.failed(now))
            assertEquals(now + delay, policy.retryAtMs)
        }
    }

    @Test fun `an immediate probe keeps the failure history while success resets it`() {
        val policy = LinkRetryPolicy { 0L }
        policy.failed(100)

        policy.allowImmediateRetry()
        assertEquals(0L, policy.retryAtMs)
        assertEquals(60_000L, policy.failed(200))
        assertEquals(60_200L, policy.retryAtMs)

        policy.reset()
        assertEquals(0L, policy.retryAtMs)
        assertEquals(30_000L, policy.failed(300))
        assertEquals(30_300L, policy.retryAtMs)
    }

    @Test fun `jitter cannot shorten a delay or exceed the cap`() {
        val negative = LinkRetryPolicy { -1L }
        assertEquals(30_000L, negative.failed(0))

        val excessive = LinkRetryPolicy { Long.MAX_VALUE }
        assertEquals(600_000L, excessive.failed(0))
        assertEquals(600_000L, excessive.retryAtMs)
        assertEquals(600_000L, excessive.failed(0))
    }

    @Test fun `default jitter stays within the retry bounds`() {
        val policy = LinkRetryPolicy()
        val bases = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L)
        bases.forEach { base ->
            val delay = policy.failed(0)
            assertTrue(delay in base..600_000L)
            assertEquals(delay, policy.retryAtMs)
        }
    }

    @Test fun `deadline saturates instead of overflowing`() {
        val policy = LinkRetryPolicy { 0L }
        assertEquals(30_000L, policy.failed(Long.MAX_VALUE - 1))
        assertEquals(Long.MAX_VALUE, policy.retryAtMs)
    }
}
