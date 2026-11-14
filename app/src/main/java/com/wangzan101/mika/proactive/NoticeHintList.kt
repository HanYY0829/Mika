package com.wangzan101.mika.proactive

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 「值得提醒」的入口:胶囊上的条数从这里取;点胶囊上的数字、或主动能力页那一行,就把那几条写成对话里 Mika 的一条消息([openTalk])。
 * 不调模型、不触发通知自带的点击或回复;写进对话之后的事(用户回一句、任务开始)和别的对话一样。
 */
object NoticeHintList {
    /** 还没看的条数;主动能力开关关着时是 0,也不去建主动能力(见 [FeatureFlags.proactiveChanges])。不计时,只跟着变化。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun unseenCount(context: Context): Flow<Int> = FeatureFlags.get(context).proactiveChanges().flatMapLatest { on ->
        if (on) (context.applicationContext as MikaApp).proactive.hints.map { it.unseen } else flowOf(0)
    }.distinctUntilChanged()

    /**
     * 用户点开了提醒:见 [ProactiveRuntime.openHintTalk]。返回提醒对话的 id(已设为当前对话);没有可开的、出了错为 null。
     * 只在看得到提醒的地方调(那时主动能力开着)。
     */
    suspend fun openTalk(context: Context): String? = try {
        (context.applicationContext as MikaApp).proactive.openHintTalk()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "could not open the notice talk: ${e.javaClass.simpleName}")
        null
    }

    private const val TAG = "MikaNoticeHint"
}

/**
 * 主动能力页上的那一行(胶囊显示不了提醒时的入口:开关不是「一直显示」、或人在 Mika 里):「值得提醒 (N)」,
 * 下面两个数是看过的和没看就处理了的条数;点开是同一个提醒对话,在 Mika 里打开。
 */
@Composable
internal fun NoticeHintEntry(state: NoticeHints.State) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    GroupCard {
        ListRow(
            null, stringResource(R.string.notice_hint_entry, state.unseen),
            stringResource(R.string.notice_hint_feedback, state.seenTotal, state.dismissedUnseenTotal),
            first = true,
            onClick = {
                scope.launch {
                    val id = NoticeHintList.openTalk(context) ?: return@launch
                    runCatching { context.startActivity(TaskService.openAppIntent(context, id)) }
                }
            },
        )
    }
}
