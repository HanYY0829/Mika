package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.decision.DecisionConfig
import com.wangzan101.mika.core.decision.DecisionModel
import com.wangzan101.mika.core.decision.SystemOneDecisionModel
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Independent, opt-in decision service. Its key never enters the public state or the provider store. */
class DecisionSettings(private val dir: File, private val cipher: KeyCipher, private val usage: UsageSink = UsageSink { _, _ -> }) {
    private val file = File(dir, "decision-model.bin")
    private val mutable = MutableStateFlow(runCatching { read()?.first }.getOrNull() ?: DecisionConfig())
    val state: StateFlow<DecisionConfig> = mutable.asStateFlow()

    @Synchronized fun apiKey(): String = runCatching {
        read()?.takeIf { it.first == state.value }?.second.orEmpty()
    }.getOrDefault("")

    /** Write the complete encrypted record before publishing it to new tasks. No network request is made. */
    @Synchronized fun save(config: DecisionConfig, apiKey: String) {
        val next = config.copy(endpoint = config.endpoint.trim(), model = config.model.trim())
        val key = apiKey.trim()
        require(valid(next, key)) { "Invalid decision model configuration" }
        val bytes = buildJsonObject {
            put("enabled", next.enabled); put("endpoint", next.endpoint); put("model", next.model); put("apiKey", key)
        }.toString().toByteArray(Charsets.UTF_8)
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeBytes(cipher.encrypt(bytes))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            mutable.value = next
        } finally { bytes.fill(0); tmp.delete() }
    }

    /** Disabled, corrupt, unreadable or incomplete settings fall back to the existing models. */
    @Synchronized fun createModel(): DecisionModel? {
        val config = state.value
        if (!config.enabled) return null
        val key = apiKey()
        if (!valid(config, key)) return null
        return runCatching { testModel(config, key) }.getOrNull()
    }

    /** Unsaved synthetic probes use the same client and accounting, without enabling the service. */
    internal fun testModel(config: DecisionConfig, apiKey: String): DecisionModel {
        require(valid(config.copy(enabled = true), apiKey)) { "Invalid decision model configuration" }
        val source = UsageSource("jev-decision", "Jev", config.model.trim(), UsagePurpose.Other)
        return SystemOneDecisionModel(config.endpoint.trim(), apiKey.trim(), config.model.trim(),
            onUsage = { tokens, attempts -> usage.record(source, tokens, attempts) })
    }

    private fun read(): Pair<DecisionConfig, String>? {
        if (!file.isFile) return null
        val bytes = cipher.decrypt(file.readBytes())
        try {
            val o = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
            fun string(name: String) = (o.getValue(name) as JsonPrimitive).content
            val enabled = (o.getValue("enabled") as JsonPrimitive).booleanOrNull ?: error("Invalid enabled state")
            val config = DecisionConfig(enabled, string("endpoint"), string("model"))
            val key = string("apiKey")
            check(valid(config, key)) { "Invalid decision model configuration" }
            return config to key
        } finally { bytes.fill(0) }
    }

    companion object {
        @Volatile private var instance: DecisionSettings? = null

        fun get(context: Context): DecisionSettings = instance ?: synchronized(this) {
            instance ?: DecisionSettings(File(context.applicationContext.filesDir, "settings"),
                KeystoreKeyCipher("mika-decision-key"), (context.applicationContext as? MikaApp)?.usage ?: UsageSink { _, _ -> })
                .also { instance = it }
        }

        fun valid(config: DecisionConfig, apiKey: String): Boolean =
            DecisionConfig.isValidEndpoint(config.endpoint.trim()) && config.model.isNotBlank() &&
                config.model.none(Char::isISOControl) && apiKey.trim().all { it.code in 33..126 } &&
                (!config.enabled || apiKey.isNotBlank())
    }
}
