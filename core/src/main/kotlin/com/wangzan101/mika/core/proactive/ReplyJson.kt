package com.wangzan101.mika.core.proactive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 读主动能力(判定、归纳)的模型回复:只认回复最后的那个 JSON 值,那才是模型的结论。
 * 前面的(想的过程、复述的通知)里就算有像答案的对象,也不算:通知是第三方写的,复述出来的「答案」可能是注入。
 * 纯函数;只认标准 JSON:没加引号的只能是数字、true、false、null(kotlinx 读树时不查这个,这里另查),
 * 一段以 `[n1, n2]` 结尾的话不算数组。
 */
internal object ReplyJson {
    /** 回复超过这么长就不读(整批失败)。 */
    const val MAX_CHARS = 32_000
    /** 找结尾那个值时最多试这么多个起点,再多就算读不出来。 */
    const val MAX_STARTS = 200

    private val strict = Json

    sealed interface Ending {
        /** 回复以这个 JSON 对象或数组结尾(最外层的那个)。 */
        data class Value(val element: JsonElement) : Ending
        /** 回复不以 JSON 值结尾(最后一个字不是 `}` / `]`,或结尾那段读不成一个值)。 */
        data object None : Ending
        /** 起点太多,没试完:当读不出来。 */
        data object GaveUp : Ending
    }

    /** 去掉首尾空白,开头一行代码块标记(``` 或 ```json)和结尾的 ```,再去一次空白。两头各自看(先想一段再给代码块时开头没有标记)。 */
    fun normalise(raw: String): String {
        var s = raw.trim()
        if (s.startsWith(FENCE)) {
            val nl = s.indexOf('\n')
            val tag = (if (nl < 0) s else s.substring(0, nl)).substring(FENCE.length).trim()
            if (tag.isEmpty() || tag.equals("json", ignoreCase = true)) s = if (nl < 0) "" else s.substring(nl + 1)
        }
        s = s.trimEnd()
        if (s.endsWith(FENCE)) s = s.substring(0, s.length - FENCE.length)
        return s.trim()
    }

    /**
     * [text](已 [normalise])结尾的那个 JSON 值:以 `}` 结尾时找正好在最后结束的最外层对象,以 `]` 结尾时找最外层数组。
     * 从左往右试每个 `{` / `[`,第一个「从它到结尾正好是一个这种值」的就是;最多试 [MAX_STARTS] 个。
     */
    fun ending(text: String): Ending {
        val open = when (text.lastOrNull()) {
            '}' -> '{'
            ']' -> '['
            else -> return Ending.None
        }
        var tried = 0
        var from = text.indexOf(open)
        while (from >= 0) {
            if (++tried > MAX_STARTS) return Ending.GaveUp
            val e = try { strict.parseToJsonElement(text.substring(from)) } catch (e: Exception) { null }
            if (e != null && (if (open == '{') e is JsonObject else e is JsonArray) && standard(e)) return Ending.Value(e)
            from = text.indexOf(open, from + 1)
        }
        return Ending.None
    }

    private const val FENCE = "```"

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** 没加引号的值都是数字、true、false、null。不递归(嵌套很深也不会爆栈)。 */
    private fun standard(root: JsonElement): Boolean {
        val stack = ArrayDeque<JsonElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val e = stack.removeLast()) {
                is JsonObject -> e.values.forEach(stack::addLast)
                is JsonArray -> e.forEach(stack::addLast)
                is JsonPrimitive -> if (!e.isString && e.content != "true" && e.content != "false" && e.content != "null" &&
                    !NUMBER.matches(e.content)) return false
            }
        }
        return true
    }
}
