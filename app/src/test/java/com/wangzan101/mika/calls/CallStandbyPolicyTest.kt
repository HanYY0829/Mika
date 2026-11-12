package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStandbyPolicyTest {
    @Test fun visibleActivityBehindKeyguardIsNotAnInteractiveForeground() {
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = true, interactive = true, keyguardShowing = true))
        assertFalse(CallStandbyPolicy.interactiveForeground(visible = true, interactive = false, keyguardShowing = false))
        assertTrue(CallStandbyPolicy.interactiveForeground(visible = true, interactive = true, keyguardShowing = false))
    }

    @Test fun lockedIncomingRequiresActualStandbyAndItsNotificationAfterFirstUnlock() {
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(true, false, false, true))
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(true, false, true, false))
        assertFalse(CallStandbyPolicy.allowsAutomaticAnswer(false, false, true, true))
        assertTrue(CallStandbyPolicy.allowsAutomaticAnswer(true, false, true, true))
        assertTrue(CallStandbyPolicy.allowsAutomaticAnswer(true, true, false, false))
    }

    @Test fun stickyStandbyRequiresExplicitAutoAnswerAndVerifiedAudio() {
        val enabled = CallConfig(enabled = true, autoAnswer = true, audioVerified = true, credentialsConfigured = true)
        assertTrue(CallStandbyPolicy.optedIn(enabled, userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled, userUnlocked = false))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(autoAnswer = false), userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(audioVerified = false), userUnlocked = true))
        assertFalse(CallStandbyPolicy.optedIn(enabled.copy(credentialsConfigured = false), userUnlocked = true))
    }

    @Test fun activeCallWakeLockIsBoundedByConfiguredDurationAndCleanupAllowance() {
        assertEquals(130_000L, CallStandbyPolicy.activeWakeBudgetMs(120))
        assertEquals(610_000L, CallStandbyPolicy.activeWakeBudgetMs(Int.MAX_VALUE))
    }

    @Test fun foregroundRestoreWaitsForTheLateHelperAndDoesNotRetryAfterFailure() {
        val resume = CallStandbyResumeGate()
        assertFalse(resume.take(visible = true, eligible = false))
        assertTrue(resume.take(visible = true, eligible = true))
        assertFalse(resume.take(visible = true, eligible = false))
        assertFalse(resume.take(visible = true, eligible = true))
        assertFalse(resume.take(visible = false, eligible = true))
        assertTrue(resume.take(visible = true, eligible = true))
    }
}
