package com.wangzan101.mika.proactive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 主动能力自己的锁屏状态:熄屏时不让手机为这个功能醒一次。
 *
 * 状态每次都从系统读([read]:屏幕亮不亮、锁屏界面在不在),SCREEN_OFF / SCREEN_ON / USER_PRESENT 广播只当
 * 「该重新看一眼」,由 [ProactiveRuntime] 转来调 [refresh]。熄屏时只等广播,什么都不定时做。
 * 唯一要轮询的是「亮着但锁屏界面还在」:有的解锁路子先发 USER_PRESENT、锁屏界面过一会才退下,之后不再有广播
 * (见 [com.wangzan101.mika.task.LockState] 上面的说明)。只在读到这种状态、且在看守([start] 之后)时
 * 每 [POLL_MS] 再读一次;读到别的(熄屏或已解锁)就停掉轮询。要不要轮询由纯函数 [shouldPoll] 决定。
 *
 * 所有方法都要在 [scope] 那条串行的协程上调(广播也先丢到那里),所以不另加锁。
 */
internal class ProactiveLockWatch(private val read: () -> Reading, private val scope: CoroutineScope) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean) {
        val locked get() = !interactive || keyguardLocked
    }

    private var active = false
    private val _locked = MutableStateFlow(true)
    /** 熄屏或锁屏;[start] 之前没读过,当锁着(宁可少读)。 */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** 正在跑的轮询;只有「在看守、亮着、锁屏界面还在」时才有。测试看它。 */
    internal var pollJob: Job? = null
        private set

    /** 开始看守:读一次。 */
    fun start() {
        active = true
        refresh()
    }

    /** 停止看守:停掉轮询;之后 [refresh] 照样读,但不再开轮询。 */
    fun stop() {
        active = false
        cancelPoll()
    }

    /** 从系统读一次,并按读到的决定开不开、停不停轮询。 */
    fun refresh() {
        val r = read()
        _locked.value = r.locked
        if (!shouldPoll(active, r)) cancelPoll()
        else if (pollJob?.isActive != true) pollJob = scope.launch { poll() }
    }

    /** 轮询自己读到不该再轮询时直接退出,不经 [refresh] 取消自己。 */
    private suspend fun poll() {
        while (true) {
            delay(POLL_MS)
            val r = read()
            _locked.value = r.locked
            if (!shouldPoll(active, r)) {
                pollJob = null
                return
            }
        }
    }

    private fun cancelPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        const val POLL_MS = 500L

        /** 只有在看守、屏幕亮着、锁屏界面还在时才轮询;熄屏一律不轮询(等 SCREEN_ON)。 */
        fun shouldPoll(active: Boolean, r: Reading): Boolean = active && r.interactive && r.keyguardLocked
    }
}
