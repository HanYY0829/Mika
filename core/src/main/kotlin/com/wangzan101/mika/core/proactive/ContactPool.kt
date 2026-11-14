package com.wangzan101.mika.core.proactive

import java.time.ZoneId
import kotlin.math.exp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 联系人是一个人,还是一个群(群里的成员不单独记)。 */
@Serializable
enum class ContactType(val wire: String) {
    @SerialName("person") Person("person"),
    @SerialName("group") Group("group");

    companion object {
        fun ofWire(s: String?): ContactType? = entries.firstOrNull { it.wire == s }
    }
}

/** 和用户的关系(固定的几种);群也用这几种(家人群、工作群、同学群)。 */
@Serializable
enum class ContactRelation(val wire: String) {
    @SerialName("family") Family("family"),
    /** 伴侣:算敏感类推断,只有「敏感类推断」开着时模型才能给。 */
    @SerialName("partner") Partner("partner"),
    @SerialName("friend") Friend("friend"),
    @SerialName("colleague") Colleague("colleague"),
    @SerialName("classmate") Classmate("classmate"),
    @SerialName("other") Other("other"),
    @SerialName("unknown") Unknown("unknown");

    companion object {
        fun ofWire(s: String?): ContactRelation? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * 攒出来的一个联系人(一个人或一个群)。[id] 是 [ContactIdentity] 算的,[name] 是通知标题里最近一个能用的显示名,
 * 都不来自模型;模型只投类型和关系的票。票按天记:[typeVotes]、[relationVotes] 是每种值在哪几天(本地日期的纪元日,升序)投过,
 * 同一天同一种值只算一票(一天里十条消息也是一票)。[days] 是在哪几天出现过。不存消息原文,也不存每天几条。
 * [topics] 是连着的话题 id:是我们按「这个会话的通知被归到了哪个话题」机械地连的,不是模型给的。
 * [userRelation] 是用户自己设的,一直压过票数。
 */
@Serializable
data class ObservedContact(
    val id: String,
    val packageName: String,
    val name: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val typeVotes: Map<ContactType, List<Long>> = emptyMap(),
    val relationVotes: Map<ContactRelation, List<Long>> = emptyMap(),
    val days: List<Long> = emptyList(),
    val topics: List<String> = emptyList(),
    val userRelation: ContactRelation? = null,
) {
    /** 敏感:有人投过「伴侣」,或用户自己设成了伴侣。 */
    val sensitive: Boolean get() = userRelation == ContactRelation.Partner || ContactRelation.Partner in relationVotes

    /** 类型:票最多的(一样多时取最近投过的);还没有票时当个人。 */
    val type: ContactType
        get() = typeVotes.entries.maxWithOrNull(compareBy({ it.value.size }, { it.value.lastOrNull() ?: Long.MIN_VALUE }))?.key
            ?: ContactType.Person

    /**
     * 显示的关系:用户设过的就用它;否则票最多的那种,只有它在至少 [LearnTuning.RELATION_MIN_DAYS] 个不同的日子里被投过、
     * 并且超过全部关系票的一半(「不确定」的票也算在总数里)才显示,不然就是 [ContactRelation.Unknown]。
     */
    val relation: ContactRelation
        get() {
            userRelation?.let { return it }
            val total = relationVotes.values.sumOf { it.size }
            val top = relationVotes.entries.maxByOrNull { it.value.size } ?: return ContactRelation.Unknown
            return if (top.value.size >= LearnTuning.RELATION_MIN_DAYS && top.value.size * 2 > total) top.key else ContactRelation.Unknown
        }

    /** 显示的关系是不是用户设的(不是就是猜的)。 */
    val userSet: Boolean get() = userRelation != null

    /** 至少在 [LearnTuning.CONTACT_ESTABLISHED_DAYS] 个不同的日子里出现过才算已确立。 */
    val established: Boolean get() = days.size >= LearnTuning.CONTACT_ESTABLISHED_DAYS

    /** 强度:出现过的不同日子数。 */
    val strength: Double get() = days.size.toDouble()

    /** 留存:和话题一样,exp(-Δt / S),S = [LearnTuning.STABILITY_DAYS] 天 × (1 + 强度)。 */
    fun retention(nowMs: Long): Double {
        val dtDays = (nowMs - lastSeenMs).coerceAtLeast(0L).toDouble() / LearnTuning.DAY_MS
        return exp(-dtDays / (LearnTuning.STABILITY_DAYS * (1 + strength)))
    }

    fun score(nowMs: Long): Double = strength * retention(nowMs)
}

/** 联系人落盘的样子:别人的显示名、类型和关系的票、日子、连着的话题;[blocked] 是删掉后「不再记录」的身份 id(只有 id)。 */
@Serializable
data class ContactPoolData(
    val version: Int = 1,
    val contacts: List<ObservedContact> = emptyList(),
    val blocked: List<String> = emptyList(),
)

/** 校验过的一条联系人归类:引用这一批里的几条通知([events] 是下标),说它们是谁发的(类型)、和用户什么关系。没有名字。 */
data class ContactSignal(val events: List<Int>, val type: ContactType, val relation: ContactRelation)

/** 联系人归类被丢掉的原因(给计数用,不含内容)。 */
enum class ContactDrop {
    /** 多出 [LearnTuning.CONTACTS_MAX] 的。 */
    OverLimit,
    /** 不是对象,或者 type 写的不认识 / 没有。 */
    Malformed,
    /** 引用的通知 id 里有不在这一批里的:整条不要(一条记一次)。 */
    UnknownId,
    /** 一个有效的通知 id 都没有。 */
    NoIds,
    /** 「归纳联系人与关系」关着(请求时或用上时)。 */
    Off,
    /** 引用的通知算不出身份,或第一次见到时标题给不出能用的显示名。 */
    NoIdentity,
    /** 用户删过这个联系人(「不再记录」)。 */
    Blocked,
}

/**
 * 联系人池:纯逻辑,不是线程安全的(在 ProactiveRuntime 那条串行协程上用)。
 * 谁是一个联系人由 [ContactIdentity] 定,模型只给类型和关系的票;关系一律当猜测,除非用户自己设了。
 * 类型(人还是群)在通知的写法说得清时([LearnEvent.group],见 [NoticeShapes])按写法算,模型的那一票只在说不清时用。
 * 不定时整理:用上归纳结果、或打开页面时整理一遍([maintain])。
 */
class ContactPool(data: ContactPoolData = ContactPoolData()) {
    private val contacts = LinkedHashMap<String, ObservedContact>().apply { data.contacts.forEach { put(it.id, it) } }
    private val blocked = LinkedHashSet(data.blocked)

    fun data(): ContactPoolData = ContactPoolData(contacts = contacts.values.toList(), blocked = blocked.toList())

    fun all(): List<ObservedContact> = contacts.values.toList()

    operator fun get(id: String): ObservedContact? = contacts[id]

    val blockedCount: Int get() = blocked.size

    data class Applied(val accepted: Int, val dropped: Map<ContactDrop, Int>, val changed: Boolean)

    /**
     * 把一批的联系人归类用到池上。[events] 是这批现在的样子(标题还在);身份按 [strategy] 算。
     * 一条归类引用的通知落在几个身份上,每个身份都记一票。「敏感类推断」关着([allowSensitive] 为 false)时「伴侣」当不确定。
     * [topicsByEvent] 是这一批里各条通知被归到的话题:属于某个联系人的通知,它的话题连到这个联系人上(机械的,不是模型给的)。
     *
     * 一次回复对一个联系人最多记一天、每种类型和关系最多投一票:几条归类落在同一个身份上时并在一起,记在它们引用的通知里
     * 最新那条发出的那天。所以跨了午夜的一批不能凭一次判断就凑够显示关系要的两天。日子取通知发出的那天,不是判断的那天。
     */
    fun apply(
        events: List<LearnEvent>,
        signals: List<ContactSignal>,
        topicsByEvent: Map<Int, Set<String>>,
        nowMs: Long,
        zone: ZoneId,
        allowSensitive: Boolean = false,
        strategy: ContactIdStrategy = LearnTuning.CONTACT_ID,
    ): Applied {
        // 标题是发言人、不是会话名的(没有会话名的群聊)认不出是哪个会话:不记
        val ids = events.map { if (it.titleIsSpeaker) null else ContactIdentity.of(it.packageName, it.key, it.title, strategy) }
        // 「不再记录」的名单里可能还是按以前的算法(带着通知键)记的 id:这条通知按以前的算法正好是它,就把现在的 id 也记进去。
        // 不然换了算法,用户删掉的联系人就悄悄回来了
        // 名单因此变长也算改动:要存上。不然进程一重启、通知键又换了,以前的 id 对不上,删掉的就回来了
        var carried = false
        if (strategy != ContactIdStrategy.KeyAndTitle) events.forEachIndexed { i, e ->
            val now = ids[i]?.id ?: return@forEachIndexed
            if (now in blocked) return@forEachIndexed
            // 现在的 id 上已经有联系人了(用户留着的那一条换过来的,或换了通知键之后新记的):不带。带了的话它既在名单里
            // 又在列表里:留在页面上,却再也不会动,也没有任何说明。用户不要它可以再删一次,这回按会话名记,换了键也挡得住
            if (now in contacts) return@forEachIndexed
            val before = ContactIdentity.of(e.packageName, e.key, e.title, ContactIdStrategy.KeyAndTitle)?.id
            // 换成现在的 id,不是再添一个:旧的那个以后用不上了(现在的 id 换了通知键也挡得住),两个都留着的话,
            // 一次删除占名单里两个位置,名单满着时换一个就挤掉一个别的、还没换过来的删除
            if (before != null && before in blocked) { blocked.remove(before); block(now); carried = true }
        }
        // 显示名用这批里这个会话最近一条能给的;没有的话沿用旧的;第一次见就给不出的不记
        fun nameOf(id: String): String? = events.indices.filter { ids[it]?.id == id }.sortedBy { events[it].postedAtMs }
            .mapNotNull { ids[it]?.name }.lastOrNull()
        val dropped = LinkedHashMap<ContactDrop, Int>()
        fun drop(d: ContactDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
        // 这一次里每个身份被引用到的通知(下标),和被投过的类型、关系
        class Cite {
            val cited = LinkedHashSet<Int>()
            val types = LinkedHashSet<ContactType>()
            val relations = LinkedHashSet<ContactRelation>()
        }
        val cites = LinkedHashMap<String, Cite>()
        var accepted = 0
        for (s in signals) {
            val cited = s.events.distinct().filter { it in events.indices }
            val relation = if (!allowSensitive && s.relation == ContactRelation.Partner) ContactRelation.Unknown else s.relation
            var used = false
            for ((id, idx) in cited.groupBy { ids[it]?.id }) {
                if (id == null) { drop(ContactDrop.NoIdentity); continue }
                if (id in blocked) { drop(ContactDrop.Blocked); continue }
                if (id !in contacts && nameOf(id) == null) { drop(ContactDrop.NoIdentity); continue }
                val c = cites.getOrPut(id) { Cite() }
                c.cited += idx
                // 是群还是人:通知的写法说得清的(引用到的几条都一样)按它算,不听模型的;说不清的才用模型给的
                c.types += knownType(idx.map { events[it].group }) ?: s.type
                c.relations += relation
                used = true
            }
            if (used) accepted++
        }
        var changed = false
        for ((id, cite) in cites) {
            val name = nameOf(id)
            val first = cite.cited.minOf { events[it].postedAtMs }.coerceAtMost(nowMs)
            val seen = cite.cited.maxOf { events[it].postedAtMs }.coerceAtMost(nowMs)
            val day = listOf(TopicPool.dayOf(seen, zone))
            val c = contacts[id] ?: ObservedContact(id, events[cite.cited.first()].packageName, name!!, firstSeenMs = first, lastSeenMs = seen)
            contacts[id] = c.copy(
                name = name ?: c.name,
                firstSeenMs = minOf(c.firstSeenMs, first),
                lastSeenMs = maxOf(c.lastSeenMs, seen),
                days = addDays(c.days, day),
                typeVotes = cite.types.fold(c.typeVotes) { votes, type -> vote(votes, type, day) },
                relationVotes = cite.relations.fold(c.relationVotes) { votes, relation -> vote(votes, relation, day) },
            )
            changed = true
        }
        // 话题连到联系人上:这批里属于(现在池里有的)联系人的通知,它被归到的话题
        for ((i, topicIds) in topicsByEvent) {
            val id = ids.getOrNull(i)?.id ?: continue
            val c = contacts[id] ?: continue
            val next = (c.topics - topicIds + topicIds).takeLast(LearnTuning.CONTACT_TOPICS)
            if (next != c.topics) { contacts[id] = c.copy(topics = next); changed = true }
        }
        return Applied(accepted, dropped, changed || carried)
    }

    /**
     * 整理:去掉指着已不存在的话题的连接([liveTopics] 为 null 时不查:话题池没读出来);留存低于 [LearnTuning.FORGET_BELOW] 的丢掉(用户设过关系的不丢);
     * 超过 [LearnTuning.CONTACT_CAP] 个时丢强度×留存最低的(同样不丢用户设过关系的)。有改动返回 true。
     */
    fun maintain(nowMs: Long, liveTopics: Set<String>?): Boolean {
        var changed = liveTopics != null && forgetTopicsWhere { it !in liveTopics }
        val auto = contacts.values.filter { it.userRelation == null }
        auto.filter { it.retention(nowMs) < LearnTuning.FORGET_BELOW }.forEach { contacts.remove(it.id); changed = true }
        if (contacts.size > LearnTuning.CONTACT_CAP) {
            contacts.values.filter { it.userRelation == null }.sortedBy { it.score(nowMs) }
                .take(contacts.size - LearnTuning.CONTACT_CAP).forEach { contacts.remove(it.id); changed = true }
        }
        return changed
    }

    /** 话题 [from] 被用户并进了 [into]:连着它的改成连着那一个(留在原来的位置;两个都连着的只留一个)。有改动返回 true。 */
    fun remapTopic(from: String, into: String): Boolean {
        var changed = false
        for (c in contacts.values.toList()) {
            if (from !in c.topics) continue
            contacts[c.id] = c.copy(topics = c.topics.map { if (it == from) into else it }.distinct())
            changed = true
        }
        return changed
    }

    /** 话题被删了:联系人上不再连着它。有改动返回 true。 */
    fun forgetTopics(ids: Set<String>): Boolean = ids.isNotEmpty() && forgetTopicsWhere { it in ids }

    private fun forgetTopicsWhere(gone: (String) -> Boolean): Boolean {
        var changed = false
        for (c in contacts.values.toList()) {
            if (c.topics.none(gone)) continue
            contacts[c.id] = c.copy(topics = c.topics.filterNot(gone))
            changed = true
        }
        return changed
    }

    /** 用户设关系;null 是交回去按票数猜。 */
    fun setRelation(id: String, relation: ContactRelation?): Boolean {
        val c = contacts[id] ?: return false
        if (c.userRelation == relation) return false
        contacts[id] = c.copy(userRelation = relation)
        return true
    }

    /**
     * 用户删一个:同一个会话以后也不再记(只记 id)。删的是按以前的算法存下来的一条时(补做以前没存上的删除),
     * 它按现在的算法该是哪个 id 也一并记进去——同名的那几条接下来会并成一条([adoptIdentities]),不能让它借着换算法回来。
     */
    fun delete(id: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Boolean {
        val c = contacts.remove(id) ?: return false
        block(id)
        if (fromAnOlderStrategy(id)) ContactIdentity.ofStored(c.packageName, c.name, strategy)?.let(::block)
        return true
    }

    /**
     * 补做一次记着的删除([id] 是当时删掉的那个联系人):还在就照常删;不在了也把 [id] 记进「不再记录」。
     * 不在是会有的:把以前的条目换成现在的身份([adoptIdentities])之后没存上,用户又删了换好的那一条、也没存上,
     * 进程没了——下次读出来的还是换之前的文件,里面没有这个 id。只是跳过的话,接着再换一遍身份,删掉的就回来了。
     * 有改动返回 true。
     */
    fun forget(id: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Boolean {
        if (delete(id, strategy)) return true
        if (id in blocked) return false
        block(id)
        return true
    }

    private fun block(id: String) {
        blocked.remove(id)
        blocked += id
        while (blocked.size > LearnTuning.CONTACT_BLOCKED_CAP) blocked.remove(blocked.first())
    }

    /**
     * 把按以前的算法(带着通知键)存下来的联系人换成现在的身份([strategy],只看会话名):同一个 App 里名字一样的并成一条。
     * 以前同一个群每换一次通知键就多存一条,这里把它们合回去。算不出现在的身份的(名字是空的)原样留着,等它淡忘。
     * 并的时候:出现过的日子、类型和关系的票取并集;名字、用户定的关系用最近出现过的那一条的,它没有定过就用另一条的;
     * 连着的话题都留着(超出上限时留最近的)。现在的身份在「不再记录」里的,不留。
     * 已经都是现在的身份时什么都不做。有改动返回 true。要在补做完记着的删除之后调(那些删除记的是以前的 id)。
     */
    fun adoptIdentities(strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Boolean {
        if (strategy != ContactIdStrategy.Title || contacts.keys.none(::fromAnOlderStrategy)) return false
        val out = LinkedHashMap<String, ObservedContact>()
        for (c in contacts.values) {
            val id = if (fromAnOlderStrategy(c.id)) ContactIdentity.ofStored(c.packageName, c.name, strategy) ?: c.id else c.id
            if (id in blocked) continue
            val moved = if (id == c.id) c else c.copy(id = id)
            out[id] = out[id]?.let { merge(it, moved) } ?: moved
        }
        contacts.clear()
        contacts.putAll(out)
        return true
    }

    /** 重新允许删掉过的联系人(一次全部)。 */
    fun allowAgain(): Boolean {
        if (blocked.isEmpty()) return false
        blocked.clear()
        return true
    }

    /** 清空全部:是重来,不记进「不再记录」。 */
    fun clear(): Boolean {
        if (contacts.isEmpty()) return false
        contacts.clear()
        return true
    }

    /** 清掉某个 App 的:同样不记进「不再记录」。 */
    fun clearApp(packageName: String): Boolean = contacts.values.removeAll { it.packageName == packageName }

    /**
     * 关掉「敏感类推断」并选了删除:去掉「伴侣」的票(用户自己设的不动,那不是推断)。有改动返回 true。
     */
    fun deleteSensitive(): Boolean {
        var changed = false
        for (c in contacts.values.toList()) {
            if (ContactRelation.Partner !in c.relationVotes) continue
            contacts[c.id] = c.copy(relationVotes = c.relationVotes - ContactRelation.Partner)
            changed = true
        }
        return changed
    }

    companion object {
        /** 几条通知都说是群(或都说是私聊)时的类型;有说不清的、互相不一致的为 null。 */
        private fun knownType(groups: List<Boolean?>): ContactType? = when {
            groups.isEmpty() || groups.any { it == null } -> null
            groups.all { it == true } -> ContactType.Group
            groups.none { it == true } -> ContactType.Person
            else -> null
        }

        // 以前的算法(键加标题,发出去的版本里只用过这一种)给的 id:它的字母加一段十六进制的哈希。逐个字符比,不用正则
        private fun fromAnOlderStrategy(id: String): Boolean {
            val prefix = ContactIdStrategy.KeyAndTitle.prefix
            return id.length == prefix.length + NoticeDedup.ID_HEX && id.startsWith(prefix) &&
                id.drop(prefix.length).all { it in '0'..'9' || it in 'a'..'f' }
        }

        /** 两条其实是同一个联系人:并成一条(规则见 [adoptIdentities])。id、App 用 [a] 的。 */
        private fun merge(a: ObservedContact, b: ObservedContact): ObservedContact {
            val (older, newer) = if (b.lastSeenMs >= a.lastSeenMs) a to b else b to a
            fun <K> votes(x: Map<K, List<Long>>, y: Map<K, List<Long>>): Map<K, List<Long>> =
                (x.keys + y.keys).associateWith { addDays(x[it].orEmpty(), y[it].orEmpty()) }
            return a.copy(
                name = newer.name,
                firstSeenMs = minOf(a.firstSeenMs, b.firstSeenMs),
                lastSeenMs = maxOf(a.lastSeenMs, b.lastSeenMs),
                typeVotes = votes(a.typeVotes, b.typeVotes),
                relationVotes = votes(a.relationVotes, b.relationVotes),
                days = addDays(a.days, b.days),
                // 超出上限时留最近的:两边都连着的话题按较新那一条里的位置算(和平时刷新连接的规矩一样)
                topics = (older.topics - newer.topics.toSet() + newer.topics).distinct().takeLast(LearnTuning.CONTACT_TOPICS),
                userRelation = newer.userRelation ?: older.userRelation,
            )
        }

        private fun addDays(old: List<Long>, days: List<Long>): List<Long> =
            (old + days).toSortedSet().toList().takeLast(LearnTuning.CONTACT_DAYS)

        /** 一天一种值只算一票:票就是投过的日子。 */
        private fun <T> vote(votes: Map<T, List<Long>>, value: T, days: List<Long>): Map<T, List<Long>> {
            val out = LinkedHashMap(votes)
            out[value] = addDays(votes[value].orEmpty(), days)
            return out
        }
    }
}
