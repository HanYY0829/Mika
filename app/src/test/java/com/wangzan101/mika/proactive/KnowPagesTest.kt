package com.wangzan101.mika.proactive

import com.wangzan101.mika.R
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.SpanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「Mika 知道了什么」分级页面的纯逻辑:索引上列哪些,某一页指的东西还在不在。 */
class KnowPagesTest {
    private val today = 20_000L
    private val week = DigestPool.weekOf(today)
    private val month = DigestPool.monthOf(today)
    private fun note(id: String, day: Long) = DigestNote(id, "com.chat", day, 1, 2, 1, "群", "句 $id")
    private val line = DigestLine("一句", notes = listOf("n1"), apps = listOf("com.chat"))
    private fun data(
        canMake: Boolean = false, notes: List<DigestNote> = emptyList(), days: List<DayDigest> = emptyList(),
        weeks: List<SpanDigest> = emptyList(), months: List<SpanDigest> = emptyList(),
    ) = KnowData(today, canMake, notes, days, weeks, months)

    // 每天:有当日归纳的、只有分段归纳的(归纳还没做出来或被删了)都列;能做时今天总有一行;新的在前,不重复
    @Test fun theIndexListsDaysWithADigestOrNotesAndTodayWhenItCanBeMade() {
        assertTrue(data().dayKeys().isEmpty())
        assertEquals(listOf(today), data(canMake = true).dayKeys())
        val d = data(
            notes = listOf(note("a", today - 3), note("b", today - 1), note("c", today - 1)),
            days = listOf(DayDigest(today - 1, 1, listOf(line)), DayDigest(today - 9, 1, emptyList())),
        )
        assertEquals(listOf(today - 1, today - 3, today - 9), d.dayKeys())
        assertEquals(listOf(today, today - 1), data(canMake = true, days = listOf(DayDigest(today - 1, 1, listOf(line)))).dayKeys())
    }

    // 每周、每月:存着的;能做时本周、本月各有一行
    @Test fun theIndexListsStoredSpansAndTheCurrentOnesWhenTheyCanBeMade() {
        val old = SpanDigest(week - 14, 1, listOf(line))
        assertEquals(listOf(week - 14), data(weeks = listOf(old)).spanKeys(SpanKind.Week))
        assertEquals(listOf(week, week - 14), data(canMake = true, weeks = listOf(old)).spanKeys(SpanKind.Week))
        assertEquals(listOf(month), data(canMake = true).spanKeys(SpanKind.Month))
        assertTrue(data().spanKeys(SpanKind.Month).isEmpty())
    }

    // 一页指的东西不在了就不算数(页面退回上一级):按哪一天、哪一句认,不按位置
    @Test fun aPageIsValidOnlyWhileWhatItPointsAtIsStillThere() {
        val other = DigestLine("另一句", notes = listOf("n2"), apps = listOf("com.chat"))
        val d = data(
            notes = listOf(note("n1", today - 1)), days = listOf(DayDigest(today - 1, 1, listOf(line))),
            weeks = listOf(SpanDigest(week, 1, listOf(line))),
        )
        for (always in listOf(KnowPage.Topics, KnowPage.Contacts, KnowPage.Clear)) assertTrue(data().valid(always))
        assertTrue(d.valid(KnowPage.Day(today - 1)))
        assertFalse(d.valid(KnowPage.Day(today - 2)))
        assertFalse("今天还没有东西、也做不了", d.valid(KnowPage.Day(today)))
        assertTrue(data(canMake = true).valid(KnowPage.Day(today)))
        assertTrue(d.valid(KnowPage.DayLine(today - 1, line)))
        // 那一句被去掉、换成了别的一句:原来那一页不再算数,不会落到现在排在那个位置上的另一句头上
        val replaced = data(days = listOf(DayDigest(today - 1, 2, listOf(other))))
        assertFalse(replaced.valid(KnowPage.DayLine(today - 1, line)))
        assertTrue(replaced.valid(KnowPage.DayLine(today - 1, other)))
        assertTrue(d.valid(KnowPage.Span(SpanKind.Week, week)))
        assertFalse(d.valid(KnowPage.Span(SpanKind.Month, month)))
        assertTrue(d.valid(KnowPage.SpanLine(SpanKind.Week, week, line)))
        assertFalse(d.valid(KnowPage.SpanLine(SpanKind.Week, week, other)))
        assertFalse(d.valid(KnowPage.SpanLine(SpanKind.Month, month, line)))
        assertTrue(d.valid(KnowPage.Note("n1")))
        assertFalse(d.valid(KnowPage.Note("n9")))
    }

    // 说「会自动归纳」「点下面这一行」的那几句,只在现在真能做的时候才用;做不了时只说「还没有」
    @Test fun theNoteOverADaysDigestOnlyPromisesWhatCanHappen() {
        val full = DayDigest(today, 1, listOf(line))
        val empty = DayDigest(today, 1, emptyList())
        for (canMake in listOf(true, false)) for (isToday in listOf(true, false)) {
            assertEquals(null, dayNote(full, removed = false, isToday = isToday, canMake = canMake))
            assertEquals(R.string.proactive_learn_day_nothing, dayNote(empty, false, isToday, canMake))
        }
        assertEquals(R.string.proactive_learn_day_none, dayNote(null, removed = false, isToday = true, canMake = true))
        assertEquals(R.string.proactive_know_day_pending, dayNote(null, removed = false, isToday = false, canMake = true))
        assertEquals(R.string.proactive_learn_empty, dayNote(null, removed = false, isToday = true, canMake = false))
        assertEquals(R.string.proactive_learn_empty, dayNote(null, removed = false, isToday = false, canMake = false))
        // 删掉了的:只有今天、而且能做的时候才说「想要的话点下面这一行」
        assertEquals(R.string.proactive_learn_span_removed, dayNote(null, removed = true, isToday = true, canMake = true))
        assertEquals(R.string.proactive_know_removed, dayNote(null, removed = true, isToday = true, canMake = false))
        assertEquals(R.string.proactive_know_removed, dayNote(null, removed = true, isToday = false, canMake = true))
    }

    @Test fun theNoteOverASpansDigestOnlyPromisesWhatCanHappen() {
        assertEquals(null, spanNote(SpanDigest(week, 1, listOf(line)), false, SpanKind.Week, true))
        assertEquals(R.string.proactive_learn_span_nothing, spanNote(SpanDigest(week, 1, emptyList()), false, SpanKind.Week, false))
        assertEquals(R.string.proactive_learn_week_none, spanNote(null, false, SpanKind.Week, canMake = true))
        assertEquals(R.string.proactive_learn_month_none, spanNote(null, false, SpanKind.Month, canMake = true))
        assertEquals(R.string.proactive_learn_empty, spanNote(null, false, SpanKind.Week, canMake = false))
        assertEquals(R.string.proactive_learn_span_removed, spanNote(null, true, SpanKind.Month, canMake = true))
        assertEquals(R.string.proactive_know_removed, spanNote(null, true, SpanKind.Month, canMake = false))
    }
}
