package com.wangzan101.mika.core.proactive

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerGrantTest {
    private val pkg = "com.wangzan101.mika.dev"
    private val cls = "com.wangzan101.mika.proactive.NoticeListenerService"

    @Test fun `builds the exact command with the component single-quoted`() {
        assertEquals(
            "cmd notification allow_listener 'com.wangzan101.mika.dev/com.wangzan101.mika.proactive.NoticeListenerService'",
            ListenerGrant.command(pkg, cls),
        )
        assertEquals("cmd notification allow_listener 'a.b/a.b.Outer\$Inner'", ListenerGrant.command("a.b", "a.b.Outer\$Inner"))
    }

    @Test fun `rejects shell metacharacters in either part`() {
        val bad = listOf(
            "a b", "a'b", "a\"b", "a;b", "a|b", "a&b", "a`b`", "a\$(id)", "a\nb", "a\n", "a\r", "a\tb",
            "a/b", "a\\b", "a>b", "a<b", "a*b", "", ".a", "a.", "中文", "a-b", "é",
        )
        bad.forEach { s ->
            assertFalse("package should reject [$s]", ListenerGrant.validPackage(s))
            assertThrows(IllegalArgumentException::class.java) { ListenerGrant.command(s, cls) }
        }
        bad.forEach { s ->
            assertFalse("class should reject [$s]", ListenerGrant.validClass(s))
            assertThrows(IllegalArgumentException::class.java) { ListenerGrant.command(pkg, s) }
        }
        // 包名里不认 $
        assertFalse(ListenerGrant.validPackage("a\$b"))
        assertTrue(ListenerGrant.validClass("a.B\$C_1"))
    }

    private class Fake(var grantedAfterPolls: Int = 0, var alreadyGranted: Boolean = false) {
        var polls = 0
        var commandRan = false
        var enabled = 0
        var rebinds = 0
        val authorized: () -> Boolean = {
            if (alreadyGranted) true
            else if (!commandRan) false
            else (polls++ >= grantedAfterPolls)
        }
    }

    @Test fun `granted when access shows up after the command`() = runTest {
        val f = Fake(grantedAfterPolls = 3)
        val r = ListenerGrant.run("cmd", { f.commandRan = true; "" }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertEquals(GrantResult.Granted, r)
        assertEquals(1, f.enabled)
        assertEquals(1, f.rebinds)
    }

    @Test fun `already authorized skips the command`() = runTest {
        val f = Fake(alreadyGranted = true)
        val r = ListenerGrant.run("cmd", { error("must not run") }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertEquals(GrantResult.Granted, r)
        assertEquals(0, f.enabled)
        assertEquals(1, f.rebinds)
    }

    @Test fun `helper unavailable when the runner throws`() = runTest {
        val f = Fake()
        val r = ListenerGrant.run("cmd", { throw IOException("no connection") }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertEquals(GrantResult.HelperUnavailable, r)
        assertEquals(1, f.enabled)
        assertEquals(0, f.rebinds)
    }

    @Test fun `an inner timeout inside the runner is helper unavailable, not a cancellation`() = runTest {
        val f = Fake()
        val r = ListenerGrant.run("cmd", { withTimeout(1) { awaitCancellation() } }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertEquals(GrantResult.HelperUnavailable, r)
    }

    @Test fun `refused keeps a short one-line output`() = runTest {
        val f = Fake(grantedAfterPolls = Int.MAX_VALUE)
        val long = "Error:\n  " + "x".repeat(500)
        val r = ListenerGrant.run("cmd", { f.commandRan = true; long }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertTrue(r is GrantResult.Refused)
        val out = (r as GrantResult.Refused).output
        assertFalse(out.contains('\n'))
        assertTrue(out.length <= ListenerGrant.OUTPUT_LOG_LIMIT + 1)
        assertTrue(out.startsWith("Error: xxx"))
        assertEquals(0, f.rebinds)
        // 等了约 2 秒(虚拟时间)才放弃
        assertEquals(ListenerGrant.WAIT_MS, testScheduler.currentTime)
    }

    @Test fun `timed out when the runner hangs`() = runTest {
        val f = Fake()
        val r = ListenerGrant.run("cmd", { awaitCancellation() }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertEquals(GrantResult.TimedOut, r)
        assertEquals(ListenerGrant.EXEC_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(0, f.rebinds)
    }

    @Test fun `a slow but finished command is not a timeout`() = runTest {
        val f = Fake()
        val r = ListenerGrant.run("cmd", { delay(9_000); f.commandRan = true; "" }, f.authorized, { f.enabled++ }, { f.rebinds++ })
        assertEquals(GrantResult.Granted, r)
    }
}
