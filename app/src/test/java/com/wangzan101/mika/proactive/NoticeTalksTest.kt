package com.wangzan101.mika.proactive

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.proactive.NoticeHint
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.NoticeTalk
import com.wangzan101.mika.core.proactive.TriageDecision
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriageVerdict
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class NoticeTalksTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 86_400_000L
    private val t0 = 20_000 * day + 9 * 3_600_000L
    private val raw = FeatureFlagsCapsuleTest.MemoryPrefs()
    private val store by lazy { ConversationStore(tmp.newFolder("c")) }
    private var n = 0
    private fun talks(io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined) =
        NoticeTalks(store, ProactivePrefs(raw), { ZoneOffset.UTC }, { "id${++n}" }, io)

    @Test fun firstOpenStartsAConversationWithNoUserWords() {
        val t = talks()
        assertNull(t.today(t0))
        val id = t.post("picked 1", "Notifications · Oct 4", t0)
        val turn = store.turns(id).single()
        assertEquals("", turn.userText)
        assertEquals("picked 1", turn.outcome)
        assertEquals("succeeded", turn.status)
        assertEquals("notice:id1", turn.runId)
        assertTrue(NoticeTurn.isNotice(turn))
        // 设成了当前对话(悬浮窗的卡片接着它),侧栏里有、有标题
        assertEquals(CurrentChat.Open(id), store.currentChat())
        assertEquals("Notifications · Oct 4", store.list().single { it.id == id }.title)
        assertEquals(id, t.today(t0))
    }

    @Test fun sameDayContinuesInTheSameConversationEvenAfterTheUserReplied() {
        val t = talks()
        val id = t.post("picked 1", "title", t0)
        store.append(id, Turn("第一条帮我回个好的", "已回复", "succeeded", runId = "run-1"))
        store.setCurrent(CurrentChat.Fresh)
        assertEquals(id, t.post("picked 2", "another title", t0 + 3 * 3_600_000L))
        assertEquals(listOf("picked 1", "已回复", "picked 2"), store.turns(id).map { it.outcome })
        assertEquals(listOf(true, false, true), store.turns(id).map(NoticeTurn::isNotice))
        assertEquals("title", store.title(id))
        assertEquals(CurrentChat.Open(id), store.currentChat())
        // 换一个进程(同一份记录)也接得上
        assertEquals(id, talks().today(t0 + 4 * 3_600_000L))
    }

    @Test fun nextDayOrADeletedConversationStartsANewOne() {
        val t = talks()
        val first = t.post("a", "title", t0)
        assertNull(t.today(t0 + day))
        val second = t.post("b", "title 2", t0 + day)
        assertNotEquals(first, second)
        assertEquals(listOf("b"), store.turns(second).map { it.outcome })
        store.delete(second)
        assertNull(t.today(t0 + day))
        val third = t.post("c", "title 3", t0 + day)
        assertNotEquals(second, third)
    }

    // 分支带着提醒那一轮的,新对话照样认得出(保护跟着走);从提醒之前分出去的不带
    @Test fun branchingKeepsTheNoticeTurn() {
        store.append("x", Turn("你好", "你好!", "succeeded", runId = "r0"))
        val prefs = ProactivePrefs(raw)
        prefs.hintTalk = "x" to 20_000L
        val id = talks().post("picked", "title", t0)
        assertEquals("x", id)
        store.append(id, Turn("帮我回一下", "已回复", "succeeded", runId = "r1"))
        assertTrue(store.branch(id, "with", throughTurn = 2))
        assertTrue(NoticeTurn.tainted(store.turns("with")))
        assertTrue(store.branchBefore(id, "without", beforeTurn = 1))
        assertFalse(NoticeTurn.tainted(store.turns("without")))
        // 重新读(换一个进程)也还认得
        assertTrue(NoticeTurn.tainted(ConversationStore(tmp.root.resolve("c")).turns(id)))
    }

    private fun rec(key: String, posted: Long) = NoticeRecord(key, "com.chat", posted, "t-$key", "x-$key")
    private fun board(vararg rs: NoticeRecord) = NoticeHints().also { h ->
        h.sync(rs.toList(), rs.associate { it.key to TriageMark.Done(it.postedAtMs, TriageVerdict(TriageDecision.Surface, "why")) }, true)
    }
    private fun draft(fresh: List<NoticeHint>) = if (fresh.isEmpty()) null else NoticeTalks.Draft(fresh.joinToString(",") { it.key }, "title", fresh.size)

    @Test fun openWritesTheUnseenOnesAndMarksThemSeen() = runTest {
        val t = talks()
        val h = board(rec("a", 1), rec("b", 2))
        val id = t.open(h, t0, ::draft)!!
        assertEquals(listOf("b,a"), store.turns(id).map { it.outcome })
        assertEquals(0, h.state.value.unseen)
        // 没有新的:什么都不写,回到同一个对话
        assertEquals(id, t.open(h, t0 + 1, ::draft))
        assertEquals(1, store.turns(id).size)
        // 一个都没有、今天也没开过:null
        assertNull(talks().open(NoticeHints(), t0 + day, ::draft))
    }

    // 连点两下、两个入口同时点:同样几条只写一遍
    @Test fun overlappingOpensWriteTheSameHintsOnce() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val t = talks(io)
        val h = board(rec("a", 1), rec("b", 2))
        val one = async { t.open(h, t0, ::draft) }
        val two = async { t.open(h, t0, ::draft) }
        advanceUntilIdle()
        val id = one.await()!!
        assertEquals(id, two.await())
        assertEquals(listOf("b,a"), store.turns(id).map { it.outcome })
        assertEquals(2, h.state.value.seenTotal)
    }

    // 写的过程中新来的提醒没给用户看过:不算看过,下次点开再写
    @Test fun hintsArrivingDuringTheWriteStayUnseen() = runTest {
        val t = talks()
        val a = rec("a", 1); val b = rec("b", 2)
        val h = board(a)
        val id = t.open(h, t0) { fresh ->
            h.sync(listOf(a, b), listOf(a, b).associate { it.key to TriageMark.Done(it.postedAtMs, TriageVerdict(TriageDecision.Surface, "why")) }, true)
            draft(fresh)
        }!!
        assertEquals(listOf("a"), store.turns(id).map { it.outcome })
        assertEquals(listOf("b"), h.state.value.list.filter { !it.seen }.map { it.key })
        assertEquals(id, t.open(h, t0 + 1, ::draft))
        assertEquals(listOf("a", "b"), store.turns(id).map { it.outcome })
    }

    // 写不进去:抛出来,那几条不算看过
    @Test fun aFailedWriteLeavesTheHintsUnseen() = runTest {
        val h = board(rec("a", 1))
        try {
            talks().open(h, t0) { throw java.io.IOException("disk") }
            fail()
        } catch (e: java.io.IOException) {}
        assertEquals(1, h.state.value.unseen)
    }

    // 一条消息最多列 6 条:第 7 条没写进对话,就不能算看过——它留着,提醒的数字还在,再点一次接着列出来
    @Test fun hintsThatDidNotFitInTheMessageStayUnseen() = runTest {
        val store = ConversationStore(tmp.newFolder("conversations-fit"))
        val talks = NoticeTalks(store, ProactivePrefs(FeatureFlagsCapsuleTest.MemoryPrefs()), { ZoneOffset.UTC }, io = Dispatchers.Unconfined)
        val records = (1..7).map { NoticeRecord("k$it", "com.chat", it.toLong(), "title$it", "unique-private-text-$it") }
        val board = NoticeHints().also { h ->
            h.sync(records, records.associate { it.key to TriageMark.Done(it.postedAtMs, TriageVerdict(TriageDecision.Surface, "why")) }, true)
        }
        val copy = NoticeTalk.Copy({ "showing $it" }, { "reason $it" }, { "and $it more" }, "reply if needed")
        // 和 ProactiveRuntime.openHintTalk 一样的写法
        val draft: (List<NoticeHint>) -> NoticeTalks.Draft? = { fresh ->
            NoticeTalk.compose(fresh.map { NoticeTalk.Item("chat", "09:00", it.title, it.text, it.reason) }, copy)
                ?.let { NoticeTalks.Draft(it.text, "notices", it.shown) }
        }
        assertEquals(7, board.state.value.unseen)
        val conversation = talks.open(board, 1_700_000_000_000, draft)!!
        val first = store.turns(conversation).single().outcome
        assertFalse("unique-private-text-1" in first)
        for (i in 2..7) assertTrue("$i", "unique-private-text-$i" in first)
        assertTrue("and 1 more" in first)
        assertEquals(1, board.state.value.unseen)
        // 再点一次:剩下的那一条列出来,这回都看过了
        assertEquals(conversation, talks.open(board, 1_700_000_000_000, draft))
        val second = store.turns(conversation).last().outcome
        assertTrue("unique-private-text-1" in second)
        assertFalse("unique-private-text-2" in second)
        assertEquals(0, board.state.value.unseen)
        assertEquals(2, store.turns(conversation).size)
    }
}
