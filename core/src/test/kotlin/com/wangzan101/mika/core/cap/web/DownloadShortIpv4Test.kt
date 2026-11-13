package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 短写 / 整数 / 八进制 IPv4 也是本机或内网地址,不能绕过下载前的字面 IP 检查。 */
class DownloadShortIpv4Test {
    @Test fun shortIntegerAndOctalIpv4LiteralsAreRefused() {
        for (u in listOf(
            "http://127.1/", "http://2130706433/", "http://0300.0250.0.1/",
            "http://192.168.1/", "http://10.1/", "http://169.254.169.254/",
        )) assertNotNull(u, DownloadAddressPolicy.refusal(u))
    }

    @Test fun ordinaryDomainsAndPublicLiteralsStillPass() {
        for (u in listOf("https://example.com/a.pdf", "http://8.8.8.8/x", "http://172.32.0.1/")) {
            assertNull(u, DownloadAddressPolicy.refusal(u))
        }
    }
}
