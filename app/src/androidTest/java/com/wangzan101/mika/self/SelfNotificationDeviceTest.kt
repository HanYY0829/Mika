package com.wangzan101.mika.self

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelfNotificationDeviceTest {
    @Suppress("DEPRECATION")
    @Test fun notificationHandlerIsPrivateInTheInstalledManifest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val handler = ComponentName(context, SelfNotificationActivity::class.java)
        assertFalse(context.packageManager.getActivityInfo(handler, 0).exported)
    }
}
