package com.wangzan101.mika.core.proactive

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同一条通知(同一个键)先后显示过的一段文字;[atMs] 是那次的发出时间。 */
data class NoticeLine(val title: String, val text: String, val atMs: Long)

/**
 * 收件箱:只在内存里,最多 [capacity] 条,满了丢最旧的;同一个通知键的更新只留最新一条(挪到最新的位置)。
 * 有些 App(比如微信)每来一句就把同一条通知重发一遍、只换正文,不带会话样式的消息列表:
 * 这种通知另按键记下先后见过的不同文字(由旧到新,最多 [historyLimit] 段,和上一段一样的不记),免得前面的话被盖掉。
 * 带消息列表的通知自己就带着前面的消息,不另记。
 * 另记当天的两个计数:收到(过了白名单的)和筛后(过了筛选和去重、进了收件箱的);换了一天从零算。
 */
class NoticeInbox(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val historyLimit: Int = HISTORY_LIMIT,
) {
    /**
     * [records] 由旧到新,每条是这个键的最新状态;[day] 是计数所在那天(本地日期的纪元日)。
     * [history] 按键给出没有消息列表的通知先后见过的文字(由旧到新,最后一段就是记录现在的文字)。
     */
    data class Snapshot(
        val records: List<NoticeRecord>,
        val day: Long,
        val received: Int,
        val kept: Int,
        val history: Map<String, List<NoticeLine>> = emptyMap(),
    )

    private val records = LinkedHashMap<String, NoticeRecord>()
    private val history = HashMap<String, List<NoticeLine>>()
    private var day = today()
    private var received = 0
    private var kept = 0
    private val mutable = MutableStateFlow(Snapshot(emptyList(), day, 0, 0))
    val state: StateFlow<Snapshot> = mutable.asStateFlow()

    /** 一条过了白名单的通知到了(不管后面留不留)。 */
    @Synchronized fun noteReceived() {
        rollDay()
        received++
        publish()
    }

    /** 放进一条已筛过、去过重的记录。 */
    @Synchronized fun add(record: NoticeRecord) {
        rollDay()
        kept++
        records.remove(record.key)
        records[record.key] = record
        remember(record)
        while (records.size > capacity) records.remove(records.keys.first())
        dropOrphanHistory()
        publish()
    }

    /** 用户已经看过(点开或划掉)的通知。 */
    @Synchronized fun remove(key: String) {
        history.remove(key)
        if (records.remove(key) != null) publish()
    }

    /**
     * 和通知栏对一遍:[active] 是通知栏里现在有的全部通知的键,不在里面的记录去掉(锁屏期间被用户处理了、被 App 撤回了、
     * 超时了——那一阵监听是解绑的,没收到移除的回调)。返回去掉了几条。只能拿一次读成了的通知栏来对:读不出来不是「空的」。
     */
    @Synchronized fun retainKeys(active: Set<String>): Int {
        val gone = records.keys.filter { it !in active }
        if (gone.isEmpty()) return 0
        gone.forEach { records.remove(it); history.remove(it) }
        publish()
        return gone.size
    }

    /** 某个 App 不再勾选时,它的记录一并去掉。 */
    @Synchronized fun removePackages(keep: (String) -> Boolean) {
        if (records.values.removeAll { !keep(it.packageName) }) {
            dropOrphanHistory()
            publish()
        }
    }

    @Synchronized fun clear() {
        records.clear()
        history.clear()
        publish()
    }

    @Synchronized fun snapshot(): Snapshot {
        rollDay()
        publish()
        return mutable.value
    }

    /** 带消息列表的不另记(并丢掉以前记的);和上一段文字一样的不记;只留最近 [historyLimit] 段。 */
    private fun remember(record: NoticeRecord) {
        if (record.messages.isNotEmpty()) {
            history.remove(record.key)
            return
        }
        val before = history[record.key].orEmpty()
        val last = before.lastOrNull()
        // 群里的每一段带上是谁说的(见 [NoticeRecord.said])
        if (last != null && last.title == record.title && last.text == record.said) return
        history[record.key] = (before + NoticeLine(record.title, record.said, record.postedAtMs)).takeLast(historyLimit)
    }

    /** 记录没了(满了被挤掉、按包名去掉)的键,它的文字记录也不留。 */
    private fun dropOrphanHistory() {
        history.keys.retainAll(records.keys)
    }

    private fun today(): Long = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toEpochDay()

    private fun rollDay() {
        val now = today()
        if (now != day) { day = now; received = 0; kept = 0 }
    }

    private fun publish() {
        mutable.value = Snapshot(records.values.toList(), day, received, kept, HashMap(history))
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        const val HISTORY_LIMIT = 10
    }
}
