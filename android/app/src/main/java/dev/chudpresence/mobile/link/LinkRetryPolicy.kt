package dev.chudpresence.mobile.link

import kotlin.random.Random

/** Reconnect delay for ordinary failures. HTTP rate limits are handled separately. */
internal class LinkRetryPolicy(
    private val jitter: (Long) -> Long = { base -> Random.nextLong(base / 5 + 1) }
) {
    private var failures = 0
    var retryAtMs: Long = 0
        private set

    /** Returns the selected delay and records its monotonic deadline. */
    fun failed(nowMs: Long): Long {
        val base = minOf(INITIAL_DELAY_MS * (1L shl failures), MAX_DELAY_MS)
        failures = minOf(failures + 1, MAX_FAILURE_SHIFT)
        // A bad/custom jitter source must neither shorten the delay nor overflow it.
        val addition = jitter(base).coerceIn(0, MAX_DELAY_MS - base)
        val delay = base + addition
        retryAtMs = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay
        return delay
    }

    /** Successful contact starts the next failure at the initial delay. */
    fun reset() {
        failures = 0
        retryAtMs = 0
    }

    /** Permit a new probe without forgetting the previous failures. */
    fun allowImmediateRetry() {
        retryAtMs = 0
    }

    private companion object {
        const val INITIAL_DELAY_MS = 30_000L
        const val MAX_DELAY_MS = 600_000L
        const val MAX_FAILURE_SHIFT = 5
    }
}
