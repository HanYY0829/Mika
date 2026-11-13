package com.wangzan101.mika.capsule

import androidx.compose.ui.geometry.Rect
import com.wangzan101.mika.cap.phone.a11y.HoldGesture
import com.wangzan101.mika.i18n.ChineseTexts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 胶囊长按说话:松开时手指落在哪个圆上、长按和拖动怎么分、发出后胶囊上闪的那句。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleVoiceTest {
    @get:Rule val texts = ChineseTexts()

    private val cancel = Rect(100f, 200f, 162f, 262f)
    private val edit = Rect(300f, 200f, 362f, 262f)

    @Test fun insideCancelCircleIsCancel() {
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun justOutsideCancelCircleStillCancels() {
        // 圆外 10px,外扩 14px 以内
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(90f, 230f, cancel, edit, slopPx = 14))
        assertEquals(CapsuleVoice.Drop.Cancel, CapsuleVoice.drop(130f, 272f, cancel, edit, slopPx = 14))
    }

    @Test fun farFromBothCirclesIsSend() {
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(230f, 900f, cancel, edit, slopPx = 14))
        // 两个圆中间、都不够近
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(231f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun insideEditCircleIsEdit() {
        assertEquals(CapsuleVoice.Drop.Edit, CapsuleVoice.drop(330f, 230f, cancel, edit, slopPx = 14))
    }

    @Test fun circlesNotLaidOutYetMeanSend() {
        // 面板还没排好版(圆的位置还没报上来)时不会误判成取消
        assertEquals(CapsuleVoice.Drop.Send, CapsuleVoice.drop(0f, 0f, Rect.Zero, Rect.Zero, slopPx = 14))
    }

    @Test fun smallMoveThenTimeoutIsHold() {
        val g = HoldGesture(slopPx = 8f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Tap, g.move(5f))
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
        // 长按以后再怎么移都还是长按,不变成拖
        assertEquals(HoldGesture.Kind.Hold, g.move(200f))
    }

    @Test fun movedPastSlopFirstIsDragAndNeverHold() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        assertEquals(HoldGesture.Kind.Drag, g.move(20f))
        assertEquals(HoldGesture.Kind.Drag, g.timeout())
        assertEquals(HoldGesture.Kind.Drag, g.move(2f))
    }

    @Test fun newDownStartsOver() {
        val g = HoldGesture(slopPx = 8f)
        g.down()
        g.move(20f)
        assertEquals(HoldGesture.Kind.Tap, g.down())
        assertEquals(HoldGesture.Kind.Hold, g.timeout())
    }

    @Test fun sentNoteSaysWhatHappenedWithTheFirstWords() {
        assertEquals("已插话：要下午三点以后的，二等座…", CapsuleVoice.sent(CardInputKind.Steer, "要下午三点以后的，二等座靠窗"))
        assertEquals("已回答：明天上午", CapsuleVoice.sent(CardInputKind.Answer, "明天上午"))
        assertEquals("已发出：查一下天气", CapsuleVoice.sent(CardInputKind.Continue, "查一下天气"))
        assertEquals("已发出：查一下天气", CapsuleVoice.sent(CardInputKind.Start, "查一下天气"))
    }

    // 松开后引擎一直不给结果:到时限一定要收尾,面板不能永远停在「转文字…」
    @Test fun waitFiresWhenTheSessionNeverAnswers() = runTest {
        var fired = 0
        val wait = VoiceWait(backgroundScope) { fired++ }
        wait.arm(8_000)
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(0, fired)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fired)
    }

    @Test fun waitCancelledByTheResultDoesNotFire() = runTest {
        var fired = 0
        val wait = VoiceWait(backgroundScope) { fired++ }
        wait.arm(8_000)
        advanceTimeBy(1_000)
        // 会话按时交出结果:面板已经收起,兜底不再开火
        wait.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(0, fired)
    }

    @Test fun armAgainRestartsTheWait() = runTest {
        var fired = 0
        val wait = VoiceWait(backgroundScope) { fired++ }
        wait.arm(8_000)
        advanceTimeBy(5_000)
        // 重新按住:上一段的兜底作废,从这次松开重新算
        wait.arm(8_000)
        advanceTimeBy(7_999)
        runCurrent()
        assertEquals(0, fired)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fired)
    }
}
