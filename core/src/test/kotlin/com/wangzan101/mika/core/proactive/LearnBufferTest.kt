package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnBufferTest {
    private fun rec(key: String, pkg: String = "com.shop", at: Long = 0, title: String = "t $key", text: String = "x $key") =
        NoticeRecord(key, pkg, at, title, text)

    @Test fun oneEventPerDistinctTextOfAKey() {
        val b = LearnBuffer()
        assertTrue(b.capture(rec("a")))
        assertFalse(b.capture(rec("a", at = 5)))
        assertTrue(b.capture(rec("a", text = "changed")))
        assertFalse(b.capture(rec("b", title = " ", text = "")))
        assertEquals(LearnBuffer.Summary(2, 2), b.state.value)
    }

    // 会话样式的通知:归纳用的是消息列表里这回才见到的几条(带着谁说的),不是通知的正文——正文常常是空的或只有最后一句
    @Test fun newMessagesOfAConversationAreWhatGetsLearnedFrom() {
        val dedup = NoticeDedup()
        val b = LearnBuffer()
        fun post(at: Long, text: String, vararg messages: Pair<String, Long>) {
            val r = NoticeRecord("chat", "com.chat", at, "Alice", text, conversation = true,
                messages = messages.map { (said, time) -> NoticeMessage("Alice", said, time) })
            dedup.admit(r)?.let { b.capture(r, it.newMessages) }
        }
        // 正文是空的,话在消息列表里
        post(100, "", "明天三点开会" to 90)
        // 锁屏期间来了两句,通知的正文只有最后一句
        post(200, "好的", "明天三点开会" to 90, "带上合同" to 150, "好的" to 190)
        val batch = b.begin("com.chat")
        assertEquals(listOf("Alice: 明天三点开会", "Alice: 带上合同 / Alice: 好的"), batch.map { it.text })
        // 发给模型的输入里有这些话
        val input = NoticeLearn.buildInput("com.chat", batch, emptyList(), "English")
        for (said in listOf("明天三点开会", "带上合同", "好的")) assertTrue(said, said in input)
    }

    // 一回见到的新消息太多:最多拿最新的几条,总长也有上限,放不下时留新的
    @Test fun manyNewMessagesAreCappedKeepingTheNewest() {
        val b = LearnBuffer()
        val many = (1..9).map { NoticeMessage("A", "m$it", it.toLong()) }
        assertTrue(b.capture(NoticeRecord("chat", "com.chat", 100, "Alice", "", conversation = true, messages = many), many))
        val text = b.begin("com.chat").single().text
        assertEquals((9 - LearnTuning.NEW_LINES_MAX + 1..9).joinToString(" / ") { "A: m$it" }, text)
        val long = (1..3).map { NoticeMessage("A", "$it" + "x".repeat(120), it.toLong()) }
        val c = LearnBuffer()
        c.capture(NoticeRecord("chat", "com.chat", 100, "Alice", "", conversation = true, messages = long), long)
        val kept = c.begin("com.chat").single().text
        assertTrue(kept.length <= LearnTuning.TEXT_MAX)
        assertTrue(kept.startsWith("A: 3"))
        // 没有新消息的(不是会话样式的通知):照旧用正文
        val d = LearnBuffer()
        d.capture(NoticeRecord("k", "com.shop", 1, "到账通知", "收到 20 元"))
        assertEquals("收到 20 元", d.begin("com.shop").single().text)
    }

    // 每来一句把同一条通知原样重发一遍的 App:同样的文字隔得不久只记一次,跨过零点几分钟的重发也不算第二天的;
    // 但第二天真的又发来的同一句话是新的一次(话题、联系人要靠它攒「出现了几天」)——归纳完了的也一样
    @Test fun theSameTextCountsAgainOnlyAfterALongGap() {
        val day = 24 * 3_600_000L
        val b = LearnBuffer()
        assertTrue(b.capture(rec("greet", at = day - 10 * 60_000, text = "早上好")))
        // 零点刚过又原样重发
        assertFalse(b.capture(rec("greet", at = day + 5 * 60_000, text = "早上好")))
        b.begin("com.shop")
        b.finish(emptyMap())
        assertFalse(b.capture(rec("greet", at = day + 30 * 60_000, text = "早上好")))
        // 第二天同一句
        assertTrue(b.capture(rec("greet", at = 2 * day - 10 * 60_000, text = "早上好")))
        assertEquals(1, b.pendingCount)
        // 刚好没到半天的不算,到了的算(从最近一次出现算起)
        val c = LearnBuffer()
        assertTrue(c.capture(rec("k", at = 0)))
        assertFalse(c.capture(rec("k", at = LearnTuning.REPEAT_WINDOW_MS - 1)))
        assertFalse(c.capture(rec("k", at = LearnTuning.REPEAT_WINDOW_MS)))
        assertFalse(c.capture(rec("k", at = 2 * LearnTuning.REPEAT_WINDOW_MS - 1)))
        assertTrue(c.capture(rec("k", at = 3 * LearnTuning.REPEAT_WINDOW_MS - 1)))
    }

    // 一条没人管的通知被 App 每小时原样重发一遍,发了两天:只算一次(不能凭一条旧消息攒出好几天);
    // 安静了一天之后又来的同一句,才是新的一次
    @Test fun aNotificationRepostedForDaysCountsOnce() {
        val hour = 3_600_000L
        val b = LearnBuffer()
        assertTrue(b.capture(rec("stale", at = 0, text = "您有一张优惠券")))
        for (h in 1..48) assertFalse("$h", b.capture(rec("stale", at = h * hour, text = "您有一张优惠券")))
        assertEquals(1, b.pendingCount)
        assertTrue(b.capture(rec("stale", at = (48 + 24) * hour, text = "您有一张优惠券")))
        // 到期看的还是第一次发出的时间(重发不把它往后推)
        assertEquals("com.shop", b.due(LearnTuning.DUE_AGE_MS, { true }))
    }

    // 32 位的 hashCode 会把「Aa」和「BB」当成同一句;现在的指纹不会
    @Test fun differentTextsWithTheSameHashCodeAreBothKept() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val b = LearnBuffer()
        assertTrue(b.capture(rec("k", title = "t", text = "Aa")))
        assertTrue(b.capture(rec("k", title = "t", text = "BB")))
        assertEquals(2, b.pendingCount)
    }

    @Test fun textIsCappedAtCapture() {
        val b = LearnBuffer()
        b.capture(rec("a", title = "t".repeat(500), text = "x".repeat(900)))
        val e = b.begin("com.shop").single()
        assertEquals(LearnTuning.TITLE_MAX, e.title.length)
        assertEquals(LearnTuning.TEXT_MAX, e.text.length)
    }

    @Test fun dueByCountOrAge() {
        val b = LearnBuffer()
        repeat(LearnTuning.DUE_COUNT - 1) { b.capture(rec("k$it", at = 1_000)) }
        assertNull(b.due(1_000, { true }))
        assertNull(b.due(1_000 + LearnTuning.DUE_AGE_MS - 1, { true }))
        assertEquals("com.shop", b.due(1_000 + LearnTuning.DUE_AGE_MS, { true }))
        b.capture(rec("last", at = 1_000))
        assertEquals("com.shop", b.due(1_000, { true }))
        assertNull(b.due(1_000) { it != "com.shop" })
    }

    @Test fun oldestDueAppGoesFirstAndBatchIsBounded() {
        val b = LearnBuffer()
        repeat(20) { b.capture(rec("s$it", at = 100L + it)) }
        b.capture(rec("m", pkg = "com.mall", at = 50))
        assertEquals("com.mall", b.due(50 + LearnTuning.DUE_AGE_MS, { true }))
        val batch = b.begin("com.shop")
        assertEquals(LearnTuning.BATCH_MAX, batch.size)
        assertEquals((0 until LearnTuning.BATCH_MAX).map { "s$it" }, batch.map { it.key })
        assertEquals(LearnBuffer.Summary(21, 9), b.state.value)
    }

    @Test fun abortFailFinish() {
        val b = LearnBuffer()
        b.capture(rec("a")); b.capture(rec("b"))
        b.begin("com.shop")
        b.abort()
        assertEquals(2, b.pendingCount)
        val batch = b.begin("com.shop")
        assertEquals(2, b.fail())
        assertEquals(LearnBuffer.Summary(0, 0), b.state.value)
        b.capture(rec("c")); b.capture(rec("d"))
        val again = b.begin("com.shop")
        b.finish(mapOf(again[0].seq to setOf("k1")))
        assertEquals(LearnBuffer.Summary(2, 0), b.state.value)
        assertTrue(batch.isNotEmpty())
        // 归纳完的不留文字
        assertTrue(b.sending().isEmpty())
    }

    @Test fun outcomeGoesToTheLatestEventOfTheKeyOnce() {
        val b = LearnBuffer()
        b.capture(rec("a", text = "one"))
        b.capture(rec("a", text = "two"))
        assertTrue(b.outcome("a", LearnOutcome.Opened).isEmpty())
        val sent = b.begin("com.shop")
        assertEquals(listOf(LearnOutcome.None, LearnOutcome.Opened), sent.map { it.outcome })
        // 已经有结果的不改
        b.outcome("a", LearnOutcome.Dismissed)
        assertEquals(LearnOutcome.Opened, b.sending().last().outcome)
        assertTrue(b.outcome("missing", LearnOutcome.Opened).isEmpty())
    }

    @Test fun lateOutcomeOnDistilledEventReturnsItsTopics() {
        val b = LearnBuffer()
        b.capture(rec("a"))
        val e = b.begin("com.shop").single()
        b.outcome("a", LearnOutcome.None)
        b.finish(mapOf(e.seq to setOf("k1", "k2")))
        assertEquals(setOf("k1", "k2"), b.outcome("a", LearnOutcome.Dismissed))
        assertTrue(b.outcome("a", LearnOutcome.Opened).isEmpty())
        b.capture(rec("z"))
        val z = b.begin("com.shop").single()
        b.finish(mapOf(z.seq to setOf("k1")))
        b.forgetTopics(setOf("k1"))
        assertTrue(b.outcome("z", LearnOutcome.Opened).isEmpty())
    }

    // 话题被用户并进了另一个:归到它上面的通知之后才被点开/划掉,算到并进去的那个上
    @Test fun eventsOfAMergedTopicPointAtTheTopicItWentInto() {
        val b = LearnBuffer()
        b.capture(rec("a")); b.capture(rec("z"))
        val (a, z) = b.begin("com.shop")
        b.finish(mapOf(a.seq to setOf("k1", "k2"), z.seq to setOf("k3")))
        b.remapTopic("k1", "k2")
        b.remapTopic("k3", "k4")
        assertEquals(setOf("k2"), b.outcome("a", LearnOutcome.Opened))
        assertEquals(setOf("k4"), b.outcome("z", LearnOutcome.Dismissed))
    }

    // 「标题是发言人」这一笔跟着事件走(认联系人时要看)
    @Test fun theSpeakerTitleMarkTravelsWithTheEvent() {
        val b = LearnBuffer()
        b.capture(rec("a").copy(group = true, titleIsSpeaker = true))
        b.capture(rec("b"))
        assertEquals(listOf(true, false), b.begin("com.shop").map { it.titleIsSpeaker })
    }

    // 被认成状态显示的那条通知之后被划掉:它的事件已经去掉了,不往回找同一个键上更早的、不相干的那条算反馈
    @Test fun dismissingAWithdrawnStatusDisplayDoesNotReachAnOlderEventOfTheKey() {
        val b = LearnBuffer()
        b.capture(rec("shared", at = 1_000, text = "一小时前的正常通知"))
        val old = b.begin("com.shop").single()
        b.finish(mapOf(old.seq to setOf("k1")))
        repeat(3) { i -> b.capture(rec("shared", at = 3_600_000L + i * 2_000L, text = "${300 - i * 10} 米后右转")) }
        assertTrue(b.forgetKey("shared", 3_600_000L - 60_000L))
        assertTrue(b.outcome("shared", LearnOutcome.Dismissed).isEmpty())
        // 只挡这一次:这个键上又收下新的一条之后照常
        b.forgetKey("shared", 7_200_000L)
        b.capture(rec("shared", at = 9_000_000L, text = "新的正常通知"))
        val next = b.begin("com.shop").single()
        b.finish(mapOf(next.seq to setOf("k2")))
        assertEquals(setOf("k2"), b.outcome("shared", LearnOutcome.Opened))
    }

    @Test fun evictsDistilledFirstThenOldestPending() {
        val b = LearnBuffer(capacity = 3)
        b.capture(rec("a")); b.capture(rec("b"))
        b.begin("com.shop"); b.finish(emptyMap())
        b.capture(rec("c")); b.capture(rec("d")); b.capture(rec("e"))
        assertEquals(LearnBuffer.Summary(3, 3), b.state.value)
        b.capture(rec("f"))
        assertEquals(listOf("d", "e", "f"), b.begin("com.shop").map { it.key })
    }

    @Test fun purgeAndClear() {
        val b = LearnBuffer()
        b.capture(rec("a")); b.capture(rec("b", pkg = "com.chat"))
        b.purge { it != "com.chat" }
        assertEquals(LearnBuffer.Summary(1, 1), b.state.value)
        b.clear()
        assertEquals(LearnBuffer.Summary(0, 0), b.state.value)
    }
}
