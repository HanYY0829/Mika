package com.wangzan101.mika.ui

import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState

/**
 * 当前 UI 状态会生成的行 key,按 LazyColumn 里的顺序。自动滚到末尾和“跳到某一轮的消息”都用这一份,
 * 不再各自数一遍。条件和 ChatScreen.exchange 及其附属行一一对应;末尾状态行即使暂时没有可见内容也占一个 LazyList item。
 */
internal fun chatItemKeys(
    exchanges: List<Exchange>,
    kept: KeptPage?,
    phoneSetupNeeded: Boolean,
    hasBlocked: Boolean,
): List<String> = buildList {
    if (exchanges.isEmpty() && !hasBlocked) add("empty")
    exchanges.forEachIndexed { index, ex ->
        if (ex.goal.isNotEmpty()) add("u$index")
        val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
        if (if (ex.status.isActive()) !answering else ex.steps.isNotEmpty() || ex.recordedRun != null) add("s$index")
        val (joined, waiting) = steersAroundDraft(ex.steers)
        (joined + waiting).forEach { note ->
            if (!note.finished.isNullOrBlank()) add("a$index-${note.id}")
            if (!note.interrupted.isNullOrBlank()) add("i$index-${note.id}")
            add("m$index-${note.id}")
        }
        if (!ex.draft.isNullOrBlank()) add("d$index")
        add("r$index")
        if (KeptRow.of(ex, kept) != null) add("k$index")
        if (ex.files.isNotEmpty()) add("f$index")
        if (ex.runId != null && (ex.memoryNotes.isNotEmpty() || ex.skillNotes.isNotEmpty())) add("m$index")
    }
    if (phoneSetupNeeded && exchanges.isNotEmpty()) add("phone-setup")
    if (hasBlocked) add("blocked")
}

/**
 * 当前 UI 状态会在 LazyColumn 里生成多少行。自动滚到末尾必须用这份当前状态算目标,不能读上一帧的
 * LazyListLayoutInfo.totalItemsCount:发送后的副作用可能先于新行测量执行,旧行数会把新轮次留在视口外。
 */
internal fun chatItemCount(
    exchanges: List<Exchange>,
    kept: KeptPage?,
    phoneSetupNeeded: Boolean,
    hasBlocked: Boolean,
): Int = chatItemKeys(exchanges, kept, phoneSetupNeeded, hasBlocked).size

/** 目标消息在 [chatItemKeys] 里的 key;轮次或消息已经不在了时返回 null。 */
internal fun chatTargetKey(exchanges: List<Exchange>, target: ChatTarget): String? {
    val turn = target.turnIndex
    val ex = exchanges.getOrNull(turn) ?: return null
    return when (target) {
        is ChatTarget.Message -> {
            val note = target.steerIndex?.let { visibleSteer(ex, it) }
            if (target.steerIndex != null) {
                if (note != null) "m$turn-${note.id}" else turnStartKey(turn, ex)
            } else {
                turnStartKey(turn, ex)
            }
        }
        is ChatTarget.Reply -> {
            val note = visibleSteer(ex, target.steerIndex)
            val key = when {
                note == null -> null
                target.interrupted && !note.interrupted.isNullOrBlank() -> "i$turn-${note.id}"
                !target.interrupted && !note.finished.isNullOrBlank() -> "a$turn-${note.id}"
                else -> null
            }
            key ?: turnStartKey(turn, ex)
        }
        is ChatTarget.Files -> if (ex.files.isNotEmpty()) "f$turn" else turnStartKey(turn, ex)
        is ChatTarget.Status -> "r$turn"
    }
}

/** 撤回的插话不在聊天页里显示,按位置找时要跳过。 */
private fun visibleSteer(ex: Exchange, index: Int): SteerNote? =
    ex.steers.filterNot { it.state == SteerState.Withdrawn }.getOrNull(index)

/** 这一轮的开头:有用户气泡就跳到气泡,否则退回结果卡。 */
private fun turnStartKey(turnIndex: Int, ex: Exchange): String =
    if (ex.goal.isNotEmpty()) "u$turnIndex" else "r$turnIndex"

private val ChatTarget.turnIndex: Int
    get() = when (this) {
        is ChatTarget.Message -> this.turnIndex
        is ChatTarget.Reply -> this.turnIndex
        is ChatTarget.Files -> this.turnIndex
        is ChatTarget.Status -> this.turnIndex
    }
