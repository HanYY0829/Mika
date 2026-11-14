package com.wangzan101.mika.proactive

/**
 * 锁屏 / 解锁时监听该做什么:纯逻辑,[ProactiveRuntime] 只照着返回的动作做。
 *
 * 一次「锁着」从第一次看到锁着开始,到看到解锁为止;锁屏时间只在这一段的开头记一次。
 * 服务因为锁着而略过一条通知时也算看到了锁着,并且把锁屏时间往前挪到这条通知之前:
 * 广播晚到时,锁屏后、记下锁屏时间前发出的通知也能在解锁后补上。
 * 进程是在锁着时起来的:沿用存着的锁屏时间(更早,只会多补,重复的由去重挡掉)。
 *
 * 补收是「欠着」的:一段锁着开始时、和 [start] 时就欠下。给出 [Action.CatchUp] 只是让调用方去读通知栏,
 * 读成了、由调用方说一声([caughtUp])才算还上——读通知栏可能失败(监听刚断开、系统抛异常),那一次等于没补:
 * 给出动作就算还上的话,下一次锁屏把锁屏时间往后一挪,还在通知栏里的那批就落在它之前,再也补不回来。
 * 欠着时有机会就再试([retry])。
 * 欠着时新的一段锁着不把锁屏时间往后挪(解锁后、监听连上之前又锁了,那次补收还没做,往后挪就漏了中间发出的通知);
 * 略过的通知照样能把它往前挪。
 */
class ProactiveLockPolicy(lockedAtMs: Long) {
    sealed interface Action {
        /** 把锁屏时间存下来(补收按它算)。 */
        data class PersistLockedAt(val atMs: Long) : Action
        /** 请系统解绑监听(只在连着时有意义)。 */
        data object Unbind : Action
        /** 请系统重新绑上监听。 */
        data object Rebind : Action
        /** 读一遍通知栏,补收锁屏期间发出的。 */
        data object CatchUp : Action
    }

    var lockedAtMs: Long = lockedAtMs
        private set
    var inLockedSpell: Boolean = false
        private set
    /** 有一次补收还没做:[lockedAtMs] 是最早那段还没补收的锁着的开头。 */
    var catchUpOwed: Boolean = true
        private set

    /** 开始看着锁屏状态(功能打开、或进程起来时功能开着)。 */
    fun start(locked: Boolean, connected: Boolean): List<Action> {
        // 存着的锁屏时间之后的还没补收过
        catchUpOwed = true
        if (locked) {
            inLockedSpell = true
            return if (connected) listOf(Action.Unbind) else emptyList()
        }
        inLockedSpell = false
        return listOf(if (connected) catchUp() else Action.Rebind)
    }

    fun locked(nowMs: Long, connected: Boolean): List<Action> {
        val out = mutableListOf<Action>()
        if (!inLockedSpell) {
            inLockedSpell = true
            // 上一次补收还欠着:锁屏时间留在那段的开头
            if (!catchUpOwed) {
                catchUpOwed = true
                lockedAtMs = nowMs
                out += Action.PersistLockedAt(nowMs)
            }
        }
        if (connected) out += Action.Unbind
        return out
    }

    fun unlocked(connected: Boolean): List<Action> {
        if (!inLockedSpell) return emptyList()
        inLockedSpell = false
        // 没解绑成功(还连着)就不会再有「连上」,直接补收
        return listOf(if (connected) catchUp() else Action.Rebind)
    }

    /** 服务读到锁着,略过了一条 [postedAtMs] 发出的通知。 */
    fun ignoredWhileLocked(postedAtMs: Long, connected: Boolean): List<Action> {
        val before = postedAtMs - 1
        val out = mutableListOf<Action>()
        if (!inLockedSpell) {
            inLockedSpell = true
            // 欠着补收时只往前挪
            if (!catchUpOwed || before < lockedAtMs) {
                lockedAtMs = before
                out += Action.PersistLockedAt(before)
            }
            catchUpOwed = true
        } else if (before < lockedAtMs) {
            lockedAtMs = before
            out += Action.PersistLockedAt(before)
        }
        if (connected) out += Action.Unbind
        return out
    }

    /** 系统把监听连上了:锁着就请它解绑,没锁就补收。 */
    fun connected(locked: Boolean, nowMs: Long): List<Action> =
        if (locked) locked(nowMs, connected = true) else listOf(catchUp())

    /** 让调用方去补收;还没还上,等它说读成了([caughtUp])。 */
    private fun catchUp(): Action = Action.CatchUp

    /** 调用方读通知栏读成了(读出来是空的也算):欠着的还上了。 */
    fun caughtUp() {
        catchUpOwed = false
    }

    /** 还欠着补收、现在没锁、监听连着:再试一次。别的情况什么都不做(锁着的等解锁,没连上的等连上,那时自会补收)。 */
    fun retry(locked: Boolean, connected: Boolean): List<Action> =
        if (catchUpOwed && !locked && !inLockedSpell && connected) listOf(catchUp()) else emptyList()
}
