package com.wangzan101.mika.voice

private val WAKE_PREFIX = Regex("^你好[，,。.!！?？：:\\s]*米卡[，,。.!！?？：:\\s]*")

/** Cloud ASR sometimes inserts punctuation inside the wake phrase. None of it is a command. */
internal fun commandAfterWake(transcript: String): String {
    var words = transcript.trim()
    while (true) {
        val rest = words.replaceFirst(WAKE_PREFIX, "").trim()
        if (rest == words) return words
        words = rest
    }
}

/** Ends a spoken command after speech stops; a hard limit still bounds a noisy room. */
internal class VoiceCommandEndpoint(private val startedAtMs: Long) {
    enum class End { ProviderSettled, Silence, TranscriptStable, NoSpeech, Limit }

    private val levels = FloatArray(40)
    private var count = 0
    private var next = 0
    private var lastVoiceAt = 0L
    private var lastTextAt = 0L
    private var partial = ""
    private var settledText = ""
    var peak = 0f
        private set

    @Synchronized fun level(value: Float, nowMs: Long) {
        peak = maxOf(peak, value)
        levels[next] = value
        next = (next + 1) % levels.size
        count = minOf(count + 1, levels.size)
        val floor = levels.copyOf(count).sorted()[count / 5]
        if (value >= 0.3f || value >= maxOf(0.12f, floor * 2.2f)) lastVoiceAt = nowMs
    }

    @Synchronized fun partial(command: String, nowMs: Long) {
        val text = command.trim()
        if (text.isNotEmpty() && text != partial) {
            partial = text
            lastTextAt = nowMs
        }
    }

    @Synchronized fun providerSettled(command: String) {
        val text = command.trim()
        if (text.isNotEmpty()) settledText = text
    }

    @Synchronized fun end(nowMs: Long): End? {
        val elapsed = nowMs - startedAtMs
        if (elapsed >= MAX_MS) return End.Limit
        if (elapsed < MIN_MS) return null
        // A definite ASR sentence is stable, but it can be only the first clause.
        // Require local silence too, and allow extra time after unfinished wording.
        if (partial.isNotEmpty() && settledText == partial && !isUnfinishedPhrase() &&
            lastVoiceAt > 0 && nowMs - lastVoiceAt >= PROVIDER_SILENCE_MS &&
            nowMs - lastTextAt >= PROVIDER_TEXT_SETTLE_MS) return End.ProviderSettled
        // The wake phrase itself can register as speech. Wait for an actual command transcript
        // before silence can finish the recording, so the user has time to see the wake cue.
        if (lastTextAt > 0 && lastVoiceAt > 0 && nowMs - lastVoiceAt >= silenceMs() &&
            nowMs - lastTextAt >= TEXT_SETTLE_MS) return End.Silence
        if (lastTextAt > 0 && nowMs - lastTextAt >= stableMs() &&
            (lastVoiceAt == 0L || nowMs - lastVoiceAt >= TEXT_SETTLE_MS)) return End.TranscriptStable
        if (lastTextAt == 0L && elapsed >= NO_SPEECH_MS) return End.NoSpeech
        return null
    }

    private fun silenceMs(): Long = when {
        isUnfinishedPhrase() -> UNFINISHED_SILENCE_MS
        partial.lastOrNull()?.let { it in "。！？?!" } == true -> SENTENCE_END_SILENCE_MS
        else -> SILENCE_MS
    }

    private fun stableMs(): Long = if (isUnfinishedPhrase()) UNFINISHED_STABLE_MS else PARTIAL_STABLE_MS

    private fun isUnfinishedPhrase(): Boolean = UNFINISHED_SUFFIX.containsMatchIn(partial)

    companion object {
        const val MAX_MS = 15_000L
        private const val MIN_MS = 1_000L
        private const val SENTENCE_END_SILENCE_MS = 1_000L
        private const val SILENCE_MS = 1_300L
        private const val UNFINISHED_SILENCE_MS = 1_900L
        private const val PARTIAL_STABLE_MS = 1_500L
        private const val UNFINISHED_STABLE_MS = 2_100L
        private const val TEXT_SETTLE_MS = 550L
        private const val PROVIDER_SILENCE_MS = 900L
        private const val PROVIDER_TEXT_SETTLE_MS = 350L
        private const val NO_SPEECH_MS = 9_000L
        private val UNFINISHED_SUFFIX = Regex("(?:然后|接着|还有|以及|或者|如果|因为|但是|先|再|把|给|帮我|请|我想|我要|我需要|比如|例如)[，,：:]?$")
    }
}
