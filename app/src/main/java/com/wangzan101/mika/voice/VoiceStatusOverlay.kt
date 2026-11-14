package com.wangzan101.mika.voice

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.DeviceStateWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A small, untouchable voice status below other apps, separate from Mika's task capsule. */
internal class VoiceStatusOverlay(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { Preparing, Waiting, Listening, Transcribing, Received, Running, Done, Attention, Error }

    private data class Display(val phase: Phase, val message: String, val transcript: String = "")

    private var display by mutableStateOf(Display(Phase.Preparing, ""))
    private var speechDisplay = Display(Phase.Preparing, "")
    private var taskDisplay: Display? = null
    private var clearTask: Job? = null
    private val screen = DeviceStateWatcher(context) { -1 }
    private var watcher: Job? = null
    private var owner: ScreenAccessibility? = null
    private var window: OverlayWindow? = null

    fun start() {
        if (watcher != null) return
        watcher = scope.launch(Dispatchers.Main.immediate) {
            combine(ScreenAccessibility.instance, screen.locked) { service, locked -> if (locked) null else service }
                .collect { service ->
                    if (owner !== service) {
                        window?.close()
                        window = null
                        owner = service
                    }
                    service?.let(::attach)
                }
        }
    }

    fun show(phase: Phase, message: String) = onMain {
        speechDisplay = Display(phase, message)
        refresh()
        owner?.let(::attach)
    }

    fun taskStarting(command: String) = onMain {
        clearTask?.cancel()
        taskDisplay = Display(Phase.Preparing, context.getString(R.string.wake_task_starting), command.take(80))
        refresh()
    }

    fun taskState(state: TaskState) = onMain {
        clearTask?.cancel()
        taskDisplay = when (state) {
            TaskState.Idle -> taskDisplay
            is TaskState.Running -> Display(Phase.Running, context.getString(R.string.wake_task_running),
                (state.narration?.takeIf(String::isNotBlank) ?: state.note).take(80))
            is TaskState.Held -> Display(Phase.Attention, context.getString(R.string.wake_task_paused), state.reason.why.take(80))
            is TaskState.WaitingInput -> Display(Phase.Attention, context.getString(R.string.wake_task_answer), state.prompt.take(80))
            is TaskState.WaitingConfirmation -> Display(Phase.Attention, context.getString(R.string.wake_manual_confirmation))
            is TaskState.WaitingTakeover -> Display(Phase.Attention, context.getString(R.string.wake_manual_takeover))
            is TaskState.Succeeded -> Display(Phase.Done, context.getString(R.string.wake_task_done), state.answer.take(80))
            is TaskState.Failed -> Display(Phase.Error, context.getString(R.string.wake_task_failed), state.reason.take(80))
            TaskState.Cancelled -> Display(Phase.Attention, context.getString(R.string.wake_task_cancelled))
        }
        refresh()
        if (state is TaskState.Terminal) clearAfterDelay()
    }

    fun taskMissing() = onMain {
        clearTask?.cancel()
        taskDisplay = Display(Phase.Error, context.getString(R.string.wake_task_missing))
        refresh()
        clearAfterDelay()
    }

    private fun clearAfterDelay() {
        clearTask = scope.launch(Dispatchers.Main.immediate) {
            delay(6_000)
            taskDisplay = null
            refresh()
        }
    }

    private fun refresh() {
        display = if (speechDisplay.phase == Phase.Waiting) taskDisplay ?: speechDisplay else speechDisplay
    }

    fun transcript(words: String) = onMain {
        val trimmed = words.take(80)
        if (speechDisplay.phase == Phase.Listening && speechDisplay.transcript != trimmed) {
            speechDisplay = speechDisplay.copy(transcript = trimmed)
            refresh()
        }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun attach(service: ScreenAccessibility) {
        if (window?.isOpen == true) return
        val opened = service.overlays().open(placement(service), touchable = false) { Content() }
        window = opened
        opened?.onResize { place() }
    }

    private fun place() {
        val service = owner ?: return
        window?.place(placement(service))
    }

    private fun placement(service: ScreenAccessibility): OverlayPlacement {
        val bottom = service.overlays().area().insetBottom
        val margin = (48 * service.resources.displayMetrics.density).toInt()
        return OverlayPlacement(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, y = bottom + margin)
    }

    @Composable
    private fun Content() {
        val current = display
        val tint = when (current.phase) {
            Phase.Preparing, Phase.Waiting -> Color(0xFF8CB8FF)
            Phase.Listening -> Color(0xFF43DBCE)
            Phase.Transcribing, Phase.Running -> Color(0xFFFFCA70)
            Phase.Received -> Color(0xFF77D997)
            Phase.Done -> Color(0xFF77D997)
            Phase.Attention, Phase.Error -> Color(0xFFFF9B89)
        }
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = Color(0xED202630),
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(tint, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(current.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current.transcript.isNotBlank()) {
                        Text(current.transcript, color = Color(0xFFD9E9F8), fontSize = 13.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    fun close() {
        clearTask?.cancel()
        watcher?.cancel()
        watcher = null
        window?.close()
        window = null
        owner = null
        screen.close()
    }
}
