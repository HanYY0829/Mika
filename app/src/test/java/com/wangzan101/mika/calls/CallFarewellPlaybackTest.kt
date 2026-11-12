package com.wangzan101.mika.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFarewellPlaybackTest {
    @Test fun naturalClosingCanFinishWithoutACallerFarewellButOnlyAfterPlayback() {
        val turn = CallFarewellPlayback()
        turn.afterPairedFlush()
        turn.callerFinal("时间地址都清楚了，好的", complete = true)
        turn.assistantFinal("周三下午两点，我记下了。那就先这样。")
        assertNull(turn.takeFence())
        turn.audioStarted()
        turn.audio(4_800)
        turn.audioEnded()
        val fence = checkNotNull(turn.takeFence())
        assertFalse(fence.explicit)
        assertFalse(fence.reached(2, 4_800, 480, 2_879, fresh = true, routed = true))
        assertFalse(fence.reached(0, 4_800, 480, 2_880, fresh = true, routed = true))
        assertFalse(fence.reached(2, 4_800, 480, 2_880, fresh = false, routed = true))
        assertFalse(fence.reached(2, 4_800, 480, 2_880, fresh = true, routed = false))
        assertTrue(fence.reached(2, 4_800, 480, 2_880, fresh = true, routed = true))
        assertNull(turn.takeFence())
    }

    @Test fun dialogueContinuesOnCourtesyQuestionsIncompleteSpeechAndInterruption() {
        val turn = CallFarewellPlayback()
        for ((caller, complete, reply) in listOf(
            Triple("谢谢", true, "不客气，您还有别的问题吗？"),
            Triple("好的", true, "谢谢您。"),
            Triple("我考虑一下", true, "这样吧。"),
            Triple("再见", false, "好的，再见"),
            Triple("我还有个问题", true, "你刚才是说‘那就先这样’吗？"),
            Triple("怎么结束推销电话", true, "遇到推销电话，您可以说：谢谢，我们就聊到这里。"),
            Triple("举个例子", true, "比如，谢谢您，那就先这样。"),
            Triple("刚才他怎么说", true, "对方说，谢谢，我们先这样吧。"),
        )) {
            turn.afterPairedFlush()
            turn.callerFinal(caller, complete)
            turn.audioStarted()
            turn.audio(480)
            turn.audioEnded()
            turn.assistantFinal(reply)
            assertNull(turn.takeFence())
        }
        turn.callerFinal("好的，再见", complete = true)
        turn.assistantFinal("好的，再见")
        turn.audioStarted()
        turn.audio(480)
        turn.cancelCandidate()
        turn.audioEnded()
        assertNull(turn.takeFence())
    }

    @Test fun explicitFarewellWorksWhenAudioEndsBeforeText() {
        val turn = CallFarewellPlayback()
        turn.callerFinal("好的，再见", complete = true)
        turn.audioStarted()
        turn.audio(480)
        turn.audioEnded()
        assertNull(turn.takeFence())
        turn.assistantFinal("好的，再见")
        val fence = turn.takeFence()
        assertNotNull(fence)
        assertTrue(checkNotNull(fence).explicit)
    }
}
