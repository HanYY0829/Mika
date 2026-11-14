package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.TopicKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「清空来自它的」列哪些 App:只在归纳里出现过的 App 也要列出来(只看话题和联系人的话,那样的 App 没有入口)。 */
class ClearableAppsTest {
    @Test fun appsThatOnlyAppearInDigestsAreListedToo() {
        val topic = ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, 1L).copy(evidence = mapOf("com.topic" to listOf(20_000L)))
        val contact = ObservedContact("c1", "com.contact", "张三", 1L, 1L)
        val note = DigestNote("d1", "com.note", 20_000L, 1L, 1L, 1, text = "一句", madeAtMs = 1L)
        val day = DayDigest(20_000, 1L, listOf(DigestLine("当日的一句", apps = listOf("com.day", "com.note"))))
        val week = SpanDigest(19_999, 1L, listOf(DigestLine("这一周的一句", apps = listOf("com.week"))))
        val month = SpanDigest(19_980, 1L, listOf(DigestLine("这个月的一句", apps = listOf("com.month"))))
        assertEquals(
            setOf("com.topic", "com.contact", "com.note", "com.day", "com.week", "com.month"),
            clearableApps(listOf(topic), listOf(contact), listOf(note), listOf(day), listOf(week, month)),
        )
        assertEquals(setOf("com.day", "com.note"), clearableApps(emptyList(), emptyList(), emptyList(), listOf(day), emptyList()))
        assertEquals(emptySet<String>(), clearableApps(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
        // 只留着通知原文、还没归纳出任何东西的 App 也列出来
        assertEquals(setOf("com.day", "com.note", "com.raw"), clearableApps(emptyList(), emptyList(), emptyList(), listOf(day), emptyList(), setOf("com.raw", "com.note")))
    }

    // 「归纳到现在」做不了的几种情况都有一句话可说;做得了时没有
    @Test fun manualDigestSaysWhyItCannotRun() {
        assertEquals(null, makeBlocked(budgetOut = false, status = LearnStatus.Idle))
        assertEquals(null, makeBlocked(budgetOut = false, status = LearnStatus.Waiting))
        assertEquals(com.wangzan101.mika.R.string.proactive_learn_state_budget, makeBlocked(budgetOut = true, status = LearnStatus.Idle))
        // 账本上还有、但请求时被拒过(执行器记着今天不再发)
        assertEquals(com.wangzan101.mika.R.string.proactive_learn_state_budget, makeBlocked(budgetOut = false, status = LearnStatus.BudgetUsedUp))
        assertEquals(com.wangzan101.mika.R.string.proactive_status_access, makeBlocked(budgetOut = false, status = LearnStatus.Suspended))
        assertEquals(com.wangzan101.mika.R.string.proactive_status_access, makeBlocked(budgetOut = true, status = LearnStatus.Suspended))
    }
}
