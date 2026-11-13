package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.*

/** A model-facing view only; evidence, recordings and the diff source keep the tool's original result. */
internal object ScreenElementTable {
    const val KEY = "elementTables"

    fun hasRows(screen: JsonObject) = screen["elements"] is JsonArray || screen[KEY] is JsonArray

    fun text(result: JsonObject, enabled: Boolean): String {
        val raw = result.toString()
        if (!enabled || raw.length !in 180..2_000_000 || result["ok"] == JsonPrimitive(false)) return raw
        val nested = result["screen"] as? JsonObject
        val screen = nested ?: result
        if (KEY in screen || "base" in screen || screen["full"] == JsonPrimitive(false) ||
            ("snapshotId" !in screen && "currentApp" !in screen)) return raw
        val elements = screen["elements"] as? JsonArray ?: return raw
        if (elements.size < 3) return raw

        // Keep consecutive groups separate: a missing field is different from JSON null.
        val tables = mutableListOf<JsonElement>()
        var start = 0
        while (start < elements.size) {
            val keys = (elements[start] as? JsonObject)?.keys?.toList() ?: return raw
            var end = start + 1
            while (end < elements.size && (elements[end] as? JsonObject)?.keys?.toList() == keys) end++
            tables += buildJsonObject {
                put("columns", JsonArray(keys.map(::JsonPrimitive)))
                put("rows", JsonArray((start until end).map { JsonArray(elements[it].jsonObject.values.toList()) }))
            }
            start = end
        }
        val view = JsonObject(screen - "elements" + (KEY to JsonArray(tables)))
        val packed = (if (nested == null) view else JsonObject(result + ("screen" to view))).toString()
        return if (packed.length <= raw.length * 0.94 && TokenEstimate.of(packed) < TokenEstimate.of(raw)) packed else raw
    }
}
