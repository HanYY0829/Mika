package com.wangzan101.mika.core.proactive

import java.time.ZoneId

/**
 * 任务读通知用的来源(只读):这两天的通知原文和分段归纳,以及存着的各天、各周、各月的归纳(留得比原文久,月归纳留两年)。由 App 给(见 ProactiveRuntime):只在主动能力、「当日消息归纳」都开着,
 * 激进程度允许把东西带进任务时才有;没给时读通知的工具不出现。给出来的只有来源档位够格的 App 的。
 */
interface NoticeSource {
    val zone: ZoneId

    /** 现在几点(「今天」「昨天」按它和 [zone] 算)。 */
    fun now(): Long = System.currentTimeMillis()

    /** App 的名字(按包名从系统读,不来自通知)。 */
    fun appLabel(packageName: String): String

    /**
     * [fromMs](含)到 [toMs](不含)之间的通知原文和分段归纳。
     * 读不出来(任务跑着的时候被关掉了、存着的那份一时读不出、等太久)为 null:不能当成「这段时间什么都没收到」。
     */
    suspend fun read(fromMs: Long, toMs: Long): NoticeView?

    /**
     * 存着的各天(各周、各月)的归纳,新的在前;只有来源档位够格的 App 的句子。读不出来为 null(同 [read])。
     */
    suspend fun digests(level: DigestLevel): DigestsView?
}

/** 归纳的哪一级:当日、周、月。 */
enum class DigestLevel { Day, Week, Month }

/**
 * 给任务读的一份归纳:[start] 到 [end] 是它管的日子(纪元日,两头都算);[partial] 为 true 是那段时间还没过完时做的
 * 「到现在为止」([madeAtMs] 是做到什么时候);[lines] 是够格给出来的句子,连同每句来自哪些 App(包名)。
 * [leftOut]:做这一份时放不下、没看到的下一级句子有几句(大于 0 就是说这一份没有照着全部写)。
 */
class DigestView(val start: Long, val end: Long, val madeAtMs: Long, val partial: Boolean, val lines: List<DigestViewLine>, val leftOut: Int = 0)

class DigestViewLine(val text: String, val apps: List<String>)

/** [withheldLines]:因为来源档位不够格没给出来的句子有几句(只给个数)。 */
class DigestsView(val digests: List<DigestView>, val withheldLines: Int = 0)

/**
 * [sinceMs] 是通知记录从什么时候开始是全的(更早的没有,或因为太多被挤掉了一部分);[entries] 是通知原文(由旧到新),
 * [notes] 是这段时间的分段归纳(由旧到新)。文字都是别的 App、别人写的(分段归纳是模型照着写的)。
 * [withheldApps]:这段时间有通知、但来源档位不够格所以没给出来的 App 有几个(只给个数)。
 * [notesUnavailable]:分段归纳那份这会儿读不出来——[notes] 是空的,但那是「不知道」,不是「没有」。
 */
class NoticeView(
    val sinceMs: Long, val entries: List<NoticeEntry>, val notes: List<DigestNote>, val withheldApps: Int = 0,
    val notesUnavailable: Boolean = false,
)
