package com.wangzan101.mika.core.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 坐标容忍 [a,b] 时,非有限的中点应当报错,不能静默变成 0。 */
class ArgsCoordTest {
    private fun args(v: JsonElement) = Args(
        ToolDef("t", "d", mapOf("x" to JsonObject(emptyMap())), emptyList()),
        JsonObject(mapOf("x" to v)),
    )

    @Test fun nonFinitePairsAreRejectedInsteadOfSilentlyBecomingZero() {
        for (pair in listOf(
            JsonArray(listOf(JsonPrimitive("NaN"), JsonPrimitive(10))),
            JsonArray(listOf(JsonPrimitive(10), JsonPrimitive("NaN"))),
            JsonArray(listOf(JsonPrimitive("1e999"), JsonPrimitive("-1e999"))),
        )) assertThrows(ArgError::class.java) { args(pair).coord("x") }
    }

    @Test fun finitePairsStillUseTheirMidpoint() {
        assertEquals(500, args(JsonArray(listOf(JsonPrimitive(400), JsonPrimitive(600)))).coord("x"))
        assertEquals(250, args(JsonPrimitive(250)).coord("x"))
        assertThrows(ArgError::class.java) { args(JsonPrimitive("NaN")).coord("x") }
    }
}
