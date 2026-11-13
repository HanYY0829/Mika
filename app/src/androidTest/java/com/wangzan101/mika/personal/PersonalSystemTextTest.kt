package com.wangzan101.mika.personal

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.personal.PersonalMessage
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.core.personal.PersonalSystemException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PersonalSystemTextTest {
    @Test fun stopErrorsTranslateKnownFailuresAndPreserveOtherMessages() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun context(locale: Locale) = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        val english = context(Locale.ENGLISH)
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val known = PersonalSystemException("stop_not_ready", "任务还未就绪或已经结束，请查看任务详情。")
        assertNotEquals(personalStopErrorText(english, known), personalStopErrorText(chinese, known))
        assertEquals(known.message, personalStopErrorText(chinese, known))
        assertNotEquals(personalStopErrorText(english, Exception()), personalStopErrorText(chinese, Exception()))
        assertEquals("external error", personalStopErrorText(english, Exception("external error")))
    }

    @Test fun taskAmendmentLocalizesOnlyTheSystemPrefix() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun context(locale: Locale) = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        val english = context(Locale.ENGLISH)
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val task = PersonalTask("task", "conversation", "title", "goal", "source", 0,
            detail = "补充：补充：用户原文", detailSystemType = "task_amendment")
        assertEquals("Additional instruction: 补充：用户原文", task.displayDetail(english))
        assertEquals("补充：补充：用户原文", task.displayDetail(chinese))
        assertEquals(task.detail, task.copy(detailSystemType = null).displayDetail(english))
        assertEquals("外部内容", task.copy(detail = "外部内容", detailSystemType = null).displayDetail(english))
    }

    @Test fun systemMessagesFollowLanguageWithoutChangingUnmarkedText() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun context(locale: Locale) = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
        val english = context(Locale.ENGLISH)
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val text = "这次回复超时了。已有任务不会因此重做，你可以继续发消息。"
        val message = PersonalMessage("id", "assistant", text, 0, systemType = "timeout")
        assertNotEquals(message.displayText(english), message.displayText(chinese))
        assertEquals(text, message.displayText(chinese))
        assertEquals(text, message.copy(systemType = null).displayText(english))
        assertEquals(text, message.copy(systemType = "future_type").displayText(english))
        assertEquals(text, message.text)

        val task = PersonalTask("task", "conversation", "title", "goal", "source", 0,
            detail = "等待开始", result = "真实结果", detailSystemType = "waiting")
        assertNotEquals(task.displayDetail(english), task.displayDetail(chinese))
        assertEquals("真实结果", task.displayResult(english))
        val cancelled = task.copy(result = "已取消", resultSystemType = "task_cancelled")
        assertNotEquals(cancelled.displayResult(english), cancelled.displayResult(chinese))
        assertEquals("已取消", cancelled.copy(resultSystemType = null).displayResult(english))
        assertEquals("等待开始", task.detail)
    }
}
