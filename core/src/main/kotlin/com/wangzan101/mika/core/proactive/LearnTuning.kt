package com.wangzan101.mika.core.proactive

/**
 * 「从通知里归纳」的所有可调的数,集中在这里。都是暂定的:先上真机看攒出来的东西像不像样,再改。
 */
object LearnTuning {
    // ---- 收集与发送 ----
    /** 内存里最多留多少条事件(满了先丢已归纳过的最旧的)。 */
    const val BUFFER_CAP = 200
    /** 一批最多几条(只有一个 App,最旧的先发)。 */
    const val BATCH_MAX = 12
    /** 一个 App 攒到这么多条没归纳的,就该发了。 */
    const val DUE_COUNT = 12
    /** 或者它最旧的一条没归纳的已经过了这么久(只在有事发生时才看,不定时)。 */
    const val DUE_AGE_MS = 30 * 60_000L
    /** 一次请求最多等多久。 */
    const val TIMEOUT_MS = 30_000L
    /**
     * 一批没归纳成之后,停多久再发下一批:按连续失败的次数逐级拉长,之后都是最后一级(和判定用同一组时长)。
     * 不定时:到点后也要等下一件事发生才看。
     */
    val FAILURE_HOLD_MS: List<Long> = listOf(2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L)

    /** 连续第 [failures] 次(从 1 起)失败之后停多久。 */
    fun failureHoldMs(failures: Int): Long = FAILURE_HOLD_MS[(failures - 1).coerceIn(0, FAILURE_HOLD_MS.lastIndex)]

    /** 发给模型的通知标题、正文各截到多长。 */
    const val TITLE_MAX = 120
    const val TEXT_MAX = 200
    /**
     * 同一条通知(同一个键)同样的文字,安静了这么久再出现就算新的一次。聊天 App 每来一句会把同一条通知原样重发一遍,
     * 那不算新的;但第二天又发来的同一句话(每天的问候、固定的服务提醒)是新的一次,话题和联系人要靠它攒「出现了几天」。
     * 半天:比一条通知被反复重发的间隔长得多,又比「每天一次」短。归纳的缓冲从最近一次出现算起(一直被重发的不算);
     * 通知原文从上一次记下算起(没有地方记「最近一次出现」)。
     */
    const val REPEAT_WINDOW_MS = 12 * 3_600_000L
    /** 一条通知的消息列表里,这回才见到的最多拿几条去归纳(新的优先)。 */
    const val NEW_LINES_MAX = 5

    // ---- 回复校验 ----
    /** 一次请求里给模型看的已有话题最多几个(按强度×留存从高到低)。 */
    const val EXISTING_MAX = 30
    /** 一次回复最多认几条。 */
    const val SIGNALS_MAX = 8
    /** 话题名最长多少(UTF-16 单位)。 */
    const val LABEL_MAX = 24
    /** 话题名里不许有这么长的一串数字(订单号、电话、验证码之类)。 */
    const val DIGIT_RUN_MAX = 5

    // ---- 话题池 ----
    /** 每个 App 下最多记多少个不同的日子(只留最近的)。 */
    const val DAYS_PER_APP = 60
    /** 一个话题最多记几个 App 的证据(多了丢最久没出现的那个 App)。 */
    const val APPS_PER_TOPIC = 20
    /** 用户点开过一次,强度加多少。 */
    const val OPENED_BONUS = 0.5
    /** 用户划掉过一次,强度减多少(强度不低于 0)。 */
    const val DISMISSED_PENALTY = 0.25
    /** 遗忘的时间尺度:S = 这么多天 × (1 + 强度);越常被印证的忘得越慢(艾宾浩斯 / MemoryBank 的想法)。 */
    const val STABILITY_DAYS = 7.0
    /** 留存 exp(-Δt / S) 低于这个数的,下次整理时丢掉。 */
    const val FORGET_BELOW = 0.1
    /** 话题池最多留多少个;多了先丢强度×留存最低的。 */
    const val POOL_CAP = 300
    /** 至少在几个不同的日子里出现过,才算「已确立」。 */
    const val ESTABLISHED_DAYS = 2

    // ---- 联系人与关系 ----
    /**
     * 联系人身份怎么算(见 [ContactIdentity]):只看会话名。真机上核实过,通知键不是一个会话一个——有的聊天 App 的通知 id
     * 是顺着往上加的,同一个群隔一会儿就换一个键;带着键算的话,同一个群每换一次键就多出一个联系人,谁也攒不够天数。
     */
    val CONTACT_ID = ContactIdStrategy.Title
    /** 一次回复最多认几条联系人。 */
    const val CONTACTS_MAX = 6
    /** 显示名最长多少(UTF-16 单位)。 */
    const val CONTACT_NAME_MAX = 40
    /** 每个联系人最多记多少个不同的日子(只留最近的;每种类型、每种关系的票也各按天记,同样只留这么多)。 */
    const val CONTACT_DAYS = 60
    /** 每个联系人最多连着几个话题(多了丢最早连上的)。 */
    const val CONTACT_TOPICS = 12
    /** 至少在几个不同的日子里出现过,才算「已确立」的联系人。 */
    const val CONTACT_ESTABLISHED_DAYS = 3
    /** 显示猜出的关系,至少要有几个不同的日子投过它。 */
    const val RELATION_MIN_DAYS = 2
    /** 联系人最多留多少个;多了先丢强度×留存最低的(用户设过关系的不丢)。 */
    const val CONTACT_CAP = 300
    /** 删掉后「不再记录」的话题名最多记多少个(多了丢最早的)。 */
    const val TOPIC_BLOCKED_CAP = 500
    /** 并掉的话题名最多记多少个(多了丢最早的;见 [TopicPool.merge])。 */
    const val TOPIC_MERGED_CAP = 500
    /** 「不再记录」的身份 id 最多记多少个(多了丢最早的)。 */
    const val CONTACT_BLOCKED_CAP = 500

    // ---- 当日消息归纳:第 1 级,分段归纳 ----
    /** 一批最多几段通知原文(只有一个 App,最旧的先发)。比归纳话题的一批大:这里只要几句话。 */
    const val NOTES_BATCH_MAX = 20
    /** 一个 App 攒到这么多段没归纳过的,就该发了。 */
    const val NOTES_DUE_COUNT = 20
    /** 或者它最旧的一段已经过了这么久(只在有事发生时才看,不定时)。 */
    const val NOTES_DUE_AGE_MS = 2 * 3_600_000L
    /** 一次回复最多认几句。 */
    const val NOTES_MAX = 10
    /** 一句最长多少(UTF-16 单位)。真机上 120 太短:一个群里的讨论只剩「在聊某某」,要留得下谁说了什么、结论是什么。 */
    const val NOTE_MAX = 240
    /** 一句里连着的数字超过这么多位就换成「…」(单号、卡号、电话;金额、日期短,留着)。 */
    const val NOTE_DIGIT_RUN_MAX = 6
    /** 分段归纳留多少天(周归纳做完之前要用)。 */
    const val NOTES_KEEP_DAYS = 8L

    // ---- 当日消息归纳:第 2 级,当日归纳 ----
    /** 一次请求最多放多少句分段归纳(多了只放最新的这么多)。 */
    const val DAY_NOTES_MAX = 60
    /** 当日归纳最多几句、每句最长多少。 */
    const val DAY_LINES_MAX = 15
    const val DAY_LINE_MAX = 300
    /**
     * 一份归纳(当日、周、月)的一句概述最长多少:主要是中日韩文字的按 [BRIEF_MAX_CJK] 个字,别的文字一个词占好几个字母,
     * 放宽到 [BRIEF_MAX]。超了就截。
     */
    const val BRIEF_MAX_CJK = 50
    const val BRIEF_MAX = 120
    /** 当日归纳留多少天(周、月归纳要用)。 */
    const val DAYS_KEEP = 35L
    /**
     * 今天还没过完时,「到现在为止」的当日归纳什么时候自动做(用户不必去点):上一份之后新写下的句子至少
     * [TODAY_AUTO_MIN_NOTES] 句,并且要么已经有 [TODAY_AUTO_READY_NOTES] 句、要么其中最早的一句等了
     * [TODAY_AUTO_WAIT_MS];离上一份至少 [TODAY_AUTO_INTERVAL_MS](一天也就做三四次,不把预算花在反复重写上)。
     * 和别的一样只在有事发生时看一眼,没有定时器。
     */
    const val TODAY_AUTO_MIN_NOTES = 2
    const val TODAY_AUTO_READY_NOTES = 5
    const val TODAY_AUTO_WAIT_MS = 2 * 3_600_000L
    const val TODAY_AUTO_INTERVAL_MS = 4 * 3_600_000L
    /** 同一天的当日归纳在一个进程里最多试几次(回复总读不懂时不一直试下去)。 */
    const val DAY_TRIES_MAX = 3

    // ---- 当日消息归纳:第 3 级(周)、第 4 级(月) ----
    /** 一次请求里下一级的句子加起来最多多少字;超了各份轮流取,每份留住排在前面的。 */
    const val SPAN_INPUT_CHARS = 16_000
    /** 周、月归纳最多几句、每句最长多少。 */
    const val SPAN_LINES_MAX = 20
    const val SPAN_LINE_MAX = 300
    /** 周、月归纳的请求等多久:输入比别的批长得多,30 秒不够慢一点的模型读完。 */
    const val SPAN_TIMEOUT_MS = 90_000L
    /** 周归纳留多少天(26 周)。 */
    const val WEEKS_KEEP_DAYS = 182L
    /** 月归纳留多少天(两年)。 */
    const val MONTHS_KEEP_DAYS = 730L
    /** 同一周(同一个月)的归纳在一个进程里最多试几次。 */
    const val SPAN_TRIES_MAX = 3

    const val DAY_MS = 86_400_000L
}
