package com.wangzan101.mika.core.proactive

/**
 * 把一条聊天通知理成后面都认的样子:[NoticeRecord.title] 是会话的名字(人或群),[NoticeRecord.sender] 是群里说话的人,
 * [NoticeRecord.text] 只剩那句话,[NoticeRecord.group] 说这是不是群。未读条数不是内容,去掉。纯函数,在清洗
 * ([NoticeFilter.sanitize])之后、进收件箱之前做一次,后面的判定、提醒、归纳、联系人都用理好的。
 *
 * 带会话样式的通知(任何 App):会话名、是不是群都听系统的([styled]),不看是哪家 App。
 *
 * 不用系统会话样式的,有几个聊天 App 把「哪个会话、谁说的、第几条」都挤在标题和正文里,这里按各家的写法拆开。
 * 只拆在真机上见过写法的那几家;别的 App 不猜——猜错了会把不是聊天的通知拆出一个「发言人」来。
 *
 * 各家的写法(只看结构,不看内容):
 * - QQ 群:真机上看到的,标题「群名(13条新消息)」,正文「某人: 那句话」。
 * - QQ 私聊:**没在真机上看到过**,按「标题是对方、正文没有『某人: 』」猜的。
 * - 微信私聊:真机上看到的,标题是对方,正文「[3条]某人: 那句话」,「某人」就是标题。
 * - 微信群:**没在真机上看到过**,按「标题是群名、正文里的『某人』不是标题」猜的。
 * 猜的两种要在真机上核实;核实前拆错的后果是那一条的说话人、群/私聊标错。
 * 带会话样式的通知、别的 App 的通知原样不动。App 改了写法、或遇到没见过的写法时,拆不出来就原样留着,不猜。
 *
 * 已知的误判:私聊里对方发来的话本身是「时间: 明天」这种样子(QQ 私聊正文没有前缀),会被当成群里一个叫「时间」的人说的。
 * 只影响这一条显示成谁说的,不影响它算在哪个会话上。
 */
object NoticeShapes {
    private val QQ = setOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.qqlite", "com.tencent.mobileqqi")
    private const val WECHAT = "com.tencent.mm"

    /** 微信正文开头的「[3条]」。 */
    private val UNREAD_PREFIX = Regex("^\\[[0-9]+条\\]\\s*")
    /** 「某人: 那句话」:名字不长、里面没有冒号。 */
    private val SAID = Regex("^([^:：]{1,40}): (.+)$")

    fun apply(r: NoticeRecord): NoticeRecord = when {
        // 带会话样式的自己就分得清谁说了什么;会话的名字要换成系统给的那个
        r.conversation || r.messages.isNotEmpty() -> styled(r)
        r.packageName in QQ -> chat(r, ContactIdentity.withoutUnreadCount(r.title), r.text, privateWithoutPrefix = true)
        r.packageName == WECHAT -> chat(r, r.title, UNREAD_PREFIX.replace(r.text, ""), privateWithoutPrefix = false)
        else -> r
    }

    /**
     * 会话样式的群聊:系统把通知标题写成「会话名: 发言人」(发言人取最新一条消息的),会话名另放在一个字段里。
     * 拿标题当会话名的话,同一个群里每个人说话都成了另一个「会话」。有会话名就用它当标题,标题里的发言人放到 [NoticeRecord.sender]
     * (提醒、判定里的那一句带上是谁说的)。
     *
     * 是不是群:系统说是就是;系统没说、有会话名的当群(老写法里会话名只给群聊设)。系统说不是、却有会话名的看不出来——
     * 老的写法只设会话名、不另说是群,新的写法也可以给私聊起会话名——留给模型判断,不硬说是私聊。
     *
     * 没有会话名:私聊的标题本来就是对方,不动。群聊(系统说是群)的标题是最新一条的发言人,认不了会话,记上一笔。
     */
    private fun styled(r: NoticeRecord): NoticeRecord {
        val name = r.conversationTitle?.trim().orEmpty()
        if (name.isEmpty()) return if (r.group == true) r.copy(titleIsSpeaker = true) else r
        val rest = r.title.trim().removePrefix(name).trimStart()
        val speaker = if (rest.startsWith(":") || rest.startsWith("：")) rest.drop(1).trim() else ""
        // 发言人就是会话名的(以对方的名字当会话名的私聊)不另记,和 [chat] 里一样
        return r.copy(title = name, sender = speaker.takeIf { it.isNotEmpty() && it != name }, group = if (r.group == false) null else true)
    }

    /**
     * [title]、[text] 是去掉了未读条数的。正文是「某人: 那句话」时:某人就是标题的是私聊,不是的是群里的某人。
     * 正文没有这个样子时:[privateWithoutPrefix] 的 App(QQ)当私聊;别的看不出来。
     */
    private fun chat(r: NoticeRecord, title: String, text: String, privateWithoutPrefix: Boolean): NoticeRecord {
        val m = if (title.isBlank()) null else SAID.matchEntire(text)
        val name = m?.groupValues?.get(1)?.trim().orEmpty()
        return when {
            m == null || name.isEmpty() -> r.copy(title = title, text = text, sender = null, group = if (privateWithoutPrefix && title.isNotBlank()) false else null)
            name == title.trim() -> r.copy(title = title, text = m.groupValues[2].trim(), sender = null, group = false)
            else -> r.copy(title = title, text = m.groupValues[2].trim(), sender = name, group = true)
        }
    }
}
