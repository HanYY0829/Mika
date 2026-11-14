package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoteSignal
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.TopicKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // 一个 App 只剩在某一份归纳的概述里(存下的句子没引过它,它的分段归纳、那一天的归纳都到期了):照样列出来。
    // 清掉它之后那份概述清空,和它无关的句子留着,列表里也不再有它。日、周、月各走一遍真的池子
    @Test fun appsThatOnlySurviveInAnOverviewAreListedAndCanBeCleared() {
        val utc = java.time.ZoneOffset.UTC
        val day = 20_000L
        val at = day * LearnTuning.DAY_MS + 9 * 3_600_000L
        var n = 0
        fun pool() = DigestPool(newId = { "n${++n}" }).also { p ->
            p.add("com.a", listOf(NoticeEntry("com.a", at, "A 的会话", text = "x")), listOf(NoteSignal(listOf(0), "A 的事")), utc, nowMs = at)
            p.add("com.b", listOf(NoticeEntry("com.b", at + 60_000, "B 的会话", text = "y")), listOf(NoteSignal(listOf(0), "B 的事")), utc, nowMs = at + 60_000)
        }
        fun listed(p: DigestPool) = clearableApps(emptyList(), emptyList(), p.all(), p.days(), p.spans(SpanKind.Week) + p.spans(SpanKind.Month))
        fun both(p: DigestPool) = p.dayInput(day).let { input -> input.indices.map { NoteSignal(listOf(it), input[it].text) } }

        // 当日归纳:存下的句子只引了 B,概述是照着 A、B 写的;分段归纳到期
        pool().let { p ->
            val input = p.dayInput(day)
            p.setDay(day, input, listOf(NoteSignal(listOf(input.indexOfFirst { it.packageName == "com.b" }), "只写了 B 的")), at, partial = false, brief = "A 和 B 的事")
            assertTrue(p.expire(day + LearnTuning.NOTES_KEEP_DAYS + 2))
            assertTrue(p.all().isEmpty())
            assertEquals(setOf("com.b", "com.a"), listed(p))
            assertTrue(p.removePackages { it != "com.a" })
            assertEquals("", p.days().single().brief)
            assertEquals(listOf("只写了 B 的"), p.days().single().lines.map { it.text })
            assertEquals(setOf("com.b"), listed(p))
        }
        // 周归纳:那一天的归纳也到期了,A 只剩在周归纳的概述里
        pool().let { p ->
            p.setDay(day, p.dayInput(day), both(p), at, partial = false)
            val week = DigestPool.weekOf(day)
            val input = p.spanInput(SpanKind.Week, week, true)
            p.setSpan(SpanKind.Week, week, input, listOf(NoteSignal(listOf(input.indexOfFirst { it.line.apps == listOf("com.b") }), "这一周只写了 B 的")),
                at + LearnTuning.DAY_MS, partial = false, brief = "这一周 A 和 B 的事")
            assertTrue(p.expire(day + 40))
            assertTrue(p.all().isEmpty() && p.days().isEmpty())
            assertEquals(setOf("com.b", "com.a"), listed(p))
            assertTrue(p.removePackages { it != "com.a" })
            assertEquals("", p.spans(SpanKind.Week).single().brief)
            assertEquals(listOf("这一周只写了 B 的"), p.spans(SpanKind.Week).single().lines.map { it.text })
            assertEquals(setOf("com.b"), listed(p))
        }
        // 月归纳:周归纳也到期了,A 只剩在月归纳的概述里
        pool().let { p ->
            p.setDay(day, p.dayInput(day), both(p), at, partial = false)
            val week = DigestPool.weekOf(day)
            val days = p.spanInput(SpanKind.Week, week, true)
            p.setSpan(SpanKind.Week, week, days, days.indices.map { NoteSignal(listOf(it), days[it].line.text) }, at + LearnTuning.DAY_MS, partial = false)
            val month = DigestPool.monthOfWeek(week)
            val input = p.spanInput(SpanKind.Month, month, true)
            p.setSpan(SpanKind.Month, month, input, listOf(NoteSignal(listOf(input.indexOfFirst { it.line.apps == listOf("com.b") }), "这个月只写了 B 的")),
                at + 2 * LearnTuning.DAY_MS, partial = false, brief = "这个月 A 和 B 的事")
            assertTrue(p.expire(day + 260))
            assertTrue(p.all().isEmpty() && p.days().isEmpty() && p.spans(SpanKind.Week).isEmpty())
            assertEquals(setOf("com.b", "com.a"), listed(p))
            assertTrue(p.removePackages { it != "com.a" })
            assertEquals("", p.spans(SpanKind.Month).single().brief)
            assertEquals(listOf("这个月只写了 B 的"), p.spans(SpanKind.Month).single().lines.map { it.text })
            assertEquals(setOf("com.b"), listed(p))
        }
    }

    // 概述已经清掉、只剩旧的来源记录:不能凭它把 App 列出来(已经没东西可清了)
    @Test fun aClearedOverviewsLeftoverSourcesAreNotListed() {
        val lines = listOf(DigestLine("只写了 B 的", apps = listOf("com.b")))
        val day = DayDigest(20_000, 1L, lines, brief = "", briefApps = listOf("com.a", "com.b"))
        val span = SpanDigest(19_996, 1L, lines, brief = "", briefApps = listOf("com.a", "com.b"))
        assertEquals(setOf("com.b"), clearableApps(emptyList(), emptyList(), emptyList(), listOf(day), listOf(span)))
        // 概述还在时才算
        assertEquals(setOf("com.b", "com.a"), clearableApps(emptyList(), emptyList(), emptyList(), listOf(day.copy(brief = "A 和 B")), emptyList()))
        assertEquals(setOf("com.b", "com.a"), clearableApps(emptyList(), emptyList(), emptyList(), emptyList(), listOf(span.copy(brief = "A 和 B"))))
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
