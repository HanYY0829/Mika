package com.wangzan101.mika.voice

import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn

/** Waiting tasks take voice answers; a completed voice conversation stays available for follow-ups. */
internal sealed interface VoiceConversationRoute {
    data object New : VoiceConversationRoute
    data class Continue(val conversationId: String) : VoiceConversationRoute
    data class Answer(val runKey: String) : VoiceConversationRoute
    data class Steer(val conversationId: String) : VoiceConversationRoute
    data object NeedsConfirmation : VoiceConversationRoute
    data object NeedsTakeover : VoiceConversationRoute
}

internal fun voiceRoute(conversationId: String?, runKey: String?, state: TaskState?, followUp: Boolean = false): VoiceConversationRoute {
    if (conversationId == null) return VoiceConversationRoute.New
    if (runKey == null) return if (followUp) VoiceConversationRoute.Continue(conversationId) else VoiceConversationRoute.New
    return when (state) {
        is TaskState.WaitingInput -> VoiceConversationRoute.Answer(runKey)
        TaskState.Idle -> if (followUp) VoiceConversationRoute.Continue(conversationId) else VoiceConversationRoute.New
        is TaskState.Running, is TaskState.Held -> VoiceConversationRoute.Steer(conversationId)
        is TaskState.WaitingConfirmation -> VoiceConversationRoute.NeedsConfirmation
        is TaskState.WaitingTakeover -> VoiceConversationRoute.NeedsTakeover
        else -> if (followUp) VoiceConversationRoute.Continue(conversationId) else VoiceConversationRoute.New
    }
}

/** Saying “新任务” explicitly breaks the voice conversation, with or without a command after it. */
internal fun newVoiceTaskText(words: String): String? {
    val prefix = Regex("^(?:(?:开始|开启|创建|新开)(?:一个)?)?(?:新任务|新的任务|新对话|新的对话)[，,。.!！?？：:\\s]*")
    val match = prefix.find(words.trim()) ?: return null
    return words.trim().substring(match.range.last + 1).trim()
}

internal fun voiceConversationId(
    current: CurrentChat?, savedId: String?, activeId: String?, savedAvailable: Boolean,
    openFollowUp: Boolean, forceNew: Boolean,
): String? {
    val openId = (current as? CurrentChat.Open)?.id
    return when {
        forceNew || current is CurrentChat.Fresh -> null
        activeId != null -> activeId
        openId != null && openId != savedId && openFollowUp -> openId
        savedAvailable -> savedId
        openFollowUp -> openId
        else -> null
    }
}

/** The agent can finish a run with a question instead of entering WaitingInput. Keep that spoken reply in the same chat. */
internal fun isVoiceFollowUp(turn: Turn?, updatedAt: Long, now: Long, words: String): Boolean {
    if (turn == null || turn.status !in setOf("succeeded", "unverified")) return false
    if (now < updatedAt || now - updatedAt > 2 * 60 * 60 * 1000L) return false
    if (turn.result == "partial") return true
    val reply = turn.outcome.takeLast(240)
    if (reply.contains('？') || reply.contains('?')) return true
    if (Regex("请(?:你|您)?(?:告诉|回复|提供|确认|选择|补充)|需要(?:你|您)(?:告诉|回复|提供|确认|选择|补充)|等(?:你|您)(?:回复|确认|选择)").containsMatchIn(reply)) return true
    return Regex("^(?:好的?|嗯|是的?|对的?|不是|不对|可以|不可以|行|不行|继续|接着|刚才|那个|这个|上一|按你说的|就按|就这样|我选|选择|第[一二三四五六七八九十])").containsMatchIn(words.trim())
}
