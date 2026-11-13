package com.wangzan101.mika.personal

import android.content.Context
import com.wangzan101.mika.R
import com.wangzan101.mika.core.personal.PersonalMessage
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.core.personal.PersonalSystemException

/** Display only: never use this text as model context or write it back to the store. */
fun personalSystemText(context: Context, type: String?, fallback: String): String {
    val id = when (type) {
        "interrupted" -> R.string.personal_system_interrupted
        "timeout" -> R.string.personal_system_timeout
        "reply_failed" -> R.string.personal_system_reply_failed
        "no_model" -> R.string.personal_system_no_model
        "context_long" -> R.string.personal_system_context_long
        "empty_reply" -> R.string.personal_system_empty_reply
        "too_many_actions" -> R.string.personal_system_too_many_actions
        "message_long" -> R.string.personal_system_message_long
        "task_start_failed" -> R.string.personal_system_task_start_failed
        "task_cancelled" -> R.string.personal_system_task_cancelled
        "stop_not_ready" -> R.string.personal_system_stop_not_ready
        "task_done_detail" -> R.string.personal_system_task_done_detail
        "task_stopped_detail" -> R.string.personal_system_task_stopped_detail
        "task_failed_detail" -> R.string.personal_system_task_failed_detail
        "history_unreadable" -> R.string.personal_system_history_unreadable
        "task_interrupted" -> R.string.personal_system_task_interrupted
        "wait_confirmation" -> R.string.personal_system_wait_confirmation
        "saving" -> R.string.personal_system_saving
        "waiting" -> R.string.personal_system_waiting
        else -> return fallback
    }
    return context.getString(id)
}

fun personalStopErrorText(context: Context, error: Throwable): String =
    personalSystemText(context, (error as? PersonalSystemException)?.systemType,
        error.message ?: context.getString(R.string.personal_system_stop_failed))

fun PersonalMessage.displayText(context: Context): String = personalSystemText(context, systemType, text)
fun PersonalTask.displayDetail(context: Context): String =
    if (detailSystemType == "task_amendment" && detail.startsWith("补充：")) {
        context.getString(R.string.personal_system_task_amendment, detail.removePrefix("补充："))
    } else personalSystemText(context, detailSystemType, detail)
fun PersonalTask.displayResult(context: Context): String? = result?.let { personalSystemText(context, resultSystemType, it) }
