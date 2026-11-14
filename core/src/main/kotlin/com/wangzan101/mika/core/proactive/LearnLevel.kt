package com.wangzan101.mika.core.proactive

/**
 * 来源(App)的置信度档位,用户按 App 定:
 * - [Low]:只判定、提醒,不从它归纳。
 * - [Mid]:归纳进话题、联系人、当日消息归纳(默认);只在最激进的一档带进任务。
 * - [High]:归纳出的东西达到门槛后带进任务——平衡、激进两档(见 [LearnLevel.intoTasks])。
 */
enum class SourceTier(val wire: String) {
    Low("low"), Mid("mid"), High("high");

    companion object {
        fun ofWire(s: String?): SourceTier? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * 激进程度:一个总开关,一次调一组门槛。[Conservative] 什么都不带进任务。
 * 不管哪一档,Mika 都不会不等用户点就自己开口,也不会替用户回复。
 *
 * - [topicDays]:一个话题至少在几个不同的日子里出现过才算已确立。
 * - [surfaceCap]:每个 App 每天最多提醒几个不同的会话。
 * - [intoTasks]:哪几档来源归纳出的东西(已确立的话题、联系人,当日归纳里的句子)带进任务;空的就是不带。
 */
enum class LearnLevel(val wire: String, val topicDays: Int, val surfaceCap: Int, val intoTasks: Set<SourceTier>) {
    Conservative("conservative", topicDays = 2, surfaceCap = 10, intoTasks = emptySet()),
    Balanced("balanced", topicDays = 2, surfaceCap = 20, intoTasks = setOf(SourceTier.High)),
    Aggressive("aggressive", topicDays = 1, surfaceCap = Int.MAX_VALUE, intoTasks = setOf(SourceTier.Mid, SourceTier.High));

    /** 这一档下,这个话题算不算已确立。 */
    fun established(t: ObservedTopic): Boolean = t.days >= topicDays

    companion object {
        fun ofWire(s: String?): LearnLevel = entries.firstOrNull { it.wire == s } ?: Conservative
    }
}
