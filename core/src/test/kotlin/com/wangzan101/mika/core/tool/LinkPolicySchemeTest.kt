package com.wangzan101.mika.core.tool

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** open_link 的注释说拒绝文件与脚本类协议;浏览器内部协议同样不该交给系统去打开。 */
class LinkPolicySchemeTest {
    @Test fun browserInternalSchemesAreRefused() {
        for (u in listOf("data:text/html,hello", "about:blank", "blob:https://example.com/abc")) {
            assertNotNull(u, LinkPolicy.check(null, u, null))
        }
    }

    @Test fun ordinaryAppAndWebLinksStillPass() {
        assertNull(LinkPolicy.check(null, "someapp://display/playlist", null))
        assertNull(LinkPolicy.check(null, "https://example.com/a", "com.example.browser"))
    }

    @Test fun previouslyBlockedSchemesStayBlocked() {
        for (u in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://media/1", "javascript:alert(1)")) {
            assertNotNull(u, LinkPolicy.check(null, u, null))
        }
    }
}
