package com.wangzan101.mika.core.proactive

/** [NoticeFilter.decide] 的结论:留下,或者为什么丢掉。 */
enum class NoticeDecision {
    Keep,
    /** 不在用户勾选的 App 里。 */
    NotWhitelisted,
    /** Mika 自己(各种安装包)的通知,勾了也不收。 */
    SelfPackage,
    /** 在「应用访问」里被禁用的 App。 */
    AppBlocked,
    Ongoing,
    GroupSummary,
    ForegroundService,
    NotClearable,
    /** 进度、播放控制、后台服务、来电、闹钟、导航、秒表这类类别。 */
    Category,
    /** 标题和正文都是空的。 */
    Blank,
}

/**
 * 通知筛选:纯函数,先看包名(白名单 → 自己 → 应用访问禁用),再看通知特征,最后看有没有内容。
 * 包名这一关([decidePackage])在读通知内容之前就做:不在白名单里的通知连内容都不读。
 */
object NoticeFilter {
    const val SELF_PREFIX = "com.wangzan101.mika"
    const val TITLE_MAX = 200
    const val TEXT_MAX = 2_000
    const val MESSAGES_MAX = 20

    /** 不收的类别(值与 android.app.Notification.CATEGORY_* 相同)。 */
    val DROPPED_CATEGORIES = setOf("progress", "transport", "service", "call", "alarm", "navigation", "stopwatch")

    /** Mika 自己的包:正式版、.dev、.dev.test 等等都算。 */
    fun isSelf(packageName: String): Boolean = packageName.startsWith(SELF_PREFIX)

    /** 只看包名的那一关;[blocked] 是「应用访问」里禁用的包名。 */
    fun decidePackage(packageName: String, whitelist: Set<String>, blocked: Set<String>): NoticeDecision = when {
        packageName !in whitelist -> NoticeDecision.NotWhitelisted
        isSelf(packageName) -> NoticeDecision.SelfPackage
        packageName in blocked -> NoticeDecision.AppBlocked
        else -> NoticeDecision.Keep
    }

    /** 整条的结论。[record] 应已经过 [sanitize],只有控制字符的标题也算空。 */
    fun decide(record: NoticeRecord, whitelist: Set<String>, blocked: Set<String>): NoticeDecision {
        val byPackage = decidePackage(record.packageName, whitelist, blocked)
        if (byPackage != NoticeDecision.Keep) return byPackage
        return when {
            record.ongoing -> NoticeDecision.Ongoing
            record.groupSummary -> NoticeDecision.GroupSummary
            record.foregroundService -> NoticeDecision.ForegroundService
            !record.clearable -> NoticeDecision.NotClearable
            record.category in DROPPED_CATEGORIES -> NoticeDecision.Category
            record.title.isBlank() && record.text.isBlank() -> NoticeDecision.Blank
            else -> NoticeDecision.Keep
        }
    }

    /** 清洗:去掉控制字符、双向文字控制符和看不见的格式字符,空白并成一个空格,按长度截断;消息只留最近 [MESSAGES_MAX] 条。 */
    fun sanitize(record: NoticeRecord): NoticeRecord = record.copy(
        title = clean(record.title, TITLE_MAX),
        text = clean(record.text, TEXT_MAX),
        messages = record.messages
            .map { m -> m.copy(sender = m.sender?.let { clean(it, TITLE_MAX) }?.ifBlank { null }, text = clean(m.text, TEXT_MAX)) }
            .filter { it.text.isNotBlank() }
            .takeLast(MESSAGES_MAX),
    )

    private val BIDI = Regex("[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u061C]")
    // 看不见的格式字符:软连字符、蒙古文元音分隔符、词连接符到不可见运算符。夹在结束标记里会让它看着不像标记;
    // 和双向控制符一起在合并空白之前去掉(U+180E 在有的平台上还算空格)。U+200C / U+200D 不去:emoji 和一些文字里要用
    private val INVISIBLE = Regex("[\\u00AD\\u180E\\u2060-\\u2064]")
    // 不用 (?U):安卓自带的正则引擎(ICU)不认这个开关,一加载就抛异常;这里把 Unicode 的空白类字符直接列出来,
    // 电脑上的 JVM 和安卓上结果一样。\p{Z} 是各种空格与行、段分隔符,另加零宽空格和 BOM。
    private val SPACES = Regex("[\\s\\p{Z}\\u200B\\uFEFF]+")

    internal fun clean(s: String, max: Int): String {
        val noControl = buildString(s.length) { s.forEach { append(if (Character.isISOControl(it)) ' ' else it) } }
        val collapsed = SPACES.replace(INVISIBLE.replace(BIDI.replace(noControl, ""), ""), " ").trim()
        return cap(collapsed, max)
    }

    /**
     * App 的显示名,给模型看之前收一收。名字是装的那个 App 自己在安装包里写的(第三方的字),我们却把它写在不可信标记外面、
     * 当成自己的标签用:清洗、截到 [APP_NAME_MAX] 个字,并去掉能搭出标记、结构的符号(尖括号、方括号、花括号、引号、反引号、
     * 冒号、逗号、分号)。这只是让它搭不出我们输入里的格式,不是把它变成可信的——名字里的词照样在。
     */
    fun appName(raw: String): String =
        clean(buildString(raw.length) { for (ch in raw) append(if (ch in APP_NAME_MARKUP) ' ' else ch) }, APP_NAME_MAX)

    /** 同上;收完是空的(名字全是符号)就用 [packageName] 顶上,不留一个空着的标签。 */
    fun appName(raw: String, packageName: String): String = appName(raw).ifEmpty { packageName }

    const val APP_NAME_MAX = 40
    // 逐个字符比,不用正则:这个类每来一条通知都要加载,安卓自带的正则引擎对写法比电脑上的 JVM 挑剔(见下面 SPACES 的注释),
    // 一个在电脑上没问题的写法到手机上可能一加载就抛异常
    private const val APP_NAME_MARKUP = "<>[]{}\"'`:：,，;；"

    /** 截到 [max] 个 UTF-16 单位以内,不把代理对切成两半。 */
    internal fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }
}
