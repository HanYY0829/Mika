package com.wangzan101.mika.calls

import kotlinx.coroutines.flow.Flow

interface CallRealtimeSession : AutoCloseable {
    val events: Flow<VolcRealtimeEvent>
    val inputSampleRate: Int get() = VolcRealtimeClient.INPUT_SAMPLE_RATE
    val outputSampleRate: Int get() = VolcRealtimeClient.OUTPUT_SAMPLE_RATE
    val inputChunkBytes: Int get() = VolcRealtimeClient.INPUT_CHUNK_BYTES
    fun sendPcm16(bytes: ByteArray, byteCount: Int = bytes.size): Boolean
    fun sendGreeting(text: String): Boolean
    fun setMuted(muted: Boolean): Boolean
    override fun close()
}

interface CallRealtimeProvider {
    suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
    ): CallRealtimeSession
}

class VolcRealtimeProvider : CallRealtimeProvider {
    override suspend fun connect(
        profile: CallVoiceProfile,
        credentials: VolcRealtimeCredentials,
        systemPrompt: String,
    ): CallRealtimeSession {
        require(profile.protocol == CallVoiceProtocol.VOLC_DUPLEX) { "不支持的实时语音协议" }
        return VolcRealtimeClient(endpoint = profile.endpoint).connect(credentials, VolcRealtimeConfig(
            systemPrompt = systemPrompt,
            speaker = profile.speaker,
            model = profile.model,
        ))
    }
}
