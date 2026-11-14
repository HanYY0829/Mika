package com.wangzan101.mika.core.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 用户拿这条系统通知做了什么:是我们自己从系统看到的(监听的移除原因),不是通知里写的。 */
enum class LearnOutcome {
    /** 还在通知栏里,或被「全部清除」一并清掉(不说明什么)。 */
    None,
    /** 点开了。 */
    Opened,
    /** 单独划掉了。 */
    Dismissed,
}

/**
 * 留着待归纳的一条通知。[title]、[text] 是清洗过的通知文字(第三方写的,不可信),归纳完([state] 为 Done)就清空,
 * 只留下它被归到了哪些话题([topics]),好让之后才到的点开/划掉也能算到那些话题上。
 * [sig] 是这个键当时文字的指纹(SHA-256 的前 8 个字节;文字归纳完就清掉了,只能靠它比),同一个键同样的文字
 * 在 [LearnTuning.REPEAT_WINDOW_MS] 之内只记一次。
 */
data class LearnEvent(
    val seq: Long,
    val key: String,
    val packageName: String,
    val postedAtMs: Long,
    val title: String,
    val text: String,
    val outcome: LearnOutcome = LearnOutcome.None,
    val state: State = State.Pending,
    val topics: Set<String> = emptySet(),
    val sig: Long = 0,
    /** 这条通知来自群聊(true)还是私聊(false);看不出来为 null(见 [NoticeRecord.group])。是我们按通知的写法拆出来的,不是模型说的。 */
    val group: Boolean? = null,
    /** 这段文字最近一次出现(原样重发也算)的时间:离它够久再出现才算新的一次(见 [LearnTuning.REPEAT_WINDOW_MS])。 */
    val seenAtMs: Long = postedAtMs,
) {
    enum class State { Pending, Sending, Done }
}

/**
 * 归纳用的事件缓冲:只在内存里(进程没了就没了,可以接受:归纳本来就是攒统计),和收件箱各管各的——
 * 收件箱里的记录被点开、划掉后,这里的事件还在。最多 [capacity] 条,满了先丢已归纳过的最旧的,再丢待归纳的最旧的(在发的不丢)。
 * 不是线程安全的:都在 ProactiveRuntime 那条串行协程上调;界面只读 [state]。
 */
class LearnBuffer(private val capacity: Int = LearnTuning.BUFFER_CAP) {
    /** 给界面的数:总共留着几条,其中几条待归纳。 */
    data class Summary(val total: Int = 0, val pending: Int = 0)

    private val events = ArrayList<LearnEvent>()
    private var seq = 0L
    private val mutable = MutableStateFlow(Summary())
    val state: StateFlow<Summary> = mutable.asStateFlow()

    /**
     * 收件箱收下了一条(已清洗)。同一个键同样的标题和正文在 [LearnTuning.REPEAT_WINDOW_MS] 之内只记一次
     * (隔得更久的是新的一次,比如第二天又发来的同一句话);都是空的不记。记下了返回 true。
     *
     * [newMessages] 是这条通知的消息列表里这回才见到的几条(会话样式的通知;见 [NoticeDedup.admit]):有的话,
     * 归纳用的那句话就是它们(由旧到新,放不下时留新的),不是通知的正文——正文常常是空的,或只有最后一句,
     * 只看正文的话锁屏期间攒下的前几句就归纳不到了。判定和通知原文也是看消息列表的。
     */
    fun capture(record: NoticeRecord, newMessages: List<NoticeMessage> = emptyList()): Boolean {
        val title = NoticeFilter.clean(record.title, LearnTuning.TITLE_MAX)
        val text = NoticeFilter.clean(if (newMessages.isEmpty()) record.said else lines(newMessages), LearnTuning.TEXT_MAX)
        if (title.isEmpty() && text.isEmpty()) return false
        val sig = fingerprint(title + "\u0000" + text)
        // 同样的文字:离它上一次出现不久的只是重发(一条没人管的通知被 App 反复重发,不能每半天算一次),记下「刚又出现过」就行;
        // 安静了够久再出现的才是新的一次
        val i = events.indexOfLast { it.key == record.key && it.sig == sig }
        if (i >= 0 && kotlin.math.abs(record.postedAtMs - events[i].seenAtMs) < LearnTuning.REPEAT_WINDOW_MS) {
            events[i] = events[i].copy(seenAtMs = maxOf(events[i].seenAtMs, record.postedAtMs))
            return false
        }
        events += LearnEvent(++seq, record.key, record.packageName, record.postedAtMs, title, text, sig = sig, group = record.group)
        evict()
        publish()
        return true
    }

    /** 几条消息并成一句(带着谁说的,由旧到新):最多 [LearnTuning.NEW_LINES_MAX] 条、总共不超过 [LearnTuning.TEXT_MAX] 个字,放不下时留新的。 */
    private fun lines(messages: List<NoticeMessage>): String {
        val picked = ArrayList<String>()
        var chars = 0
        for (m in messages.asReversed()) {
            if (picked.size >= LearnTuning.NEW_LINES_MAX) break
            val line = NoticeFilter.clean(listOfNotNull(m.sender, m.text).joinToString(": "), LearnTuning.TEXT_MAX)
            if (line.isEmpty()) continue
            val cost = line.length + if (picked.isEmpty()) 0 else LINE_BREAK.length
            if (picked.isNotEmpty() && chars + cost > LearnTuning.TEXT_MAX) break
            picked += line
            chars += cost
        }
        return picked.asReversed().joinToString(LINE_BREAK)
    }

    /**
     * 系统说这条通知被点开/划掉了:记到这个键最新的那条事件上(用户点的是通知栏里现在那段文字),只记一次。
     * 那条已经归纳过了的,返回它归到的话题(由调用方补到话题池上);否则返回空。
     */
    fun outcome(key: String, outcome: LearnOutcome): Set<String> {
        if (outcome == LearnOutcome.None) return emptySet()
        val i = events.indexOfLast { it.key == key }
        if (i < 0) return emptySet()
        val e = events[i]
        if (e.outcome != LearnOutcome.None) return emptySet()
        events[i] = e.copy(outcome = outcome)
        return if (e.state == LearnEvent.State.Done) e.topics else emptySet()
    }

    /**
     * 现在该发哪个 App:只看 [eligible] 认的 App 里待归纳的;攒够 [LearnTuning.DUE_COUNT] 条,或最旧的一条已过了
     * [LearnTuning.DUE_AGE_MS],就算到期。几个都到期时先发最旧的那个。没有到期的为 null。
     */
    fun due(nowMs: Long, eligible: (String) -> Boolean): String? = events
        .filter { it.state == LearnEvent.State.Pending && eligible(it.packageName) }
        .groupBy { it.packageName }
        .filter { (_, list) -> list.size >= LearnTuning.DUE_COUNT || nowMs - list.minOf { it.postedAtMs } >= LearnTuning.DUE_AGE_MS }
        .minByOrNull { (_, list) -> list.minOf { it.postedAtMs } }
        ?.key

    val pendingCount: Int get() = events.count { it.state == LearnEvent.State.Pending }

    /** 开一批:这个 App 最旧的待归纳的最多 [LearnTuning.BATCH_MAX] 条,标成在发。 */
    fun begin(packageName: String): List<LearnEvent> {
        check(events.none { it.state == LearnEvent.State.Sending }) { "a batch is already in flight" }
        val picked = events.filter { it.state == LearnEvent.State.Pending && it.packageName == packageName }
            .sortedWith(compareBy({ it.postedAtMs }, { it.seq }))
            .take(LearnTuning.BATCH_MAX)
            .map { it.seq }.toSet()
        events.replaceAll { if (it.seq in picked) it.copy(state = LearnEvent.State.Sending) else it }
        publish()
        return sending()
    }

    /** 在发的这批现在的样子(点开/划掉可能是发出之后才到的),按发出时的顺序。 */
    fun sending(): List<LearnEvent> = events.filter { it.state == LearnEvent.State.Sending }
        .sortedWith(compareBy({ it.postedAtMs }, { it.seq }))

    /** 这批没发成(锁屏取消、预算用完、开关变了):照旧待归纳。 */
    fun abort() {
        events.replaceAll { if (it.state == LearnEvent.State.Sending) it.copy(state = LearnEvent.State.Pending) else it }
        publish()
    }

    /** 这批失败了(超时、模型出错、回复读不懂):丢掉,不重试。返回丢了几条。 */
    fun fail(): Int {
        val before = events.size
        events.removeAll { it.state == LearnEvent.State.Sending }
        publish()
        return before - events.size
    }

    /** 这批归纳完了:按 seq 记下各条归到的话题,文字清掉。 */
    fun finish(topicsBySeq: Map<Long, Set<String>>) {
        events.replaceAll {
            if (it.state != LearnEvent.State.Sending) it
            else it.copy(state = LearnEvent.State.Done, title = "", text = "", topics = topicsBySeq[it.seq].orEmpty())
        }
        publish()
    }

    /** 话题被删了:事件上不再指着它。 */
    fun forgetTopics(ids: Set<String>) {
        if (ids.isEmpty()) return
        events.replaceAll { if (it.topics.any(ids::contains)) it.copy(topics = it.topics - ids) else it }
    }

    /** 不再收的 App(取消勾选、不从它归纳)的事件一并去掉;在发的那批不动(由调用方先取消)。 */
    fun purge(keep: (String) -> Boolean) {
        if (events.removeAll { it.state != LearnEvent.State.Sending && !keep(it.packageName) }) publish()
    }

    fun clear() {
        events.clear()
        publish()
    }

    private fun evict() {
        while (events.size > capacity) {
            val i = events.indexOfFirst { it.state == LearnEvent.State.Done }
                .takeIf { it >= 0 } ?: events.indexOfFirst { it.state == LearnEvent.State.Pending }
            if (i < 0) break
            events.removeAt(i)
        }
    }

    private fun publish() {
        mutable.value = Summary(events.size, pendingCount)
    }

    private companion object {
        /** 并成一句时两条消息之间的分隔(清洗会把换行并成空格,所以不用换行)。 */
        const val LINE_BREAK = " / "

        /** 文字的指纹:SHA-256 的前 8 个字节(用 32 位的 hashCode 的话,「Aa」和「BB」这样不同的两句会撞上)。只在内存里用。 */
        fun fingerprint(s: String): Long {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            var out = 0L
            for (i in 0 until 8) out = (out shl 8) or (d[i].toLong() and 0xff)
            return out
        }
    }
}
