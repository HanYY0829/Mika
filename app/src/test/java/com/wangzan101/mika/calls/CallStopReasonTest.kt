package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStopReasonTest {
    @Test fun closingMediaAfterTakeoverDoesNotReplaceHistoryOutcome() {
        val stop = CallStopReason()
        val takeover = "已停止 AI，通话交给系统拨号器"
        assertTrue(stop.record(takeover))
        assertFalse(stop.record("AI 音频连接失败，电话由系统处理"))
        stop.refine("已达到 AI 通话时长上限", "达到时长上限，Android 已挂断")
        assertEquals(takeover, stop.value)
    }

    @Test fun aGenuineMediaFailureStaysRecordedWhenCallLaterEnds() {
        val stop = CallStopReason()
        val mediaFailure = "AI 音频连接失败，电话由系统处理"
        assertTrue(stop.record(mediaFailure))
        assertFalse(stop.record("通话已结束"))
        assertEquals(mediaFailure, stop.value)
    }

    @Test fun timeoutMayBeRefinedWithItsOwnTelecomResult() {
        val stop = CallStopReason()
        val timeout = "已达到 AI 通话时长上限"
        assertTrue(stop.record(timeout))
        stop.refine(timeout, "达到时长上限，Android 已挂断")
        assertEquals("达到时长上限，Android 已挂断", stop.value)
    }
}
