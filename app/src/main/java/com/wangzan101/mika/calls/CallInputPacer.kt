package com.wangzan101.mika.calls

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A single PCM16 mono frame in flight, targeting its absolute media deadline.
 * Ordinary encode/scheduling overhead must not accumulate as capture latency. Larger overruns
 * rebase the clock instead of bursting buffered PCM to catch up. A blocked websocket send also
 * rebases after completion. The existing bounded pipe owns the capture-backpressure limit.
 */
internal class CallInputPacer(
    frameBytes: Int = VolcRealtimeClient.INPUT_CHUNK_BYTES,
    sampleRate: Int = VolcRealtimeClient.INPUT_SAMPLE_RATE,
    private val nowNanos: () -> Long = System::nanoTime,
    private val sleepMs: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(frameBytes > 0 && frameBytes % 2 == 0) { "Input frame must contain whole PCM16 samples" }
        require(sampleRate > 0) { "Input sample rate must be positive" }
    }
    private val frameNanos = frameBytes.toLong() / 2 * 1_000_000_000L / sampleRate
    private val rebaseLagNanos = frameNanos / 2
    private var nextFrameNanos: Long? = null

    suspend fun send(accept: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        nextFrameNanos?.let { deadline ->
            while (true) {
                val remaining = deadline - nowNanos()
                if (remaining <= 0) break
                sleepMs((remaining + NANOS_PER_MS - 1) / NANOS_PER_MS)
                currentCoroutineContext().ensureActive()
            }
        }
        currentCoroutineContext().ensureActive()
        val started = nowNanos()
        val planned = nextFrameNanos ?: started
        // Keeping a deadline almost a whole frame in the past would cause a rapid next send.
        val slot = if (started - planned >= rebaseLagNanos) started else planned
        val accepted = accept()
        if (accepted) {
            val finished = nowNanos()
            nextFrameNanos = if (finished - slot >= frameNanos) finished + frameNanos
                else slot + frameNanos
        }
        return accepted
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
