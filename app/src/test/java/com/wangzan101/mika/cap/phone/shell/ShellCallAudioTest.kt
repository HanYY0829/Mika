package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallAudioTest {
    @Test
    fun explicitlySelectedBackendNeverFallsBackToAnotherRoute() {
        assertTrue(ShellCallAudio.candidates(ShellCallAudio.DIRECT, false, true).isEmpty())
        assertTrue(ShellCallAudio.candidates(ShellCallAudio.REDIRECT, true, false).isEmpty())
        assertEquals(listOf(ShellCallAudio.DIRECT, ShellCallAudio.REDIRECT),
            ShellCallAudio.candidates(ShellCallAudio.AUTO, true, true))
    }

    @Test
    fun capturePeakDetectsQuietAndSignedLittleEndianSamples() {
        assertEquals(0, ShellCallAudio.pcmPeak(ByteArray(640), 640))
        assertEquals(32768, ShellCallAudio.pcmPeak(byteArrayOf(0, 0x80.toByte(), 10, 0), 4))
        assertEquals(256, ShellCallAudio.pcmPeak(byteArrayOf(0, 1, 0x7f), 3))
    }

    @Test
    fun continuousFreshFrameworkSilenceTripsAtTwoSeconds() {
        val capture = ShellCallAudio.CaptureSilenceDiagnostics()
        for (atMs in 0L until 2_000L step 100L) assertFalse(capture.observe(true, atMs))
        assertFalse(capture.observe(true, 1_999L))
        assertEquals(1_999L, capture.maxMs)
        assertTrue(capture.observe(true, 2_000L))
        assertEquals(2_000L, capture.maxMs)
    }

    @Test
    fun normalAndUnknownObservationsBreakSilencingWithoutExtendingTheReportedMaximum() {
        val capture = ShellCallAudio.CaptureSilenceDiagnostics()
        for (atMs in 0L..1_800L step 100L) assertFalse(capture.observe(true, atMs))
        assertFalse(capture.observe(false, 1_900L))
        for (atMs in 2_000L..3_700L step 100L) assertFalse(capture.observe(true, atMs))
        assertFalse(capture.observe(null, 3_800L))
        for (atMs in 3_900L..5_700L step 100L) assertFalse(capture.observe(true, atMs))
        for (atMs in 5_800L..10_000L step 100L) assertFalse(capture.observe(false, atMs))
        assertFalse(capture.observe(null, 20_000L))
        assertEquals(1_800L, capture.maxMs)
    }

    @Test
    fun missingPollsCannotTurnOldSilencingIntoAContinuousInterval() {
        val capture = ShellCallAudio.CaptureSilenceDiagnostics()
        for (atMs in 0L..1_900L step 100L) assertFalse(capture.observe(true, atMs))
        for (atMs in 2_301L..4_201L step 100L) assertFalse(capture.observe(true, atMs))
        assertEquals(1_900L, capture.maxMs)
        assertTrue(capture.observe(true, 4_301L))
        assertEquals(2_000L, capture.maxMs)
    }

    @Test
    fun playbackDiagnosticsTracksPendingZeroWritesAndFreezesAtStop() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertEquals(-1L, stats.lastWriteAgeMs(0L))
        stats.recordWrite(0, 0L)
        stats.recordWrite(0, 6L)
        assertEquals(2L, stats.writeZeroCount)
        assertEquals(12L, stats.zeroMaxMs(12L))
        stats.recordWrite(480, 15L)
        assertEquals(15L, stats.zeroMaxMs(45L))
        assertEquals(30L, stats.lastWriteAgeMs(45L))

        stats.recordWrite(0, 50L)
        stats.stop(71L)
        assertEquals(3L, stats.writeZeroCount)
        assertEquals(21L, stats.zeroMaxMs(10_000L))
        assertEquals(56L, stats.lastWriteAgeMs(10_000L))
    }

    @Test
    fun flushingOrLosingTheRouteDoesNotExtendTheZeroWriteStreak() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        stats.recordWrite(0, 100L)
        stats.endZeroStreak(114L)
        stats.recordWrite(0, 800L)
        stats.recordWrite(960, 811L)
        assertEquals(2L, stats.writeZeroCount)
        assertEquals(14L, stats.zeroMaxMs(900L))
        stats.recordFlush(21L)
        stats.recordFlush(12L)
        assertEquals(21L, stats.flushMaxMs)
    }

    @Test
    fun pendingUplinkStallRequiresContinuousZeroWritesRatherThanAQuietCall() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertFalse(stats.pendingWriteStalled(10_000L))
        stats.recordWrite(960, 10_000L)
        assertFalse(stats.pendingWriteStalled(30_000L))

        stats.recordWrite(0, 30_000L)
        stats.recordWrite(0, 31_499L)
        assertFalse(stats.pendingWriteStalled(31_499L))
        stats.recordWrite(0, 31_500L)
        assertTrue(stats.pendingWriteStalled(31_500L))
    }

    @Test
    fun actualProgressAndDiscardedPlaybackCannotCarryAStallIntoTheNextReply() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        stats.recordWrite(0, 500L)
        stats.recordWrite(2, 1_999L)
        stats.recordWrite(0, 2_000L)
        assertFalse(stats.pendingWriteStalled(3_499L))

        stats.endZeroStreak(3_499L) // Flush or loss of the confirmed route.
        assertFalse(stats.pendingWriteStalled(9_000L))
        stats.recordWrite(0, 9_000L)
        assertFalse(stats.pendingWriteStalled(9_001L))
        stats.stop(10_500L)
        assertFalse(stats.pendingWriteStalled(20_000L))
        assertEquals(1_500L, stats.zeroMaxMs(20_000L))
    }

    @Test
    fun playbackHeadIsAnUnsignedPositionAndCanBeInvalidatedAfterFlush() {
        val stats = ShellCallAudio.PlaybackDiagnostics()
        assertEquals(-1L, stats.playbackHeadFrames)
        stats.recordPlaybackHead(Int.MIN_VALUE)
        assertEquals(0x8000_0000L, stats.playbackHeadFrames)
        stats.resetPlaybackHead()
        assertEquals(-1L, stats.playbackHeadFrames)
        stats.recordPlaybackHead(0)
        assertEquals(0L, stats.playbackHeadFrames)
    }
}
