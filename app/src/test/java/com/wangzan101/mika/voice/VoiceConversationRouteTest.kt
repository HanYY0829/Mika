package com.wangzan101.mika.voice

import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceConversationRouteTest {
    @Test fun voiceSessionContinuesUntilExplicitlyReset() {
        assertEquals("voice-chat", voiceConversationId(null, "voice-chat", null, true, false, false))
        assertEquals("voice-chat", voiceConversationId(CurrentChat.Open("other-chat"), "voice-chat", null, true, false, false))
        assertEquals("other-chat", voiceConversationId(CurrentChat.Open("other-chat"), "voice-chat", null, true, true, false))
        assertEquals("waiting-chat", voiceConversationId(null, "voice-chat", "waiting-chat", true, false, false))
        assertEquals(null, voiceConversationId(CurrentChat.Fresh, "voice-chat", "waiting-chat", true, false, false))
        assertEquals(null, voiceConversationId(null, "voice-chat", "waiting-chat", true, false, true))
        assertEquals(null, voiceConversationId(null, "deleted-chat", null, false, false, false))
    }

    @Test fun explicitNewTaskMayCarryItsFirstInstruction() {
        assertEquals("打开微信", newVoiceTaskText("新任务，打开微信"))
        assertEquals("打开微信", newVoiceTaskText("开始一个新任务打开微信"))
        assertEquals("", newVoiceTaskText("新对话"))
        assertEquals(null, newVoiceTaskText("打开微信"))
    }

    @Test fun anAnswerReturnsToTheTaskThatAsked() {
        assertEquals(VoiceConversationRoute.Answer("run-1"),
            voiceRoute("chat-1", "run-1", TaskState.WaitingInput("哪一天？")))
    }

    @Test fun runningTaskReceivesFollowUpButFinishedTaskStartsFresh() {
        assertEquals(VoiceConversationRoute.Steer("chat-1"),
            voiceRoute("chat-1", "run-1", TaskState.Running(1, "working")))
        assertEquals(VoiceConversationRoute.New, voiceRoute("chat-1", null, null))
        assertEquals(VoiceConversationRoute.New,
            voiceRoute("chat-1", "run-1", TaskState.Succeeded("done")))
        assertEquals(VoiceConversationRoute.Continue("chat-1"),
            voiceRoute("chat-1", "run-1", TaskState.Idle, followUp = true))
        assertEquals(VoiceConversationRoute.Continue("chat-1"),
            voiceRoute("chat-1", null, null, followUp = true))
        assertEquals(VoiceConversationRoute.Continue("chat-1"),
            voiceRoute("chat-1", "run-1", TaskState.Succeeded("你想选哪一个？"), followUp = true))
    }

    @Test fun finishedQuestionAndPartialResultAcceptSpokenReply() {
        val now = 1_000_000L
        assertEquals(true, isVoiceFollowUp(Turn("original", "你想选哪一个？", "succeeded"), now - 1_000, now, "第二个"))
        assertEquals(true, isVoiceFollowUp(Turn("original", "还缺一些信息", "succeeded", result = "partial"), now - 1_000, now, "明天"))
        assertEquals(true, isVoiceFollowUp(Turn("original", "任务完成", "succeeded"), now - 1_000, now, "继续刚才的话题"))
        assertEquals(false, isVoiceFollowUp(Turn("original", "任务完成", "succeeded"), now - 1_000, now, "打开微信"))
        assertEquals(false, isVoiceFollowUp(Turn("original", "你想选哪一个？", "succeeded"), now - 3 * 60 * 60 * 1000L, now, "第二个"))
    }

    @Test fun speechNeverApprovesAConfirmation() {
        val confirmation = ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat")
        assertEquals(VoiceConversationRoute.NeedsConfirmation,
            voiceRoute("chat-1", "run-1", TaskState.WaitingConfirmation(confirmation)))
    }
}
