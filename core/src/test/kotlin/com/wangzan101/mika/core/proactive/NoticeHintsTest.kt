package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeHintsTest {
    // 认出来是状态显示的通知:提醒拿掉,但不是用户处理掉的,不记「没看就处理了」
    @Test fun aWithdrawnHintIsNotCountedAsDismissedUnseen() {
        val h = NoticeHints()
        val a = rec("a", pkg = "p1")
        h.sync(listOf(a), surface(a), triageOn = true)
        assertEquals(1, h.state.value.list.size)
        h.withdraw("a")
        assertTrue(h.state.value.list.isEmpty())
        assertEquals(0, h.state.value.dismissedUnseenTotal)
        h.withdraw("nothing")
    }

    private fun rec(key: String, posted: Long = 1, pkg: String = "p", text: String = "x") = NoticeRecord(key, pkg, posted, "t-$key", text)
    private fun done(posted: Long, d: TriageDecision, reason: String = "why") = TriageMark.Done(posted, TriageVerdict(d, reason))
    private fun surface(vararg rs: NoticeRecord) = rs.associate { it.key to done(it.postedAtMs, TriageDecision.Surface) }

    @Test fun onlySurfaceWithMatchingPostTimeCounts() {
        val h = NoticeHints()
        val a = rec("a"); val b = rec("b"); val c = rec("c", posted = 5); val d = rec("d")
        val marks = mapOf(
            "a" to done(1, TriageDecision.Surface, "r-a"),
            "b" to done(1, TriageDecision.Remember),
            "c" to done(4, TriageDecision.Surface), // 判的是更早那次
            "d" to TriageMark.Failed(1),
        )
        h.sync(listOf(a, b, c, d), marks, triageOn = true)
        val s = h.state.value
        assertEquals(listOf("a"), s.list.map { it.key })
        assertEquals(1, s.unseen)
        assertEquals("r-a", s.list.single().reason)
        assertEquals("t-a", s.list.single().title)
    }

    @Test fun newestFirstAndBounded() {
        val h = NoticeHints(limit = 3)
        val rs = (1..5).map { rec("k$it", posted = it.toLong()) }
        h.sync(rs, surface(*rs.toTypedArray()), true)
        assertEquals(listOf("k5", "k4", "k3"), h.state.value.list.map { it.key })
        // 再同步一次结果不变
        h.sync(rs, surface(*rs.toTypedArray()), true)
        assertEquals(listOf("k5", "k4", "k3"), h.state.value.list.map { it.key })
    }

    @Test fun droppedByBoundDoNotComeBack() {
        val h = NoticeHints(limit = 2)
        val rs = (1..4).map { rec("k$it", posted = it.toLong()) }
        h.sync(rs, surface(*rs.toTypedArray()), true)
        h.markSeen(h.state.value.list)
        h.sync(rs, surface(*rs.toTypedArray()), true)
        assertEquals(listOf("k4", "k3"), h.state.value.list.map { it.key })
        assertEquals(0, h.state.value.unseen)
        // 挤掉的那条更新了、又判为值得提醒:算新的
        val k1 = rec("k1", posted = 9)
        val now = rs.drop(1) + k1
        h.sync(now, surface(*now.toTypedArray()), true)
        assertEquals(listOf("k1"), h.state.value.list.filter { !it.seen }.map { it.key })
    }

    @Test fun openingMarksSeenKeepsListAndCountsPerApp() {
        val h = NoticeHints()
        val a = rec("a", pkg = "p1"); val b = rec("b", pkg = "p2"); val c = rec("c", pkg = "p1")
        h.sync(listOf(a, b, c), surface(a, b, c), true)
        assertEquals(3, h.state.value.unseen)
        h.markSeen(h.state.value.list)
        val s = h.state.value
        assertEquals(0, s.unseen)
        assertEquals(3, s.list.size)
        assertTrue(s.list.all { it.seen })
        assertEquals(HintFeedback(seen = 2), s.feedback["p1"])
        assertEquals(HintFeedback(seen = 1), s.feedback["p2"])
        // 再打开一次不重复计
        h.markSeen(h.state.value.list)
        assertEquals(3, h.state.value.seenTotal)
        // 同样的判定再同步也不变回没看过
        h.sync(listOf(a, b, c), surface(a, b, c), true)
        assertEquals(0, h.state.value.unseen)
    }

    @Test fun removedFromInboxDisappears() {
        val h = NoticeHints()
        val a = rec("a"); val b = rec("b")
        h.sync(listOf(a, b), surface(a, b), true)
        h.sync(listOf(b), surface(a, b), true) // 不再勾选、被挤掉
        assertEquals(listOf("b"), h.state.value.list.map { it.key })
        assertEquals(0, h.state.value.dismissedUnseenTotal)
    }

    @Test fun userDismissBeforeSeenIsCounted() {
        val h = NoticeHints()
        val a = rec("a", pkg = "p1"); val b = rec("b", pkg = "p1"); val c = rec("c", pkg = "p2")
        h.sync(listOf(a, b, c), surface(a, b, c), true)
        h.dismissed("a")
        h.sync(listOf(b, c), surface(a, b, c), true)
        assertEquals(listOf("b", "c").toSet(), h.state.value.list.map { it.key }.toSet())
        assertEquals(HintFeedback(dismissedUnseen = 1), h.state.value.feedback["p1"])
        // 看过之后再划掉不算「没看就处理了」
        h.markSeen(h.state.value.list)
        h.dismissed("b")
        assertEquals(1, h.state.value.dismissedUnseenTotal)
        assertEquals(2, h.state.value.seenTotal)
        // 不是提醒的键划掉没有影响
        h.dismissed("zzz")
        assertEquals(1, h.state.value.dismissedUnseenTotal)
    }

    @Test fun reSurfaceAfterSeenOnlyWhenPostedLater() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10, text = "one")
        h.sync(listOf(a1), surface(a1), true)
        h.markSeen(h.state.value.list)
        assertEquals(0, h.state.value.unseen)
        // 同一次(发出时间没变)再判一遍也不变回没看过
        h.sync(listOf(a1), surface(a1), true)
        assertEquals(0, h.state.value.unseen)
        // 通知更新了、又判为值得提醒:算新的
        val a2 = rec("a", posted = 20, text = "two")
        h.sync(listOf(a2), surface(a2), true)
        val s = h.state.value
        assertEquals(1, s.unseen)
        assertEquals("two", s.list.single().text)
    }

    @Test fun updatedNoticeReplacesUnseenItemInPlace() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10, text = "one")
        h.sync(listOf(a1), surface(a1), true)
        val a2 = rec("a", posted = 20, text = "two")
        // 新的那次还没判:先留着原来那条
        h.sync(listOf(a2), surface(a1), true)
        assertEquals("one", h.state.value.list.single().text)
        h.sync(listOf(a2), surface(a2), true)
        assertEquals("two", h.state.value.list.single().text)
        assertEquals(1, h.state.value.unseen)
        // 再更新、判成忽略:拿掉
        val a3 = rec("a", posted = 30)
        h.sync(listOf(a3), mapOf("a" to done(30, TriageDecision.Ignore)), true)
        assertEquals(0, h.state.value.list.size)
    }

    @Test fun seenItemUpdatedButStillPendingStaysSeen() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10)
        h.sync(listOf(a1), surface(a1), true)
        h.markSeen(h.state.value.list)
        h.sync(listOf(rec("a", posted = 20)), surface(a1), true)
        assertEquals(0, h.state.value.unseen)
        assertEquals(1, h.state.value.list.size)
    }

    @Test fun triageOffClearsListAndSeenOnesDoNotReturn() {
        val h = NoticeHints()
        val a = rec("a"); val b = rec("b")
        h.sync(listOf(a), surface(a), true)
        h.markSeen(h.state.value.list)
        h.sync(listOf(a, b), surface(a, b), true)
        assertEquals(1, h.state.value.unseen)
        // 关掉判定:结果还在 marks 里,但列表清空
        h.sync(listOf(a, b), surface(a, b), false)
        assertEquals(0, h.state.value.list.size)
        assertEquals(0, h.state.value.unseen)
        // 再打开:看过的 a 不回来,没看过的 b 回来
        h.sync(listOf(a, b), surface(a, b), true)
        assertEquals(listOf("b"), h.state.value.list.map { it.key })
    }

    // 点开提醒时只把写进对话的那几条记为看过:写的过程中新来的、同一条又更新了的,还没给用户看过
    @Test fun markSeenOnlyCoversWhatWasShown() {
        val h = NoticeHints()
        val a1 = rec("a", posted = 10, text = "one"); val b = rec("b", posted = 11)
        h.sync(listOf(a1, b), surface(a1, b), true)
        val shown = h.state.value.list
        // 写的过程中:a 更新了并又判为值得提醒,c 新来
        val a2 = rec("a", posted = 20, text = "two"); val c = rec("c", posted = 21)
        h.sync(listOf(a2, b, c), surface(a2, b, c), true)
        h.markSeen(shown)
        val s = h.state.value
        assertEquals(setOf("a", "c"), s.list.filter { !it.seen }.map { it.key }.toSet())
        assertEquals(2, s.unseen)
        assertEquals(1, s.seenTotal)
        // 已经不在的、空的都没有影响
        h.markSeen(listOf(NoticeHint("gone", "p", 1, "", "", "")))
        h.markSeen(emptyList())
        assertEquals(2, h.state.value.unseen)
    }

    @Test fun featureOffEmptiesListButKeepsCounters() {
        val h = NoticeHints()
        val a = rec("a")
        h.sync(listOf(a), surface(a), true)
        h.markSeen(h.state.value.list)
        h.sync(emptyList(), emptyMap(), true)
        assertEquals(0, h.state.value.list.size)
        assertEquals(1, h.state.value.seenTotal)
        // 收件箱清空后同一个键重新进来(重新打开功能)算新的
        h.sync(listOf(a), surface(a), true)
        assertEquals(1, h.state.value.unseen)
    }
}
