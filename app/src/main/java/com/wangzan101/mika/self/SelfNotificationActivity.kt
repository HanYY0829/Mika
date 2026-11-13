package com.wangzan101.mika.self

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.self.SelfScreen
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.ui.MainActivity

/** Receives the app's immutable notification PendingIntent. */
class SelfNotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && FeatureFlags.get(this).selfOperate()) {
            val screen = intent.getStringExtra(SelfIntents.EXTRA_SCREEN)?.let(SelfScreen::of)
            val file = intent.getStringExtra(SelfIntents.EXTRA_FILE)?.let { path ->
                SelfFileRequest(
                    intent.getStringExtra(SelfIntents.EXTRA_FILE_CONVERSATION).orEmpty(),
                    path,
                    intent.getStringExtra(SelfIntents.EXTRA_FILE_NAME),
                )
            }
            val url = intent.getStringExtra(SelfIntents.EXTRA_URL)
            val loginHost = intent.getStringExtra(SelfIntents.EXTRA_LOGIN_HOST)
            if (screen != null || file != null || url != null || loginHost != null) {
                (application as MikaApp).selfUi.requestOpen(
                    screen = screen,
                    file = file,
                    url = url,
                    loginHost = loginHost,
                    newTab = intent.getBooleanExtra(SelfIntents.EXTRA_NEW_TAB, false),
                    show = intent.getBooleanExtra(SelfIntents.EXTRA_SHOW, true),
                )
            }
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
