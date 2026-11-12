package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 网络安全配置的守护:企业内网自建服务靠这两条声明直连(明文 http + 用户安装的 CA),
 * 删掉任何一条都会退化成「只信系统证书的加密端点」,#361 的场景重新连不上。
 */
class NetworkSecurityConfigTest {

    private fun baseConfig(): Element {
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network security config not found from ${File(".").absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val configs = doc.getElementsByTagName("base-config")
        assertEquals("expected exactly one base-config", 1, configs.length)
        return configs.item(0) as Element
    }

    /** 用户自己在本机或局域网跑的模型服务多半只有 http,这条删了它们全连不上。 */
    @Test
    fun cleartextStaysPermitted() {
        assertEquals("true", baseConfig().getAttribute("cleartextTrafficPermitted"))
    }

    /**
     * 公司内部 CA 签发的自建端点(GPUStack/vLLM 一类)全靠用户 CA 这条:显式声明锚点后系统默认
     * 不再接受用户证书,普通用户没有 root 装不进系统信任区。装 CA 需用户在系统设置里主动操作。
     */
    @Test
    fun trustAnchorsIncludeUserInstalledCertificates() {
        val anchors = baseConfig().getElementsByTagName("certificates")
        val sources = (0 until anchors.length).map { (anchors.item(it) as Element).getAttribute("src") }
        assertEquals(setOf("system", "user"), sources.toSet())
    }
}
