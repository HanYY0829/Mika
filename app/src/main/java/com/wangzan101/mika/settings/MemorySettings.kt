package com.wangzan101.mika.settings

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

/**
 * 「记忆与技能」的三个开关:[useMemory] 总开关(关 = 不检索、不注入、不提取、不提供记忆工具);
 * [autoRemember]「自动记住我说过的事」;[learnSkills]「从办成的任务里学技能」(默认开,学到的要用户收下才用)。
 */
data class MemorySwitches(
    val useMemory: Boolean = true,
    val autoRemember: Boolean = true,
    val learnSkills: Boolean = true,
    /** 任务结束时自动记一条跨会话摘要,供下次同类任务检索;关掉就既不记、也不提供检索工具。 */
    val rememberTasks: Boolean = true,
)

/**
 * 开关存成一个小 JSON 文件;读不懂按默认值。
 * 旧版本存下的文件没有 [LEARN_DEFAULT_APPLIED]:那时「学技能」默认关,读的时候升级一次打开;之后一存就带上它,用户手动关掉就保持关闭。
 */
class MemorySettings(private val file: File) {
    private val _switches = MutableStateFlow(read())
    val switches: StateFlow<MemorySwitches> = _switches.asStateFlow()

    @Synchronized fun update(transform: (MemorySwitches) -> MemorySwitches) {
        val next = transform(_switches.value)
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject {
            put("useMemory", next.useMemory); put("autoRemember", next.autoRemember); put("learnSkills", next.learnSkills)
            put("rememberTasks", next.rememberTasks)
            put(LEARN_DEFAULT_APPLIED, true)
        }.toString())
        _switches.value = next
    }

    private fun read(): MemorySwitches {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return MemorySwitches()
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        val def = MemorySwitches()
        val upgraded = b(LEARN_DEFAULT_APPLIED, false)
        return MemorySwitches(b("useMemory", def.useMemory), b("autoRemember", def.autoRemember),
            if (upgraded) b("learnSkills", def.learnSkills) else def.learnSkills,
            b("rememberTasks", def.rememberTasks))
    }

    private companion object {
        const val LEARN_DEFAULT_APPLIED = "learnDefaultApplied"
    }
}
