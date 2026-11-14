package com.wangzan101.mika.proactive

import android.app.Notification
import com.wangzan101.mika.core.proactive.NoticeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** StatusBarNotification 之后那一半(纯映射);读系统对象的那一半要在真机上验。 */
class RawNoticeTest {
    private val raw = RawNotice("k", "com.tencent.mm", 42, "张三", null, "一行", emptyList(), false, 0, false, true, null)

    @Test fun bigTextWinsOverText() {
        assertEquals("展开的长文", raw.copy(bigText = "展开的长文").toRecord().text)
        assertEquals("一行", raw.copy(bigText = "  ").toRecord().text)
        assertEquals("", raw.copy(text = null).toRecord().text)
        assertEquals("", raw.copy(title = null).toRecord().title)
    }

    @Test fun usesSystemPostTime() = assertEquals(42L, raw.toRecord().postedAtMs)

    @Test fun flagBitsBecomeTraits() {
        val r = raw.copy(flags = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT,
            clearable = false, category = "call").toRecord()
        assertTrue(r.groupSummary)
        assertTrue(r.foregroundService)
        assertTrue(r.ongoing)
        assertFalse(r.clearable)
        assertEquals("call", r.category)
        val plain = raw.toRecord()
        assertFalse(plain.groupSummary || plain.foregroundService || plain.ongoing)
    }

    // 会话名、是不是群:只有带会话样式的才往下传
    @Test fun conversationNameAndGroupOnlyComeFromMessagingStyle() {
        val styled = raw.copy(messagingStyle = true, title = "爬山群: 张三", conversationTitle = "爬山群", groupConversation = true).toRecord()
        assertEquals("爬山群", styled.conversationTitle)
        assertEquals(true, styled.group)
        val plain = raw.copy(conversationTitle = "爬山群", groupConversation = true).toRecord()
        assertEquals(null, plain.conversationTitle)
        assertEquals(null, plain.group)
    }

    // 重要程度在「默认」以下的是不出声的;拿不到重要程度时当会出声
    @Test fun lowImportanceMeansSilent() {
        assertFalse(raw.toRecord().silent)
        assertTrue(raw.copy(importance = 2).toRecord().silent)
        assertTrue(raw.copy(importance = 1).toRecord().silent)
        assertFalse(raw.copy(importance = 3).toRecord().silent)
        assertFalse(raw.copy(importance = 4).toRecord().silent)
    }

    @Test fun conversationFromStyleOrMessages() {
        assertFalse(raw.toRecord().conversation)
        assertTrue(raw.copy(messagingStyle = true).toRecord().conversation)
        assertTrue(raw.copy(messages = listOf(NoticeMessage("a", "hi", 1))).toRecord().conversation)
    }
}
