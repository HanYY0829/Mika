package com.wangzan101.mika.calls

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelecomMuteStartupTest {
    @Test fun realIncomingInitializationUnmuteFinishesBeforeTheSingleAiMuteRequest() = runTest {
        // Relative to real Telecom ACTIVE. The two captured calls had one default-dialer
        // unmute at +151/+143 ms, then MODE_IN_CALL at +158/+146 ms, respectively.
        for ((startAt, unmuteAt, modeAt) in listOf(
            Triple(32L, 151L, 158L), Triple(29L, 143L, 146L),
            // getMode may expose Telecom's earlier selection before AudioService's logged application.
            Triple(0L, 151L, 0L), Triple(0L, 143L, 0L),
        )) {
            val born = currentTime
            var telecom = false; var hardware = false; var revision = 0L; var mode = false
            var activeLease: TelecomMuteLease? = null
            var handoffs = 0
            val requests = mutableListOf<Long>()
            val modeChange = launch { delay(modeAt); mode = true }
            val initialization = launch {
                delay(unmuteAt)
                telecom = false; hardware = false; revision++
                if (activeLease?.observeTelecomMute(false) == true) handoffs++
            }
            delay(startAt)
            val lease = awaitTelecomMuteBaseline({ true }, { currentTime }) {
                TelecomMuteBaseline(mode, telecom, hardware, revision)
            }
            activeLease = lease
            if (lease.needsMuteRequest) {
                lease.markMuteRequested(); requests += currentTime - born
                telecom = true; hardware = true; revision++
                lease.observeTelecomMute(true)
            }
            initialization.join(); modeChange.join()
            assertEquals("An initialization edge must not stop a freshly owned AI call", 0, handoffs)
            assertEquals(1, requests.size)
            assertTrue("Do not request mute before the real cellular audio mode", requests.single() >= modeAt)
            assertTrue(lease.isConfirmed(telecom, hardware))
        }
    }
    @Test fun authoritativeFalseReplaysResetStabilityInsteadOfUsingABlindDelay() = runTest {
        var revision = 0L
        launch { repeat(3) { delay(200L); revision++ } }
        val lease = awaitTelecomMuteBaseline({ true }, { currentTime }) { TelecomMuteBaseline(true, false, false, revision) }
        assertEquals(850L, currentTime)
        assertTrue(lease.needsMuteRequest)
    }

    @Test fun cellularModeMustRemainReadyAndMuteSourcesMustAgreeThroughoutTheWindow() = runTest {
        var state = TelecomMuteBaseline(true, false, false, 0)
        launch {
            delay(200L); state = state.copy(cellularModeReady = false)
            delay(100L); state = state.copy(cellularModeReady = true, hardwareMuted = null)
            delay(100L); state = state.copy(hardwareMuted = false)
        }
        awaitTelecomMuteBaseline({ true }, { currentTime }) { state }
        assertEquals(650L, currentTime)
    }

    @Test fun unmuteAfterConfirmedOwnershipHandsOffImmediatelyWithoutAnotherMuteRequest() = runTest {
        val lease = awaitTelecomMuteBaseline({ true }, { currentTime }) { TelecomMuteBaseline(true, false, false, 0) }
        var requests = 0
        lease.markMuteRequested(); requests++
        lease.observeTelecomMute(true)
        val before = currentTime
        assertTrue(lease.observeTelecomMute(false))
        assertEquals(before, currentTime)
        assertEquals(1, requests)
        assertFalse(lease.isConfirmed(true, true))
        assertEquals(TelecomMuteLease.Release.VERIFY_OVERRIDE, lease.beginRelease(true))
    }

    @Test fun alreadyMutedBaselineIsPreservedAndItsUnmuteDuringStartupIsNotReclaimed() = runTest {
        val preserved = awaitTelecomMuteBaseline({ true }, { currentTime }) { TelecomMuteBaseline(true, true, true, 0) }
        assertFalse(preserved.needsMuteRequest)
        assertEquals(TelecomMuteLease.Release.PRESERVE, preserved.beginRelease(true))
        var state = TelecomMuteBaseline(true, true, true, 0)
        launch { delay(75L); state = state.copy(telecomMuted = false, hardwareMuted = false, observationRevision = 1) }
        assertTrue(runCatching { awaitTelecomMuteBaseline({ true }, { currentTime }) { state } }.exceptionOrNull() is CallMediaFailure)
    }

    @Test fun replacedCallOrHelperCannotReachTheMuteRequest() = runTest {
        var current = true; var requested = false
        launch { delay(75L); current = false }
        assertTrue(runCatching {
            awaitTelecomMuteBaseline({ current }, { currentTime }) { TelecomMuteBaseline(true, false, false, 0) }
            requested = true
        }.exceptionOrNull() is CallMediaFailure)
        assertFalse(requested)
    }

    @Test fun unknownModeOrContinuousStartupActivityHasABoundedFailure() = runTest {
        val before = currentTime
        assertTrue(runCatching {
            awaitTelecomMuteBaseline({ true }, { currentTime }) { TelecomMuteBaseline(false, false, false, 0) }
        }.exceptionOrNull() is CallMediaFailure)
        assertEquals(1_500L, currentTime - before)
        val busyAt = currentTime
        assertTrue(runCatching {
            awaitTelecomMuteBaseline({ true }, { currentTime }) { TelecomMuteBaseline(true, false, false, currentTime) }
        }.exceptionOrNull() is CallMediaFailure)
        assertEquals(1_500L, currentTime - busyAt)
    }

    @Test fun cancellationStopsStartupPollingBeforeAnyMuteRequest() = runTest {
        var reads = 0; var requested = false
        val pending = launch {
            awaitTelecomMuteBaseline({ true }, { currentTime }) { reads++; TelecomMuteBaseline(false, false, false, 0) }
            requested = true
        }
        runCurrent(); pending.cancelAndJoin()
        val canceledAt = reads
        advanceTimeBy(2_000L); runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(canceledAt, reads)
        assertFalse(requested)
    }

}
