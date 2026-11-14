package com.wangzan101.mika.proactive

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.agent.NoticeTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.proactive.NoticeHint
import com.wangzan101.mika.core.proactive.NoticeHints
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 提醒对话:用户点开提醒时,Mika 把挑出来的几条通知写成自己的一条消息放进去(消息怎么写见
 * [com.wangzan101.mika.core.proactive.NoticeTalk];那一轮怎么认、怎么对待见 [NoticeTurn])。
 *
 * - 一天一个:当天再点开,接着写在同一个对话里;过了一天、或那个对话被用户删了,就新开一个。
 *   「今天是哪一个」记在 [ProactivePrefs.hintTalk] 里(只有对话 id 和日子)。
 * - 写完把它设成「用户此刻所在的对话」:悬浮窗的卡片接着它,用户在卡片里回一句,任务就在这个对话里跑;
 *   回到 Mika 里看到的也是同一个对话,可以接着说。
 * - 通知的标题和正文到这一刻才落盘(对话文件,和别的对话一样不加密):没点开的提醒不写。
 */
class NoticeTalks(
    private val store: ConversationStore,
    private val prefs: ProactivePrefs,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 要写的那条消息,和新开对话时用的标题。[shown] 是消息里真列出来的条数(交给它的那几条里的前几条):
     * 只有这几条记为看过。一条消息列不下的不能跟着算看过——用户没见到它们,提醒的数字却清零了,就再也看不到了。
     */
    class Draft(val message: String, val title: String, val shown: Int)

    private val lock = Mutex()

    /**
     * 用户点开了提醒:把 [board] 里还没看的几条(新的在前)交给 [draft] 写成一条消息,放进今天的提醒对话,这几条记为看过。
     * 返回那个对话的 id。[draft] 给 null(没有可写的):什么都不写,返回今天已有的提醒对话(没有为 null)。
     *
     * 一次只做一个:连点两下、两个入口同时点,后一个要等前一个写完、记为看过,所以同样几条不会写两遍。
     * 只把消息里列出来的那几条记为看过([Draft.shown];写的过程中新来的、一条消息列不下的都不算——再点一次接着列)。
     * 写对话文件在 [io] 上;写不进去照常抛出,那几条不算看过。
     */
    suspend fun open(board: NoticeHints, nowMs: Long, draft: (List<NoticeHint>) -> Draft?): String? = lock.withLock {
        val fresh = board.state.value.list.filter { !it.seen }
        var shown = 0
        withContext(io) {
            val d = draft(fresh) ?: return@withContext today(nowMs)
            post(d.message, d.title, nowMs).also { shown = d.shown.coerceIn(0, fresh.size) }
        }.also { board.markSeen(fresh.take(shown)) }
    }

    /** 今天的提醒对话;没开过、已经是昨天的、被删了的为 null。 */
    @Synchronized fun today(nowMs: Long): String? =
        prefs.hintTalk?.takeIf { (id, day) -> day == dayOf(nowMs) && store.turns(id).isNotEmpty() }?.first

    /**
     * 把 [message] 写成提醒对话里 Mika 的一轮(没有用户的话),并把这个对话设为当前对话;[title] 只在新开对话时用。
     * 返回对话 id。写不进去照常抛出,由调用方决定(那几条提醒因此不算看过)。
     */
    @Synchronized fun post(message: String, title: String, nowMs: Long): String {
        val existing = today(nowMs)
        val id = existing ?: store.newConversation()
        store.append(id, Turn("", message, "succeeded", runId = NoticeTurn.RUN_PREFIX + newId()))
        if (existing == null) {
            // 第一轮没有用户的话,起不出标题(自动起名也只看还没有轮次的对话):自己写一个
            store.setTitle(id, title)
            prefs.hintTalk = id to dayOf(nowMs)
        }
        store.setCurrent(CurrentChat.Open(id))
        return id
    }

    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()
}
