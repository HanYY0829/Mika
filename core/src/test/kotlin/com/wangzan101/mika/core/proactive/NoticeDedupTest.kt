package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeDedupTest {
    private fun rec(key: String, at: Long, vararg msgAt: Long) = NoticeRecord(key, "com.tencent.mm", at, "t", "x",
        messages = msgAt.map { NoticeMessage("a", "m$it", it) }, conversation = msgAt.isNotEmpty())

    @Test fun samePostIsSeenOnce() {
        val d = NoticeDedup()
        assertTrue(d.accept(rec("k", 1)))
        assertFalse(d.accept(rec("k", 1)))
        assertTrue(d.accept(rec("k", 2)))
        assertTrue(d.accept(rec("other", 2)))
    }

    @Test fun messagingUpdatesCountOnlyNewerMessages() {
        val d = NoticeDedup()
        assertTrue(d.accept(rec("chat", 10, 5, 8)))
        // 重新发出(发出时间变了)但没有新消息:不算新
        assertFalse(d.accept(rec("chat", 11, 5, 8)))
        assertTrue(d.accept(rec("chat", 12, 5, 8, 9)))
        assertFalse(d.accept(rec("chat", 13, 8, 9)))
    }

    @Test fun boundedLeastRecentlyUsed() {
        val d = NoticeDedup(capacity = 3)
        d.accept(rec("a", 1)); d.accept(rec("b", 1)); d.accept(rec("c", 1))
        assertFalse(d.accept(rec("a", 1))) // 用过一次,a 变成最近的
        d.accept(rec("d", 1)) // 挤掉最久没用的 b
        assertEquals(3, d.size())
        assertTrue(d.accept(rec("b", 1)))
    }

    @Test fun roundTripKeepsStateAndHostileKeys() {
        val d = NoticeDedup()
        val nasty = "0|pkg|1|\"],\n\t[\\u0000|x"
        d.accept(rec(nasty, 7))
        d.accept(rec("chat", 10, 5, 8))
        val back = NoticeDedup.decode(d.encode())
        assertEquals(2, back.size())
        assertFalse(back.accept(rec(nasty, 7)))
        assertFalse(back.accept(rec("chat", 11, 8)))
        assertTrue(back.accept(rec("chat", 12, 9)))
    }

    @Test fun garbageDecodesToEmpty() {
        for (s in listOf(
            null, "", "not json", "[[1]]", "{\"a\":1}", "[[\"k\",\"x\",1]]",
            "{\"v\":2,\"seen\":[[\"k\",1,1]]}", // id 不像 id
            "{\"v\":2,\"seen\":[[1234567890123456,1,1]]}", // id 不是字符串
            "{\"v\":3,\"seen\":[]}",
        )) assertEquals(s, 0, NoticeDedup.decode(s).size())
    }

    // 存下来的东西里没有原始的键,连一小段都没有
    @Test fun encodedFormHasNoRawKey() {
        val keys = listOf("0|com.tencent.mm|1|wxid_abcdefg12345@chatroom|10123", "0|p|7|secret-tag|0")
        val d = NoticeDedup()
        keys.forEach { d.accept(rec(it, 1)) }
        val out = d.encode()
        for (k in keys) for (part in k.split('|').filter { it.length >= 4 }) assertFalse(part, out.contains(part))
        assertEquals(16, NoticeDedup.idOf(keys[0]).length)
        assertTrue(NoticeDedup.idOf(keys[0]).all { it in "0123456789abcdef" })
        assertEquals(NoticeDedup.idOf(keys[0]), NoticeDedup.idOf(keys[0]))
        assertFalse(NoticeDedup.idOf(keys[0]) == NoticeDedup.idOf(keys[1]))
        // SHA-256("abc") 的前 16 位
        assertEquals("ba7816bf8f01cfea", NoticeDedup.idOf("abc"))
    }

    // 旧格式(直接记原始键,v1)读到就当空的,不会被当成新格式再原样写回去
    @Test fun oldFormatDecodesToEmpty() {
        val v1 = "[[\"0|com.tencent.mm|1|wxid_abc@chatroom|10\",1700000000000,-9223372036854775808],[\"0123456789abcdef\",1,1]]"
        val back = NoticeDedup.decode(v1)
        assertEquals(0, back.size())
        assertFalse(back.encode().contains("wxid"))
    }

    @Test fun decodeRespectsCapacity() {
        val d = NoticeDedup()
        (1..10).forEach { d.accept(rec("k$it", 1)) }
        val back = NoticeDedup.decode(d.encode(), capacity = 4)
        assertEquals(4, back.size())
        assertFalse(back.accept(rec("k10", 1)))
        assertTrue(back.accept(rec("k1", 1)))
    }

    // ---- 消息的时间是 App 填的,不能全信 ----

    private fun chat(at: Long, vararg messages: Pair<String, Long>) = NoticeRecord("chat", "com.chat", at, "t", "x",
        messages = messages.map { (text, time) -> NoticeMessage("a", text, time) }, conversation = true)

    // 填到将来的时间:不能记成水位,不然之后真的新消息永远比它旧
    @Test fun aFutureMessageTimeDoesNotHideLaterMessages() {
        val d = NoticeDedup()
        assertTrue(d.accept(chat(100, "first" to 9_999_999)))
        assertTrue(d.accept(chat(200, "first" to 9_999_999, "second" to 150)))
        assertFalse(d.accept(chat(300, "first" to 9_999_999, "second" to 150)))
        // 水位里没有那个将来的时间
        assertFalse("9999999" in d.encode())
    }

    // 都填 0、几条挤在同一毫秒:新消息照样算新;原样重发的(发出时间变了)不算
    @Test fun zeroAndSameMillisecondTimesStillShowNewMessages() {
        val zero = NoticeDedup()
        assertTrue(zero.accept(chat(100, "one" to 0)))
        assertFalse(zero.accept(chat(110, "one" to 0)))
        assertTrue(zero.accept(chat(120, "one" to 0, "two" to 0)))
        assertFalse(zero.accept(chat(130, "one" to 0, "two" to 0)))
        val same = NoticeDedup()
        assertTrue(same.accept(chat(100, "one" to 50)))
        assertTrue(same.accept(chat(110, "one" to 50, "two" to 50)))
        assertFalse(same.accept(chat(120, "one" to 50, "two" to 50)))
        // App 把最旧的一条挤出了列表:没有新消息,不算新
        assertFalse(same.accept(chat(130, "two" to 50)))
    }

    // 哪几条是这回才见到的(给归纳用):第一次是全部,之后只有新的;不是会话样式的没有
    @Test fun admitSaysWhichMessagesAreNew() {
        val d = NoticeDedup()
        assertEquals(listOf("one", "two"), d.admit(chat(100, "one" to 10, "two" to 20))!!.newMessages.map { it.text })
        assertEquals(listOf("three"), d.admit(chat(200, "one" to 10, "two" to 20, "three" to 0))!!.newMessages.map { it.text })
        assertEquals(null, d.admit(chat(300, "two" to 20, "three" to 0)))
        assertEquals(emptyList<NoticeMessage>(), d.admit(rec("plain", 1))!!.newMessages)
    }

    // 进程重启后(指纹没了)头一回只能看时间:时间信得过的照常;原样重发的不算新
    @Test fun afterARestartTimesDecideTheFirstSighting() {
        val before = NoticeDedup()
        before.accept(chat(100, "one" to 10, "two" to 20))
        val saved = before.encode()
        val repost = NoticeDedup.decode(saved)
        assertFalse(repost.accept(chat(300, "one" to 10, "two" to 20)))
        // 那一回之后又有得比了
        assertTrue(repost.accept(chat(310, "one" to 10, "two" to 20, "three" to 0)))
        val newer = NoticeDedup.decode(saved)
        assertEquals(listOf("three"), newer.admit(chat(300, "two" to 20, "three" to 250))!!.newMessages.map { it.text })
        // 发消息的服务器的钟比手机快一点(消息的时间比通知的发出时间晚一秒):照样信,照样算新
        val skewed = NoticeDedup.decode(saved)
        assertEquals(listOf("three"), skewed.admit(chat(300, "two" to 20, "three" to 1_300))!!.newMessages.map { it.text })
        assertFalse(skewed.accept(chat(310, "two" to 20, "three" to 1_300)))
        // 头一回时间又信不过的:算见过(没法知道),下一回起照常
        val blind = NoticeDedup.decode(saved)
        assertFalse(blind.accept(chat(300, "two" to 20, "three" to 0)))
        assertTrue(blind.accept(chat(310, "two" to 20, "three" to 0, "four" to 0)))
    }

    // 存着的水位比通知的发出时间还晚(被 App 填的将来时间弄坏的):不可能是真的,当没有
    @Test fun aSavedWatermarkFromTheFutureIsIgnored() {
        val id = NoticeDedup.idOf("chat")
        val poisoned = NoticeDedup.decode("""{"v":2,"seen":[["$id",100,9999999]]}""")
        assertTrue(poisoned.accept(chat(200, "one" to 150)))
        assertFalse(poisoned.accept(chat(210, "one" to 150)))
        assertTrue(poisoned.accept(chat(220, "one" to 150, "two" to 215)))
        assertFalse("9999999" in poisoned.encode())
    }

    // 中间来了一回不带消息的(都是空的):上回消息的指纹还留着,下回带了照样比内容
    @Test fun aSightingWithoutMessagesKeepsWhatWasSeen() {
        val d = NoticeDedup()
        assertTrue(d.accept(chat(100, "one" to 0)))
        assertTrue(d.accept(NoticeRecord("chat", "com.chat", 110, "t", "x", conversation = true)))
        assertFalse(d.accept(chat(120, "one" to 0)))
        assertTrue(d.accept(chat(130, "one" to 0, "two" to 0)))
    }

    // 存下来的还是原来的样子:键的 id、发出时间、最新消息时间;没有内容的指纹
    @Test fun encodedFormIsUnchanged() {
        val d = NoticeDedup()
        d.accept(chat(100, "one" to 10, "two" to 20))
        assertEquals("""{"v":2,"seen":[["${NoticeDedup.idOf("chat")}",100,20]]}""", d.encode())
    }
}
