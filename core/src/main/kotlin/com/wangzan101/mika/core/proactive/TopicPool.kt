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
 * (见 [TopicPool.fingerprint]:只有指纹,不留名字)。[merged] 是用户并掉的话题名的指纹 → 并进去的那个话题的 id
 * (见 [TopicPool.merge];同样只有指纹)。
 */
@Serializable
data class TopicPoolData(
    val version: Int = 1,
    val topics: List<ObservedTopic> = emptyList(),
    val blocked: List<String> = emptyList(),
    val merged: Map<String, String> = emptyMap(),
)

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
 * 用户把一个话题并进另一个([merge])之后,并掉的那个名字记成别名:模型之后再新起这个名字的话题,算引用并进去的那个。
 * 「不再记录」和别名是同一个名字上的两种决定,后做的算数:并掉一个名字时把它从「不再记录」里拿出来;删掉一个话题时,
 * 它的名字、并进过它的名字都记进「不再记录」(用户删的是并好的这一个,那些名字不该借着别名没了又被学回来)。
 *
 * 话题的 id 是随机的([randomId]),不是顺着往下编的序号:序号在「新起了话题却没存上」「池子读不出来、从空的重来」之后会被
 * 再发一遍,而联系人上连着的话题、事件上记着的话题都只认 id,会连到不相干的话题上。
 */
class TopicPool(data: TopicPoolData = TopicPoolData(), private val newId: () -> String = ::randomId) {
    private val topics = LinkedHashMap<String, ObservedTopic>().apply { data.topics.forEach { put(it.id, it) } }
    private val blocked = LinkedHashSet(data.blocked)
    /** 并掉的话题名的指纹 → 并进去的话题的 id。指着的话题没了的(忘掉、清掉了)不算数,存盘、认名字时都只看还在的。 */
    private val aliases = LinkedHashMap(data.merged)
    /** 这个进程里并掉的话题 id → 并进去的 id:并的那一刻在途的请求引用的还是并掉的那个。不落盘(请求不会跨进程)。 */
    private val moved = HashMap<String, String>()

    fun data(): TopicPoolData =
        TopicPoolData(topics = topics.values.toList(), blocked = blocked.toList(), merged = aliases.filterValues { it in topics })

    /** 用户删掉后不再记录的话题有几个。 */
    val blockedCount: Int get() = blocked.size

    fun all(): List<ObservedTopic> = topics.values.toList()

    operator fun get(id: String): ObservedTopic? = topics[id]

    /**
     * 给模型看的已有话题,一共 [limit] 个,都按强度×留存从高到低挑:先是 [packageName](这一批的 App)印证过的,最多占三分之二的位置;
     * 剩下的位置给其余的里最强的(不管哪个 App;它自己的没占满时位置也归这边)。
     * 模型只能引用它看得到的:池子里的话题比 [limit] 多得多时,只按强度挑的话,这个 App 以前推出来的那个话题多半不在里面,
     * 它再推同样的东西,模型就只能另起一个说法差不多的。同一个 App 推的多半还是那些题材,所以先给它自己的;
     * 但不全给它:话题很多的 App(比如聊天 App)不该把别的 App 的强话题全挤出去——同一个题材也会从别的 App 来。
     */
    fun strongest(
        nowMs: Long,
        limit: Int = LearnTuning.EXISTING_MAX,
        includeSensitive: Boolean = false,
        packageName: String? = null,
    ): List<ObservedTopic> {
        val byScore = topics.values.filter { includeSensitive || !it.sensitive }.sortedByDescending { it.score(nowMs) }
        val own = if (packageName == null) emptyList() else byScore.filter { packageName in it.evidence }.take(limit * 2 / 3)
        val ownIds = own.mapTo(HashSet()) { it.id }
        return own + byScore.filter { it.id !in ownIds }.take(limit - own.size)
    }

    /** 用上一次结果后的统计。[byEvent]:这一批的下标 → 归到的话题 id。[blocked]:新起的话题是用户删掉过的,没收的有几条。 */
    data class Applied(val byEvent: Map<Int, Set<String>>, val applied: Int, val missing: Int, val blocked: Int = 0)

    /**
     * 把一批的归纳用到池上。[packageName] 是这批的 App(我们自己分的批),证据只记到它名下。
     * 引用的话题在请求途中被用户删了的,丢掉,不复活;被用户并进另一个了的,算引用那一个。
     * 新话题和已有的同种类、同名(大小写、全半角、空白不计)就当引用;没有同名的、名字是用户并掉过的(不管哪一种),算引用并进去的那个;
     * 都不是、名字却是用户删掉过的(不管当初是哪一种),不收。
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
                s.topicId != null -> (moved[s.topicId] ?: s.topicId).takeIf { it in topics }
                // 先认已有的同名话题(留着的照常用),再认用户并掉过的名字;要新起时才看这个名字是不是用户删掉过的
                s.newKind != null && s.newLabel != null -> findSame(s.newKind, s.newLabel)?.id
                    ?: aliases[fingerprint(s.newLabel)]?.takeIf { it in topics }
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

    /** 用户删一个:同名的话题以后也不再记(只记名字的指纹);并进过它的那些名字也一样。 */
    fun delete(id: String): Boolean {
        val t = topics.remove(id) ?: return false
        // 它自己的名字放在最后(名单满了先丢最早的)
        val names = aliases.filterValues { it == id }.keys + fingerprint(t.label)
        for (f in names) {
            // 这个名字不再是谁的别名(同名的另一个话题以前被并掉过的也一样:后做的决定算数)
            aliases.remove(f)
            blocked.remove(f)
            blocked += f
        }
        while (blocked.size > LearnTuning.TOPIC_BLOCKED_CAP) blocked.remove(blocked.first())
        return true
    }

    /**
     * 用户把 [fromId] 并进 [intoId]:两个其实是一回事(模型换了个说法另起的)。并完只剩 [intoId],名字、种类用它的:
     * 证据按「App × 天」并在一起(同一个 App 同一天两边都有的只算一份),点开、划掉的次数相加(同一条通知两边都算过的会多算一次,
     * 分不出来,不去管),有一个是敏感的就算敏感(只会变严),最早、最近出现的时间各取两边的。
     * [fromId] 的名字记成别名(见类注释);以前并进 [fromId] 的名字跟着改指 [intoId]。
     * 两个是同一个、或有一个已经不在了(页面上是早先的样子)时什么都不做,返回 false。分不开:没有反过来的操作。
     */
    fun merge(fromId: String, intoId: String): Boolean {
        if (fromId == intoId) return false
        val from = topics[fromId] ?: return false
        val into = topics[intoId] ?: return false
        topics.remove(fromId)
        val union = LinkedHashMap<String, List<Long>>()
        for (pkg in into.evidence.keys + from.evidence.keys) {
            union[pkg] = (into.evidence[pkg].orEmpty() + from.evidence[pkg].orEmpty()).toSortedSet().toList().takeLast(LearnTuning.DAYS_PER_APP)
        }
        // App 多了留最近出现过的那些
        val kept = union.entries.sortedByDescending { it.value.lastOrNull() ?: Long.MIN_VALUE }.take(LearnTuning.APPS_PER_TOPIC).map { it.key }.toSet()
        topics[intoId] = into.copy(
            firstSeenMs = minOf(into.firstSeenMs, from.firstSeenMs),
            lastSeenMs = maxOf(into.lastSeenMs, from.lastSeenMs),
            evidence = union.filterKeys { it in kept },
            opened = into.opened + from.opened,
            dismissed = into.dismissed + from.dismissed,
            sensitive = into.sensitive || from.sensitive,
        )
        // 指着的话题已经没了的别名趁这时去掉;以前并进 from 的跟着改指 into
        aliases.values.removeAll { it != fromId && it !in topics }
        aliases.replaceAll { _, id -> if (id == fromId) intoId else id }
        val f = fingerprint(from.label)
        aliases.remove(f)
        aliases[f] = intoId
        blocked.remove(f)
        while (aliases.size > LearnTuning.TOPIC_MERGED_CAP) aliases.remove(aliases.keys.first())
        moved.replaceAll { _, id -> if (id == fromId) intoId else id }
        moved[fromId] = intoId
        return true
    }

    /** 重新允许删掉过的话题(一次全部)。 */
    fun allowAgain(): Boolean {
        if (blocked.isEmpty()) return false
        blocked.clear()
        return true
    }

    /** 清空全部:是重来,不记进「不再记录」(已经记着的留着);并掉过的名字也不再记着。 */
    fun clear() {
        topics.clear()
        aliases.clear()
        moved.clear()
    }

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

        /**
         * 「并入」时列给用户挑的其他话题:名字和 [of] 像的排在前面(相同的相邻两个字越多越靠前;要并的多半是说法差不多的那个,
         * 话题多的时候不该让人从头翻),其余按强度×留存从高到低。
         */
        fun mergeCandidates(of: ObservedTopic, all: List<ObservedTopic>, nowMs: Long): List<ObservedTopic> {
            val pairs = pairsOf(of.label)
            return all.filter { it.id != of.id }
                .sortedWith(compareByDescending<ObservedTopic> { t -> pairsOf(t.label).count { it in pairs } }.thenByDescending { it.score(nowMs) })
        }

        private fun pairsOf(label: String): Set<String> = normalize(label).windowed(2).toSet()

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
