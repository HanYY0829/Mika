package com.wangzan101.mika.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
class CallForegroundLeaseAwaitTest {
    @Test fun outgoingWaitsForActualAcknowledgementAlthoughIncomingUiFlagIsAlreadyFalse() = runTest {
        val incomingUiStarting = MutableStateFlow(false)
        var actualStarting = true
        var serviceReady = false
        val waiting = async { awaitCallForegroundReady({ actualStarting }, { serviceReady }) }
        runCurrent()
        assertFalse(incomingUiStarting.value)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(100L)
        serviceReady = true
        actualStarting = false
        runCurrent()
        assertTrue(waiting.await())
        assertEquals(100L, currentTime)
    }

    @Test fun failedAcknowledgementReturnsFalseWithoutWaitingForTheFullTimeout() = runTest {
        var starting = true
        launch { delay(75L); starting = false }
        assertFalse(awaitCallForegroundReady({ starting }, { false }))
        assertEquals(75L, currentTime)
    }

    @Test fun cancellationStopsPollingAndNeverRunsTheReadyCheck() = runTest {
        var polls = 0
        var readyChecks = 0
        val waiting = async {
            awaitCallForegroundReady({ polls++; true }, { readyChecks++; true })
        }
        runCurrent()
        assertEquals(1, polls)
        waiting.cancelAndJoin()
        advanceTimeBy(6_000L)
        runCurrent()
        assertTrue(waiting.isCancelled)
        assertEquals(1, polls)
        assertEquals(0, readyChecks)
    }

    @Test fun missingAcknowledgementIsBoundedAndCannotReportReadyFromAStaleService() = runTest {
        var readyChecks = 0
        assertFalse(awaitCallForegroundReady({ true }, { readyChecks++; true }))
        assertEquals(5_250L, currentTime)
        assertEquals(0, readyChecks)
    }

    @Test fun stoppedOrNonReadyServiceFailsImmediatelyEvenWithNoStartInFlight() = runTest {
        assertFalse(awaitCallForegroundReady({ false }, { false }))
        assertEquals(0L, currentTime)
        assertTrue(awaitCallForegroundReady({ false }, { true }))
        assertEquals(0L, currentTime)
    }
}
