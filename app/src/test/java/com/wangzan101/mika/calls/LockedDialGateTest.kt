package com.wangzan101.mika.calls

import com.wangzan101.mika.calls.LockedDialGate.Decision
import com.wangzan101.mika.calls.LockedDialGate.Rejection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedDialGateTest {
    @Test fun waitsForSecureLockAndClaimsOnlyTheConfirmedSnapshot() {
        var time = 100L
        val gate = LockedDialGate { time }
        val questions = mutableListOf(" 几点停止入场？ ")
        val ticket = checkNotNull(gate.arm(" +1 (555) 123-4567 ", "sim-1",
            CallTaskBrief(" 问几点关门 ", questions, " 节假日 ", " 不预约 ")))
        assertEquals(45_100L, ticket.expiresAtMs)
        assertEquals(Decision.WaitingForLock, gate.poll(ticket, deviceLocked = false, eligible = true))
        questions.clear()
        assertNull(gate.arm(OTHER_NUMBER, "sim-2", TASK))

        time = ticket.expiresAtMs - 1
        val result = gate.poll(ticket, deviceLocked = true, eligible = true) as Decision.Claimed
        assertEquals(NUMBER, result.request.number)
        assertEquals("sim-1", result.request.simId)
        assertEquals(CallTaskBrief("问几点关门", listOf("几点停止入场？"), "节假日", "不预约"), result.request.task)
        assertFalse(result.toString().contains(NUMBER))
        assertEquals(Decision.Rejected(Rejection.NOT_PENDING), gate.poll(ticket, deviceLocked = true, eligible = true))
    }

    @Test fun expiryAndEligibilityFailureAreTerminalEvenIfTheDeviceLocksLater() {
        var time = 100L
        val gate = LockedDialGate { time }
        val expired = checkNotNull(gate.arm(NUMBER, "sim-1", TASK))
        time = expired.expiresAtMs
        assertEquals(Decision.Rejected(Rejection.EXPIRED), gate.poll(expired, deviceLocked = true, eligible = true))
        val blocked = checkNotNull(gate.arm(NUMBER, "sim-1", TASK))
        assertEquals(Decision.Rejected(Rejection.INELIGIBLE), gate.poll(blocked, deviceLocked = false, eligible = false))
        assertEquals(Decision.Rejected(Rejection.NOT_PENDING), gate.poll(blocked, deviceLocked = true, eligible = true))
    }

    @Test fun anEligibleSystemQueryCannotConsumeTheRemainingSubmissionWindow() {
        var time = 100L
        val gate = LockedDialGate { time }
        val ticket = checkNotNull(gate.arm(NUMBER, "sim-1", TASK))
        time = ticket.expiresAtMs - 1
        assertTrue(gate.poll(ticket, deviceLocked = true, eligible = true) is Decision.Claimed)
        assertNull(gate.submissionRejection(ticket) { true })
        val rejection = gate.submissionRejection(ticket) {
            // Simulate a successful Keyguard/Telecom Binder read crossing the deadline.
            time += 2
            true
        }
        assertEquals(Rejection.EXPIRED, rejection)
        assertEquals(Rejection.EXPIRED, gate.submissionRejection(ticket) { error("Must not query after expiry") })
    }

    @Test fun cancelAndFreshProcessCannotReplayOrDisarmAnotherTicket() {
        val gate = LockedDialGate { 100L }
        val original = checkNotNull(gate.arm(NUMBER, "sim-1", TASK))
        val freshProcess = LockedDialGate { 100L }
        assertEquals(Decision.Rejected(Rejection.NOT_PENDING), freshProcess.poll(original, deviceLocked = true, eligible = true))
        assertTrue(gate.cancel(original))
        assertFalse(gate.cancel(original))
        val replacement = checkNotNull(gate.arm(OTHER_NUMBER, "sim-2", TASK))
        assertNotEquals(original.id, replacement.id)
        assertFalse(gate.cancel(original))
        assertEquals(Decision.Rejected(Rejection.NOT_PENDING), gate.poll(original, deviceLocked = true, eligible = true))
        assertEquals(OTHER_NUMBER, (gate.poll(replacement, deviceLocked = true, eligible = true) as Decision.Claimed).request.number)
    }

    @Test fun rejectsControlCodesUnresolvedSimAndMissingGoalWithoutArming() {
        val gate = LockedDialGate { 100L }
        assertNull(gate.arm("*#06#", "sim-1", TASK))
        assertNull(gate.arm(NUMBER, "default", TASK))
        assertNull(gate.arm(NUMBER, "sim-1", CallTaskBrief("  ")))
        val ticket = checkNotNull(gate.arm(NUMBER, "sim-1", TASK))
        assertEquals(Decision.WaitingForLock, gate.poll(ticket, deviceLocked = false, eligible = true))
    }

    @Test fun simultaneousLockObserversGetAtMostOneClaim() {
        val gate = LockedDialGate { 100L }
        val ticket = checkNotNull(gate.arm(NUMBER, "sim-1", TASK))
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val decisions = List(4) {
                workers.submit<Decision> {
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.poll(ticket, deviceLocked = true, eligible = true)
                }
            }
            start.countDown()
            val results = decisions.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is Decision.Claimed })
            assertEquals(3, results.count { it == Decision.Rejected(Rejection.NOT_PENDING) })
        } finally {
            workers.shutdownNow()
        }
    }

    private companion object {
        const val NUMBER = "+15551234567"
        const val OTHER_NUMBER = "+15551234568"
        val TASK = CallTaskBrief("确认店家营业时间")
    }
}
