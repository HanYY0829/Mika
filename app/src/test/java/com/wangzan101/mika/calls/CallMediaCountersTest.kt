package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CallMediaCountersTest {
    @Test fun summarizesSignedPcmAndTimingWithoutRetainingFrames() {
        val counters = CallMediaCounters()
        counters.inputAccepted(pcm(200, -200), 100_000_000L)
        assertEquals(0L, counters.snapshot()["input_first_rms_over_200_frame"])
        assertEquals(0L, counters.snapshot()["input_last_rms_over_200_frame"])
        counters.inputAccepted(pcm(0, 400), 105_000_000L)
        counters.inputAccepted(pcm(-32768), 115_000_000L)
        counters.inputAccepted(pcm(0, 1), 136_000_000L)

        val result = counters.snapshot()
        assertEquals(4L, result["input_frames_accepted"])
        assertEquals(14L, result["input_bytes_accepted"])
        assertEquals(32768L, result["input_peak_max"])
        assertEquals(2L, result["input_rms_over_200_frames"]) // Exactly 200 does not qualify.
        assertEquals(2L, result["input_first_rms_over_200_frame"])
        assertEquals(3L, result["input_last_rms_over_200_frame"])
        assertEquals(21_000L, result["input_max_gap_us"])
        assertEquals(1L, result["input_under_10ms_gap_frames"]) // The first frame and exactly 10 ms do not qualify.
    }

    @Test fun recordsProviderEventCountsWithoutTextOrPcmContent() {
        val counters = CallMediaCounters()
        counters.eventReceived(VolcRealtimeEvent.UserSpeaking)
        counters.eventReceived(VolcRealtimeEvent.UserTranscript("private test input", false))
        counters.eventReceived(VolcRealtimeEvent.UserTranscript("private test input", true))
        counters.eventReceived(VolcRealtimeEvent.UserSpeechEnded)
        counters.eventReceived(VolcRealtimeEvent.AssistantAudioStarted)
        counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(12)))
        counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(8)))
        counters.eventReceived(VolcRealtimeEvent.AssistantAudioEnded)

        val result = counters.snapshot()
        assertEquals(1L, result["user_speaking_events"])
        assertEquals(1L, result["user_speech_ended_events"])
        assertEquals(1L, result["user_transcript_final_events"])
        assertEquals(1L, result["user_transcript_partial_events"])
        assertEquals(1L, result["assistant_audio_started_events"])
        assertEquals(2L, result["assistant_audio_events"])
        assertEquals(20L, result["assistant_audio_bytes"])
    }

    @Test fun distinguishesReceivedQueuedWrittenAndShellAcceptedPlayback() {
        val counters = CallMediaCounters()
        assertNull(counters.playbackDiagnostic())
        counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(1920)))
        counters.outputQueued(1920)
        counters.outputWritten(960)
        counters.outputQueued(960)
        counters.outputRejected()
        counters.outputShellAccepted(640)
        val result = counters.snapshot()
        assertEquals(1920L, result["assistant_audio_bytes"])
        assertEquals(1920L, result["playback_max_queued_bytes"])
        assertEquals(960L, result["playback_written_bytes"])
        assertEquals(1L, result["playback_rejected_packets"])
        assertEquals(640L, result["playback_shell_accepted_bytes"])
    }

    @Test fun laterUnansweredSpeechDoesNotInheritAudioFromThePreviousTurn() {
        val counters = CallMediaCounters(measureInputSignal = false)
        assertNull(counters.recordReport())
        counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(640))) // greeting is not a reply to the caller
        assertEquals(CallAudioReport.Turn.NONE, counters.recordReport()?.lastObservedTurn)
        repeat(2) {
            counters.eventReceived(VolcRealtimeEvent.UserSpeaking)
            counters.eventReceived(VolcRealtimeEvent.UserTranscript("private text", isFinal = true))
            counters.eventReceived(VolcRealtimeEvent.Audio(ByteArray(640)))
        }
        counters.eventReceived(VolcRealtimeEvent.UserSpeaking)
        assertEquals(CallAudioReport.Turn.SPEECH_DETECTED, counters.recordReport()?.lastObservedTurn)
        counters.eventReceived(VolcRealtimeEvent.UserTranscript("last private text", isFinal = true))
        val report = checkNotNull(counters.recordReport())
        assertEquals(3L, report.completeTranscripts)
        assertEquals(CallAudioReport.Turn.TRANSCRIPT_RECEIVED, report.lastObservedTurn)
        assertEquals(1920L, report.receivedAudioBytes)
        assertNull(report.deviceAcceptedBytes)
        assertFalse(report.supportSummary().contains("private"))
    }

    @Test fun inputAfterTheLatestReplyIsMeasuredSeparatelyFromEarlierSpeechAndRecognitionFailures() {
        val counters = CallMediaCounters()
        counters.inputAccepted(pcm(400, -400), 100_000_000L)
        counters.eventReceived(VolcRealtimeEvent.AssistantAudioEnded)
        counters.inputAccepted(pcm(500, -500), 120_000_000L)
        counters.eventReceived(VolcRealtimeEvent.AssistantAudioEnded)
        counters.inputAccepted(pcm(0, 0), 140_000_000L)
        counters.inputAccepted(pcm(450, -450), 160_000_000L)
        counters.eventReceived(VolcRealtimeEvent.InputAudioCommitted)
        counters.eventReceived(VolcRealtimeEvent.UserTranscriptionFailed)
        counters.eventReceived(VolcRealtimeEvent.UserTranscriptionEmpty)
        counters.outputShellAccepted(0, captureSilencedMs = 2_000L)

        val report = checkNotNull(counters.recordReport())
        assertEquals(2L, report.inputFramesAfterLastReply)
        assertEquals(1L, report.inputSignalFramesAfterLastReply)
        assertEquals(2_000L, report.captureSilencedMaxMs)
        assertEquals(1L, report.recognitionFailures)
        assertEquals(1L, report.emptyRecognitions)
        assertEquals(1L, report.providerInputCommits)
        assertEquals(0L, report.completeTranscripts)
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2) { index ->
        val sample = samples[index / 2]
        (if (index % 2 == 0) sample else sample shr 8).toByte()
    }
}
