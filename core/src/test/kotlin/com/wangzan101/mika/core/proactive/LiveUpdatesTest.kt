package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.proactive.LiveUpdates.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdatesTest {
    private val t0 = 1_000_000_000L
    private fun post(key: String, at: Long, text: String, silent: Boolean = true, category: String? = null, pkg: String = "com.maps") =
        NoticeRecord(key, pkg, at, "标题", text, category = category, silent = silent)

    // 很多 App 所有推送共用一个通知键:一分钟里来了几条各不相干的,用户一条条点开、划掉了——不是同一条在重发
    @Test fun notificationsTheUserDealtWithOneByOneAreNotCountedAsOneBeingReposted() {
        val live = LiveUpdates()
        repeat(6) { i ->
            assertEquals(Verdict.Normal, live.observe(post("shared", t0 + i * 10_000L, "第 $i 件事")))
            live.ended("shared")
        }
        // 没处理掉的照旧数
        val other = LiveUpdates()
        assertEquals(listOf(Verdict.Normal, Verdict.Normal, Verdict.Normal, Verdict.BecameLive),
            (0 until 4).map { i -> other.observe(post("shared", t0 + i * 10_000L, "第 $i 件事")) })
        // 认成状态显示的那条被划掉:同一个键的下一条从头数;真在重发的,数够了照样再认出来
        other.ended("shared")
        assertEquals(listOf(Verdict.Normal, Verdict.Normal, Verdict.Normal, Verdict.BecameLive, Verdict.Live),
            (5 until 10).map { i -> other.observe(post("shared", t0 + i * 2_500L + 60_000L, "${i} 米后右转")) })
        other.ended("nothing")
    }

    // 真机上看到的导航播报:一条普通的可清除通知,没有类别,不出声,每两三秒重发一次,距离一变就是另一段字
    @Test fun aSilentNotificationRepostedEveryFewSecondsIsAStatusDisplay() {
        val live = LiveUpdates()
        val verdicts = (0 until 40).map { i -> live.observe(post("nav", t0 + i * 2_500L, "${300 - i * 5} 米后右转")) }
        assertEquals(listOf(Verdict.Normal, Verdict.Normal, Verdict.Normal, Verdict.BecameLive), verdicts.take(4))
        assertTrue(verdicts.drop(4).all { it == Verdict.Live })
        // 停了不到安静期又接着发:还是不收
        assertEquals(Verdict.Live, live.observe(post("nav", t0 + 39 * 2_500L + LiveUpdates.QUIET_MS - 1, "到达目的地附近")))
        // 安静够久之后,这个键上再来的照常收
        val later = t0 + 40 * 2_500L + 3 * LiveUpdates.QUIET_MS
        assertEquals(Verdict.Normal, live.observe(post("nav", later, "新的一条")))
        // 别的键不受影响
        assertEquals(Verdict.Normal, live.observe(post("promo", t0 + 10_000L, "满减券到账", pkg = "com.shop")))
    }

    // 隔得开的更新不算:半分钟一次、几分钟一次的状态变化是一件件的事
    @Test fun spacedOutUpdatesAreOrdinaryNotifications() {
        val live = LiveUpdates()
        assertTrue((0 until 30).all { live.observe(post("order", t0 + it * 25_000L, "订单状态 $it")) == Verdict.Normal })
    }

    // 会出声的通知要密得多才算:一分钟里第十二次
    @Test fun aNotificationThatAlertsNeedsFarMoreReposts() {
        val live = LiveUpdates()
        val verdicts = (0 until 14).map { live.observe(post("ticker", t0 + it * 4_000L, "第 $it 次", silent = false)) }
        assertTrue(verdicts.take(LiveUpdates.LOUD_POSTS - 1).all { it == Verdict.Normal })
        assertEquals(Verdict.BecameLive, verdicts[LiveUpdates.LOUD_POSTS - 1])
        assertEquals(Verdict.Live, verdicts.last())
        // 不到这么密的不算(五秒一次,一分钟十二次整,窗口里最多十二次——这里放慢到六秒)
        val slower = LiveUpdates()
        assertTrue((0 until 60).all { slower.observe(post("x", t0 + it * 6_000L, "第 $it 次", silent = false)) == Verdict.Normal })
    }

    // 聊天不看:一个会话的通知来一句更新一次是它本来的样子(会话样式、消息类别、按聊天写法拆开了的)
    @Test fun conversationsAreNeverTreatedAsStatusDisplays() {
        val styled = { at: Long -> NoticeRecord("c", "com.chat", at, "群", "一句", listOf(NoticeMessage("甲", "一句", at)), conversation = true, silent = true) }
        val byCategory = { at: Long -> post("m", at, "张三: 一句", category = "msg") }
        val shaped = { at: Long -> NoticeRecord("q", "com.chat.other", at, "群", "一句", sender = "甲", group = true, silent = true) }
        for (make in listOf(styled, byCategory, shaped)) {
            val live = LiveUpdates()
            assertTrue((0 until 50).all { live.observe(make(t0 + it * 1_000L)) == Verdict.Normal })
        }
    }

    // 同一次发出再看一遍(补收时把通知栏里的又读了一遍)不多算一次
    @Test fun readingTheSamePostAgainDoesNotCount() {
        val live = LiveUpdates()
        repeat(20) { assertEquals(Verdict.Normal, live.observe(post("nav", t0, "300 米后右转"))) }
        assertEquals(Verdict.Normal, live.observe(post("nav", t0 + 1_000L, "290 米后右转")))
    }

    // 只记最近用到的那些键
    @Test fun onlyTheMostRecentKeysAreRemembered() {
        val live = LiveUpdates(capacity = 2)
        repeat(3) { live.observe(post("a", t0 + it * 1_000L, "a$it")) }
        live.observe(post("b", t0, "b"))
        live.observe(post("c", t0, "c"))
        // a 被挤出去了:从头数
        assertEquals(Verdict.Normal, live.observe(post("a", t0 + 3_000L, "a3")))
    }
}
