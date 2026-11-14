package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeJournalTest {
    private var now = 1_000_000_000L
    private fun journal(capacity: Int = 100, keepMs: Long = 48 * 3_600_000L, data: NoticeJournalData? = null) =
        NoticeJournal(data, capacity, keepMs) { now }
    private fun plain(key: String, text: String, at: Long = now, title: String = "T", pkg: String = "com.chat") =
        NoticeRecord(key, pkg, at, title, text)

    // 收件箱里的通知被点开、划掉之后就没了;日志里的还在,而且同一条通知先后的每一段文字都在
    @Test fun keepsEveryDistinctTextOfANotificationInOrder() {
        val j = journal()
        assertEquals(1, j.add(plain("k", "[1条]A: 在吗", at = now + 1)))
        assertEquals(1, j.add(plain("k", "[2条]A: 明天来吗", at = now + 2)))
        // 原样重发的不重复记
        assertEquals(0, j.add(plain("k", "[2条]A: 明天来吗", at = now + 3)))
        assertEquals(1, j.add(plain("other", "快递到了", at = now + 4, pkg = "com.mail")))
        assertEquals(listOf("[1条]A: 在吗", "[2条]A: 明天来吗", "快递到了"), j.between(0, Long.MAX_VALUE).map { it.text })
        assertEquals(listOf("com.chat", "com.chat", "com.mail"), j.between(0, Long.MAX_VALUE).map { it.packageName })
        // 都是空的不记
        assertEquals(0, j.add(plain("e", "", title = "")))
    }

    // 带会话样式的通知每次重发都带着前面的消息:每条消息只记一次,带着是谁说的和它自己的时间
    // 指纹只有 32 位,不同的两句会撞上:撞上了要比原文,两句都得记下
    @Test fun differentTextsWithTheSameFingerprintAreBothRecorded() {
        // 「Aa」「BB」的 hashCode 一样,整条的指纹也就一样
        assertEquals(listOf("T", null, "Aa").hashCode(), listOf("T", null, "BB").hashCode())
        val j = journal()
        assertEquals(1, j.add(plain("k", "Aa", at = now + 1)))
        assertEquals(1, j.add(plain("k", "BB", at = now + 2)))
        assertEquals(0, j.add(plain("k", "BB", at = now + 3)))
        assertEquals(listOf("Aa", "BB"), j.between(0, Long.MAX_VALUE).map { it.text })
        // 会话样式的也一样
        val said = now
        assertEquals(listOf("a", "Aa", said).hashCode(), listOf("a", "BB", said).hashCode())
        val styled = journal()
        fun chat(at: Long, vararg texts: String) = NoticeRecord("c", "com.chat", at, "T", "", conversation = true,
            messages = texts.map { NoticeMessage("a", it, said) })
        assertEquals(1, styled.add(chat(now + 1, "Aa")))
        assertEquals(1, styled.add(chat(now + 2, "Aa", "BB")))
        assertEquals(0, styled.add(chat(now + 3, "Aa", "BB")))
    }

    // 原样重发的不重复记;但隔了半天以上又发来的同一句(第二天的问候)是新的一次,要记——不然那一天的归纳里没有它
    @Test fun theSameTextIsRecordedAgainAfterALongGap() {
        val j = journal()
        val t0 = now
        assertEquals(1, j.add(plain("k", "早上好", at = t0)))
        assertEquals(0, j.add(plain("k", "早上好", at = t0 + LearnTuning.REPEAT_WINDOW_MS - 1)))
        now = t0 + LearnTuning.REPEAT_WINDOW_MS
        assertEquals(1, j.add(plain("k", "早上好", at = now)))
        assertEquals(2, j.between(0, Long.MAX_VALUE).size)
    }

    @Test fun messagingStyleRecordsEachMessageOnce() {
        val j = journal()
        val m1 = NoticeMessage("Alice", "hi", now + 1); val m2 = NoticeMessage("Bob", "hello", now + 2); val m3 = NoticeMessage(null, "ok", 0)
        assertEquals(2, j.add(NoticeRecord("k", "com.chat", now + 2, "Group", "hello", listOf(m1, m2))))
        assertEquals(1, j.add(NoticeRecord("k", "com.chat", now + 9, "Group", "ok", listOf(m1, m2, m3))))
        val all = j.between(0, Long.MAX_VALUE)
        assertEquals(listOf("Alice" to "hi", "Bob" to "hello", null to "ok"), all.map { it.sender to it.text })
        assertTrue(all.all { it.title == "Group" })
        // 消息自己没带时间的,用通知的发出时间
        assertEquals(listOf(now + 1, now + 2, now + 9), all.map { it.atMs })
    }

    @Test fun readsATimeRangeOldestFirst() {
        val j = journal()
        j.add(plain("b", "second", at = now + 200))
        j.add(plain("a", "first", at = now + 100))
        j.add(plain("c", "third", at = now + 300))
        assertEquals(listOf("first", "second"), j.between(now + 100, now + 300).map { it.text })
        assertEquals(emptyList<String>(), j.between(now + 301, now + 400).map { it.text })
    }

    @Test fun boundedByAgeAndCount() {
        val j = journal(capacity = 3, keepMs = 1_000)
        j.add(plain("old", "too old", at = now - 5_000))
        assertEquals(0, j.size)
        (1..5).forEach { j.add(plain("k$it", "t$it", at = now + it)) }
        assertEquals(listOf("t3", "t4", "t5"), j.between(0, Long.MAX_VALUE).map { it.text })
        now += 2_000
        j.add(plain("new", "fresh", at = now))
        assertEquals(listOf("fresh"), j.between(0, Long.MAX_VALUE).map { it.text })
    }

    // 取消勾选或禁用的 App:它的记录一并去掉;功能关掉:全清,并记下是从什么时候重新开始的
    @Test fun purgeByAppAndClear() {
        val j = journal()
        val started = j.sinceMs
        j.add(plain("a", "x", pkg = "com.chat"))
        j.add(plain("b", "y", pkg = "com.mail"))
        j.removePackages { it != "com.chat" }
        assertEquals(listOf("y"), j.between(0, Long.MAX_VALUE).map { it.text })
        assertEquals(started, j.sinceMs)
        now += 60_000
        j.clear()
        assertEquals(0, j.size)
        assertEquals(now, j.sinceMs)
        // 清掉之后同样的内容再来,照常记(去重的记录也清了)
        assertEquals(1, j.add(plain("b", "y", pkg = "com.mail")))
    }

    // 存下来再读回(重启):内容、从什么时候开始记都接着上次的
    @Test fun dataRoundTripKeepsEntriesAndSince() {
        val j = journal()
        val since = j.sinceMs
        j.add(plain("a", "one", at = now + 1))
        j.add(NoticeRecord("s", "com.chat", now + 2, "Group", "x", listOf(NoticeMessage("Alice", "hi", now + 2))))
        now += 3_600_000
        val back = journal(data = j.data())
        assertEquals(since, back.sinceMs)
        assertEquals(j.between(0, Long.MAX_VALUE), back.between(0, Long.MAX_VALUE))
        assertEquals(listOf("one", "hi"), back.between(0, Long.MAX_VALUE).map { it.text })
        // 键只存了哈希
        assertTrue(back.data().entries.none { it.keyId == "a" || it.keyId == "s" })
        assertEquals(NoticeDedup.idOf("a"), back.data().entries.first().keyId)
    }

    // 重启之后同一条通知原样重发、或带着前面几条消息重发:只记新的那一段
    @Test fun repostsAfterRestartAreNotRecordedAgain() {
        val j = journal()
        val m1 = NoticeMessage("Alice", "hi", now + 1); val m2 = NoticeMessage("Bob", "hello", now + 2)
        j.add(NoticeRecord("s", "com.chat", now + 2, "Group", "hello", listOf(m1, m2)))
        j.add(plain("p", "[2条]A: 明天来吗", at = now + 3))
        val back = journal(data = j.data())
        assertEquals(0, back.add(plain("p", "[2条]A: 明天来吗", at = now + 9)))
        val m3 = NoticeMessage("Alice", "new one", now + 10)
        assertEquals(1, back.add(NoticeRecord("s", "com.chat", now + 10, "Group", "new one", listOf(m1, m2, m3))))
        assertEquals(listOf("hi", "hello", "[2条]A: 明天来吗", "new one"), back.between(0, Long.MAX_VALUE).map { it.text })
    }

    // 到期的要删:没有新通知来也一样,读回来的时候也一样
    @Test fun expiresWithoutNewNotifications() {
        val j = journal(keepMs = 1_000)
        j.add(plain("a", "old", at = now))
        now += 500
        j.add(plain("b", "newer", at = now))
        assertFalse(j.expire())
        now += 600
        assertTrue(j.expire())
        assertEquals(listOf("newer"), j.between(0, Long.MAX_VALUE).map { it.text })
        val saved = j.data()
        now += 1_000
        assertEquals(0, journal(keepMs = 1_000, data = saved).size)
    }

    // 已经归纳完的那一天:整段去掉
    @Test fun removeBeforeDropsADigestedDay() {
        val j = journal()
        j.add(plain("a", "yesterday", at = now + 1))
        j.add(plain("b", "today", at = now + 100))
        assertTrue(j.removeBefore(now + 50))
        assertEquals(listOf("today"), j.between(0, Long.MAX_VALUE).map { it.text })
        assertFalse(j.removeBefore(now + 50))
    }

    // 分段归纳要用的:哪个 App 到期、它还没归纳过的是哪些;做过的(成没成都算)不再出现,存下来之后也一样
    @Test fun pendingDueAndNoted() {
        val j = journal()
        (1..3).forEach { j.add(plain("a$it", "a$it", at = now + it, pkg = "com.a")) }
        j.add(plain("b1", "b1", at = now + 10, pkg = "com.b"))
        assertNull(j.due(now + 20, { true }, dueCount = 5, dueAgeMs = 1_000))
        assertEquals("com.a", j.due(now + 20, { true }, dueCount = 3, dueAgeMs = 1_000))
        // 到了时间的也算;几个都到期先做最旧的;不归纳的 App 不看
        assertEquals("com.a", j.due(now + 2_000, { true }, dueCount = 50, dueAgeMs = 1_000))
        assertEquals("com.b", j.due(now + 2_000, { it != "com.a" }, dueCount = 50, dueAgeMs = 1_000))
        val batch = j.pending("com.a", limit = 2)
        assertEquals(listOf("a1", "a2"), batch.map { it.text })
        assertTrue(j.markNoted(batch))
        assertFalse(j.markNoted(batch))
        assertEquals(listOf("a3"), j.pending("com.a", 10).map { it.text })
        assertEquals(2, j.pendingCount)
        val back = journal(data = j.data())
        assertEquals(listOf("a3"), back.pending("com.a", 10).map { it.text })
        // 归纳过的还留着(当天要查原文时用)
        assertEquals(4, back.size)
    }

    // 消息自己带的时间是发通知的 App 填的:填到将来的按通知的发出时间算(不然过不了期,还会算到将来的某一天)
    @Test fun aMessageTimeInTheFutureIsClampedToThePostTime() {
        val j = journal(keepMs = 1_000)
        val future = NoticeMessage("Eve", "from the future", now + 10_000_000)
        val ok = NoticeMessage("Bob", "fine", now - 10)
        j.add(NoticeRecord("s", "com.chat", now, "Group", "x", listOf(ok, future)))
        assertEquals(listOf(now - 10, now), j.between(0, Long.MAX_VALUE).map { it.atMs })
        now += 2_000
        assertTrue(j.expire())
        assertEquals(0, j.size)
    }

    // 条数满了挤掉过旧的:「从什么时候起是全的」跟着往后挪(不然通知最多的那天反而被当成全的),存下来、读回来还在;清空后重新算
    @Test fun completeSinceMovesWhenTheCapEvicts() {
        val j = journal(capacity = 3)
        val started = j.sinceMs
        (1..3).forEach { j.add(plain("k$it", "t$it", at = now + it)) }
        assertEquals(started, j.completeSinceMs)
        (4..5).forEach { j.add(plain("k$it", "t$it", at = now + it)) }
        assertEquals(now + 2, j.completeSinceMs)
        assertEquals(started, j.sinceMs)
        val back = journal(capacity = 3, data = j.data())
        assertEquals(now + 2, back.completeSinceMs)
        // 过了保留期被删掉的不算「挤掉」
        val k = journal(capacity = 100, keepMs = 1_000)
        k.add(plain("a", "x", at = now))
        now += 5_000
        k.add(plain("b", "y", at = now))
        assertEquals(k.sinceMs, k.completeSinceMs)
        now += 10
        back.clear()
        assertEquals(now, back.completeSinceMs)
    }

    // 一句分段归纳是照着哪些通知写的:按 App、会话名、起止时间(都含)找;会话名按存句子时同样的办法算(去掉未读条数)
    @Test fun aroundFindsOneChatsEntriesInsideTheWindow() {
        val j = NoticeJournal(clock = { 10_000L })
        fun rec(key: String, pkg: String, at: Long, title: String, text: String) = NoticeRecord(key, pkg, at, title, text)
        j.add(rec("a", "com.chat", 1_000, "家长群(2条新消息)", "王老师: 明天开会"))
        j.add(rec("a", "com.chat", 2_000, "家长群(3条新消息)", "李老师: 收到"))
        j.add(rec("b", "com.chat", 2_500, "同事群", "张三: 周报"))
        j.add(rec("c", "com.mail", 2_000, "家长群", "别的 App"))
        j.add(rec("a", "com.chat", 9_000, "家长群(4条新消息)", "王老师: 改到后天"))
        assertEquals(listOf("王老师: 明天开会", "李老师: 收到"), j.around("com.chat", "家长群", 1_000, 2_000).map { it.text.substringAfter("]") })
        // 没记会话名的那种句子(依据的几段不是同一个会话):这个 App 这段时间的都给
        assertEquals(3, j.around("com.chat", null, 1_000, 2_500).size)
        assertTrue(j.around("com.chat", "家长群", 3_000, 8_000).isEmpty())
        assertTrue(j.around("com.other", null, 0, 10_000).isEmpty())
    }
}
