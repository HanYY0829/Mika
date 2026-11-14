package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.memory.SensitiveFilter

/**
 * 联系人身份怎么算。真机上核实过:微信同一个会话,通知划掉后再来消息,通知键不变;还没核实不同会话的键一定不同。
 */
enum class ContactIdStrategy(val prefix: String) {
    /**
     * 只看通知键:键是 App / 系统给的,发消息的人改不了;标题是对方自己的昵称(除非用户给了备注),
     * 陌生人把昵称改成「妈妈」也并不进已有的联系人。标题只当显示名。要求键一个会话一个。
     */
    Key("k"),
    /** 只看标题(键不是一个会话一个时的退路):同名的会并到一起,陌生人改成同样的昵称也会。 */
    Title("t"),
    /**
     * 键和标题都看(默认):键是一个会话一个时,陌生人抄了昵称键也不同,并不进来;一个 App 所有会话共用一个键时,标题还能把人分开。
     * 代价:用户改了对方的备注名,就当成一个新的联系人从头算(旧的慢慢淡忘)。
     */
    KeyAndTitle("b"),
}

/**
 * 联系人的身份由我们自己算,不由模型起名:只用(包名、通知键、标题)这三样,一个纯函数。
 * id 是 SHA-256 取前几位十六进制(和 [NoticeDedup.idOf] 同一个办法),前面带上策略的字母:id 里没有名字,也倒推不出键,
 * 换策略时旧的 id(包括「不再记录」里的)也不会和新的撞上。
 *
 * 已知的局限:群聊里的各个成员不单独记,一个群就是一个「群」类型的联系人。标题里带着会变的东西(未读条数之外的写法、
 * 群聊里把发言人写进标题)的 App,在看标题的策略下还是会把一个会话拆开:见到一种补一种([withoutUnreadCount])。
 */
object ContactIdentity {
    /** 算出来的身份;[name] 是这条通知能给的显示名(清洗过、过了敏感信息关卡),给不出时为 null。 */
    data class Identity(val id: String, val name: String?)

    // 真机上看到的:QQ 的标题是「群名(2条新消息)」,条数一变标题就变
    private val UNREAD_COUNT = Regex("\\s*[(（]\\s*[0-9]+\\s*条(?:以上)?新消息\\s*[)）]$")

    /**
     * 去掉标题结尾的未读条数(「(2条新消息)」这一种写法):它不是名字的一部分。不去掉的话,同一个会话每多一条未读就成了另一个联系人,
     * 永远攒不够天数,用户删掉的也会换个条数再回来。只认这一种结尾,名字里别处的括号和数字不动。
     */
    internal fun withoutUnreadCount(cleanTitle: String): String = UNREAD_COUNT.replace(cleanTitle, "")

    /** 算不出身份(标题策略下标题是空的)为 null。 */
    fun of(packageName: String, key: String, title: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Identity? {
        val name = displayName(title)
        val norm = TopicPool.normalize(withoutUnreadCount(NoticeFilter.clean(title, LearnTuning.TITLE_MAX)))
        val material = when (strategy) {
            ContactIdStrategy.Key -> key
            ContactIdStrategy.Title -> norm.ifEmpty { return null }
            ContactIdStrategy.KeyAndTitle -> key + "\n" + norm.ifEmpty { return null }
        }
        return Identity(strategy.prefix + NoticeDedup.idOf("contact\u0000$packageName\u0000$material"), name)
    }

    /**
     * 显示名:按通知文字的办法清洗,截到 [LearnTuning.CONTACT_NAME_MAX]。空的为 null;和新话题名一样,像网址、有一长串数字、
     * 过不了 [SensitiveFilter] 的也为 null(对没截短的整段查):标题就是一个电话号码的发件人、带单号的推送,不当名字存下来。
     * 所以只显示成号码的发件人不会成为联系人(第一次见就给不出名字的不记)。
     */
    fun displayName(title: String): String? {
        val full = withoutUnreadCount(NoticeFilter.clean(title, 1_000))
        if (full.isEmpty()) return null
        val normalized = LearnText.nfkc(full)
        if (LearnText.urlLike(normalized) || LearnText.longDigits(normalized) || LearnText.sensitive(full)) return null
        return NoticeFilter.cap(full, LearnTuning.CONTACT_NAME_MAX).trim().ifEmpty { null }
    }
}
