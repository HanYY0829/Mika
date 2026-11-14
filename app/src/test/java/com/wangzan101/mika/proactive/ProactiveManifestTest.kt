package com.wangzan101.mika.proactive

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** 通知监听的清单写法:默认停用(没开功能时系统「通知使用权」里没有 Mika),只滤常驻通知。 */
class ProactiveManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    @Test fun listenerIsDisabledByDefaultAndOnlyFiltersOngoing() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val s = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".proactive.NoticeListenerService" }
        assertEquals("false", s.getAttributeNS(ns, "enabled"))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", s.getAttributeNS(ns, "permission"))
        val metas = s.getElementsByTagName("meta-data")
        val filters = (0 until metas.length).map { metas.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == "android.service.notification.disabled_filter_types" }
        assertEquals("ongoing", filters.getAttributeNS(ns, "value"))
    }
}
