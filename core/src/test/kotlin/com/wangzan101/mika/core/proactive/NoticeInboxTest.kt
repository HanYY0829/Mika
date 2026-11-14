package com.wangzan101.mika.core.proactive

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeInboxTest {
    private val utc = ZoneId.of("UTC")
    private val day = 86_400_000L
    private fun rec(key: String, pkg: String = "p", text: String = "x") = NoticeRecord(key, pkg, 1, "t", text)

    @Test fun boundedDropsOldest() {
        val inbox = NoticeInbox(capacity = 3, clock = { 0 }, zone = { utc })
        (1..5).forEach { inbox.add(rec("k$it")) }
        assertEquals(listOf("k3", "k4", "k5"), inbox.snapshot().records.map { it.key })
    }

    @Test fun sameKeyKeepsLatestAndMovesToNewest() {
        val inbox = NoticeInbox(clock = { 0 }, zone = { utc })
        inbox.add(rec("a", text = "old")); inbox.add(rec("b")); inbox.add(rec("a", text = "new"))
        val s = inbox.snapshot()
        assertEquals(listOf("b", "a"), s.records.map { it.key })
        assertEquals("new", s.records.last().text)
    }

    @Test fun removeAndRemovePackages() {
        val inbox = NoticeInbox(clock = { 0 }, zone = { utc })
        inbox.add(rec("a", "p1")); inbox.add(rec("b", "p2")); inbox.add(rec("c", "p1"))
        inbox.remove("a")
        inbox.remove("missing")
        assertEquals(listOf("b", "c"), inbox.state.value.records.map { it.key })
        inbox.removePackages { it != "p1" }
        assertEquals(listOf("b"), inbox.state.value.records.map { it.key })
    }

    @Test fun countersResetOnANewDay() {
        var now = 10L
        val inbox = NoticeInbox(clock = { now }, zone = { utc })
        inbox.noteReceived(); inbox.noteReceived(); inbox.add(rec("a"))
        assertEquals(2, inbox.state.value.received)
        assertEquals(1, inbox.state.value.kept)
        now += day
        assertEquals(0, inbox.snapshot().received)
        inbox.noteReceived()
        val s = inbox.snapshot()
        assertEquals(1, s.received)
        assertEquals(0, s.kept)
        assertEquals(1, s.records.size) // 换天只清计数,不清记录
    }

    private fun post(key: String, text: String, at: Long, pkg: String = "p") = NoticeRecord(key, pkg, at, "t", text)
    private fun texts(inbox: NoticeInbox, key: String) = inbox.snapshot().history[key].orEmpty().map { it.text }

    // 同一条通知每来一句就重发一遍、只换正文:先后的话都留着,记录本身是最新的那句
    @Test fun repostedTextsAccumulateNewestLast() {
        val inbox = NoticeInbox(clock = { 0 }, zone = { utc })
        inbox.add(post("w", "one", 1)); inbox.add(post("o", "other", 2)); inbox.add(post("w", "two", 3))
        val s = inbox.snapshot()
        assertEquals(listOf("o", "w"), s.records.map { it.key })
        assertEquals("two", s.records.last().text)
        assertEquals(listOf(NoticeLine("t", "one", 1), NoticeLine("t", "two", 3)), s.history["w"])
        assertEquals(listOf("other"), texts(inbox, "o"))
    }

    @Test fun historyIsBoundedPerKey() {
        val inbox = NoticeInbox(clock = { 0 }, zone = { utc }, historyLimit = 3)
        (1..5).forEach { inbox.add(post("w", "m$it", it.toLong())) }
        assertEquals(listOf("m3", "m4", "m5"), texts(inbox, "w"))
        assertEquals(10, NoticeInbox.HISTORY_LIMIT)
    }

    // 文字没变的更新只更新记录(时间、位置、计数),不多记一段
    @Test fun equalTextIsNotRecordedTwice() {
        val inbox = NoticeInbox(clock = { 0 }, zone = { utc })
        inbox.add(post("w", "same", 1)); inbox.add(post("o", "x", 2)); inbox.add(post("w", "same", 3))
        val s = inbox.snapshot()
        assertEquals(listOf(NoticeLine("t", "same", 1)), s.history["w"])
        assertEquals(listOf("o", "w"), s.records.map { it.key })
        assertEquals(3L, s.records.last().postedAtMs)
        assertEquals(3, s.kept)
        // 标题变了也算不同
        inbox.add(post("w", "same", 4).copy(title = "t2"))
        assertEquals(2, inbox.snapshot().history["w"]!!.size)
    }

    @Test fun removalDropsHistory() {
        val inbox = NoticeInbox(capacity = 2, clock = { 0 }, zone = { utc })
        inbox.add(post("a", "a1", 1, "p1")); inbox.add(post("a", "a2", 2, "p1")); inbox.add(post("b", "b1", 3, "p2"))
        inbox.remove("a")
        assertFalse("a" in inbox.snapshot().history)
        inbox.add(post("a", "again", 4, "p1"))
        assertEquals(listOf("again"), texts(inbox, "a")) // 去掉后再来,从头记
        // 满了被挤掉
        inbox.add(post("c", "c1", 5, "p2"))
        assertEquals(setOf("a", "c"), inbox.snapshot().history.keys)
        // 按包名去掉
        inbox.removePackages { it != "p1" }
        assertEquals(setOf("c"), inbox.snapshot().history.keys)
        inbox.clear()
        assertTrue(inbox.snapshot().history.isEmpty())
    }

    // 带消息列表的通知自己带着前面的消息:不另记;以前记的(那时还没有消息列表)也丢掉
    @Test fun messagingRecordsKeepNoHistory() {
        val inbox = NoticeInbox(clock = { 0 }, zone = { utc })
        val chat = NoticeRecord("c", "p", 1, "t", "x", messages = listOf(NoticeMessage("a", "hi", 1)), conversation = true)
        inbox.add(chat); inbox.add(chat.copy(postedAtMs = 2, messages = chat.messages + NoticeMessage("a", "yo", 2)))
        assertFalse("c" in inbox.snapshot().history)
        assertEquals(2, inbox.snapshot().records.single().messages.size)
        inbox.add(post("d", "plain", 3)); inbox.add(chat.copy(key = "d", postedAtMs = 4))
        assertFalse("d" in inbox.snapshot().history)
        // 会话样式但没有消息列表的,照普通通知记
        inbox.add(post("e", "e1", 5).copy(conversation = true))
        assertEquals(listOf("e1"), texts(inbox, "e"))
    }

    // 和通知栏对一遍:不在通知栏里的记录(和它先后显示过的文字)去掉,还在的不动;都还在时什么都不发生
    @Test fun retainKeysDropsRecordsNoLongerInTheShade() {
        val inbox = NoticeInbox(clock = { 0L }, zone = { java.time.ZoneOffset.UTC })
        inbox.add(NoticeRecord("a", "com.chat", 1, "t", "one"))
        inbox.add(NoticeRecord("a", "com.chat", 2, "t", "two"))
        inbox.add(NoticeRecord("b", "com.chat", 3, "t", "three"))
        inbox.add(NoticeRecord("c", "com.mail", 4, "t", "four"))
        assertEquals(0, inbox.retainKeys(setOf("a", "b", "c", "other-apps-notification")))
        assertEquals(3, inbox.state.value.records.size)
        assertEquals(2, inbox.retainKeys(setOf("b", "other-apps-notification")))
        assertEquals(listOf("b"), inbox.state.value.records.map { it.key })
        assertTrue(inbox.state.value.history.keys.none { it == "a" || it == "c" })
        // 通知栏真的空了:全去掉
        assertEquals(1, inbox.retainKeys(emptySet()))
        assertTrue(inbox.state.value.records.isEmpty())
    }

    // 对账去掉的记录,提醒也跟着没了:数字降下来,点开时不会再写一条早就不在通知栏里的提醒。
    // 这样去掉的不算用户「没看就划掉」(那是用户自己在通知栏里处理时才记的)
    @Test fun hintsFollowRecordsDroppedByReconciling() {
        val inbox = NoticeInbox(clock = { 0L }, zone = { java.time.ZoneOffset.UTC })
        val records = listOf(NoticeRecord("a", "com.chat", 1, "t", "one"), NoticeRecord("b", "com.chat", 2, "t", "two"))
        records.forEach(inbox::add)
        val marks = records.associate { it.key to TriageMark.Done(it.postedAtMs, TriageVerdict(TriageDecision.Surface, "why")) }
        val hints = NoticeHints()
        hints.sync(inbox.state.value.records, marks, true)
        assertEquals(2, hints.state.value.unseen)
        val feedback = hints.state.value.feedback
        assertEquals(1, inbox.retainKeys(setOf("b")))
        hints.sync(inbox.state.value.records, marks, true)
        assertEquals(1, hints.state.value.unseen)
        assertEquals(listOf("b"), hints.state.value.list.map { it.key })
        assertEquals(feedback, hints.state.value.feedback)
    }
}
