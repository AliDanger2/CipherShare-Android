package com.ciphershare.android.net

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.io.Closeable

/**
 * Tracks one in-progress transfer so the UI can pause/resume/cancel it by id - the Android
 * equivalent of CipherShare (desktop) Services/TransferService.cs's per-session
 * CancellationTokenSource + ManualResetEventSlim "PauseGate":
 *  - [awaitIfPaused] is the pause gate: called once per chunk, it suspends for as long as
 *    [pause] has been called, and resumes right where it left off once [resume] is called -
 *    the TCP connection stays open and idle while paused, it's never torn down.
 *  - [requestCancel] both cancels the coroutine [job] AND force-closes [socket], because a
 *    coroutine Job being cancelled only takes effect at the next suspension point - if the
 *    code is blocked inside a plain (non-suspending) Socket read/write at that exact moment,
 *    only closing the socket directly will unblock it immediately.
 *
 * Matches the desktop's own restriction: pause/resume only make sense for a transfer *this*
 * device is sending (see [isSender]) - pausing an in-progress receive isn't supported on
 * either side. Cancel works in both directions.
 */
class TransferSession(val transferId: String, val isSender: Boolean) {

    private val pausedFlow = MutableStateFlow(false)

    @Volatile var job: Job? = null
    @Volatile var socket: Closeable? = null
    @Volatile var cancelRequested: Boolean = false
        private set

    val isPaused: Boolean get() = pausedFlow.value

    /** Suspends here for as long as the transfer is paused - call once per chunk. */
    suspend fun awaitIfPaused() {
        pausedFlow.filter { !it }.first()
    }

    fun pause() {
        if (!isSender) return
        pausedFlow.value = true
    }

    fun resume() {
        if (!isSender) return
        pausedFlow.value = false
    }

    fun requestCancel() {
        cancelRequested = true
        pausedFlow.value = false // release a paused transfer so cancellation can proceed immediately
        job?.cancel()
        try {
            socket?.close()
        } catch (_: Exception) {
            // Already closed or never opened yet - either way, nothing left to unblock.
        }
    }
}
