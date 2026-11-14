package com.wangzan101.mika.calls

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallInputPacerTest {
    @Test fun `provider frame size and sample rate determine media cadence`() = runTest {
        val sent = mutableListOf<Long>()
        val pacer = CallInputPacer(frameBytes = 1_920, sampleRate = 24_000,
            nowNanos = { currentTime * 1_000_000 })
        repeat(4) { assertTrue(pacer.send { sent += currentTime; true }) }
        assertEquals(listOf(0L, 40L, 80L, 120L), sent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial PCM samples are rejected`() { CallInputPacer(frameBytes = 3) }

    @Test(expected = IllegalArgumentException::class)
    fun `zero sample rate is rejected`() { CallInputPacer(sampleRate = 0) }

    @Test fun `buffered frames retain their order and actual twenty millisecond cadence`() = runTest {
        val sent = mutableListOf<Pair<Int, Long>>()
        val pacer = CallInputPacer(nowNanos = { currentTime * 1_000_000 })
        repeat(8) { index ->
            assertTrue(pacer.send { sent += index to currentTime; true })
        }
        assertEquals((0..7).toList(), sent.map { it.first })
        assertEquals(listOf(0L, 20L, 40L, 60L, 80L, 100L, 120L, 140L), sent.map { it.second })
    }

    @Test fun `a delayed capture resumes immediately then does not burst catchup frames`() = runTest {
        val sent = mutableListOf<Long>()
        val pacer = CallInputPacer(nowNanos = { currentTime * 1_000_000 })
        assertTrue(pacer.send { sent += currentTime; true })
        advanceTimeBy(175)
        repeat(3) { assertTrue(pacer.send { sent += currentTime; true }) }
        assertEquals(listOf(0L, 175L, 195L, 215L), sent)
    }

    @Test fun `websocket send delay is not reused as credit for the following frame`() = runTest {
        var clock = 0L
        val sent = mutableListOf<Long>()
        val pacer = CallInputPacer(nowNanos = { clock }, sleepMs = { clock += it * 1_000_000 })
        assertTrue(pacer.send { clock += 150_000_000; sent += clock; true })
        assertTrue(pacer.send { sent += clock; true })
        assertEquals(listOf(150_000_000L, 170_000_000L), sent)
    }

    @Test fun `early wakeups cannot compress the PCM cadence`() = runTest {
        var clock = 0L
        val sent = mutableListOf<Long>()
        val pacer = CallInputPacer(nowNanos = { clock }, sleepMs = { clock += minOf(it, 3) * 1_000_000 })
        repeat(3) { assertTrue(pacer.send { sent += clock; true }) }
        assertEquals(listOf(0L, 20_000_000L, 40_000_000L), sent)
    }

    @Test fun `ordinary send overhead does not accumulate as capture latency`() = runTest {
        var clock = 0L
        val sent = mutableListOf<Long>()
        val pacer = CallInputPacer(nowNanos = { clock }, sleepMs = { clock += it * 1_000_000 })
        repeat(5) {
            assertTrue(pacer.send { clock += 5_000_000; sent += clock; true })
        }
        assertEquals(listOf(5_000_000L, 25_000_000L, 45_000_000L, 65_000_000L, 85_000_000L), sent)
    }

    @Test fun `a large scheduler overrun rebases instead of causing an immediate next send`() = runTest {
        var clock = 0L
        val sent = mutableListOf<Long>()
        val pacer = CallInputPacer(nowNanos = { clock }, sleepMs = { clock += (it + 13) * 1_000_000 })
        repeat(3) { assertTrue(pacer.send { sent += clock; true }) }
        assertEquals(listOf(0L, 33_000_000L, 66_000_000L), sent)
    }

    @Test fun `cancel during pacing never submits the queued next frame`() = runTest {
        var sends = 0
        val pacer = CallInputPacer(nowNanos = { currentTime * 1_000_000 })
        assertTrue(pacer.send { sends++; true })
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { pacer.send { sends++; true } }
        advanceTimeBy(10)
        waiting.cancelAndJoin()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, sends)
    }

    @Test fun `failed websocket submission is returned without retry or artificial media progress`() = runTest {
        var attempts = 0
        val pacer = CallInputPacer(nowNanos = { currentTime * 1_000_000 })
        assertFalse(pacer.send { attempts++; false })
        assertEquals(1, attempts)
        assertEquals(0L, currentTime)
    }
}
