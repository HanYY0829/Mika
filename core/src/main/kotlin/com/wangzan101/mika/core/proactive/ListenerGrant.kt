package com.wangzan101.mika.core.proactive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** 「由 Mika 助手授权」的结果。界面只按类别给一句话,不显示命令的原始输出。 */
sealed interface GrantResult {
    data object Granted : GrantResult
    /** 助手和已配对的连接都用不上(命令根本没跑起来)。 */
    data object HelperUnavailable : GrantResult
    /** 命令跑了,系统里还是没有使用权;[output] 是截短的命令输出,只给日志用。 */
    data class Refused(val output: String) : GrantResult
    /** 命令在限定时间里没跑完。 */
    data object TimedOut : GrantResult
}

/**
 * 以 shell 身份给 Mika 的通知监听开「通知使用权」:只在用户点按钮并确认后跑一次,不在后台自动跑。
 * 纯逻辑:跑命令、等系统那边生效都经参数传入,便于用假的测。
 */
object ListenerGrant {
    const val EXEC_TIMEOUT_MS = 10_000L
    const val WAIT_MS = 2_000L
    const val POLL_MS = 200L
    const val OUTPUT_LOG_LIMIT = 120

    /**
     * 拼出授权命令。包名和类名先按严格的字符集整串校验(只认 ASCII 字母、数字、`_`、`.`,类名多认 `$`),
     * 不合格就抛 [IllegalArgumentException],绝不把没校验过的字符串拼进 shell 命令。
     * 组件名再整体加单引号:类名里的 `$` 不会被 sh 当成变量展开(校验已保证里面没有单引号)。
     */
    fun command(packageName: String, className: String): String {
        require(validPackage(packageName)) { "bad package name" }
        require(validClass(className)) { "bad class name" }
        return "cmd notification allow_listener '$packageName/$className'"
    }

    fun validPackage(s: String): Boolean = valid(s, allowDollar = false)
    fun validClass(s: String): Boolean = valid(s, allowDollar = true)

    // 逐个字符判断,不用正则:不受各平台正则方言(比如 \w 认不认非 ASCII 字母、$ 能不能匹配到末尾换行之前)影响
    private fun valid(s: String, allowDollar: Boolean): Boolean {
        if (s.isEmpty() || s.length > 255) return false
        if (s.first() == '.' || s.last() == '.') return false
        return s.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '.' || (allowDollar && c == '$')
        }
    }

    /**
     * 授权的全过程:已经有使用权就直接算成功;否则先确保监听组件启用(停用的组件授了权也会被系统收回),
     * 在 [EXEC_TIMEOUT_MS] 内跑完命令,再在 [WAIT_MS] 内每隔 [POLL_MS] 看一次系统那边生效没有;生效了调 [onGranted]。
     * [exec] 抛异常(调用方自己被取消除外)当作助手用不上。
     */
    suspend fun run(
        command: String,
        exec: suspend (String) -> String,
        authorized: () -> Boolean,
        ensureEnabled: () -> Unit,
        onGranted: () -> Unit,
    ): GrantResult {
        if (authorized()) {
            onGranted()
            return GrantResult.Granted
        }
        ensureEnabled()
        val output = try {
            withTimeoutOrNull(EXEC_TIMEOUT_MS) { exec(command) } ?: return GrantResult.TimedOut
        } catch (e: CancellationException) {
            // 只有调用方自己被取消才往外抛;命令通道内部的超时之类(也是 CancellationException)算助手用不上
            currentCoroutineContext().ensureActive()
            return GrantResult.HelperUnavailable
        } catch (e: Exception) {
            return GrantResult.HelperUnavailable
        }
        var waited = 0L
        while (true) {
            if (authorized()) {
                onGranted()
                return GrantResult.Granted
            }
            if (waited >= WAIT_MS) break
            delay(POLL_MS)
            waited += POLL_MS
        }
        return GrantResult.Refused(shorten(output))
    }

    /** 命令输出只留一行、截短,给日志用。 */
    fun shorten(output: String): String {
        val one = output.trim().replace(Regex("\\s+"), " ")
        return if (one.length <= OUTPUT_LOG_LIMIT) one else one.take(OUTPUT_LOG_LIMIT) + "…"
    }
}
