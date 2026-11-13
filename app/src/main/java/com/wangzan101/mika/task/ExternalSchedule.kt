package com.wangzan101.mika.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSource
import com.wangzan101.mika.settings.FeatureFlags
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 外部脚本经广播(com.wangzan101.mika.SCHEDULE)建定时任务的入口。
 * 广播是 fire-and-forget,没有返回值:拒绝原因写进 logcat,绝不静默排队。
 */
object ExternalSchedule {
    const val ACTION = "com.wangzan101.mika.SCHEDULE"

    /** 每分钟最多接几条;超过直接拒绝,不排队。 */
    private const val LIMIT_PER_MINUTE = 5
    private const val WINDOW_MS = 60_000L
    private const val PREFS = "external_schedule"
    private const val KEY_RECENT = "recent_accepted_ms"
    private const val MAX_PROMPT = 4000

    enum class Outcome { Disabled, RateLimited, MissingPrompt, PromptTooLong, InvalidTime, TimeNotFuture, Scheduled }

    @Synchronized
    fun accept(context: Context, prompt: String?, at: String?): Outcome {
        val app = context.applicationContext
        if (!FeatureFlags.get(app).externalSchedule()) {
            Log.w("MikaExternal", "external schedule rejected: disabled")
            return Outcome.Disabled
        }
        val goal = prompt?.trim().orEmpty()
        if (goal.isEmpty()) {
            Log.w("MikaExternal", "external schedule rejected: missing prompt")
            return Outcome.MissingPrompt
        }
        if (goal.length > MAX_PROMPT) {
            Log.w("MikaExternal", "external schedule rejected: prompt too long")
            return Outcome.PromptTooLong
        }
        val atMs = when {
            at == null -> System.currentTimeMillis()
            else -> {
                val parsed = runCatching { Instant.parse(at.trim()).toEpochMilli() }.getOrNull()
                if (parsed == null) {
                    Log.w("MikaExternal", "external schedule rejected: invalid time")
                    return Outcome.InvalidTime
                }
                if (parsed <= System.currentTimeMillis()) {
                    Log.w("MikaExternal", "external schedule rejected: time not future")
                    return Outcome.TimeNotFuture
                }
                parsed
            }
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!allowNow(prefs)) {
            Log.w("MikaExternal", "external schedule rejected: rate limited")
            return Outcome.RateLimited
        }
        val threadId = ConversationStore.shared(app).newConversation()
        val plan = TriggerDefinition(UUID.randomUUID().toString(), threadId, goal, atMs, source = TriggerSource.External)
        TriggerRuntime.schedule(app, plan)
        Log.i("MikaExternal", "external schedule accepted: id=${plan.id} at=$atMs")
        return Outcome.Scheduled
    }

    /** 记下最近一分钟内真正接下的任务;窗口内满 [LIMIT_PER_MINUTE] 条即拒绝。 */
    private fun allowNow(prefs: SharedPreferences): Boolean {
        val now = System.currentTimeMillis()
        val recent = prefs.getString(KEY_RECENT, "").orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < WINDOW_MS }
        if (recent.size >= LIMIT_PER_MINUTE) return false
        prefs.edit().putString(KEY_RECENT, (recent + now).joinToString(",")).apply()
        return true
    }
}

/** 接外部脚本的广播:prompt 必填,at 可选(ISO 8601 带时区,缺省立即执行)。 */
class ExternalScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExternalSchedule.ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ExternalSchedule.accept(context, intent.getStringExtra("prompt"), intent.getStringExtra("at"))
            } catch (e: Exception) {
                Log.e("MikaExternal", "external schedule failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
