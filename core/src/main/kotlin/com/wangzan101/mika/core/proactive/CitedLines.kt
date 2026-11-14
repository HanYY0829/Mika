package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.ModelJson.field
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 当日消息归纳各级共用的读法:模型的回复是一串句子,每句必须指向它依据的输入条目(这一次请求里的 id),指不上的不要;
 * 引用的 id 里有一个不是这次请求里的,也整句不要。
 * 只认回复结尾的那个 JSON 值([ReplyJson]):`{"<key>":[…]}` 或直接一个数组;别的写法、超长的读不出来。
 * 每句的文字按通知文字的办法清洗,网址和长串数字遮掉([LearnText.maskLinksAndLongNumbers]),截到 [maxLen];
 * 过不了敏感信息这一关([LearnText.sensitive]:密码、验证码、卡号之类,加上登录码这些别的叫法)的整句不要。
 */
internal object CitedLines {
    /**
     * [brief]:回复里和句子并排给的一句概述(只有 `{"<key>":[…],"brief":"…"}` 这种写法才有;当日、周、月归纳用)。
     * 它不指向任何输入,所以只在它说的就是收下的这些句子时才留:一句都没收下、或有句子因为说不出依据、过不了敏感信息那一关
     * 之类被丢掉了([NoteDrop.OverLimit] 不算——那只是写多了),概述里可能还带着被丢掉的内容,不要。没有、不合格为 null。
     */
    class Parsed(val lines: List<NoteSignal>, val dropped: Map<NoteDrop, Int>, val brief: String? = null)

    /**
     * [idPrefix] 加序号是输入条目的 id(`e1`…、`s1`…),一共 [count] 个;一次最多认 [maxItems] 句。读不出来为 null。
     */
    fun parse(raw: String, key: String, idPrefix: String, count: Int, maxItems: Int, maxLen: Int): Parsed? {
        if (raw.length > ReplyJson.MAX_CHARS) return null
        var rawBrief: String? = null
        val items = when (val end = ReplyJson.ending(ReplyJson.normalise(raw))) {
            is ReplyJson.Ending.Value -> when (val v = end.element) {
                is JsonArray -> v
                is JsonObject -> {
                    rawBrief = (v.field("brief") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    v.field(key) as? JsonArray ?: return null
                }
                else -> return null
            }
            else -> return null
        }
        // 给了东西、却没有一条是对象(比如 [1,2,3]):回复的结构就不对,读不出来——不能当成「一句都没写」收下
        // (分段归纳那边,一句都没写的一批会把原文标成做过了)
        if (items.isNotEmpty() && items.none { it is JsonObject }) return null
        val dropped = LinkedHashMap<NoteDrop, Int>()
        fun drop(d: NoteDrop) { dropped[d] = (dropped[d] ?: 0) + 1 }
        val out = ArrayList<NoteSignal>()
        items.forEachIndexed { i, e ->
            if (i >= maxItems) return@forEachIndexed drop(NoteDrop.OverLimit)
            val o = e as? JsonObject ?: return@forEachIndexed drop(NoteDrop.Malformed)
            val ids = (o.field("ids") as? JsonArray).orEmpty()
                .map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.let { id -> index(id, idPrefix, count) } }
            // 引用了这次请求里没有的 id:这句有一部分说不出依据,整句不要(不是只把那个 id 划掉)
            if (ids.any { it == null }) return@forEachIndexed drop(NoteDrop.UnknownId)
            val valid = ids.filterNotNull().distinct()
            if (valid.isEmpty()) return@forEachIndexed drop(NoteDrop.NoIds)
            val rawText = (o.field("text") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val full = NoticeFilter.clean(rawText, 1_000)
            if (full.isEmpty()) return@forEachIndexed drop(NoteDrop.Malformed)
            if (LearnText.sensitive(full)) return@forEachIndexed drop(NoteDrop.Sensitive)
            val text = NoticeFilter.cap(LearnText.maskLinksAndLongNumbers(full, LearnTuning.NOTE_DIGIT_RUN_MAX), maxLen).trim()
            if (text.isEmpty()) return@forEachIndexed drop(NoteDrop.Malformed)
            out += NoteSignal(valid, text)
        }
        val clean = out.isNotEmpty() && dropped.keys.all { it == NoteDrop.OverLimit }
        return Parsed(out, dropped, if (clean) rawBrief?.let(LearnText::brief) else null)
    }

    fun id(prefix: String, index: Int): String = "$prefix${index + 1}"

    private fun index(id: String, prefix: String, count: Int): Int? {
        if (!id.startsWith(prefix)) return null
        val n = id.substring(prefix.length).toIntOrNull() ?: return null
        return (n - 1).takeIf { it in 0 until count && id(prefix, it) == id }
    }
}
