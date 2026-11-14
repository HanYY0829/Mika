package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ContactPool
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.ContactType
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.proactive.DayResult
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.ExistingTopic
import com.wangzan101.mika.core.proactive.LearnBuffer
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.LearnOutcome
import com.wangzan101.mika.core.proactive.LearnResult
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.NoticeDay
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.NoticeLearn
import com.wangzan101.mika.core.proactive.ObservedContext
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.NoticeNotes
import com.wangzan101.mika.core.proactive.NoticeView
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestView
import com.wangzan101.mika.core.proactive.DigestViewLine
import com.wangzan101.mika.core.proactive.DigestsView
import com.wangzan101.mika.core.proactive.NoticeSpan
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.NotesResult
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageGate
import com.wangzan101.mika.core.usage.ProactiveBudget
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

enum class LearnStatus {
    Off,
    /** 归纳开着,但主动能力没在看守(完全退出期间)。 */
    Paused,
    NoModel,
    NeedsConsent,
    /** 「应用访问」设置读不出来:不知道哪些 App 不让碰,停着;攒下的不动。 */
    Suspended,
    Locked,
    /** 有任务在跑,等它结束。 */
    Busy,
    BudgetUsedUp,
    /** 话题池读不出来(密钥库一时不可用之类):不发,等下次有事时再试。 */
    StoreUnavailable,
    /** 有待归纳的,但还没有哪个 App 到期;或者上一批没归纳成,正停着(见 [LearnTuning.FAILURE_HOLD_MS])。 */
    Waiting,
    Sending,
    Idle,
}

/**
 * 一个 App 的计数(只在内存里,不含任何内容):收下几条、归纳了几条、发了几批、失败几批、认了几条、按原因丢了几条;
 * 联系人归类认了几条、按原因丢了几条(和话题分开算)。
 */
data class LearnFeed(
    val captured: Int = 0,
    val distilled: Int = 0,
    val batches: Int = 0,
    val failed: Int = 0,
    val accepted: Int = 0,
    val dropped: Map<LearnDrop, Int> = emptyMap(),
    val contactsAccepted: Int = 0,
    val contactDropped: Map<ContactDrop, Int> = emptyMap(),
) {
    val droppedTotal: Int get() = dropped.values.sum()
    val contactDroppedTotal: Int get() = contactDropped.values.sum()
}

/** 联系人给页面的样子:都有谁,和「不再记录」里有几个(只有个数)。 */
data class ContactsView(val contacts: List<ObservedContact> = emptyList(), val blocked: Int = 0)

data class LearnState(
    val status: LearnStatus = LearnStatus.Off,
    val buffered: Int = 0,
    val pending: Int = 0,
    val lastDistilledAtMs: Long? = null,
    val feed: Map<String, LearnFeed> = emptyMap(),
    /** 通知日志里现在留着几段通知原文(「当日消息归纳」关着时是 0)。 */
    val journal: Int = 0,
    /** 留着的通知原文出自哪些 App(只有包名):还没归纳出任何东西的 App 也要能单独清掉,见「清空来自它的」。 */
    val journalApps: Set<String> = emptySet(),
)

/** 页面上点开一句分段归纳、看它是照着哪些通知写的([LearnRunner.rawFor])。 */
sealed interface RawNotices {
    /** 还存着的那几段(可能一段都没有:过了保留期,或被清掉了)。 */
    data class Found(val entries: List<NoticeEntry>) : RawNotices
    /** 总开关或「当日消息归纳」关着:通知原文那时就删掉了。 */
    data object Off : RawNotices
    /** 这会儿读不出来(存储、「应用访问」读不出来)。 */
    data object Unavailable : RawNotices
}

/**
 * 话题池放在哪、怎么读写:正式用 [TopicPoolFile],测试给内存里的。读、写、删出错都抛出,由 [LearnRunner] 接住。
 */
interface TopicPoolStore {
    fun load(): TopicPoolData
    fun save(data: TopicPoolData)
    /** 删掉读不出来时另存下来的旧文件:用户删了东西,那些看不到的副本里可能还有。 */
    fun dropBroken()
    /** 整份删掉(文件、临时文件、另存的旧文件):「清空全部」时读不出来也能清。 */
    fun wipe()
}

/**
 * 联系人放在哪、怎么读写:正式用 [ContactPoolFile]。和话题池分开放:联系人读不出来不耽误归纳话题,
 * 页面上对联系人的操作也不用等话题池。反过来不成立:话题池读不出来时这一批不发(联系人是搭在同一个请求上的)。
 */
interface ContactPoolStore {
    fun load(): ContactPoolData
    fun save(data: ContactPoolData)
    /** 同 [TopicPoolStore.dropBroken]。 */
    fun dropBroken()
    /** 同 [TopicPoolStore.wipe]。 */
    fun wipe()
}

/**
 * 通知日志(通知原文,见 [NoticeJournal])放在哪:正式用 [NoticeJournalFile]。读出来没有为 null;读、写、删出错都抛出,由 [LearnRunner] 接住。
 */
interface NoticeJournalStore {
    fun load(): NoticeJournalData?
    fun save(data: NoticeJournalData)
    /** 整份删掉;读不出来时也能删。 */
    fun wipe()
}

/** 当日消息归纳攒下的句子放在哪:正式用 [DigestFile]。读出来没有为空的;读、写、删出错都抛出,由 [LearnRunner] 接住。 */
interface DigestStore {
    fun load(): DigestData
    fun save(data: DigestData)
    /** 整份删掉;读不出来时也能删。 */
    fun wipe()
}

/**
 * 还没落盘的删除:用户要删(关掉「敏感类推断」并删除、清掉某个 App 的、单删一个话题或联系人、一句、某天某周某月的归纳),
 * 当时那一份却读不出来,或者删了却没存上。记下来:读得出来时、下次启动时先补做,存上了才划掉。
 * 不记的话,删了没存上、进程又没了,文件里还是旧的,删掉的东西下次启动又回来了。
 *
 * 正式用 [ProactivePrefs](进程没了也还记着)。里面是包名(清掉这个 App 的),和以冒号开头的记号(包名里不会有冒号):
 * [LearnRunner.PURGE_SENSITIVE],以及单删的那些(只有编号、日子,没有内容;话题和句子的编号是随机的,
 * 联系人的是从 App 和会话名算出来的哈希——偏好文件不加密,这一项存上之前会在里面待一会儿)。记这一笔是尽力而为:
 * 存盘写不下去多半是存储满了,那时偏好多半也写不下去。
 *
 * 话题、联系人这两份是按记下的先后排着的(集合的遍历顺序就是先后),补做时照这个先后做:同一个话题名上,
 * 「删掉」和「并进别的」是后做的算数,合并的目标也得先于并进它的那一步存在。实现要把先后一起存下来。
 */
interface LearnPurges {
    /** 话题池上还没落盘的,按记下的先后。 */
    var topicPurges: Set<String>
    /** 联系人上还没落盘的,按记下的先后。 */
    var contactPurges: Set<String>

    /**
     * 同一个记号记到话题、联系人两份的末尾(一次合并两份都要改)。正式的实现([ProactivePrefs])一次落盘:
     * 分两次写的话,中间进程没了只记上一半,补做时话题并了、联系人上的连接没跟上。
     * 这里的默认写法分两次(先联系人),只给测试里的替身用。
     */
    fun addToTopicsAndContacts(mark: String) {
        contactPurges = contactPurges - mark + mark
        topicPurges = topicPurges - mark + mark
    }
    /** 分段归纳的句子和各级归纳上还没落盘的。 */
    var digestPurges: Set<String>
    /** 通知原文上还没落盘的:要去掉其全部原文的 App 包名,和「某个通知键某一阵的那几段」的记号(只有键的哈希和时间)。 */
    var journalPurges: Set<String>
}

/**
 * 「从通知里归纳」的调度,和 [TriageRunner] 一样在 [ProactiveRuntime] 那条串行协程上跑,只在看守期间([watch])活着。
 *
 * 省电:没有任何定时器、没有轮询。只在事情发生时看一遍([evaluate]):缓冲变了(收下一条)、锁屏状态变了、
 * [TriageHooks.busy] 变了、开关/同意/预算/不归纳的 App 变了([poke])。「最旧的一条过了 30 分钟」也只在这些时候才看。
 * 锁着时正在发的请求取消,那一批照旧待归纳。
 * 一次只发一批,一批只有一个 App、最多 12 条。没归纳成(记一次失败)之后停一段再发下一批,连续失败越多停得越久
 * ([LearnTuning.FAILURE_HOLD_MS]):不然模型一出问题,到期的各个 App 会一批接一批地发出去、失败。到点后也是等下一件事发生才看。
 * 那一批本身:模型回了错、超时、回复读不懂的,丢掉不重试(归纳本来就是攒统计);根本没连上模型的(模型只在家里的网络上、
 * 人出门了之类)留着,等连得上了再发。预算用完:安静停下,事件留着,当天不再发(改了预算才重新算)。
 * 日志([log])只记类别和条数,不记通知文字和话题名。
 *
 * 联系人与关系搭同一个请求(联系人读得出来时,请求里说可以给),不另发请求。用上结果时按这批事件(标题还在)
 * 算身份,再连上话题;这之后标题、正文照旧清掉。
 *
 * 开关和存着的东西:[on] 是总开关,[digest] 是「当日消息归纳」。关掉哪一个,都只是不再收、不再发,并把通知原文删掉;
 * 已经归纳出的(话题、联系人、分段归纳的句子、各级归纳)留着,由用户自己删、或到期删。所以清除、删除、到期这几条路
 * 在开关关着时也照做,不能假定「关着就什么都没存」。
 */
internal class LearnRunner(
    private val hooks: TriageHooks,
    private val on: () -> Boolean,
    private val consent: () -> TriageConsent?,
    private val budgetLimit: () -> Long,
    private val language: () -> String,
    /**
     * 这个 App 现在能不能归纳:勾选了、不是「不从它归纳」。只用来决定收不收、发不发;
     * 不拿它去删存着的东西(名单读不出来时每个 App 都「不够格」,那不是用户取消的)——删由 [forgetApps] 点名来做。
     */
    private val eligible: (String) -> Boolean,
    /**
     * 「应用访问」读不出来:不知道哪些 App 不让碰,整个停着——不收、不发、不往任务里带;攒下的一样都不删,
     * 读得出来了接着做。(不并进 [on] 和 [eligible]:那两个为假时会删原文、清待归纳的事件。)
     */
    private val suspended: () -> Boolean = { false },
    /** 和判定共用的请求位(一次只发一个后台请求);不给就是自己一个。 */
    private val slot: RequestSlot = RequestSlot(),
    /** 「敏感类推断」开着没有(默认关)。 */
    private val sensitive: () -> Boolean,
    private val store: TopicPoolStore,
    private val contactStore: ContactPoolStore = NoContacts,
    private val purges: LearnPurges = MemoryPurges(),
    /** 「当日消息归纳」开着没有(默认关):开着才把通知原文记进日志、存盘。 */
    private val digest: () -> Boolean = { false },
    private val journalStore: NoticeJournalStore = NoJournal,
    private val digestStore: DigestStore = NoDigest,
    /** App 的名字(按包名从系统读):当日归纳的输入里用,好让模型说「在微信里」。 */
    private val appLabel: (String) -> String = { it },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: (String) -> Unit = {},
) {
    private val buffer = LearnBuffer()
    private var pool: TopicPool? = null
    private var contactPool: ContactPool? = null
    private val feed = HashMap<String, LearnFeed>()
    private var lastDistilledAtMs: Long? = null
    private var budgetOutDay: Long? = null
    /** 连续几批没归纳成,和因此停到什么时候(见 [held])。 */
    private var failures = 0
    private var holdUntilMs = 0L
    /** 已经在内存里做掉、还没存上的补删(见 [purge]):存上的那一刻从记着的里划掉。 */
    private var topicPurgesApplied: Set<String> = emptySet()
    /** 文件里有的话题的 id(读出来时、每次存上时记下):内存里多出来的(刚归纳出来、还没存上的)不在里面。 */
    private var savedTopics: Set<String> = emptySet()
    private var contactPurgesApplied: Set<String> = emptySet()
    private var digestPurgesApplied: Set<String> = emptySet()
    private var journalPurgesApplied: Set<String> = emptySet()
    /**
     * 归纳那份上一次没存上:内存里的比文件里的新。再发分段、当日、周、月归纳的请求之前先把它存上,
     * 还存不上就不发——结果留不住,发了只是白花一次模型调用。
     */
    private var digestUnsaved = false
    /** 通知日志:第一次用到时读出来([loadedJournal]);[journalUnsaved] 是还没存盘的改动数。 */
    private var journal: NoticeJournal? = null
    private var journalUnsaved = 0
    /** 分段归纳攒下的句子:第一次用到时读出来([loadedDigest])。 */
    private var digestPool: DigestPool? = null
    /** 正在发的那一批分段归纳是哪个 App 的(没有在发为 null);和归纳话题的那一批共用 [inFlight],一次只发一个。 */
    private var notesApp: String? = null
    /** 正在做的那一份当日归纳是哪一天的(没有在做为 null);同样占着 [inFlight]。 */
    private var dayInFlight: Long? = null
    /** 用户点了「归纳今天到现在的」,还没做。 */
    private var todayWanted = false
    /** 各天的当日归纳在这个进程里已经试过几次(回复总读不懂时不一直试)。 */
    private val dayTries = HashMap<Long, Int>()
    /**
     * 「到现在为止」的那一份(自动做的、用户点的)在这个进程里失败过几次,另记:不能算进 [dayTries]——
     * 不然今天自动做的几次都读不懂,明天该做今天完整的那一份时就不试了,后面的日子也跟着等在它后面。
     */
    private val partialTries = HashMap<Long, Int>()
    private val daysFlow = MutableStateFlow<List<DayDigest>>(emptyList())
    /** 各天的当日归纳,新的在前。 */
    val digestDays: StateFlow<List<DayDigest>> = daysFlow.asStateFlow()
    /** 正在做的那一份周(月)归纳是哪一周(哪个月)的(没有在做为 null);同样占着 [inFlight]。 */
    private var spanInFlight: Pair<SpanKind, Long>? = null
    /** 用户点了「归纳本周(本月)到现在的」,还没做。 */
    private var weekWanted = false
    private var monthWanted = false
    /** 各周、各月的归纳在这个进程里已经试过几次。 */
    private val spanTries = HashMap<Pair<SpanKind, Long>, Int>()
    private val weeksFlow = MutableStateFlow<List<SpanDigest>>(emptyList())
    private val monthsFlow = MutableStateFlow<List<SpanDigest>>(emptyList())
    /** 各周的周归纳、各月的月归纳,新的在前。 */
    val digestWeeks: StateFlow<List<SpanDigest>> = weeksFlow.asStateFlow()
    val digestMonths: StateFlow<List<SpanDigest>> = monthsFlow.asStateFlow()
    private val removedFlow = MutableStateFlow<Set<Pair<SpanKind, Long>>>(emptySet())
    /** 被用户删掉了、不再自动做的周(月)归纳。 */
    val digestRemoved: StateFlow<Set<Pair<SpanKind, Long>>> = removedFlow.asStateFlow()
    private val removedDaysFlow = MutableStateFlow<Set<Long>>(emptySet())
    /** 被用户删掉了当日归纳的那几天(纪元日):不会再自动做,页面上要这么说。 */
    val digestRemovedDays: StateFlow<Set<Long>> = removedDaysFlow.asStateFlow()
    private val topicsBlockedFlow = MutableStateFlow(0)
    /** 用户删掉后不再记录的话题有几个。 */
    val topicsBlocked: StateFlow<Int> = topicsBlockedFlow.asStateFlow()
    private val notesFlow = MutableStateFlow<List<DigestNote>>(emptyList())
    /** 攒下的分段归纳(「当日消息归纳」关着、还没读出来时是空的)。 */
    val digestNotes: StateFlow<List<DigestNote>> = notesFlow.asStateFlow()
    private var status = LearnStatus.Off

    private val mutable = MutableStateFlow(LearnState())
    val state: StateFlow<LearnState> = mutable.asStateFlow()
    private val topicsFlow = MutableStateFlow<List<ObservedTopic>>(emptyList())
    /** 话题池现在的样子;打开页面([open])之前、或读不出来时是空的。 */
    val topics: StateFlow<List<ObservedTopic>> = topicsFlow.asStateFlow()
    private val contactsFlow = MutableStateFlow(ContactsView())
    /** 联系人现在的样子;打开页面之前、或读不出来时是空的。 */
    val contactsView: StateFlow<ContactsView> = contactsFlow.asStateFlow()

    private var host: CoroutineScope? = null
    private var readLocked: () -> Boolean = { true }
    private var inFlight: Job? = null

    internal val requestActive: Boolean get() = inFlight?.isActive == true

    suspend fun watch(locked: StateFlow<Boolean>, readLocked: () -> Boolean) {
        var mine: CoroutineScope? = null
        try {
            coroutineScope {
                mine = this
                inFlight?.cancel()
                inFlight = null
                slot.release(this@LearnRunner)
                slot.listen(this@LearnRunner) { evaluate() }
                // 上一次看守留下的「在发的是谁的」也清掉:留着的话,这一次发的别的请求会被当成它的、被错取消
                notesApp = null
                dayInFlight = null
                spanInFlight = null
                buffer.abort()
                host = this
                this@LearnRunner.readLocked = readLocked
                launch { buffer.state.collect { evaluate() } }
                launch { locked.collect { evaluate() } }
                // 通知日志不定时:每次锁屏、解锁时把到期的删掉、把没存的存上(进程多半是在锁屏后被系统收走的)
                launch { locked.collect { tendJournal() } }
                launch { hooks.busy.collect { evaluate() } }
                awaitCancellation()
            }
        } finally {
            if (host === mine) {
                host = null
                inFlight = null
                notesApp = null
                dayInFlight = null
                spanInFlight = null
                // 不再看守(完全退出):日志里没存的存上
                if (on() && digest()) saveJournal()
                buffer.abort()
                publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
                slot.unlisten(this)
                slot.release(this)
                slot.wakeOthers(this)
            }
        }
    }

    /**
     * 收件箱收下了一条(已清洗、筛过、去过重)。总开关关着、这个 App 不归纳时不收;用户还没同意过把通知发给模型时也不收——
     * 没同意就不为归纳攒任何东西,原文也不落盘(总开关开着但没配模型、等用户确认的时候)。
     * 同意过、只是服务商或模型变了在等重新确认的,照收:确认之后接着归纳。
     */
    fun capture(record: NoticeRecord, newMessages: List<NoticeMessage> = emptyList()) {
        if (!on() || suspended() || consent() == null || !eligible(record.packageName)) return
        // 通知日志只收归纳收的那些,而且「当日消息归纳」得开着
        if (digest()) record(record)
        if (buffer.capture(record, newMessages)) bump(record.packageName) { it.copy(captured = it.captured + 1) }
    }

    /**
     * 这个通知键其实是一条被反复重发的状态显示(导航播报之类,见 [LiveUpdates]):它从 [sinceMs] 起(这一阵重发)收进来、
     * 还没归纳的那几条去掉,通知原文里的也去掉。同一个键上更早的不动——很多 App 所有推送共用一个通知键。
     */
    fun dropLive(key: String, packageName: String, sinceMs: Long) {
        // 在途的请求里可能就有这几条:正在给这个 App 写分段归纳的那一批,或正在归纳话题的那一批里这个键这一阵的事件。
        // 取消(和清掉一个 App 时一样):不然回复回来,照着它们写的句子、归纳出的话题还是存下了。
        // 取消后归纳话题的那一批回到待归纳,下面一并去掉(不然锁屏之类再取消一次,它们又会被发出去);剩下的照常再发
        val stopped = notesApp == packageName || buffer.sending().any { it.key == key && it.postedAtMs >= sinceMs }
        if (stopped) stopRequest()
        buffer.forgetKey(key, sinceMs)
        if (on() && digest()) {
            // 通知原文里的:和按 App 删一样先记下来(见 [LearnPurges.journalPurges])。这会儿读不出来的、去掉了没存上的,
            // 读得出来时补做——不然它们还算没归纳过,下一次分段归纳又发给模型
            val keyId = com.wangzan101.mika.core.proactive.NoticeDedup.idOf(key)
            val mark = MARK_KEY + keyId + KEY_SINCE + sinceMs
            val j = loadedJournal()
            if (j == null) {
                purges.journalPurges = purges.journalPurges + mark
            } else if (j.removeKey(keyId, sinceMs, sinceMs + LIVE_WINDOW_MS)) {
                purges.journalPurges = purges.journalPurges + mark
                journalPurgesApplied = journalPurgesApplied + mark
                saveJournal()
            }
        }
        if (stopped && host != null) evaluate()
    }

    /** 记着的「这个通知键这一阵的去掉」:键的哈希、从什么时候起;不是这种记号、写得不对的为 null。 */
    private fun keyIn(mark: String): Pair<String, Long>? {
        if (!mark.startsWith(MARK_KEY)) return null
        val parts = mark.removePrefix(MARK_KEY).split(KEY_SINCE)
        val since = parts.getOrNull(1)?.toLongOrNull()
        return if (parts.size == 2 && parts[0].isNotEmpty() && since != null) parts[0] to since else null
    }

    /** 记进通知日志;攒够 [JOURNAL_FLUSH_EVERY] 段没存的就存一次(进程在两次存盘之间没了,最多丢这么多段,或上次锁屏之后的)。 */
    private fun record(record: NoticeRecord) {
        val j = loadedJournal() ?: return
        val added = j.add(record)
        if (added == 0) return
        journalUnsaved += added
        if (journalUnsaved >= JOURNAL_FLUSH_EVERY) saveJournal() else publish(status)
    }

    /** 系统说这条通知被移除了:点开、划掉记下来;「全部清除」不说明什么。已经归纳过的,补到它归到的话题上。 */
    fun removed(key: String, reason: Int) {
        val outcome = outcomeOf(reason)
        val late = buffer.outcome(key, outcome)
        if (late.isEmpty()) return
        val p = loadedPool() ?: return
        if (p.addOutcome(late, outcome, allowSensitive = sensitive())) save(p)
    }

    /**
     * 这几个 App 不再归纳了(用户取消勾选、调成「低」,或在「应用访问」里禁用):它们存着的通知原文、分段归纳的句子,
     * 和各级归纳里依据过它们的句子去掉;话题和联系人不动(要删用「清空来自它的」)。总开关关着时也照做。
     * 点名删,不按「现在不够格的都删」:读哪些 App 的名单读不出来时,没有一个 App 够格,那样会把攒下的全删光。
     * 归纳那份这时读不出来的,记下来,读得出来时补做。要在串行协程上调。
     */
    fun forgetApps(packages: Set<String>) {
        if (packages.isNotEmpty()) {
            if (on() && digest()) journalRemove(packages)
            // 正在做的当日(周、月)归纳里可能就有刚去掉的那个 App 的句子:取消,之后按剩下的重做
            digestRemove(packages, stopRequests = true)
        }
        poke()
    }

    /** 开关、同意、预算、不归纳的 App 变了:不再收的 App 的事件去掉,在发的请求该停的停,重新看一遍。要在串行协程上调。 */
    fun poke() {
        val sending = buffer.sending().firstOrNull()?.packageName
        if (sending != null && (!on() || !eligible(sending))) stopRequest()
        notesApp?.let { if (!on() || !digest() || !eligible(it)) stopRequest() }
        if ((dayInFlight != null || spanInFlight != null) && (!on() || !digest())) stopRequest()
        buffer.purge(eligible)
        // 通知原文:总开关或「当日消息归纳」关了就整份删掉。已经归纳出的句子和各级归纳不跟着开关删;
        // 某个 App 不再归纳时它存着的东西由 forgetApps 点名去掉,这里不按够不够格去删
        if (!on() || !digest()) dropJournal()
        if (host != null) evaluate() else publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
    }

    fun budgetChanged() {
        budgetOutDay = null
        poke()
    }

    /**
     * 关掉主动能力:请求取消,没处理完的事件、通知原文和计数清掉。已经归纳出的(话题、联系人、句子、各级归纳)不动,
     * 由用户自己删。
     */
    fun clear() {
        stopRequest()
        buffer.clear()
        dropJournal()
        todayWanted = false
        weekWanted = false
        monthWanted = false
        feed.clear()
        lastDistilledAtMs = null
        failures = 0
        holdUntilMs = 0L
        publish(if (on()) LearnStatus.Paused else LearnStatus.Off)
    }

    // ---- 页面上的操作(在串行协程上调) ----

    /**
     * 打开页面时:读出话题池和联系人并整理一遍(丢掉淡忘的、去掉指着没了的话题的连接);有改动就存。
     * 失败后停着的也不再停(用户在看这一页,多半是刚改了设置想看结果):有到期的就再试一次,再失败照样接着拉长。
     */
    fun open() {
        holdUntilMs = 0L
        val p = loadedPool()
        if (p != null) {
            val dropped = p.maintain(clock())
            if (dropped.isNotEmpty()) {
                buffer.forgetTopics(dropped)
                save(p)
            } else {
                topicsFlow.value = p.all()
        topicsBlockedFlow.value = p.blockedCount
            }
        }
        loadedContacts()?.let { c -> if (c.maintain(clock(), p?.all()?.map { it.id }?.toSet())) saveContacts(c) }
        // 到期的归纳不管开关开没开都删(关着时东西还留着,期限照算)
        freshDigest()
        // 通知原文也读出来:页面上「清空来自它的」要列出只有原文、还没归纳出东西的 App(没在看守时它还没读过)
        if (on() && digest()) loadedJournal()
        if (p == null) publish(LearnStatus.StoreUnavailable) else if (host != null) evaluate()
    }

    fun deleteTopic(id: String) {
        val p = loadedPool() ?: return
        if (p.delete(id)) {
            // 先记下来再存:没存上、进程又没了的,下次读出来时补做(见 [LearnPurges])
            val mark = MARK_TOPIC + id
            purges.topicPurges = purges.topicPurges - mark + mark
            topicPurgesApplied = topicPurgesApplied + mark
            topicsGone(setOf(id))
            save(p)
            dropBroken()
        }
    }

    /**
     * 把话题 [fromId] 并进 [intoId](用户在话题页上做的;规则见 [TopicPool.merge]):事件上、联系人上指着 [fromId] 的改指 [intoId]。
     * 不走删除那条路:并掉的名字不进「不再记录」,连接也不丢。
     *
     * 和删除一样先记下来再存(见 [LearnPurges]),存上了才划掉;没存上、进程又没了的,下次读出来时照记下的先后补做:
     * - 话题那份:不补做的话,文件里两个话题都还在。用户并完接着把并好的那个删了(删除是记着的、会补做),
     *   补做时文件里没有这次合并,并掉的那个就带着它的证据回来了,名字也不在「不再记录」里。
     * - 联系人那份:这会儿读不出来、或改了没存上的,文件里连着的还是并掉的那个 id;等话题那份存上了,
     *   下次整理就把这些连接当成指着空处的去掉了。
     * 这一笔两份一次记上([LearnPurges.addToTopicsAndContacts]):只记上话题那一半的话,补做时联系人上的连接就丢了。
     *
     * 记号里只有两个 id,补做时两个话题都得在文件里。有一个还不在文件里(刚归纳出来、没存上的)时先存一次,还存不上就不并——
     * 并了也记不住:进程一没,目标不在文件里,这次合并补不回来,并掉的那个又回来了。合并分不开,存不下的时候宁可不做。
     *
     * 先存联系人再存话题:中途进程没了,联系人上连着的是还在的那个话题。
     */
    fun mergeTopic(fromId: String, intoId: String) {
        val p = loadedPool() ?: return
        if (p[fromId] == null || p[intoId] == null || fromId == intoId) return
        if ((fromId !in savedTopics || intoId !in savedTopics) && !save(p)) {
            log("learn merge not done: topic not saved")
            return
        }
        if (!p.merge(fromId, intoId)) return
        val mark = MARK_MERGE + fromId + MERGE_INTO + intoId
        purges.addToTopicsAndContacts(mark)
        topicPurgesApplied = topicPurgesApplied + mark
        buffer.remapTopic(fromId, intoId)
        val c = contactPool
        if (c == null) {
            // 联系人还没读出来:读出来的那一刻把记着的(连同这一项)做掉;读不出来就留到读得出来时
            loadedContacts()
        } else {
            c.remapTopic(fromId, intoId)
            contactPurgesApplied = contactPurgesApplied + mark
            saveContacts(c)
        }
        save(p)
    }

    /** 记着的一次合并:并掉的话题 id、并进去的 id;不是合并的记号、写得不对的为 null。 */
    private fun mergeIn(mark: String): Pair<String, String>? {
        if (!mark.startsWith(MARK_MERGE)) return null
        val ids = mark.removePrefix(MARK_MERGE).split(MERGE_INTO)
        return if (ids.size == 2 && ids[0].isNotEmpty() && ids[1].isNotEmpty()) ids[0] to ids[1] else null
    }

    /**
     * 清空全部:话题和联系人(联系人不记进「不再记录」,是重来)。哪一份读不出来,就把它的文件整份删掉:
     * 清空不能因为读不出来就什么都没做(联系人那份这样删时,「不再记录」的名单也没了:读不出来,留不住)。
     * 读得出来的照常清、存,另存的旧文件一并删。
     */
    fun clearAll() {
        // 正在发的那一批取消,缓冲里没发的也清掉:不然回复回来又把刚清空的加回去,剩下的通知文字过后还会发给模型
        stopRequest()
        buffer.clear()
        val p = loadedPool()
        val topicsCleared: Boolean
        if (p != null) {
            topicsGone(p.all().map { it.id }.toSet())
            p.clear()
            // 清空了却没存上:文件里还是旧的,下次启动又读回来了。那就把文件删掉
            topicsCleared = save(p) || wipe("topics") { store.wipe() }
        } else {
            topicsCleared = wipe("topics") { store.wipe() }
            pool = TopicPool()
            topicsFlow.value = emptyList()
            topicsBlockedFlow.value = 0
        }
        val c = loadedContacts()
        val contactsCleared: Boolean
        if (c != null) {
            // 内存里本来就是空的也照存:可能是之前删了却没存上,文件里还是旧的——这时跳过不存,
            // 下面又把记着的待删划掉,旧的联系人下次启动就回来了
            c.clear()
            contactsCleared = saveContacts(c) || wipe("contacts") { contactStore.wipe() }
        } else {
            contactsCleared = wipe("contacts") { contactStore.wipe() }
            contactPool = ContactPool()
            contactsFlow.value = ContactsView()
        }
        dropBroken()
        // 清空了、落了盘的,没有还要补做的删除。既没存上也没删掉的(文件里还是旧的):记一个「全清」,
        // 内存里已经是空的了,下次存上时划掉;在那之前进程没了的,下次读出来时照它清空(见 purgeTopics、purgeContacts)
        purges.topicPurges = if (topicsCleared) emptySet() else setOf(MARK_ALL)
        topicPurgesApplied = purges.topicPurges
        purges.contactPurges = if (contactsCleared) emptySet() else setOf(MARK_ALL)
        contactPurgesApplied = purges.contactPurges
        // 通知原文和当日消息归纳也清掉(删不掉的,它们各自记着,见 dropJournal、dropDigest)
        dropJournal()
        dropDigest()
        if (host != null) evaluate()
    }

    /** 关掉「敏感类推断」并选了删除:删掉标了敏感的话题,去掉联系人上「伴侣」的票。当时读不出来的那一份记下来,读得出来时补做。 */
    fun deleteSensitive() = purge(PURGE_SENSITIVE)

    /** 清掉某个 App 的:话题上它的证据,和它的联系人(同样不记进「不再记录」)。当时读不出来的那一份记下来,读得出来时补做。 */
    fun clearApp(packageName: String) = purge(packageName)

    /** 删掉一个联系人:同一个会话以后也不再记。 */
    fun deleteContact(id: String) {
        loadedContacts()?.let {
            if (it.delete(id)) {
                val mark = MARK_CONTACT + id
                purges.contactPurges = purges.contactPurges + mark
                contactPurgesApplied = contactPurgesApplied + mark
                saveContacts(it)
                dropBroken()
            }
        }
    }

    /** 用户设关系;null 是交回给 Mika 判断。 */
    fun setContactRelation(id: String, relation: ContactRelation?) {
        loadedContacts()?.let { if (it.setRelation(id, relation)) saveContacts(it) }
    }

    /** 重新允许删掉过的话题(一次全部)。 */
    fun allowDeletedTopics() {
        loadedPool()?.let { if (it.allowAgain()) save(it) }
    }

    /** 重新允许删掉过的联系人(一次全部)。 */
    fun allowDeletedContacts() {
        loadedContacts()?.let { if (it.allowAgain()) saveContacts(it) }
    }

    /**
     * [what] 是 [PURGE_SENSITIVE] 或一个包名:两份里各删一遍。先记进 [purges] 再做,删过的那份存上了才划掉:
     * 所以当时读不出来的、删了却没存上就没了进程的,下次读出来时([loadedPool]、[loadedContacts])都会补做。
     * 存上之后就不再记着,不会过后再做一遍、把这之后新攒的也删掉。
     */
    private fun purge(what: String) {
        if (what != PURGE_SENSITIVE) {
            // 这个 App 正在发的那一批(归纳话题的、分段归纳的)取消,缓冲里它还没发的也去掉:
            // 不然回复回来又把刚清掉的话题、联系人加回去,剩下的通知文字过后还会发给模型
            if (notesApp == what || buffer.sending().firstOrNull()?.packageName == what) stopRequest()
            buffer.purge { it != what }
            // 「清掉这个 App 的」也清通知原文里它的那些段;日志这时读不出来就整份删(原文不留着等以后补删)。
            // 开关关着时日志本来就删掉了
            if (on() && digest()) {
                if (loadedJournal() == null) {
                    // 整份删;删不掉的话 dropJournal 记着,读得出来时补做
                    dropJournal()
                } else {
                    journalRemove(setOf(what))
                }
            }
            // 句子和各级归纳:开关关着时也还留着,所以照样清。这时读不出来(密钥库一时不可用)的,不整份删——
            // 里面有留两年的月归纳;记下来,读得出来时补做(见 loadedDigest)
            digestRemove(setOf(what), stopRequests = true)
        }
        purges.addToTopicsAndContacts(what)
        // 这一次才读出来的,读出来的那一刻已经把记着的(连同这一项)做掉了
        val hadTopics = pool != null
        val p = loadedPool()
        if (p != null && hadTopics) purgeTopics(p, setOf(what))
        val hadContacts = contactPool != null
        val c = loadedContacts()
        if (c != null && hadContacts) purgeContacts(c, setOf(what))
        dropBroken()
    }

    private fun purgeTopics(p: TopicPool, what: Set<String>) {
        if (what.isEmpty()) return
        val gone = HashSet<String>()
        // 照记下的先后做(见 [LearnPurges]):并完才删的,删的是并好的那个,并进过它的名字要跟着进「不再记录」;
        // 先删了一个名字、后来又把同名的另一个并进别的,这个名字算并掉的。已经存上了的再做一遍什么都不会变:
        // 并掉、删掉的那个 id 不在了,id 也不会再发给别的话题
        for (w in what) when {
            w == MARK_ALL -> { gone += p.all().map { it.id }; p.clear() }
            w == PURGE_SENSITIVE -> gone += p.deleteSensitive()
            w.startsWith(MARK_MERGE) -> mergeIn(w)?.let { (from, into) -> p.merge(from, into) }
            w.startsWith(MARK_TOPIC) -> w.removePrefix(MARK_TOPIC).let { if (p.delete(it)) gone += it }
            // 别的记号不是话题池的事(也不能当包名去清)
            w.startsWith(":") -> {}
            else -> gone += p.clearApp(w)
        }
        topicPurgesApplied = topicPurgesApplied + what
        topicsGone(gone)
        save(p)
    }

    private fun purgeContacts(c: ContactPool, what: Set<String>) {
        if (what.isEmpty()) return
        // 照记下的先后做。记着的合并:连着并掉的那个话题的,改连并进去的那个
        for (w in what) when {
            w == MARK_ALL -> c.clear()
            w == PURGE_SENSITIVE -> c.deleteSensitive()
            w.startsWith(MARK_MERGE) -> mergeIn(w)?.let { (from, into) -> c.remapTopic(from, into) }
            // 不在了也要记进「不再记录」(见 [ContactPool.forget])
            w.startsWith(MARK_CONTACT) -> c.forget(w.removePrefix(MARK_CONTACT))
            w.startsWith(":") -> {}
            else -> c.clearApp(w)
        }
        contactPurgesApplied = contactPurgesApplied + what
        saveContacts(c)
    }

    /**
     * 在归纳那份上照 [marks] 删一遍(包名:去掉这个 App 的;记号:单删的那一句、那一天、那一周、那个月)。
     * 返回其中真删掉了东西的几项。
     */
    private fun applyDigestPurges(d: DigestPool, marks: Set<String>): Set<String> = marks.filterTo(HashSet()) { w ->
        when {
            w == MARK_ALL -> d.clear()
            w.startsWith(MARK_NOTE) -> d.delete(w.removePrefix(MARK_NOTE))
            w.startsWith(MARK_DAY) -> w.removePrefix(MARK_DAY).toLongOrNull()?.let(d::deleteDay) ?: false
            w.startsWith(MARK_WEEK) -> w.removePrefix(MARK_WEEK).toLongOrNull()?.let { d.deleteSpan(SpanKind.Week, it) } ?: false
            w.startsWith(MARK_MONTH) -> w.removePrefix(MARK_MONTH).toLongOrNull()?.let { d.deleteSpan(SpanKind.Month, it) } ?: false
            w.startsWith(":") -> false
            else -> d.removePackages { it != w }
        }
    }

    /**
     * 从归纳那份里删东西([marks] 的写法见 [applyDigestPurges])。删掉了什么就先记进 [purges] 再存,存上了才划掉(见 [saveDigest]):
     * 没存上、进程又没了的,下次读出来时补做([loadedDigest])。这时读不出来的整个记下来,读得出来时补做。
     * 什么都没删掉、但其中有上回没存上的:再存一次。[stopRequests]:删掉了东西就取消在做的当日(周、月)归纳。
     */
    private fun digestRemove(marks: Set<String>, stopRequests: Boolean = false) {
        if (marks.isEmpty()) return
        val pending = purges.digestPurges
        val d = loadedDigest()
        if (d == null) {
            if (!pending.containsAll(marks)) purges.digestPurges = pending + marks
            return
        }
        val done = applyDigestPurges(d, marks)
        if (done.isNotEmpty()) {
            if (stopRequests && (dayInFlight != null || spanInFlight != null)) stopRequest()
            if (!purges.digestPurges.containsAll(done)) purges.digestPurges = purges.digestPurges + done
            digestPurgesApplied = digestPurgesApplied + done
            saveDigest(d)
        } else if (marks.any { it in purges.digestPurges }) {
            saveDigest(d)
        }
    }

    /** 用户删了东西:两份另存的旧文件都删掉(读不出来的副本里可能还有刚删的)。删不掉只记一笔。 */
    private fun dropBroken() {
        wipe("topics copies") { store.dropBroken() }
        wipe("contacts copies") { contactStore.dropBroken() }
    }

    /** 删掉了返回 true;删不掉只记一笔,返回 false。 */
    private inline fun wipe(what: String, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn could not delete $what: ${e.javaClass.simpleName}")
            false
        }

    // ---- 通知日志(通知原文) ----

    /** 没读出来时读一次(没有文件就从现在开始记);读不出来(密钥库一时不可用、IO)为 null,下次再试,这期间不记也不存。 */
    private fun loadedJournal(): NoticeJournal? {
        journal?.let { return it }
        return try {
            NoticeJournal(journalStore.load(), clock = clock).also {
                journal = it
                // 记着的还没落盘的按 App 删除(当时读不出来的、删了没存上的):现在补做,存上了才划掉(见 saveJournal)
                val pending = purges.journalPurges
                if (pending.isNotEmpty()) {
                    it.removePackages { p -> MARK_ALL !in pending && p !in pending }
                    // 记着的状态显示(见 dropLive):只去掉那一阵的——同一个键上之后的正常通知不动
                    for (w in pending) keyIn(w)?.let { (keyId, since) -> it.removeKey(keyId, since, since + LIVE_WINDOW_MS) }
                    journalPurgesApplied = journalPurgesApplied + pending
                }
                // 读进来时就有过期的:内存里已经去掉了,文件里还在——马上存一次(存里面会刷新页面上的数)
                if (it.expiredOnLoad || pending.isNotEmpty()) saveJournal() else publish(status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn journal unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 到期的删掉,有改动或有没存的就存上。「当日消息归纳」关着、或日志还没用过时什么都不做。 */
    private fun tendJournal() {
        if (!on() || !digest()) return
        val j = loadedJournal() ?: return
        if (j.expire() || journalUnsaved > 0) saveJournal()
    }

    private fun saveJournal() {
        val j = journal ?: return
        j.expire()
        try {
            journalStore.save(j.data())
            journalUnsaved = 0
            // 做过的按 App 删除存上了:不再记着,免得这个 App 以后又被允许时,把那之后新记的也删掉
            if (journalPurgesApplied.isNotEmpty()) {
                purges.journalPurges = purges.journalPurges - journalPurgesApplied
                journalPurgesApplied = emptySet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn journal save failed: ${e.javaClass.simpleName}")
            // 记着还有没存的:下次整理时再存(不然到期删掉的那些,之后再没有别的改动来带它们落盘)
            if (journalUnsaved == 0) journalUnsaved = 1
        }
        publish(status)
    }

    /** 整份删掉:内存里的和文件(读不出来时也删)。正在发的那一批分段归纳取消。 */
    private fun dropJournal() {
        if (notesApp != null) stopRequest()
        val had = journal?.size ?: 0
        journal = null
        journalUnsaved = 0
        // 整份删掉了,没有还要补做的。删不掉的(文件里还是旧的,内存里那份已经丢了,下次一读又回来了):
        // 记一个「全清」,读得出来时照它清空
        journalPurgesApplied = emptySet()
        if (wipe("journal") { journalStore.wipe() }) {
            if (purges.journalPurges.isNotEmpty()) purges.journalPurges = emptySet()
        } else if (purges.journalPurges != setOf(MARK_ALL)) {
            purges.journalPurges = setOf(MARK_ALL)
        }
        if (had > 0) publish(status)
    }

    /**
     * 从通知原文里去掉这几个 App 的。和 [digestRemove] 一个做法:删掉了就先记进 [purges] 再存,存上了才划掉([saveJournal]);
     * 这时读不出来的记下来,读得出来时补做([loadedJournal])。不记的话,读不出来那次等于没删,删了没存上、进程又没了的
     * 文件里还是旧的——之后这个 App 又被允许,两天内的旧原文就又能被读到、被拿去归纳。
     * 不因为读不出来就整份删:[forgetApps] 每次名单变动都带着整个不够格的名单来,密钥库一时不可用就会把攒下的原文全删光。
     */
    private fun journalRemove(packages: Set<String>) {
        if (packages.isEmpty()) return
        val j = loadedJournal()
        if (j == null) {
            if (!purges.journalPurges.containsAll(packages)) purges.journalPurges = purges.journalPurges + packages
            return
        }
        if (j.removePackages { it !in packages }) {
            if (!purges.journalPurges.containsAll(packages)) purges.journalPurges = purges.journalPurges + packages
            journalPurgesApplied = journalPurgesApplied + packages
            saveJournal()
        } else if (packages.any { it in purges.journalPurges }) {
            saveJournal()
        }
    }

    // ---- 带进任务的那一段 ----

    /**
     * 这次任务要带的「从通知里得来的」那一段(见 [ObservedContext]):按激进程度 [level] 和各 App 的来源档位 [tier] 挑出够格的,
     * 排成一段、限好长。归纳关着、最保守的一档、什么都不够格时为 null。不调模型;在串行协程上调。
     *
     * 够格的:来源档位在 [LearnLevel.intoTasks] 里、现在还在归纳的 App 的——
     * - 当日归纳里今天(到现在的)和昨天的句子:一句依据的几个 App 都够格才带;
     * - 话题:只数够格的 App 印证过的日子,满 [LearnLevel.topicDays] 天;
     * - 联系人:已确立的。
     * 敏感的话题和联系人只有「敏感类推断」开着时才带。
     */
    fun observed(level: LearnLevel, tier: (String) -> SourceTier): String? {
        if (!on() || suspended() || level.intoTasks.isEmpty()) return null
        val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
        val now = clock()
        val today = dayOf(now)
        val digests = if (digest()) freshDigest() else null
        fun linesOf(day: Long): List<String> =
            digests?.day(day)?.lines.orEmpty().filter { it.apps.isNotEmpty() && it.apps.all(ok) }.map { it.text }
        val allowSensitive = sensitive()
        // 该淡忘的(留存低于 FORGET_BELOW)不带:它们要等下一次整理才真的从池里去掉,而整理只在收下新归纳、
        // 打开内容页时做——很久没有新归纳、也没开过页面的话,早该忘掉的话题和联系人还在池里。
        // 这里只是不带,不在任务这条路上改池、存盘。用户自己定过关系的联系人不淡忘(同 ContactPool.maintain)
        val topics = loadedPool()?.all().orEmpty()
            .filter { (allowSensitive || !it.sensitive) && it.retention(now) >= LearnTuning.FORGET_BELOW && ObservedContext.qualifiedTopic(it, level, ok) }
            .sortedByDescending { it.score(now) }.take(OBSERVED_TOPICS)
        val people = loadedContacts()?.all().orEmpty()
            .filter { ok(it.packageName) && it.established && (allowSensitive || !it.sensitive) }
            .filter { it.userSet || it.retention(now) >= LearnTuning.FORGET_BELOW }
            .sortedByDescending { it.score(now) }.take(OBSERVED_PEOPLE)
            .map { ObservedContext.Person(it.name, it.type == ContactType.Group, it.relation, it.userSet) }
        val asOf = digests?.day(today)?.let { java.time.Instant.ofEpochMilli(it.madeAtMs).atZone(zone()).toLocalTime() }
            ?.let { "%02d:%02d".format(java.util.Locale.ENGLISH, it.hour, it.minute) }
        // 刚过完的那一周、那个月:只带完整的那份(「到现在为止」的不当成上周、上月)
        fun earlier(kind: SpanKind, key: Long, days: LongRange): ObservedContext.Earlier? =
            digests?.span(kind, key)?.takeIf { !it.partial }?.lines.orEmpty()
                .filter { it.apps.isNotEmpty() && it.apps.all(ok) }.map { it.text }
                .takeIf { it.isNotEmpty() }?.let { ObservedContext.Earlier(days.first, days.last, it, digests?.span(kind, key)?.leftOut ?: 0) }
        val lastWeek = DigestPool.weekOf(today) - 7
        val lastMonth = DigestPool.monthOf(DigestPool.monthOf(today) - 1)
        return ObservedContext.render(
            linesOf(today), linesOf(today - 1), topics, people, asOf,
            lastWeek = earlier(SpanKind.Week, lastWeek, lastWeek..lastWeek + 6),
            // 月归纳管的是结束在那个月的几周:标题写它实际管到的日子
            lastMonth = earlier(SpanKind.Month, lastMonth, DigestPool.monthCover(lastMonth)),
            // 那两天的当日归纳做的时候有没有放不下的句子:有就在标题里说
            todayLeftOut = digests?.day(today)?.leftOut ?: 0,
            yesterdayLeftOut = digests?.day(today - 1)?.leftOut ?: 0,
        )
    }

    /**
     * 任务读存着的归纳用([NoticeSource.digests]):各天(各周、各月)的归纳,新的在前;每份只给依据的 App 档位都够格的句子,
     * 一句都给不出来的那份不列。条件和 [notices] 一样;存着的那份读不出来时为 null。在串行协程上调。
     */
    fun digests(which: DigestLevel, level: LearnLevel, tier: (String) -> SourceTier): DigestsView? {
        if (!on() || suspended() || !digest() || level.intoTasks.isEmpty()) return null
        val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
        val pool = freshDigest() ?: return null
        var withheld = 0
        fun view(start: Long, end: Long, madeAtMs: Long, partial: Boolean, lines: List<DigestLine>, leftOut: Int): DigestView? {
            val (given, rest) = lines.filter { it.apps.isNotEmpty() }.partition { it.apps.all(ok) }
            withheld += rest.size
            return if (given.isEmpty()) null else DigestView(start, end, madeAtMs, partial, given.map { DigestViewLine(it.text, it.apps) }, leftOut)
        }
        val out = when (which) {
            DigestLevel.Day -> pool.days().mapNotNull { view(it.day, it.day, it.madeAtMs, it.partial, it.lines, it.leftOut) }
            DigestLevel.Week -> pool.spans(SpanKind.Week).mapNotNull { view(it.start, it.start + 6, it.madeAtMs, it.partial, it.lines, it.leftOut) }
            DigestLevel.Month -> pool.spans(SpanKind.Month).mapNotNull {
                val cover = DigestPool.monthCover(it.start)
                view(cover.first, cover.last, it.madeAtMs, it.partial, it.lines, it.leftOut)
            }
        }
        return DigestsView(out, withheld)
    }

    /**
     * 任务读通知用([NoticeSource]):[fromMs] 到 [toMs] 之间、来源档位够格的 App 的通知原文和分段归纳。
     * 归纳或「当日消息归纳」关着、最保守的一档、日志读不出来时为 null。在串行协程上调。
     */
    fun notices(fromMs: Long, toMs: Long, level: LearnLevel, tier: (String) -> SourceTier): NoticeView? {
        if (!on() || suspended() || !digest() || level.intoTasks.isEmpty()) return null
        val ok: (String) -> Boolean = { eligible(it) && tier(it) in level.intoTasks }
        val j = loadedJournal() ?: return null
        // 到期的先删:读的人不该看到过了保留期的
        if (j.expire()) saveJournal()
        // 归纳那份读不出来时不能当成「没有句子」给出去:原文照给,另说明句子这会儿读不出来
        val pool = freshDigest()
        val notes = pool?.all().orEmpty()
            .filter { ok(it.packageName) && it.toMs in fromMs until toMs }.sortedBy { it.fromMs }
        val (given, rest) = j.between(fromMs, toMs).partition { ok(it.packageName) }
        // 档位不够格所以没给的 App 有几个(只给个数):读的人据此说「还有几个 App 的没看」,而不是「今天没别的了」
        val withheld = rest.filter { eligible(it.packageName) }.mapTo(HashSet()) { it.packageName }.size
        return NoticeView(j.completeSinceMs, given, notes, withheld, notesUnavailable = pool == null)
    }

    /**
     * 页面用:[note] 这一句分段归纳是照着哪些通知写的(见 [NoticeJournal.around];给用户自己看,不看来源档位)。
     * 到期的先删再给。在串行协程上调。
     */
    fun rawFor(note: DigestNote): RawNotices {
        if (!on() || !digest()) return RawNotices.Off
        if (suspended()) return RawNotices.Unavailable
        val j = loadedJournal() ?: return RawNotices.Unavailable
        if (j.expire()) saveJournal()
        return RawNotices.Found(j.around(note.packageName, note.title, note.fromMs, note.toMs))
    }

    // ---- 当日消息归纳攒下的句子 ----

    /**
     * 要拿归纳去用(给任务、挑该做哪一份)之前:过了留存期的先去掉,有去掉的就存。
     * 到期不靠定时器(息屏不干活),谁来读谁先清——不然不开「Mika 知道了什么」那一页,过期的归纳照样会被带进任务。
     */
    private fun freshDigest(): DigestPool? = loadedDigest()?.also { if (it.expire(dayOf(clock()))) saveDigest(it) }

    /** 没读出来时读一次;读不出来(密钥库一时不可用、IO)为 null,下次再试,绝不拿空的去盖文件。 */
    private fun loadedDigest(): DigestPool? {
        digestPool?.let { return it }
        return try {
            DigestPool(digestStore.load()).also {
                digestPool = it
                notesFlow.value = it.all()
                daysFlow.value = it.days()
                weeksFlow.value = it.spans(SpanKind.Week)
                monthsFlow.value = it.spans(SpanKind.Month)
                removedFlow.value = removedOf(it)
                removedDaysFlow.value = it.removedDays()
                // 记着的还没落盘的删除(当时读不出来的、删了没存上的):现在补做。存上了才从记着的里面划掉(见 saveDigest)
                val pending = purges.digestPurges
                if (pending.isNotEmpty()) {
                    applyDigestPurges(it, pending)
                    digestPurgesApplied = digestPurgesApplied + pending
                    saveDigest(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn digest unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 存上了返回 true。 */
    private fun saveDigest(pool: DigestPool): Boolean {
        notesFlow.value = pool.all()
        daysFlow.value = pool.days()
        weeksFlow.value = pool.spans(SpanKind.Week)
        monthsFlow.value = pool.spans(SpanKind.Month)
        removedFlow.value = removedOf(pool)
        removedDaysFlow.value = pool.removedDays()
        return try {
            digestStore.save(pool.data())
            digestUnsaved = false
            // 做过的删除存上了:不再记着,免得以后再做一遍、把这之后新攒的也删掉
            if (digestPurgesApplied.isNotEmpty()) {
                purges.digestPurges = purges.digestPurges - digestPurgesApplied
                digestPurgesApplied = emptySet()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn digest save failed: ${e.javaClass.simpleName}")
            digestUnsaved = true
            false
        }
    }

    /** 内存里的归纳退回到 [data] 那时的样子(页面跟着变)。记着的删除不受影响:它们已经做在 [data] 里了,存上之前一直记着。 */
    private fun restoreDigest(data: DigestData) {
        val pool = DigestPool(data)
        digestPool = pool
        notesFlow.value = pool.all()
        daysFlow.value = pool.days()
        weeksFlow.value = pool.spans(SpanKind.Week)
        monthsFlow.value = pool.spans(SpanKind.Month)
        removedFlow.value = removedOf(pool)
        removedDaysFlow.value = pool.removedDays()
    }

    private fun removedOf(pool: DigestPool): Set<Pair<SpanKind, Long>> =
        SpanKind.entries.flatMapTo(HashSet()) { kind -> pool.removedSpans(kind).map { kind to it } }

    /** 用户删掉一句。 */
    fun deleteNote(id: String) = digestRemove(setOf(MARK_NOTE + id))

    /** 整份删掉:内存里的和文件(读不出来时也删)。 */
    private fun dropDigest() {
        if (notesApp != null || dayInFlight != null || spanInFlight != null) stopRequest()
        digestPool = null
        digestUnsaved = false
        todayWanted = false
        weekWanted = false
        monthWanted = false
        dayTries.clear()
        partialTries.clear()
        spanTries.clear()
        if (notesFlow.value.isNotEmpty()) notesFlow.value = emptyList()
        if (daysFlow.value.isNotEmpty()) daysFlow.value = emptyList()
        if (weeksFlow.value.isNotEmpty()) weeksFlow.value = emptyList()
        if (monthsFlow.value.isNotEmpty()) monthsFlow.value = emptyList()
        if (removedFlow.value.isNotEmpty()) removedFlow.value = emptySet()
        if (removedDaysFlow.value.isNotEmpty()) removedDaysFlow.value = emptySet()
        // 同 dropJournal:删掉了就没有还要补做的;删不掉的记一个「全清」,读得出来时照它清空
        digestPurgesApplied = emptySet()
        purges.digestPurges = if (wipe("digest") { digestStore.wipe() }) emptySet() else setOf(MARK_ALL)
    }

    /** 话题没了:事件上、联系人上都不再指着它。 */
    private fun topicsGone(ids: Set<String>) {
        if (ids.isEmpty()) return
        buffer.forgetTopics(ids)
        val c = contactPool ?: loadedContacts() ?: return
        if (c.forgetTopics(ids)) saveContacts(c)
    }

    // ---- 内部 ----

    private fun evaluate() {
        try {
            evaluateOrThrow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 外面的东西(模型配置、用量账本)出错不能带垮看守(和锁屏、判定在同一个作业里)
            log("learn evaluate failed: ${e.javaClass.simpleName}")
            // 已开了一批、请求却没发出去:出错的是外面的东西,不是这批,留着,停一段再发
            if (inFlight == null && buffer.sending().isNotEmpty()) keepBatch(clock())
            // 已经在发的请求不动:读一下配置出错不说明它该停(停了它已经算过钱,回头还得再发一遍)。只是这一回不发新的。
            // 但只在确实没锁屏时才留着:锁屏状态读不出来、或锁着,照旧停——锁屏不发是硬规矩
            if (inFlight != null && unlockedForSure()) publish(LearnStatus.Sending) else stopAndPublish(LearnStatus.Idle)
        }
    }

    /** 锁屏状态读得出来、而且没锁。读不出来的不算。 */
    private fun unlockedForSure(): Boolean =
        try { !readLocked() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }

    private fun evaluateOrThrow() {
        val scope = host ?: return
        if (!on()) return stopAndPublish(LearnStatus.Off)
        if (suspended()) return stopAndPublish(LearnStatus.Suspended)
        if (readLocked()) return stopAndPublish(LearnStatus.Locked)
        when (TriageGate.of(true, consent(), hooks.target())) {
            TriageGate.NoModel -> return stopAndPublish(LearnStatus.NoModel)
            TriageGate.NeedsConsent -> return stopAndPublish(LearnStatus.NeedsConsent)
            else -> {}
        }
        if (inFlight != null) return publish(LearnStatus.Sending)
        // 归纳那份上回没存上的:每次有事先补存一次(不等到又有归纳要做)
        if (digestUnsaved) digestPool?.let(::saveDigest)
        // 「当日消息归纳」开着时,通知日志里还没做过分段归纳的、该做当日归纳的那一天也是待办
        val notesPending = digest() && (loadedJournal()?.pendingCount ?: 0) > 0
        val now = clock()
        val dayDue = dayToDigest(now)
        // 周、月归纳排在当日归纳后面:有哪一天该做,先做那一天
        val spanDue = if (dayDue == null) spanToDigest(now) else null
        if (buffer.pendingCount == 0 && !notesPending && dayDue == null && spanDue == null) return publish(LearnStatus.Idle)
        if (hooks.busy.value) return publish(LearnStatus.Busy)
        if (!budgetLeft(now)) return publish(LearnStatus.BudgetUsedUp)
        if (held(now)) return publish(LearnStatus.Waiting)
        // 判定那边正在发:等它发完(它发完会叫这边再看一遍)。主动能力的后台请求一次只发一个
        if (slot.heldByOther(this)) return publish(LearnStatus.Waiting)
        // 先发归纳话题的那一批;没有到期的,再看分段归纳有没有到期的。一次只发一个请求
        buffer.due(now, eligible)?.let { return send(scope, it) }
        // 归纳那份还是没存上(上面刚补存过一次):不发分段、当日、周、月的请求(见 digestUnsaved)
        if (digestUnsaved && (notesPending || dayDue != null || spanDue != null)) return publish(LearnStatus.StoreUnavailable)
        if (notesPending) {
            journal?.due(now, eligible, LearnTuning.NOTES_DUE_COUNT, LearnTuning.NOTES_DUE_AGE_MS)?.let { return sendNotes(scope, it) }
        }
        dayDue?.let { (day, partial) -> return sendDay(scope, day, partial) }
        spanDue?.let { (kind, start, partial) -> return sendSpan(scope, kind, start, partial) }
        publish(LearnStatus.Waiting)
    }

    /**
     * 现在该做哪一天的当日归纳(第 2 级):用户刚要了「今天到现在的」(今天有句子才做);否则是今天之前、有句子、还没有完整归纳、
     * 那天的通知也都做过分段归纳的最早的一天;都没有,再看今天「到现在为止」的要不要自动做一份([DigestPool.todayAutoDue])。
     * 在这个进程里试过 [LearnTuning.DAY_TRIES_MAX] 次还不成的不再试。没有为 null。
     */
    private fun dayToDigest(now: Long): Pair<Long, Boolean>? {
        if (!digest()) {
            // 「当日消息归纳」关着:东西还留着,期限照算。已经读进来的那份照样清;不为清它专门去读文件
            // (这时没人拿得到这些归纳——任务不给,页面一打开就清)
            digestPool?.let { if (it.expire(dayOf(now))) saveDigest(it) }
            return null
        }
        // 每次看有没有归纳要做,都先把到期的去掉:存着的归纳按期删,不靠有人打开页面或任务来读
        val pool = freshDigest() ?: return null
        val today = dayOf(now)
        if (todayWanted) {
            if (pool.dayInput(today).isNotEmpty()) return today to true
            todayWanted = false
        }
        val j = journal
        pool.dueDay(today) { d ->
            (dayTries[d] ?: 0) < LearnTuning.DAY_TRIES_MAX && (j == null || !j.hasPending(startOf(d), startOf(d + 1), eligible))
        }?.let { return it to false }
        // 今天的不用等用户去点:句子攒够了、离上一份够久了就自动做一份「到现在为止」的
        if ((partialTries[today] ?: 0) < LearnTuning.DAY_TRIES_MAX && pool.todayAutoDue(today, now)) return today to true
        return null
    }

    private fun startOf(day: Long): Long = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone()).toInstant().toEpochMilli()

    /** 用户点了「归纳今天到现在的」:今天有分段归纳就做一份(标着是到现在为止的),过完这一天会重做完整的。 */
    fun digestToday() {
        if (!on() || suspended() || !digest()) return
        todayWanted = true
        // 用户在看这一页:失败后停着的也不再停
        holdUntilMs = 0L
        if (host != null) evaluate()
    }

    /** 用户删掉某一天的当日归纳。 */
    fun deleteDay(day: Long) {
        loadedDigest() ?: return
        if (dayInFlight == day) stopRequest()
        if (day == dayOf(clock())) todayWanted = false
        digestRemove(setOf(MARK_DAY + day))
    }

    /**
     * 当日消息归纳的第 2 级:把 [day] 这一天各个 App 的分段归纳合成一份。放进请求的只有第 1 级的句子,没有通知原文。
     * 同意、建模型、失败后停一段都和别的批一样。
     */
    private fun sendDay(scope: CoroutineScope, day: Long, partial: Boolean) {
        val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
        if (consent() == null) return publish(LearnStatus.NeedsConsent)
        val input = pool.dayInput(day)
        if (input.isEmpty()) return publish(LearnStatus.Idle)
        sendRollup(
            scope, "day digest", ProactiveCall.Day, pool,
            begin = {
                dayInFlight = day
                if (partial) todayWanted = false
            },
            request = { model, lang, z -> NoticeDay(model, lang).digest(input, z, appLabel) },
            apply = {
                // 途中被清掉的 App 的句子:依据过它的那几句由 setDay 整句丢掉
                pool.setDay(day, input, it.lines, clock(), partial, it.brief)
                "${input.size} notes, ${pool.day(day)?.lines?.size ?: 0} lines, partial=$partial, brief=${pool.day(day)?.brief?.isNotEmpty() == true}"
            },
            tried = { (if (partial) partialTries else dayTries).let { it[day] = (it[day] ?: 0) + 1 } },
        )
    }

    /**
     * 现在该做哪一周、哪个月的归纳(第 3、4 级):用户刚要了「本周(本月)到现在的」(有东西可归纳才做);否则是已经过完、
     * 下一级都做完了、还没有完整归纳的最早的一周,没有该做的周再看月。下一级一句话都没有的,不问模型,直接记成做过了。
     * 在这个进程里试过 [LearnTuning.SPAN_TRIES_MAX] 次还不成的不再试。没有为 null。
     */
    private fun spanToDigest(now: Long): Triple<SpanKind, Long, Boolean>? {
        if (!digest()) return null
        val pool = loadedDigest() ?: return null
        val today = dayOf(now)
        val j = journal
        fun given(kind: SpanKind, start: Long) = (spanTries[kind to start] ?: 0) >= LearnTuning.SPAN_TRIES_MAX
        // 自动做的:只拿完整的下一级当输入;那段时间一句都没有的直接记下(上面一级不必等它)
        fun auto(kind: SpanKind, due: () -> Long?): Long? {
            repeat(AUTO_SPANS_MAX) {
                val start = due() ?: return null
                if (pool.spanInput(kind, start, withPartial = false).isNotEmpty()) return start
                pool.setSpan(kind, start, emptyList(), emptyList(), now, partial = false)
                saveDigest(pool)
                log("${kind.name.lowercase()} digest: nothing to merge, recorded empty")
            }
            return null
        }
        if (weekWanted) {
            val week = DigestPool.weekOf(today)
            if (pool.spanInput(SpanKind.Week, week, withPartial = true).isNotEmpty()) return Triple(SpanKind.Week, week, true)
            weekWanted = false
        }
        auto(SpanKind.Week) {
            pool.dueWeek(
                today,
                // 还欠着当日归纳的日子先等它(试了几次都不成的不等);还有通知没做过分段归纳的也等
                waitForDay = { d, owed ->
                    (owed && (dayTries[d] ?: 0) < LearnTuning.DAY_TRIES_MAX) || (j != null && j.hasPending(startOf(d), startOf(d + 1), eligible))
                },
                skip = { given(SpanKind.Week, it) },
            )
        }?.let { return Triple(SpanKind.Week, it, false) }
        if (monthWanted) {
            val month = DigestPool.monthOf(today)
            if (pool.spanInput(SpanKind.Month, month, withPartial = true).isNotEmpty()) return Triple(SpanKind.Month, month, true)
            monthWanted = false
        }
        auto(SpanKind.Month) {
            pool.dueMonth(today, waitForWeek = { w, owed -> owed && !given(SpanKind.Week, w) }, skip = { given(SpanKind.Month, it) })
        }?.let { return Triple(SpanKind.Month, it, false) }
        return null
    }

    /**
     * 用户点了「归纳本周到现在的」:本周有当日归纳就做一份(标着是到现在为止的),过完这一周会重做完整的。
     * 今天又来了新句子的话,先把今天到现在的当日归纳重做一遍,再做这一周的。
     */
    fun digestWeek() {
        if (!on() || suspended() || !digest()) return
        if (loadedDigest()?.todayStale(dayOf(clock())) == true) todayWanted = true
        weekWanted = true
        holdUntilMs = 0L
        if (host != null) evaluate()
    }

    /**
     * 用户点了「归纳本月到现在的」:先把今天、本周(它算在这个月里的话)到现在的做了,再做这个月的。
     * 本周的归纳被用户删掉了的话不顺带做回来(要的是这个月的,不是它);今天的同理(见 [DigestPool.todayStale])。
     */
    fun digestMonth() {
        if (!on() || suspended() || !digest()) return
        val today = dayOf(clock())
        val pool = loadedDigest()
        if (pool?.todayStale(today) == true) todayWanted = true
        val week = DigestPool.weekOf(today)
        if (DigestPool.monthOfWeek(week) == DigestPool.monthOf(today) && pool?.removedSpans(SpanKind.Week)?.contains(week) != true) weekWanted = true
        monthWanted = true
        holdUntilMs = 0L
        if (host != null) evaluate()
    }

    /** 用户删掉某一周(某个月)的归纳。 */
    fun deleteSpan(kind: SpanKind, start: Long) {
        loadedDigest() ?: return
        if (spanInFlight == kind to start) stopRequest()
        // 刚点过「归纳到现在」还没做的:删了就不做了
        val today = dayOf(clock())
        if (kind == SpanKind.Week && start == DigestPool.weekOf(today)) weekWanted = false
        if (kind == SpanKind.Month && start == DigestPool.monthOf(today)) monthWanted = false
        digestRemove(setOf((if (kind == SpanKind.Week) MARK_WEEK else MARK_MONTH) + start))
    }

    /**
     * 当日消息归纳的第 3、4 级:把这一周各天的当日归纳(这个月各周的周归纳)合成一份。放进请求的只有下一级的句子。
     * [partial] 为 true 是用户要的「到现在为止」:下一级里还没过完的那份也放进去。
     */
    private fun sendSpan(scope: CoroutineScope, kind: SpanKind, start: Long, partial: Boolean) {
        val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
        if (consent() == null) return publish(LearnStatus.NeedsConsent)
        val input = pool.spanInput(kind, start, withPartial = partial)
        if (input.isEmpty()) return publish(LearnStatus.Idle)
        val name = "${kind.name.lowercase()} digest"
        sendRollup(
            scope, name, ProactiveCall.Span, pool,
            begin = {
                spanInFlight = kind to start
                if (partial) { if (kind == SpanKind.Week) weekWanted = false else monthWanted = false }
            },
            request = { model, lang, _ -> NoticeSpan(model, lang).digest(kind, start, input, appLabel) },
            apply = {
                // 途中被删掉、被清掉 App 的句子:依据过它的那几句由 setSpan 整句丢掉
                pool.setSpan(kind, start, input, it.lines, clock(), partial, it.brief)
                "${input.size} lines in, ${pool.span(kind, start)?.lines?.size ?: 0} lines, partial=$partial, brief=${pool.span(kind, start)?.brief?.isNotEmpty() == true}"
            },
            tried = { spanTries[kind to start] = (spanTries[kind to start] ?: 0) + 1 },
        )
    }

    /**
     * 当日、周、月归纳共用的发请求:同意、建模型、失败后停一段都和别的批一样。[begin] 在请求发出前记下在做哪一份;
     * [request] 调模型;[apply] 把读懂了的结果记进 [pool],返回写进日志的一句;[tried] 给这一份的失败次数加一
     * (模型回了错、回复读不懂才算;根本没连上的不算)。
     */
    private fun sendRollup(
        scope: CoroutineScope, what: String, call: ProactiveCall, pool: DigestPool, begin: () -> Unit,
        request: suspend (ChatModel, String, java.time.ZoneId) -> DayResult, apply: (DayResult.Done) -> String, tried: () -> Unit,
    ) {
        val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
        val built = try {
            hooks.model(call)
        } catch (e: Exception) {
            log("learn model unavailable: ${e.javaClass.simpleName}")
            failed(clock())
            return publish(LearnStatus.Waiting)
        }
        if (built == null) return publish(LearnStatus.NoModel)
        if (!consented.covers(built.target)) {
            log("$what not sent: target is not the consented one")
            return publish(LearnStatus.NeedsConsent)
        }
        val model = built.model
        val lang = language()
        val z = zone()
        begin()
        publish(LearnStatus.Sending)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                request(model, lang, z)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$what request failed: ${e.javaClass.simpleName}")
                DayResult.Failed()
            }
            if (inFlight !== coroutineContext.job) return@launch
            inFlight = null
            slot.release(this@LearnRunner)
            dayInFlight = null
            spanInFlight = null
            try {
                // 途中被清空过的(句子换成了新的一份),这次的结果不写回去
                val stillOk = digestPool === pool &&
                    runCatching { on() && digest() && consent() == consented && consented.covers(hooks.target()) }.getOrDefault(false)
                if (!stillOk) {
                    log("$what dropped: switch or consent changed")
                } else when (result) {
                    is DayResult.Done -> {
                        val summary = apply(result)
                        saveDigest(pool)
                        failures = 0
                        holdUntilMs = 0L
                        val drops = result.dropped.entries.joinToString(",") { "${it.key}=${it.value}" }
                        log("$what: $summary" + if (drops.isEmpty()) "" else ", dropped $drops")
                    }
                    is DayResult.Failed -> {
                        failed(clock())
                        if (!result.unreachable) tried()
                        log("$what failed: unreachable=${result.unreachable}")
                    }
                    DayResult.BudgetExhausted -> budgetOutDay = dayOf(clock())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$what apply failed: ${e.javaClass.simpleName}")
                failed(clock())
                tried()
            }
            // 先叫等着的判定(判定先),再看自己还有没有要发的
            slot.wakeOthers(this@LearnRunner)
            evaluate()
        }
        slot.take(this)
        inFlight = job
        job.start()
    }

    /**
     * 当日消息归纳的第 1 级:这个 App 还没归纳过的通知原文(最旧的先发,一批最多 [LearnTuning.NOTES_BATCH_MAX] 段)交给模型出几句话。
     * 同意、建模型、失败后停一段都和归纳话题的那一批一样。这一批回来之后:读懂了的记下句子;模型回了错、回复读不懂的也算做过
     * (不再发同一批);根本没连上的、被取消的(锁屏、开关变了)不算,留着下次。
     */
    private fun sendNotes(scope: CoroutineScope, packageName: String) {
        val j = journal ?: return publish(LearnStatus.Waiting)
        val pool = loadedDigest() ?: return publish(LearnStatus.StoreUnavailable)
        val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
        val batch = j.pending(packageName, LearnTuning.NOTES_BATCH_MAX)
        if (batch.isEmpty()) return publish(LearnStatus.Idle)
        val built = try {
            hooks.model(ProactiveCall.Notes)
        } catch (e: Exception) {
            log("learn model unavailable: ${e.javaClass.simpleName}")
            failed(clock())
            return publish(LearnStatus.Waiting)
        }
        if (built == null) return publish(LearnStatus.NoModel)
        if (!consented.covers(built.target)) {
            log("notes not sent: target is not the consented one")
            return publish(LearnStatus.NeedsConsent)
        }
        val model = built.model
        val lang = language()
        val z = zone()
        notesApp = packageName
        publish(LearnStatus.Sending)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                NoticeNotes(model, lang).note(packageName, batch, z)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("notes request failed: ${e.javaClass.simpleName}")
                NotesResult.Failed()
            }
            if (inFlight !== coroutineContext.job) return@launch
            inFlight = null
            slot.release(this@LearnRunner)
            notesApp = null
            try {
                // 途中被清空过(日志、句子换成了新的一份)的,这次的结果不能写回去:那会把清掉的东西带回来
                val stillOk = journal === j && digestPool === pool &&
                    runCatching { on() && digest() && eligible(packageName) && consent() == consented && consented.covers(hooks.target()) }
                        .getOrDefault(false)
                if (!stillOk) {
                    log("notes result dropped: switch, app or consent changed")
                } else when (result) {
                    is NotesResult.Noted -> {
                        val before = pool.data()
                        val added = pool.add(packageName, batch, result.notes, z, clock())
                        pool.expire(dayOf(clock()))
                        // 句子存上了,才把原文标成「做过了」并存:中途进程没了,最坏是同一批再归纳一次,不会丢
                        if (saveDigest(pool)) {
                            j.markNoted(batch)
                            saveJournal()
                            failures = 0
                            holdUntilMs = 0L
                            val drops = result.dropped.entries.joinToString(",") { "${it.key}=${it.value}" }
                            log("notes batch: ${batch.size} entries, $added notes" + if (drops.isEmpty()) "" else ", dropped $drops")
                        } else {
                            // 句子没存上:这一批不算做过。原文要是照样标成「做过了」并存下去,重启后句子没有、原文也不再排队,
                            // 这一批就永远丢了。内存里的句子也退回去——留着的话,下次再归纳这一批就重了
                            restoreDigest(before)
                            failed(clock())
                            log("notes batch not kept: ${batch.size} entries, digest save failed")
                        }
                    }
                    is NotesResult.Failed -> {
                        failed(clock())
                        if (!result.unreachable) {
                            j.markNoted(batch)
                            saveJournal()
                        }
                        log("notes batch failed: ${batch.size} entries, unreachable=${result.unreachable}")
                    }
                    NotesResult.BudgetExhausted -> budgetOutDay = dayOf(clock())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("notes apply failed: ${e.javaClass.simpleName}")
                failed(clock())
            }
            // 先叫等着的判定(判定先),再看自己还有没有要发的
            slot.wakeOthers(this@LearnRunner)
            evaluate()
        }
        slot.take(this)
        inFlight = job
        job.start()
    }

    private fun send(scope: CoroutineScope, packageName: String) {
        val p = loadedPool() ?: return publish(LearnStatus.StoreUnavailable)
        val consented = consent() ?: return publish(LearnStatus.NeedsConsent)
        val events = buffer.begin(packageName)
        if (events.isEmpty()) return publish(LearnStatus.Idle)
        val built = try {
            hooks.model(ProactiveCall.Learn)
        } catch (e: Exception) {
            // 建模型(要解密 Key)出错:不是这批的问题,留着;停一段再试,免得每来一条都再出一次错
            log("learn model unavailable: ${e.javaClass.simpleName}")
            keepBatch(clock())
            return publish(LearnStatus.Waiting)
        }
        if (built == null) {
            buffer.abort()
            return publish(LearnStatus.NoModel)
        }
        // 门口查过之后配置可能变了:建模型用的不是同意过的那个,就不发(事件留着)
        if (!consented.covers(built.target)) {
            buffer.abort()
            log("learn not sent: target is not the consented one")
            return publish(LearnStatus.NeedsConsent)
        }
        val model = built.model
        val now = clock()
        // 给模型看的话题,和它回复里的 t1… 一一对应;请求途中被删了的,用的时候丢掉
        val allowSensitive = sensitive()
        // 这个 App 自己印证过的排在前面:它多半还在推那些题材,模型看得到才引用得上
        val shown = p.strongest(now, includeSensitive = allowSensitive, packageName = packageName).map { ExistingTopic(it.id, it.kind, it.label) }
        // 联系人读不出来时不请模型给(给了也存不下)
        val contactsOn = loadedContacts() != null
        val lang = language()
        bump(packageName) { it.copy(batches = it.batches + 1) }
        publish(LearnStatus.Sending)
        // 先记下作业再开始跑:请求体里要拿它和 inFlight 比
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // 这里抛出去会带垮整个看守(锁屏、判定都在里面):读回复时出了意外也只算这批失败
            val result = try {
                NoticeLearn(model, lang, allowSensitive, contactsOn).learn(packageName, events, shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("learn request failed: ${e.javaClass.simpleName}")
                LearnResult.Failed()
            }
            // 已经不是在发的那一个(被取消了,模型却还是回来了;新的一批可能已经在发):什么都不动
            if (inFlight !== coroutineContext.job) return@launch
            inFlight = null
            slot.release(this@LearnRunner)
            // 用上结果时也一样:出了意外就当这批失败
            try {
                val stillOk = runCatching { on() && eligible(packageName) && consent() == consented && consented.covers(hooks.target()) }
                    .getOrDefault(false)
                if (!stillOk) {
                    buffer.abort()
                    log("learn result dropped: switch, app or consent changed")
                } else when (result) {
                    is LearnResult.Learned -> {
                        applyResult(p, packageName, result)
                        failures = 0
                        holdUntilMs = 0L
                    }
                    is LearnResult.Failed -> if (result.unreachable) keepBatch(clock()) else dropBatch(clock())
                    LearnResult.BudgetExhausted -> {
                        buffer.abort()
                        budgetOutDay = dayOf(clock())
                    }
                }
                val drops = (result as? LearnResult.Learned)?.dropped.orEmpty().entries.joinToString(",") { "${it.key}=${it.value}" }
                val why = (result as? LearnResult.Failed)?.let { if (it.unreachable) " unreachable" else if (it.unreadable) " unreadable" else "" }.orEmpty()
                log("learn batch: ${result.javaClass.simpleName}$why, ${events.size} events" + if (drops.isEmpty()) "" else ", dropped $drops")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("learn apply failed: ${e.javaClass.simpleName}")
                if (buffer.sending().isNotEmpty()) dropBatch(clock())
            }
            // 先叫等着的判定(判定先),再看自己还有没有要发的
            slot.wakeOthers(this@LearnRunner)
            evaluate()
        }
        slot.take(this)
        inFlight = job
        job.start()
    }

    /** 用上一批的结果:用的是这批现在的样子(点开、划掉可能是发出之后才到的)。中间没有挂起点。 */
    private fun applyResult(p: TopicPool, packageName: String, result: LearnResult.Learned) {
        val events = buffer.sending()
        val now = clock()
        // 敏感类按用上这一刻的开关算:请求途中关掉了的,标了敏感的这次也不收
        val applied = p.apply(packageName, events, result.signals, now, zone(), allowSensitive = sensitive())
        // 先存话题,再存联系人:联系人上连着的话题落盘时,话题池里得已经有了(中途进程没了也不会留下指着空处的连接)
        save(p)
        // 联系人:身份要用标题,得在 finish 清掉标题之前算
        val contactDropped = HashMap(result.contactDropped)
        var contactsAccepted = 0
        val c = loadedContacts()
        if (c == null) {
            if (result.contacts.isNotEmpty()) contactDropped[ContactDrop.Off] = (contactDropped[ContactDrop.Off] ?: 0) + result.contacts.size
        } else {
            val r = c.apply(events, result.contacts, applied.byEvent, now, zone(), allowSensitive = sensitive())
            r.dropped.forEach { (k, v) -> contactDropped[k] = (contactDropped[k] ?: 0) + v }
            contactsAccepted = r.accepted
            if (c.maintain(now, p.all().map { it.id }.toSet()) || r.changed) saveContacts(c)
        }
        buffer.finish(applied.byEvent.mapKeys { (i, _) -> events[i].seq })
        lastDistilledAtMs = now
        bump(packageName) { f ->
            val dropped = HashMap(f.dropped)
            result.dropped.forEach { (k, v) -> dropped[k] = (dropped[k] ?: 0) + v }
            // 校验过了、池子却没收的(请求途中话题被删、敏感类在途中关掉)也算丢弃
            if (applied.missing > 0) dropped[LearnDrop.NotApplied] = (dropped[LearnDrop.NotApplied] ?: 0) + applied.missing
            if (applied.blocked > 0) dropped[LearnDrop.Blocked] = (dropped[LearnDrop.Blocked] ?: 0) + applied.blocked
            val cd = HashMap(f.contactDropped)
            contactDropped.forEach { (k, v) -> cd[k] = (cd[k] ?: 0) + v }
            f.copy(
                distilled = f.distilled + events.size, accepted = f.accepted + applied.applied, dropped = dropped,
                contactsAccepted = f.contactsAccepted + contactsAccepted, contactDropped = cd,
            )
        }
    }

    /** 这批没归纳成,丢掉;记一次失败,停一段再发下一批。 */
    private fun dropBatch(now: Long) {
        val pkg = buffer.sending().firstOrNull()?.packageName
        // 先停住再动缓冲:缓冲一变就会重新看一遍,那时已经得是停着的
        failed(now)
        buffer.fail()
        if (pkg != null) bump(pkg) { it.copy(failed = it.failed + 1) }
    }

    /** 这批没发成、但不是它的问题(没连上模型、建模型出错):留着待归纳;同样记一次失败,停一段再发。 */
    private fun keepBatch(now: Long) {
        val pkg = buffer.sending().firstOrNull()?.packageName
        failed(now)
        buffer.abort()
        if (pkg != null) bump(pkg) { it.copy(failed = it.failed + 1) }
    }

    private fun failed(now: Long) {
        failures++
        holdUntilMs = now + LearnTuning.failureHoldMs(failures)
    }

    /** 上一批没归纳成,还在停着。钟被往回拨了(剩下的比最长的一级还长)的不算。 */
    private fun held(now: Long): Boolean =
        failures > 0 && now < holdUntilMs && holdUntilMs - now <= LearnTuning.FAILURE_HOLD_MS.last()

    /** 话题池没读出来时读一次;读不出来(密钥库一时不可用、IO)为 null,下次再试,绝不拿空池去盖文件。 */
    private fun loadedPool(): TopicPool? {
        pool?.let { return it }
        return try {
            TopicPool(store.load()).also {
                pool = it
                savedTopics = it.all().mapTo(HashSet()) { t -> t.id }
                topicsFlow.value = it.all()
                topicsBlockedFlow.value = it.blockedCount
                purgeTopics(it, purges.topicPurges)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn pool unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 联系人没读出来时读一次;读不出来为 null,下次再试,绝不拿空的去盖文件。 */
    private fun loadedContacts(): ContactPool? {
        contactPool?.let { return it }
        return try {
            ContactPool(contactStore.load()).also {
                contactPool = it
                contactsFlow.value = ContactsView(it.all(), it.blockedCount)
                purgeContacts(it, purges.contactPurges)
                // 以前按通知键存下来的:同名的并成一条(要在上面补做完记着的删除之后,那些记的是以前的 id)
                if (it.adoptIdentities()) saveContacts(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn contacts unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 存上了返回 true。 */
    private fun saveContacts(c: ContactPool): Boolean {
        contactsFlow.value = ContactsView(c.all(), c.blockedCount)
        return try {
            contactStore.save(c.data())
            // 做过的补删这下落盘了
            if (contactPurgesApplied.isNotEmpty()) {
                purges.contactPurges = purges.contactPurges - contactPurgesApplied
                contactPurgesApplied = emptySet()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("learn contacts save failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** 存上了返回 true。 */
    private fun save(p: TopicPool): Boolean {
        topicsFlow.value = p.all()
        topicsBlockedFlow.value = p.blockedCount
        return try {
            store.save(p.data())
            savedTopics = p.all().mapTo(HashSet()) { it.id }
            // 做过的补删这下落盘了
            if (topicPurgesApplied.isNotEmpty()) {
                purges.topicPurges = purges.topicPurges - topicPurgesApplied
                topicPurgesApplied = emptySet()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 存不下也不丢内存里的,下次有改动时再存
            log("learn pool save failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun budgetLeft(now: Long): Boolean = budgetOutDay != dayOf(now) &&
        // 归纳按整份预算算(判定只用其中一部分,见 [ProactiveBudget.triageExhausted])
        ProactiveBudget.exhausted(hooks.tokensToday(), hooks.callsToday(), budgetLimit()) == null

    private fun dayOf(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()

    private fun stopRequest() {
        val job = inFlight ?: return
        inFlight = null
        notesApp = null
        dayInFlight = null
        spanInFlight = null
        job.cancel()
        buffer.abort()
        log("learn request cancelled")
        slot.release(this)
        slot.wakeOthers(this)
    }

    private fun stopAndPublish(s: LearnStatus) {
        stopRequest()
        publish(s)
    }

    private fun bump(pkg: String, f: (LearnFeed) -> LearnFeed) {
        feed[pkg] = f(feed[pkg] ?: LearnFeed())
        publish(status)
    }

    private fun publish(s: LearnStatus) {
        status = s
        val summary = buffer.state.value
        mutable.value = LearnState(s, summary.total, summary.pending, lastDistilledAtMs, HashMap(feed), journal?.size ?: 0, journal?.packages().orEmpty())
    }

    companion object {
        /** 记着的删除里表示「敏感类」的那一项;不以冒号开头的都是包名(包名里不会有冒号)。 */
        const val PURGE_SENSITIVE = ":sensitive"
        /** 记着的单删:后面跟着编号(话题、联系人、一句分段归纳)或纪元日(某天、某周、某个月的归纳的起头那天)。 */
        /** 记着的「全清」:整份没删掉(存不上、文件也删不掉),读得出来时把里面的全清掉。 */
        private const val MARK_ALL = ":all"
        private const val MARK_TOPIC = ":topic:"
        private const val MARK_CONTACT = ":contact:"
        /** 通知原文上:某个通知键某一阵的那几段。后面是键的哈希、[KEY_SINCE]、从什么时候起(毫秒)。 */
        private const val MARK_KEY = ":key:"
        private const val KEY_SINCE = "@"
        /** 那一阵有多长:认状态显示时往回看的窗口。 */
        private const val LIVE_WINDOW_MS = com.wangzan101.mika.core.proactive.LiveUpdates.WINDOW_MS
        /** 一次合并:后面是并掉的话题 id、[MERGE_INTO]、并进去的话题 id。话题、联系人两份上都可能记着。 */
        private const val MARK_MERGE = ":merge:"
        private const val MERGE_INTO = ">"
        private const val MARK_NOTE = ":note:"
        private const val MARK_DAY = ":day:"
        private const val MARK_WEEK = ":week:"
        private const val MARK_MONTH = ":month:"

        /** 不记联系人时用的(测试、或没给存放处):读出来总是空的,存了也不留。 */
        private object NoContacts : ContactPoolStore {
            override fun load() = ContactPoolData()
            override fun save(data: ContactPoolData) {}
            override fun dropBroken() {}
            override fun wipe() {}
        }

        /** 带进任务的那一段里最多放几个话题、几个联系人(按强弱取;整段另有 token 上限)。 */
        /** 一次最多连着直接记下几份「没有可归纳的」周(月)归纳(防着写错了转不出来)。 */
        private const val AUTO_SPANS_MAX = 64
        const val OBSERVED_TOPICS = 40
        const val OBSERVED_PEOPLE = 20

        /** 通知日志攒够这么多段没存的就存一次。 */
        const val JOURNAL_FLUSH_EVERY = 20

        /** 不存当日消息归纳时用的(测试、或没给存放处)。 */
        private object NoDigest : DigestStore {
            override fun load() = DigestData()
            override fun save(data: DigestData) {}
            override fun wipe() {}
        }

        /** 不存通知日志时用的(测试、或没给存放处)。 */
        private object NoJournal : NoticeJournalStore {
            override fun load(): NoticeJournalData? = null
            override fun save(data: NoticeJournalData) {}
            override fun wipe() {}
        }

        /** 只在内存里记着的(测试用;正式的是 [ProactivePrefs])。 */
        private class MemoryPurges : LearnPurges {
            override var topicPurges: Set<String> = emptySet()
            override var contactPurges: Set<String> = emptySet()
            override var digestPurges: Set<String> = emptySet()
            override var journalPurges: Set<String> = emptySet()
        }

        /** 系统的移除原因 → 用户做了什么。点开、单独划掉才算;「全部清除」和别的都不说明什么。 */
        fun outcomeOf(reason: Int): LearnOutcome = when (reason) {
            NotificationListenerService.REASON_CLICK -> LearnOutcome.Opened
            NotificationListenerService.REASON_CANCEL -> LearnOutcome.Dismissed
            else -> LearnOutcome.None
        }
    }
}
