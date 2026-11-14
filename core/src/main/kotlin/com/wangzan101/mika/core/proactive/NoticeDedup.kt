package com.wangzan101.mika.core.proactive

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * 去重:每个通知键记下见过的发出时间和见过的最新一条消息的时间,按最近使用留 [capacity] 个键。
 * 普通通知按(键, 发出时间)判断;会话样式的通知只有比上次见过的更新的消息才算新。
 * 只记时间,不记通知内容;键也不原样记:键里带着发通知的 App 自定的 tag(可能含会话标识),
 * 内存里和 [encode] 写出去的都只是键的 SHA-256 取前 [ID_HEX] 位十六进制([idOf]),落盘的东西倒推不出原来的键。
 *
 * 消息的时间是发通知的 App 填的,不能全信([trusted]):只看时间的话,填到将来的被原样记成水位,之后真的新消息永远比它「旧」;
 * 都填 0、几条挤在同一毫秒的,后面的也永远不算新。所以信不过的时间不进水位,而且有上回的内容可比时就比内容:
 * 这回的消息里有没有上回没见过的。每条消息的指纹只在内存里(不落盘:落盘的那份说好了只有时间),
 * 所以进程重启后头一回没得比,只能看时间——那一回时间又信不过的,算见过。
 */
class NoticeDedup(private val capacity: Int = DEFAULT_CAPACITY) {
    /** [sigs] 是上次见到的那几条消息各自的指纹(只在内存里,读回来的没有)。 */
    private data class Seen(val postedAtMs: Long, val lastMessageAtMs: Long, val sigs: Set<Long>? = null)

    /** 收下的一条通知里这回才见到的消息(由旧到新);不是会话样式的为空。 */
    class Admitted(val newMessages: List<NoticeMessage>)

    private val seen = object : LinkedHashMap<String, Seen>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>) = size > capacity
    }

    /** [record] 有没有新内容;有就记下,返回 true。 */
    fun accept(record: NoticeRecord): Boolean = admit(record) != null

    /** [record] 有新内容就记下,并说出里面哪几条消息是这回才见到的;没有新内容为 null。 */
    @Synchronized fun admit(record: NoticeRecord): Admitted? {
        val id = idOf(record.key)
        val before = seen[id]
        if (!record.conversation || record.messages.isEmpty()) {
            val fresh = before == null || record.postedAtMs != before.postedAtMs
            // 上回消息的指纹留着:这一回只是没带消息(或都是空的),下回带了还要有得比
            if (fresh) seen[id] = Seen(record.postedAtMs, before?.lastMessageAtMs ?: Long.MIN_VALUE, before?.sigs)
            return if (fresh) Admitted(emptyList()) else null
        }
        // 记着的水位比这条通知的发出时间还晚:不可能是真的,当没有
        val mark = before?.lastMessageAtMs?.takeIf { trusted(it, record.postedAtMs) || it < 1 } ?: Long.MIN_VALUE
        val sigs = record.messages.map(::fingerprint)
        val known = before?.sigs
        val new = when {
            before == null -> record.messages
            // 有上回的内容可比:这回的消息里上回没见过的(App 只是把最旧的几条挤出列表、时间填得不对,都不会算错)
            known != null -> record.messages.filterIndexed { i, _ -> sigs[i] !in known }
            // 重启后头一回:只能看时间,而且只认信得过的时间
            else -> record.messages.filter { trusted(it.atMs, record.postedAtMs) && it.atMs > mark }
        }
        val newest = record.messages.filter { trusted(it.atMs, record.postedAtMs) }.maxOfOrNull { it.atMs }
        val fresh = before == null || new.isNotEmpty()
        // 不算新的也把这回的内容记下:下回才有得比
        seen[id] = Seen(if (fresh) record.postedAtMs else before!!.postedAtMs, maxOf(newest ?: Long.MIN_VALUE, mark), sigs.toSet())
        return if (fresh) Admitted(new) else null
    }

    @Synchronized fun clear() = seen.clear()

    @Synchronized fun size(): Int = seen.size

    /** `{"v":2,"seen":[[键的 id, 发出时间, 最新消息时间], …]}`,由旧到新。 */
    @Synchronized fun encode(): String = JsonObject(mapOf(
        "v" to JsonPrimitive(FORMAT),
        "seen" to JsonArray(seen.map { (id, v) ->
            JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(v.postedAtMs), JsonPrimitive(v.lastMessageAtMs)))
        }),
    )).toString()

    companion object {
        const val DEFAULT_CAPACITY = 500
        /** 存下来的格式版本;不是这个版本的读到就当空的。 */
        const val FORMAT = 2
        const val ID_HEX = 16
        private const val HEX = "0123456789abcdef"

        /** 通知键的 id:UTF-8 的 SHA-256 取前 [ID_HEX] 位小写十六进制。手写十六进制,不用安卓 34 才有的 HexFormat。 */
        fun idOf(key: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            return buildString(ID_HEX) {
                for (i in 0 until ID_HEX / 2) {
                    val b = digest[i].toInt() and 0xff
                    append(HEX[b ushr 4]); append(HEX[b and 0x0f])
                }
            }
        }

        private fun isId(s: String) = s.length == ID_HEX && s.all { it in HEX }

        /** 消息的时间可以比通知的发出时间晚这么多:发消息的服务器的钟比手机快一点是常事。 */
        const val CLOCK_SKEW_MS = 2 * 60_000L

        /**
         * 消息自己带的时间([atMs],发通知的 App 填的)信不信得过:得在 1 和系统记下的发出时间([postedAtMs])之间,
         * 晚一点点([CLOCK_SKEW_MS])也算。没填的、填 0 的、比通知发出晚得多的(填错、故意填到将来)都信不过。
         * 通知日志给条目定时间也用这一条。
         */
        fun trusted(atMs: Long, postedAtMs: Long): Boolean = atMs in 1..postedAtMs + CLOCK_SKEW_MS

        /** 一条消息的指纹(谁说的、说了什么、几点):SHA-256 的前 8 个字节。只用来在内存里比「上回见没见过」。 */
        private fun fingerprint(m: NoticeMessage): Long {
            val d = MessageDigest.getInstance("SHA-256").digest((m.sender.orEmpty() + "\u0000" + m.text + "\u0000" + m.atMs).toByteArray(Charsets.UTF_8))
            var out = 0L
            for (i in 0 until 8) out = (out shl 8) or (d[i].toLong() and 0xff)
            return out
        }

        /** 读回 [encode] 的结果;读不懂的(损坏、别的格式版本、id 不像 id)当空的。 */
        fun decode(text: String?, capacity: Int = DEFAULT_CAPACITY): NoticeDedup {
            val d = NoticeDedup(capacity)
            if (text.isNullOrBlank()) return d
            runCatching {
                val o = Json.parseToJsonElement(text).jsonObject
                require(o.getValue("v").jsonPrimitive.int == FORMAT)
                val entries = o.getValue("seen").jsonArray.map { e ->
                    val a = e.jsonArray
                    val id = a[0].jsonPrimitive.content
                    require(a[0].jsonPrimitive.isString && isId(id))
                    id to Seen(a[1].jsonPrimitive.long, a[2].jsonPrimitive.long)
                }
                entries.forEach { (k, v) -> d.seen[k] = v }
            }.onFailure { d.seen.clear() }
            return d
        }
    }
}
