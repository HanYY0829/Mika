package com.wangzan101.mika.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主机名归一化、同站点判定与可注册域名:这几处被知识匹配、网页策略和技能草稿共用。 */
class UrlHostTest {
    @Test fun ofNormalizesCaseWwwPortAndTrailingDot() {
        assertEquals("example.com", UrlHost.of("https://WWW.Example.COM:443/a?b=1"))
        assertEquals("example.com", UrlHost.of("http://example.com./"))
        assertEquals("example.com", UrlHost.of("https://www.example.com:8443/x"))
        assertEquals("localhost", UrlHost.of("http://localhost:11434/v1"))
        assertNull(UrlHost.of("not a url"))
        assertNull(UrlHost.of(""))
    }

    @Test fun internationalHostsBecomeAscii() {
        val host = UrlHost.of("https://例子.中国/")
        assertTrue("$host", host != null && host.startsWith("xn--"))
    }

    @Test fun sameSiteIgnoresSchemePortAndWww() {
        assertTrue(UrlHost.sameSite("http://example.com/a", "https://www.example.com:8443/b"))
        assertTrue(UrlHost.sameSite("https://a.example.com", "https://b.example.com"))
        assertFalse(UrlHost.sameSite("https://example.com", "https://example.org"))
        assertFalse(UrlHost.sameSite("https://a.github.io", "https://b.github.io"))
        assertFalse(UrlHost.sameSite("not a url", "https://example.com"))
    }

    @Test fun registrableHandlesTwoPartAndPrivateSuffixes() {
        assertEquals("example.com", UrlHost.registrable("www.example.com"))
        assertEquals("example.co.uk", UrlHost.registrable("shop.example.co.uk"))
        assertEquals("example.com.cn", UrlHost.registrable("a.example.com.cn"))
        assertEquals("me.github.io", UrlHost.registrable("me.github.io"))
        assertNull(UrlHost.registrable("com"))
        assertNull(UrlHost.registrable("co.uk"))
        assertNull(UrlHost.registrable("1.2.3.4"))
    }
}
