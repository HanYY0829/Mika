package com.wangzan101.mika.calls

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallAudioOpenGateTest {
    @Test fun transientNotReadyCanOpenWithinTheSameCall() = runTest {
        val starts = mutableListOf<Long>()
        val bridge = Any()
        val actual = awaitCallAudioOpen({ true }, { currentTime }) {
            starts += currentTime
            if (starts.size < 4) null else bridge
        }
        assertSame(bridge, actual)
        assertEquals(listOf(0L, 50L, 100L, 150L), starts)
    }

    @Test fun noAttemptMayStartAtOrAfterTheDeadline() = runTest {
        val starts = mutableListOf<Long>()
        val failure = runCatching {
            awaitCallAudioOpen<Any>({ true }, { currentTime }) { starts += currentTime; null }
        }.exceptionOrNull()
        assertTrue(failure is CallMediaFailure)
        assertEquals(1_500L, currentTime)
        assertEquals((0L..1_450L step 50L).toList(), starts)
    }

    @Test fun timeSpentInARejectedAttemptDoesNotBuyAnExtraRetry() = runTest {
        var calls = 0
        val failure = runCatching {
            awaitCallAudioOpen<Any>({ true }, { currentTime }) { calls++; delay(1_500L); null }
        }.exceptionOrNull()
        assertTrue(failure is CallMediaFailure)
        assertEquals(1, calls)
    }

    @Test fun anOwnedSuccessfulAttemptMayCompleteAfterTheRetryStartWindow() = runTest {
        val bridge = Any()
        var calls = 0
        val actual = awaitCallAudioOpen({ true }, { currentTime }) {
            calls++
            delay(1_600L)
            bridge
        }
        assertSame(bridge, actual)
        assertEquals(1, calls)
        assertEquals(1_600L, currentTime)
    }

    @Test fun aDifferentCallOrLostMuteCannotReceiveTheNextAttempt() = runTest {
        var current = true
        var calls = 0
        launch { delay(75L); current = false }
        val failure = runCatching {
            awaitCallAudioOpen<Any>({ current }, { currentTime }) { calls++; null }
        }.exceptionOrNull()
        assertTrue(failure is CallMediaFailure)
        assertEquals(2, calls)
    }

    @Test fun identityChangedDuringTheSuccessfulAttemptIsNotReportedAsReady() = runTest {
        var current = true
        var calls = 0
        val failure = runCatching {
            awaitCallAudioOpen({ current }, { currentTime }) { calls++; current = false; Any() }
        }.exceptionOrNull()
        assertTrue(failure is CallMediaFailure)
        assertEquals(1, calls)
    }

    @Test fun cancellationDuringTheRetryWaitCannotStartAnotherOpen() = runTest {
        var calls = 0
        val opening = async {
            awaitCallAudioOpen<Any>({ true }, { currentTime }) { calls++; null }
        }
        runCurrent()
        assertEquals(1, calls)
        opening.cancelAndJoin()
        assertTrue(opening.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun uncancellableBinderCompletionCannotTurnCancellationIntoSuccess() = runTest {
        val reply = CompletableDeferred<Unit>()
        var completed = false
        var calls = 0
        val opening = async {
            awaitCallAudioOpen({ true }, { currentTime }) {
                calls++
                withContext(NonCancellable) { reply.await(); Any() }
            }
            completed = true
        }
        runCurrent()
        opening.cancel()
        reply.complete(Unit)
        opening.join()
        assertTrue(opening.isCancelled)
        assertFalse(completed)
        assertEquals(1, calls)
    }

    @Test fun permanentErrorsAndDirectCancellationPropagateWithoutRetry() = runTest {
        for (fault in listOf(IOException("synthetic permanent IPC failure"), CancellationException("synthetic cancellation"))) {
            var calls = 0
            val actual = runCatching {
                awaitCallAudioOpen<Any>({ true }, { currentTime }) { calls++; throw fault }
            }.exceptionOrNull()
            assertSame(fault, actual)
            assertEquals(1, calls)
        }
    }
}
