package com.wangzan101.mika.proactive

import com.wangzan101.mika.proactive.ProactiveLockPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveLockPolicyTest {
    // 下面三个:照着返回的动作做,给了补收就当通知栏读成了(运行时读成之后会说一声 caughtUp)。读不成的情况另有测试
    private fun ProactiveLockPolicy.startOk(locked: Boolean, connected: Boolean) = start(locked, connected).also { if (Action.CatchUp in it) caughtUp() }
    private fun ProactiveLockPolicy.unlockedOk(connected: Boolean) = unlocked(connected).also { if (Action.CatchUp in it) caughtUp() }
    private fun ProactiveLockPolicy.connectedOk(locked: Boolean, nowMs: Long) = connected(locked, nowMs).also { if (Action.CatchUp in it) caughtUp() }

    @Test fun startUnlockedRebinds() = assertEquals(listOf(Action.Rebind), ProactiveLockPolicy(0).start(locked = false, connected = false))

    @Test fun startLockedKeepsTheStoredLockTime() {
        val p = ProactiveLockPolicy(100)
        assertEquals(emptyList<Action>(), p.startOk(locked = true, connected = false))
        assertEquals(100L, p.lockedAtMs)
        // 同一段锁着里再看到锁着,不改锁屏时间
        assertEquals(emptyList<Action>(), p.locked(500, connected = false))
        assertEquals(100L, p.lockedAtMs)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
    }

    @Test fun lockPersistsOnceAndUnbinds() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        assertEquals(listOf(Action.PersistLockedAt(1_000), Action.Unbind), p.locked(1_000, connected = true))
        assertEquals(listOf(Action.Unbind), p.locked(2_000, connected = true))
        assertEquals(emptyList<Action>(), p.locked(3_000, connected = false))
        assertEquals(1_000L, p.lockedAtMs)
    }

    @Test fun unlockRebindsOrCatchesUpWhenStillConnected() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        p.locked(1_000, connected = true)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        assertEquals(emptyList<Action>(), p.unlockedOk(connected = false)) // 已经解锁,不重复
        p.locked(2_000, connected = true)
        // 解绑没成功、一直连着:不会再有「连上」,直接补收
        assertEquals(listOf(Action.CatchUp), p.unlockedOk(connected = true))
    }

    @Test fun connectedWhileUnlockedCatchesUpAndWhileLockedUnbinds() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = false)
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 10))
        assertEquals(listOf(Action.PersistLockedAt(20), Action.Unbind), p.connectedOk(locked = true, nowMs = 20))
    }

    @Test fun postIgnoredBeforeTheLockBroadcastMovesTheLockTimeBack() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        // 服务先读到锁着,略过了 900 发出的通知;广播在 1000 才到
        assertEquals(listOf(Action.PersistLockedAt(899), Action.Unbind), p.ignoredWhileLocked(900, connected = true))
        assertEquals(emptyList<Action>(), p.locked(1_000, connected = false))
        assertEquals(899L, p.lockedAtMs)
        // 更早的一条再往前挪,更晚的不动
        assertEquals(listOf(Action.PersistLockedAt(799)), p.ignoredWhileLocked(800, connected = false))
        assertEquals(emptyList<Action>(), p.ignoredWhileLocked(950, connected = false))
        assertEquals(799L, p.lockedAtMs)
    }

    // ---- 欠着的补收:锁屏时间留在最早那段还没补收的锁着的开头 ----

    // 锁(解绑)→ 通知 N 发出 → 解锁(请重绑)→ 连上之前又锁了 → 锁着时连上(只解绑)→ 解锁、连上:补收要盖住 N
    @Test fun relockBeforeTheListenerConnectsKeepsTheEarlierLockTime() {
        val p = ProactiveLockPolicy(0)
        assertEquals(listOf(Action.CatchUp), p.startOk(locked = false, connected = true))
        assertEquals(listOf(Action.PersistLockedAt(1_000), Action.Unbind), p.locked(1_000, connected = true))
        val postedN = 2_000L
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        // 还没连上就又锁了:不往后挪,也不存
        assertEquals(emptyList<Action>(), p.locked(3_000, connected = false))
        assertEquals(listOf(Action.Unbind), p.connectedOk(locked = true, nowMs = 3_500))
        assertEquals(1_000L, p.lockedAtMs)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 4_000))
        assertTrue("补收从 N 之前算起", p.lockedAtMs < postedN)
        // 补收过了:下一次锁屏照常记新的锁屏时间
        assertEquals(listOf(Action.PersistLockedAt(5_000), Action.Unbind), p.locked(5_000, connected = true))
        assertEquals(5_000L, p.lockedAtMs)
    }

    // 进程起来时没锁,第一次连上之前锁了:沿用存着的锁屏时间,直到真的补收过
    @Test fun lockBeforeTheFirstConnectAfterStartKeepsTheStoredLockTime() {
        val p = ProactiveLockPolicy(100)
        assertEquals(listOf(Action.Rebind), p.startOk(locked = false, connected = false))
        assertEquals(emptyList<Action>(), p.locked(500, connected = false))
        assertEquals(100L, p.lockedAtMs)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 600))
        assertEquals(100L, p.lockedAtMs)
        assertEquals(listOf(Action.PersistLockedAt(900)), p.locked(900, connected = false))
    }

    @Test fun severalFlapsBeforeAConnectKeepTheFirstLockTime() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        p.locked(1_000, connected = true)
        for (t in listOf(2_000L, 3_000L, 4_000L)) {
            assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
            assertEquals(emptyList<Action>(), p.locked(t, connected = false))
        }
        assertEquals(listOf(Action.Unbind), p.connectedOk(locked = true, nowMs = 4_500))
        p.unlockedOk(connected = false)
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 5_000))
        assertEquals(1_000L, p.lockedAtMs)
    }

    // 普通的一锁一解:每次补收过后,下一次锁屏都存新的时间
    @Test fun eachLockAfterACatchUpPersistsItsOwnTime() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        for (t in listOf(1_000L, 2_000L, 3_000L)) {
            assertEquals(listOf(Action.PersistLockedAt(t), Action.Unbind), p.locked(t, connected = true))
            assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
            assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = t + 100))
            assertEquals(t, p.lockedAtMs)
        }
        // 解绑没成功、一直连着时解锁就补收,同样算补收过
        p.locked(4_000, connected = true)
        assertEquals(listOf(Action.CatchUp), p.unlockedOk(connected = true))
        assertEquals(listOf(Action.PersistLockedAt(5_000)), p.locked(5_000, connected = false))
    }

    // 欠着补收时,略过的通知只能把锁屏时间往前挪
    @Test fun ignoredWhileOwedOnlyMovesTheTimeEarlier() {
        val p = ProactiveLockPolicy(1_000)
        p.startOk(locked = false, connected = false)
        assertEquals(emptyList<Action>(), p.ignoredWhileLocked(1_500, connected = false))
        assertEquals(1_000L, p.lockedAtMs)
        assertEquals(listOf(Action.PersistLockedAt(799)), p.ignoredWhileLocked(800, connected = false))
        assertEquals(799L, p.lockedAtMs)
        p.unlockedOk(connected = false)
        assertEquals(listOf(Action.Unbind), p.ignoredWhileLocked(900, connected = true))
        assertEquals(799L, p.lockedAtMs)
    }

    // 补收时通知栏读不出来(监听刚断开、系统抛异常):这一次等于没补,还欠着。
    // 下一次锁屏不能把锁屏时间往后挪——还在通知栏里的那批是在它之前发出的,往后挪就再也补不回来
    @Test fun aCatchUpThatCouldNotReadTheShadeIsStillOwed() {
        val p = ProactiveLockPolicy(100_000)
        p.start(locked = true, connected = false)
        assertEquals(listOf(Action.Rebind), p.unlocked(connected = false))
        // 连上了,给出补收——但读通知栏失败,没有人说 caughtUp
        assertEquals(listOf(Action.CatchUp), p.connected(locked = false, nowMs = 160_000))
        assertTrue(p.catchUpOwed)
        // 又锁了:锁屏时间留在原来那一段的开头,不存新的
        assertEquals(listOf(Action.Unbind), p.locked(nowMs = 200_000, connected = true))
        assertEquals(100_000L, p.lockedAtMs)
        // 再解锁、连上:这回读成了,150 秒时发出的那条在「锁屏之后」的范围里
        p.unlocked(connected = false)
        assertEquals(listOf(Action.CatchUp), p.connected(locked = false, nowMs = 260_000))
        val earlier = com.wangzan101.mika.core.proactive.NoticeRecord("k", "com.chat", 150_000, "t", "x")
        assertEquals(listOf(earlier), com.wangzan101.mika.core.proactive.CatchUp.select(listOf(earlier), enabledAtMs = 0, lastLockedAtMs = p.lockedAtMs - 5_000))
        p.caughtUp()
        assertEquals(false, p.catchUpOwed)
        // 还上之后,下一次锁屏才记自己的时间
        assertEquals(listOf(Action.PersistLockedAt(300_000), Action.Unbind), p.locked(nowMs = 300_000, connected = true))
    }

    // 还欠着的时候有机会就再试:没锁、监听连着才试;锁着的等解锁,没连上的等连上,还上了的不试
    @Test fun anOwedCatchUpIsRetriedWhenPossible() {
        val p = ProactiveLockPolicy(0)
        p.start(locked = false, connected = true)
        assertTrue(p.catchUpOwed)
        assertEquals(listOf(Action.CatchUp), p.retry(locked = false, connected = true))
        assertEquals(emptyList<Action>(), p.retry(locked = true, connected = true))
        assertEquals(emptyList<Action>(), p.retry(locked = false, connected = false))
        p.caughtUp()
        assertEquals(emptyList<Action>(), p.retry(locked = false, connected = true))
        // 锁着的那一段里不试
        p.locked(1_000, connected = true)
        assertEquals(emptyList<Action>(), p.retry(locked = false, connected = true))
    }
}
