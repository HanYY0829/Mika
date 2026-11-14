package com.wangzan101.mika.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandEndpointTest {
    @Test fun stopsAfterSpeechEndsButKeepsBriefPauses() {
        val end = VoiceCommandEndpoint(1_000)
        end.level(0.6f, 1_200)
        end.level(0.04f, 2_400)
        assertNull(end.end(2_400))
        end.level(0.5f, 2_600)
        end.partial("打开微信", 2_600)
        assertNull(end.end(3_800))
        assertEquals(VoiceCommandEndpoint.End.Silence, end.end(3_900))
    }

    @Test fun aStableCommandTranscriptCanFinishQuietSpeech() {
        val end = VoiceCommandEndpoint(1_000)
        end.partial("帮我查天气", 1_800)
        assertNull(end.end(3_200))
        assertEquals(VoiceCommandEndpoint.End.TranscriptStable, end.end(3_300))
    }

    @Test fun sentenceEndingFinishesSoonerButAnUnfinishedPhraseWaitsForContinuation() {
        val complete = VoiceCommandEndpoint(1_000)
        complete.level(0.6f, 1_500)
        complete.partial("现在几点？", 1_500)
        assertNull(complete.end(2_499))
        assertEquals(VoiceCommandEndpoint.End.Silence, complete.end(2_500))

        val unfinished = VoiceCommandEndpoint(1_000)
        unfinished.level(0.6f, 1_500)
        unfinished.partial("打开微信，然后", 1_500)
        assertNull(unfinished.end(2_800))
        unfinished.level(0.6f, 3_100)
        unfinished.partial("打开微信，然后搜索天气", 3_100)
        assertNull(unfinished.end(4_399))
        assertEquals(VoiceCommandEndpoint.End.Silence, unfinished.end(4_400))
    }

    @Test fun providerSentenceBoundaryNeedsLocalSilenceAndDoesNotCutAnUnfinishedClause() {
        val end = VoiceCommandEndpoint(1_000)
        end.level(0.6f, 1_500)
        end.partial("现在几点", 1_500)
        end.providerSettled("现在几点")
        assertNull(end.end(2_399))
        assertEquals(VoiceCommandEndpoint.End.ProviderSettled, end.end(2_400))

        val unfinished = VoiceCommandEndpoint(1_000)
        unfinished.level(0.6f, 1_500)
        unfinished.partial("打开微信然后", 1_500)
        unfinished.providerSettled("打开微信然后")
        assertNull(unfinished.end(2_400))
        assertNull(unfinished.end(2_800))
        assertEquals(VoiceCommandEndpoint.End.Silence, unfinished.end(3_400))
    }

    @Test fun silenceAndNoiseAreBounded() {
        val quiet = VoiceCommandEndpoint(1_000)
        assertEquals(VoiceCommandEndpoint.End.NoSpeech, quiet.end(10_000))
        val noisy = VoiceCommandEndpoint(1_000)
        for (time in 1_000L..16_000L step 500) noisy.level(0.5f, time)
        assertEquals(VoiceCommandEndpoint.End.Limit, noisy.end(16_000))
    }

    @Test fun wakeWordAloneDoesNotCutOffTheCommandWindow() {
        val end = VoiceCommandEndpoint(1_000)
        end.level(0.5f, 1_200) // tail of the wake phrase, before any command words
        assertNull(end.end(4_200))
        assertNull(end.end(7_000))
        assertEquals(VoiceCommandEndpoint.End.NoSpeech, end.end(10_000))
    }

    @Test fun cloudPunctuationInWakePhraseIsNotACommand() {
        assertEquals("", commandAfterWake("你好。米卡。"))
        assertEquals("", commandAfterWake("你好，米卡！你好米卡。"))
        assertEquals("现在几点", commandAfterWake("你好。米卡。现在几点"))
        assertEquals("打开微信", commandAfterWake("你好米卡，打开微信"))
    }
}
