package com.wangzan101.mika.core.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 片段(fragment)里的问号不是查询串,不能从里面读出包名。 */
class WebUrlQueryParamTest {
    @Test fun aQuestionMarkInsideTheFragmentIsNotAQueryString() {
        val link = WebUrlPolicy.external("market://details#frag?id=com.evil.app")!!
        assertNull(link.packageName)
    }

    @Test fun realQueryParametersStillWorkWithAFragment() {
        assertEquals(
            "com.example.app",
            WebUrlPolicy.external("market://details?id=com.example.app#frag")!!.packageName,
        )
        assertEquals(
            "com.example.app",
            WebUrlPolicy.external("https://play.google.com/store/apps/details?id=com.example.app")!!.packageName,
        )
    }
}
