package com.wangzan101.mika.core.proactive

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 话题的种类(固定的几种;模型给别的一律不认)。 */
@Serializable
enum class TopicKind(val wire: String) {
    /** 看起来感兴趣的题材。 */
    @SerialName("interest") Interest("interest"),
    /** 关注的账号、作者、频道。 */
    @SerialName("follow") Follow("follow"),
    /** 在看、想买的东西。 */
    @SerialName("shopping") Shopping("shopping"),
    /** 在用的服务、订阅。 */
    @SerialName("service") Service("service"),
    /** 反复出现的个人事务(快递、通勤、账单),只按类别,不含金额、单号、地址。 */
    @SerialName("routine") Routine("routine");

    companion object {
        fun ofWire(s: String?): TopicKind? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * 攒出来的一个话题。[label] 是模型从不可信的通知文字里归纳出来的短语(已清洗、校验),只当普通文字显示。
 * [evidence]:App 包名 → 这个 App 在哪几天(本地日期的纪元日,升序)印证过它;证据按「App × 天」算,不按条数。
 * App 永远是我们自己给的那批的包名,不来自模型。[opened]、[dismissed] 是用户点开、划掉过几条被归到这里的通知。
 * 不存任何通知原文。
 */
@Serializable
data class ObservedTopic(
    val id: String,
    val kind: TopicKind,
    val label: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val evidence: Map<String, List<Long>> = emptyMap(),
    val opened: Int = 0,
    val dismissed: Int = 0,
    /** 敏感类推断(健康、财务、宗教、政治、性取向):只有用户打开「敏感类推断」时才会有。 */
    val sensitive: Boolean = false,
) {
    /** 不同的「App × 天」有几份。 */
    val evidenceCount: Int get() = evidence.values.sumOf { it.size }

    /** 在几个不同的日子里出现过(不管哪个 App)。 */
    val days: Int get() = evidence.values.flatten().toSet().size

    /** 有几个 App 印证过。 */
    val apps: Int get() = evidence.size

    /** 至少在 [LearnTuning.ESTABLISHED_DAYS] 个不同的日子里出现过才算已确立;只出现在一天里的还不确定。 */
    val established: Boolean get() = days >= LearnTuning.ESTABLISHED_DAYS

    /** 强度:证据份数,点开的加一点,划掉的减一点,不低于 0。 */
    val strength: Double
        get() = (evidenceCount + LearnTuning.OPENED_BONUS * opened - LearnTuning.DISMISSED_PENALTY * dismissed).coerceAtLeast(0.0)

    /** 留存:exp(-Δt / S),Δt 是离上次出现多久,S = [LearnTuning.STABILITY_DAYS] 天 × (1 + 强度)。 */
    fun retention(nowMs: Long): Double {
        val dtDays = (nowMs - lastSeenMs).coerceAtLeast(0L).toDouble() / LearnTuning.DAY_MS
        return exp(-dtDays / (LearnTuning.STABILITY_DAYS * (1 + strength)))
    }

    fun score(nowMs: Long): Double = strength * retention(nowMs)
}

/**
 * 话题池落盘的样子:只有话题名、种类、计数和时间。[blocked] 是用户删掉后「不再记录」的话题名的指纹
 * (见 [TopicPool.fingerprint]:只有指纹,不留名字)。
 */
@Serializable
data class TopicPoolData(val version: Int = 1, val topics: List<ObservedTopic> = emptyList(), val blocked: List<String> = emptyList())

/** 校验过的一条归纳:引用一个已有话题([topicId]),或者新起一个([newKind] + [newLabel])。[events] 是这一批里的下标。没有 App 这一项。 */
data class LearnSignal(
    val events: List<Int>,
    val topicId: String? = null,
    val newKind: TopicKind? = null,
    val newLabel: String? = null,
    /** 模型标了敏感的新话题。 */
    val sensitive: Boolean = false,
)

/**
 * 话题池:纯逻辑,不是线程安全的(在 ProactiveRuntime 那条串行协程上用)。
 * 模型只能「加一个话题」或「引用一个话题」,改名、合并、删除都只有用户能做。唯一的例外是有意留的:模型新起的话题标了敏感、
 * 又和一个已有的同名时,已有的那个也标成敏感(只会变严,不会放宽;早先漏标的能这样补上)。
 * 不定时整理:每次用上归纳结果、或打开那个页面时整理一遍([maintain])。
 *
 * 用户单独删掉的话题不再被学回来:记下它名字的指纹,模型之后再新起同名的话题(不管归到哪一种)不收。
 * 只按名字认:换一个说法的拦不住。「清空全部」「清掉某个 App 的」「删掉敏感的」和久了自己忘掉的都是另一回事,不记。
 *
 * 话题的 id 是随机的([randomId]),不是顺着往下编的序号:序号在「新起了话题却没存上」「池子读不出来、从空的重来」之后会被
 * 再发一遍,而联系人上连着的话题、事件上记着的话题都只认 id,会连到不相干的话题上。
 */
class TopicPool(data: TopicPoolData = TopicPoolData(), private val newId: () -> String = ::randomId) {
    private val topics = LinkedHashMap<String, ObservedTopic>().apply { data.topics.forEach { put(it.id, it) } }
    private val blocked = LinkedHashSet(data.blocked)

    fun data(): TopicPoolData = TopicPoolData(topics = topics.values.toList(), blocked = blocked.toList())

    /** 用户删掉后不再记录的话题有几个。 */
    val blockedCount: Int get() = blocked.size

    fun all(): List<ObservedTopic> = topics.values.toList()

    operator fun get(id: String): ObservedTopic? = topics[id]

    /** 给模型看的已有话题:强度×留存最高的 [limit] 个。 */
    fun strongest(nowMs: Long, limit: Int = LearnTuning.EXISTING_MAX, includeSensitive: Boolean = false): List<ObservedTopic> =
        topics.values.filter { includeSensitive || !it.sensitive }.sortedByDescending { it.score(nowMs) }.take(limit)

    /** 用上一次结果后的统计。[byEvent]:这一批的下标 → 归到的话题 id。[blocked]:新起的话题是用户删掉过的,没收的有几条。 */
    data class Applied(val byEvent: Map<Int, Set<String>>, val applied: Int, val missing: Int, val blocked: Int = 0)

    /**
     * 把一批的归纳用到池上。[packageName] 是这批的 App(我们自己分的批),证据只记到它名下。
     * 引用的话题在请求途中被用户删了的,丢掉,不复活。新话题和已有的同种类、同名(大小写、全半角、空白不计)就当引用;
     * 没有同名的、名字却是用户删掉过的(不管当初是哪一种),不收。
     * 同一条通知对同一个话题只算一次点开/划掉。
     *
     * 一次回复对一个话题最多记一天的证据:几条归纳落在同一个话题上时并在一起,记在它们引用的通知里最新那条发出的那天。
     * 所以跨了午夜的一批(早上解锁后补收到昨晚和今早的)不能凭一次判断就让话题「已确立」。日子取通知发出的那天,不是判断的那天。
     */
    fun apply(
        packageName: String,
        events: List<LearnEvent>,
        signals: List<LearnSignal>,
        nowMs: Long,
        zone: ZoneId,
        allowSensitive: Boolean = false,
    ): Applied {
        // 这一次里每个话题被引用到的通知(下标),和有没有哪条给它标了敏感
        val cites = LinkedHashMap<String, LinkedHashSet<Int>>()
        val flagged = HashSet<String>()
        var applied = 0
        var missing = 0
        var refused = 0
        for (s in signals) {
            val cited = s.events.distinct().filter { it in events.indices }
            if (cited.isEmpty()) continue
            // 「敏感类推断」关着时:标了敏感的新话题不收,已有的敏感话题不加证据(用的是用上结果这一刻的开关)
            if (!allowSensitive && s.sensitive) { missing++; continue }
            val id = when {
                s.topicId != null -> s.topicId.takeIf { it in topics }
                // 先认已有的同名话题(留着的照常用);要新起时才看这个名字是不是用户删掉过的
                s.newKind != null && s.newLabel != null -> findSame(s.newKind, s.newLabel)?.id
                    ?: if (fingerprint(s.newLabel) in blocked) { refused++; continue } else create(s.newKind, s.newLabel, s.sensitive, nowMs)
                else -> null
            }
            if (id == null || (!allowSensitive && topics.getValue(id).sensitive)) { missing++; continue }
            cites.getOrPut(id) { LinkedHashSet() } += cited
            if (s.sensitive) flagged += id
            applied++
        }
        val byEvent = HashMap<Int, MutableSet<String>>()
        for ((id, cited) in cites) {
            val t = topics.getValue(id)
            val seen = cited.maxOf { events[it].postedAtMs }.coerceAtMost(nowMs)
            topics[id] = t.copy(
                evidence = addDays(t.evidence, packageName, listOf(dayOf(seen, zone))),
                lastSeenMs = maxOf(t.lastSeenMs, seen),
                opened = t.opened + cited.count { events[it].outcome == LearnOutcome.Opened },
                dismissed = t.dismissed + cited.count { events[it].outcome == LearnOutcome.Dismissed },
                sensitive = t.sensitive || id in flagged,
            )
            cited.forEach { byEvent.getOrPut(it) { HashSet() } += id }
        }
        maintain(nowMs)
        // 整理时被丢掉的话题不再指着
        return Applied(byEvent.mapValues { (_, ids) -> ids.filterTo(HashSet()) { it in topics } }, applied, missing, refused)
    }

    /**
     * 归纳过的通知后来才被点开/划掉:补到它归到的话题上。有改动返回 true。
     * 「敏感类推断」关着([allowSensitive] 为 false)时敏感话题不动:关着就是不再往上加任何东西。
     */
    fun addOutcome(ids: Set<String>, outcome: LearnOutcome, allowSensitive: Boolean = false): Boolean {
        var changed = false
        for (id in ids) {
            val t = topics[id] ?: continue
            if (t.sensitive && !allowSensitive) continue
            topics[id] = when (outcome) {
                LearnOutcome.Opened -> t.copy(opened = t.opened + 1)
                LearnOutcome.Dismissed -> t.copy(dismissed = t.dismissed + 1)
                LearnOutcome.None -> continue
            }
            changed = true
        }
        return changed
    }

    /** 整理:留存低于 [LearnTuning.FORGET_BELOW] 的丢掉;超过 [LearnTuning.POOL_CAP] 个时丢强度×留存最低的。返回丢掉的 id。 */
    fun maintain(nowMs: Long): Set<String> {
        val dropped = HashSet<String>()
        topics.values.filter { it.retention(nowMs) < LearnTuning.FORGET_BELOW }.forEach { dropped += it.id }
        dropped.forEach(topics::remove)
        if (topics.size > LearnTuning.POOL_CAP) {
            topics.values.sortedBy { it.score(nowMs) }.take(topics.size - LearnTuning.POOL_CAP).forEach {
                dropped += it.id
                topics.remove(it.id)
            }
        }
        return dropped
    }

    /** 用户删一个:同名的话题以后也不再记(只记名字的指纹)。 */
    fun delete(id: String): Boolean {
        val t = topics.remove(id) ?: return false
        val f = fingerprint(t.label)
        blocked.remove(f)
        blocked += f
        while (blocked.size > LearnTuning.TOPIC_BLOCKED_CAP) blocked.remove(blocked.first())
        return true
    }

    /** 重新允许删掉过的话题(一次全部)。 */
    fun allowAgain(): Boolean {
        if (blocked.isEmpty()) return false
        blocked.clear()
        return true
    }

    /** 清空全部:是重来,不记进「不再记录」(已经记着的留着)。 */
    fun clear() = topics.clear()

    /** 删掉所有敏感话题(用户关掉「敏感类推断」并选了删除)。返回删掉的 id。 */
    fun deleteSensitive(): Set<String> {
        val ids = topics.values.filter { it.sensitive }.map { it.id }.toSet()
        ids.forEach(topics::remove)
        return ids
    }

    /** 去掉某个 App 的全部证据;因此没有证据了的话题删掉。返回删掉的话题 id。 */
    fun clearApp(packageName: String): Set<String> {
        val dropped = HashSet<String>()
        for (t in topics.values.toList()) {
            if (packageName !in t.evidence) continue
            val left = t.evidence - packageName
            if (left.isEmpty()) {
                topics.remove(t.id)
                dropped += t.id
            } else {
                topics[t.id] = t.copy(evidence = left)
            }
        }
        return dropped
    }

    private fun findSame(kind: TopicKind, label: String): ObservedTopic? {
        val n = normalize(label)
        return topics.values.firstOrNull { it.kind == kind && normalize(it.label) == n }
    }

    private fun create(kind: TopicKind, label: String, sensitive: Boolean, nowMs: Long): String {
        var id = newId()
        while (id in topics) id = newId()
        topics[id] = ObservedTopic(id, kind, label, firstSeenMs = nowMs, lastSeenMs = nowMs, sensitive = sensitive)
        return id
    }

    companion object {
        /** 新话题的 id:`k` 加 12 位随机的十六进制。 */
        fun randomId(): String = "k" + UUID.randomUUID().toString().replace("-", "").take(12)

        private val SPACES = Regex("[\\s\\p{Z}\\u200B\\uFEFF]+")

        /** 话题名的指纹:按 [normalize] 之后的名字算,和种类无关。存它而不存名字:用户删掉的东西不该原样留着。 */
        fun fingerprint(label: String): String = NoticeDedup.idOf("topic:" + normalize(label))

        /** 比较话题名用:全半角、兼容字符统一,不分大小写,不计空白。 */
        fun normalize(label: String): String =
            SPACES.replace(Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT), "")

        fun dayOf(ms: Long, zone: ZoneId): Long = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

        /** 加上这个 App 的几天(去重、升序、只留最近 [LearnTuning.DAYS_PER_APP] 天);App 多了丢最久没出现的。 */
        internal fun addDays(evidence: Map<String, List<Long>>, packageName: String, days: List<Long>): Map<String, List<Long>> {
            val merged = (evidence[packageName].orEmpty() + days).toSortedSet().toList().takeLast(LearnTuning.DAYS_PER_APP)
            val out = LinkedHashMap(evidence)
            out[packageName] = merged
            while (out.size > LearnTuning.APPS_PER_TOPIC) {
                val stalest = out.entries.filter { it.key != packageName }.minByOrNull { it.value.lastOrNull() ?: Long.MIN_VALUE } ?: break
                out.remove(stalest.key)
            }
            return out
        }
    }
}
