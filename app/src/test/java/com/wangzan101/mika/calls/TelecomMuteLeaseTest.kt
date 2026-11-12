package com.wangzan101.mika.calls

import org.junit.Assert.*
import org.junit.Test

class TelecomMuteLeaseTest {
    @Test fun baselineAndNewMuteRequireBothSources() {
        assertNull(TelecomMuteLease.fromBaseline(null, false))
        assertNull(TelecomMuteLease.fromBaseline(false, true))
        val lease = requireNotNull(TelecomMuteLease.fromBaseline(false, false))
        assertTrue(lease.needsMuteRequest)
        lease.markMuteRequested()
        assertFalse(lease.isConfirmed(true, true))
        assertFalse(lease.observeTelecomMute(true))
        assertFalse(lease.isConfirmed(true, false))
        assertTrue(lease.isConfirmed(true, true))
        assertEquals(TelecomMuteLease.Release.REQUEST_UNMUTE, lease.beginRelease(sameCall = true))
        assertFalse(lease.observeTelecomMute(false))
        assertEquals(TelecomMuteLease.Release.VERIFY_UNMUTED, lease.beginRelease(sameCall = true))
    }

    @Test fun initiallyMutedAndUserOverrideAreNeverChangedOnRelease() {
        val alreadyMuted = requireNotNull(TelecomMuteLease.fromBaseline(true, true))
        assertFalse(alreadyMuted.needsMuteRequest)
        assertTrue(alreadyMuted.isConfirmed(true, true))
        assertEquals(TelecomMuteLease.Release.PRESERVE, alreadyMuted.beginRelease(sameCall = true))

        val takeover = requireNotNull(TelecomMuteLease.fromBaseline(false, false))
        takeover.markMuteRequested()
        assertFalse(takeover.observeTelecomMute(false)) // Initial state, before acknowledgement.
        assertFalse(takeover.observeTelecomMute(true))
        assertTrue(takeover.observeTelecomMute(false))
        assertFalse(takeover.observeTelecomMute(true))
        assertFalse(takeover.isConfirmed(true, true))
        assertEquals(TelecomMuteLease.Release.VERIFY_OVERRIDE, takeover.beginRelease(sameCall = true))
    }

    @Test fun delayedAcknowledgementCanBeUndoneOnlyOnTheOriginalCall() {
        val stillCurrent = requireNotNull(TelecomMuteLease.fromBaseline(false, false))
        stillCurrent.markMuteRequested()
        assertEquals(TelecomMuteLease.Release.REQUEST_UNMUTE, stillCurrent.beginRelease(sameCall = true))

        val callChanged = requireNotNull(TelecomMuteLease.fromBaseline(false, false))
        callChanged.markMuteRequested()
        assertEquals(TelecomMuteLease.Release.VERIFY_UNMUTED, callChanged.beginRelease(sameCall = false))
        assertEquals(TelecomMuteLease.Release.VERIFY_UNMUTED, callChanged.beginRelease(sameCall = true))
    }

    @Test fun takeoverWaitsForTelecomCallbackAndPhysicalRestore() {
        val lease = requireNotNull(TelecomMuteLease.fromBaseline(false, false))
        lease.markMuteRequested()
        lease.observeTelecomMute(true)
        val release = lease.beginRelease(sameCall = true)
        assertFalse(release.confirmed(sameCall = true, telecomMuted = true, hardwareMuted = false))
        assertFalse(release.confirmed(sameCall = true, telecomMuted = false, hardwareMuted = true))
        assertFalse(release.confirmed(sameCall = true, telecomMuted = null, hardwareMuted = false))
        assertTrue(release.confirmed(sameCall = true, telecomMuted = false, hardwareMuted = false))
        assertTrue(release.confirmed(sameCall = false, telecomMuted = null, hardwareMuted = false))
    }

    @Test fun android14LegacySnapshotCannotOverrideNewMuteCallbackOrReleaseLease() {
        val state = TelecomMuteState(dedicatedMuteCallback = true)
        assertEquals(TelecomMuteState.Update.CHANGED, state.update(TelecomMuteState.Source.LEGACY_AUDIO, false))
        val lease = requireNotNull(TelecomMuteLease.fromBaseline(state.value, false))
        lease.markMuteRequested()
        fun report(source: TelecomMuteState.Source, muted: Boolean): Boolean =
            state.update(source, muted) == TelecomMuteState.Update.CHANGED && lease.observeTelecomMute(muted)

        assertFalse(report(TelecomMuteState.Source.MUTE_CALLBACK, true))
        assertTrue(lease.isConfirmed(state.value, true))
        assertFalse(report(TelecomMuteState.Source.LEGACY_AUDIO, false))
        assertEquals(true, state.value)
        assertTrue(lease.isConfirmed(state.value, true))
        assertTrue(report(TelecomMuteState.Source.MUTE_CALLBACK, false))
        assertFalse(lease.isConfirmed(state.value, false))
        assertEquals(TelecomMuteLease.Release.VERIFY_OVERRIDE, lease.beginRelease(sameCall = true))
    }

    @Test fun legacyUpdatesRemainAuthoritativeBeforeAndroid14AndSeedAgainOnRebind() {
        val legacy = TelecomMuteState(dedicatedMuteCallback = false)
        assertEquals(TelecomMuteState.Update.CHANGED, legacy.update(TelecomMuteState.Source.LEGACY_AUDIO, false))
        assertEquals(TelecomMuteState.Update.CHANGED, legacy.update(TelecomMuteState.Source.LEGACY_AUDIO, true))
        assertEquals(TelecomMuteState.Update.CHANGED, legacy.update(TelecomMuteState.Source.LEGACY_AUDIO, false))
        assertEquals(false, legacy.value)

        val modern = TelecomMuteState(dedicatedMuteCallback = true)
        modern.update(TelecomMuteState.Source.MUTE_CALLBACK, true)
        assertEquals(TelecomMuteState.Update.IGNORED_LEGACY, modern.update(TelecomMuteState.Source.LEGACY_AUDIO, false))
        modern.reset()
        assertEquals(TelecomMuteState.Update.CHANGED, modern.update(TelecomMuteState.Source.LEGACY_AUDIO, false))
        assertEquals(false, modern.value)
    }

    @Test fun shellReasonIsSpecificButNeverLeaksRawContent() {
        assertEquals("设备麦克风静音已解除，AI 已交还通话",
            shellAudioStopDiagnostic("Handset microphone was unmuted."))
        assertEquals("安卓停止向 AI 提供通话声音，已交还系统",
            shellAudioStopDiagnostic("Android silenced the cellular audio capture."))
        assertEquals("设备上行未路由至运营商通话，电话由系统处理",
            shellAudioStopDiagnostic("Uplink did not route to the telephony device (private diagnostic)."))
        assertNull(shellAudioStopDiagnostic("unknown private diagnostic"))
        assertNull(shellAudioStopDiagnostic("Call PCM pipe closed.")) // May be caused by normal app-side release.
    }
}
