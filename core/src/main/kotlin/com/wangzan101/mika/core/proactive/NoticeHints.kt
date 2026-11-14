package com.wangzan101.mika.core.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条「值得提醒」:判定为 [TriageDecision.Surface] 时那条记录的样子和模型写的理由。文字都来自通知(和模型),只当普通文字显示。
 * [seen] 是用户点开过提醒、它已经写进了提醒对话。
 */
data class NoticeHint(
    val key: String,
    val packageName: String,
    val postedAtMs: Long,
    val title: String,
    val text: String,
    val reason: String,
    val seen: Boolean = false,
)

/** 用户对提醒的反应,按 App 计(只在内存里,暂时只显示,不影响任何行为)。 */
data class HintFeedback(
    /** 提醒还在时点开了(看到了)。 */
    val seen: Int = 0,
    /** 还没点开提醒,用户就在通知栏里点开或划掉了那条通知。 */
    val dismissedUnseen: Int = 0,
)

/**
 * 「值得提醒、还没看」的那几条:纯逻辑,只在内存里,不碰通知以外的东西。
 *
 * 由 [sync] 喂收件箱的记录和判定结果(发出时间对上、判为值得提醒的才算);[list] 是还在收件箱里的提醒(新的在前,最多 [limit] 条),
 * [unseen] 是其中还没看过的条数(胶囊上的数)。
 * - 点开提醒([markSeen]):写进提醒对话的那几条记为看过,条数归零;它们还留在 [list] 里(主动能力页上的计数用)。
 * - 记录从收件箱里没了(用户点开或划掉了通知、App 不再勾选、功能关掉),它的提醒随之没了;用户点开或划掉的先经 [dismissed] 记一笔。
 * - 判定关掉([sync] 的 triageOn 为 false):列表清空;看过到哪条的记录留着,再打开时看过的不再冒出来。
 * - 反馈计数([State.feedback])跟着进程,功能关掉也不清(暂时只在主动能力页上显示两个数)。
 * - 同一个键又被判为值得提醒:看过之后,只有通知的发出时间比看过的那次新才算新的。
 * - 通知更新了、新的这次还没判完(或判定失败):先留着原来那条;判完不是值得提醒的就拿掉。
 * - 超过 [limit] 条时最旧的挤掉,不计数,之后也不再冒出来(除非那条通知又更新、又判为值得提醒)。
 */
class NoticeHints(private val limit: Int = LIMIT) {
    data class State(val list: List<NoticeHint> = emptyList(), val feedback: Map<String, HintFeedback> = emptyMap()) {
        val unseen: Int get() = list.count { !it.seen }
        val seenTotal: Int get() = feedback.values.sumOf { it.seen }
        val dismissedUnseenTotal: Int get() = feedback.values.sumOf { it.dismissedUnseen }
    }

    private val items = HashMap<String, NoticeHint>()
    /** 各键看过的那次发出时间:不比它新的同一键不再算新提醒。 */
    private val acked = HashMap<String, Long>()
    private val feedback = HashMap<String, HintFeedback>()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    /** 收件箱和判定结果变了:[marks] 按通知键,发出时间和记录对上才算数。 */
    @Synchronized fun sync(records: List<NoticeRecord>, marks: Map<String, TriageMark>, triageOn: Boolean) {
        val byKey = records.associateBy { it.key }
        items.keys.retainAll(byKey.keys)
        acked.keys.retainAll(byKey.keys)
        if (!triageOn) {
            items.clear()
            publish()
            return
        }
        for (r in records) {
            val mark = marks[r.key]?.takeIf { it.postedAtMs == r.postedAtMs }
            val verdict = (mark as? TriageMark.Done)?.verdict
            val old = items[r.key]
            if (verdict == null) continue // 还没判完或失败:原来那条(如果有)先留着
            if (verdict.decision != TriageDecision.Surface) {
                items.remove(r.key)
                continue
            }
            if (old != null && old.postedAtMs >= r.postedAtMs) continue
            if ((acked[r.key] ?: Long.MIN_VALUE) >= r.postedAtMs) continue
            items[r.key] = NoticeHint(r.key, r.packageName, r.postedAtMs, r.title, r.said, verdict.reason)
        }
        trim()
        publish()
    }

    /** 用户在通知栏里点开或划掉了这条通知(收件箱随后拿掉它):还没看过的记一笔「没看就处理了」。 */
    @Synchronized fun dismissed(key: String) {
        val h = items.remove(key) ?: return
        if (!h.seen) bump(h.packageName) { it.copy(dismissedUnseen = it.dismissedUnseen + 1) }
        acked.remove(key)
        publish()
    }

    /**
     * 用户点开了提醒,[shown] 这几条已经写进了提醒对话:记为看过。只认键和发出时间都对得上的——
     * 写的过程中同一条通知又更新了、又来了新的提醒,那些还没给用户看过,照样算没看。
     */
    @Synchronized fun markSeen(shown: List<NoticeHint>) {
        var changed = false
        for (s in shown) {
            val h = items[s.key] ?: continue
            if (h.seen || h.postedAtMs != s.postedAtMs) continue
            items[s.key] = h.copy(seen = true)
            acked[s.key] = maxOf(acked[s.key] ?: Long.MIN_VALUE, h.postedAtMs)
            bump(h.packageName) { it.copy(seen = it.seen + 1) }
            changed = true
        }
        if (changed) publish()
    }

    /** 只留最新的 [limit] 条(按发出时间);挤掉的当作过期,记为处理过(不再冒出来,除非那条通知又更新了)。 */
    private fun trim() {
        if (items.size <= limit) return
        items.values.sortedByDescending { it.postedAtMs }.drop(limit).forEach {
            items.remove(it.key)
            acked[it.key] = maxOf(acked[it.key] ?: Long.MIN_VALUE, it.postedAtMs)
        }
    }

    private inline fun bump(pkg: String, f: (HintFeedback) -> HintFeedback) {
        feedback[pkg] = f(feedback[pkg] ?: HintFeedback())
    }

    private fun publish() {
        mutable.value = State(items.values.sortedByDescending { it.postedAtMs }, HashMap(feedback))
    }

    companion object {
        const val LIMIT = 50
    }
}
