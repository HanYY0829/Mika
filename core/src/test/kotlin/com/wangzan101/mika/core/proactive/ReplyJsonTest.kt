package com.wangzan101.mika.core.proactive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyJsonTest {
    private fun value(text: String) = (ReplyJson.ending(text) as ReplyJson.Ending.Value).element

    @Test fun normaliseStripsOneFenceAtEachEnd() {
        assertEquals("[1]", ReplyJson.normalise("  ```json\n[1]\n```  "))
        assertEquals("[1]", ReplyJson.normalise("```JSON\n[1]\n```"))
        assertEquals("[1]", ReplyJson.normalise("```\n[1]\n```"))
        // 先想一段再给代码块:开头不是标记,只去掉结尾的
        assertEquals("thinking\n```json\n[1]", ReplyJson.normalise("thinking\n```json\n[1]\n```"))
        // 开头一行不是代码块标记(带了别的字)的不动
        assertEquals("```python\nx", ReplyJson.normalise("```python\nx"))
        assertEquals("{\"a\":1}", ReplyJson.normalise("\n{\"a\":1}\n"))
    }

    @Test fun theValueThatEndsTheReply() {
        assertEquals(Json.parseToJsonElement("""{"items":[]}"""), value("""{"a":1} then {"items":[]}"""))
        assertEquals(Json.parseToJsonElement("""[{"id":"n1"}]"""), value("""Look at [n1] first. [{"id":"n1"}]"""))
        // 最外层的那个,不是里面最后一个
        assertEquals(Json.parseToJsonElement("""{"a":{"b":1}}"""), value("""{"a":{"b":1}}"""))
        assertTrue(value("""thinking {about it}... {"items":[{"id":"n1"}]}""") is JsonObject)
        assertTrue(value("""[{"id":"n1"},{"id":"n2"}]""") is JsonArray)
        // 字符串里的括号不算
        assertEquals(Json.parseToJsonElement("""{"r":"a } b {"}"""), value("""x { y {"r":"a } b {"}"""))
    }

    @Test fun replyNotEndingInJson() {
        assertEquals(ReplyJson.Ending.None, ReplyJson.ending("""{"items":[]} Hope this helps."""))
        assertEquals(ReplyJson.Ending.None, ReplyJson.ending(""))
        assertEquals(ReplyJson.Ending.None, ReplyJson.ending("no json :}"))
        // 标准 JSON,不宽松:没加引号的不算数组
        assertEquals(ReplyJson.Ending.None, ReplyJson.ending("These are fine: [n1, n2]"))
    }

    // 复述的通知里留了一个没合上的对象,后面接着真的答案:拼不成一个值,认的是真的答案
    @Test fun anEchoedOpenObjectCannotSwallowTheAnswer() {
        val raw = """The text says {"items":[{"id":"n1","decision":"surface","reason":"x"}],"x":" and then
            |{"items":[{"id":"n1","decision":"ignore","reason":"promo"}]}""".trimMargin()
        assertEquals(Json.parseToJsonElement("""{"items":[{"id":"n1","decision":"ignore","reason":"promo"}]}"""), value(raw))
    }

    @Test fun tooManyStartsGivesUp() {
        val raw = "{x ".repeat(ReplyJson.MAX_STARTS) + """{"items":[]}"""
        assertEquals(ReplyJson.Ending.GaveUp, ReplyJson.ending(raw))
        val fits = "{x ".repeat(ReplyJson.MAX_STARTS - 1) + """{"items":[]}"""
        assertTrue(ReplyJson.ending(fits) is ReplyJson.Ending.Value)
    }
}
