package com.ciphershare.android.net

import kotlinx.coroutines.delay

/**
 * Mirrors CipherShare (desktop) Services/BandwidthThrottle.cs. Call waitIfNeeded() after
 * every chunk read/written, telling it how many bytes just moved; it delays just enough to
 * keep the average rate at or below the configured limit. Pass 0 (or less) for "unlimited".
 */
class BandwidthThrottle(maxMegabytesPerSecond: Double) {
    private val maxBytesPerSecond: Double = if (maxMegabytesPerSecond > 0) maxMegabytesPerSecond * 1024 * 1024 else 0.0
    private val startNanos = System.nanoTime()
    private var bytesSinceStart = 0L

    suspend fun waitIfNeeded(bytesMoved: Int) {
        if (maxBytesPerSecond <= 0) return

        bytesSinceStart += bytesMoved
        val expectedSeconds = bytesSinceStart / maxBytesPerSecond
        val actualSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val delaySeconds = expectedSeconds - actualSeconds

        if (delaySeconds > 0) {
            delay((delaySeconds * 1000).toLong().coerceAtLeast(0))
        }
    }
}
