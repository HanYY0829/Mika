package com.wangzan101.mika.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Only a visible user action starts this microphone foreground service. */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var taskWatch: Job? = null
    @Volatile private var recorder: AudioRecord? = null
    private var pendingVoice: PendingVoice? = null
    private var voiceOverlay: VoiceStatusOverlay? = null

    private sealed interface PendingVoice {
        data class Start(val text: String, val conversationId: String) : PendingVoice
        data class Answer(val text: String, val runKey: String) : PendingVoice
        data class Steer(val text: String, val conversationId: String, val messageId: String) : PendingVoice
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setRequested(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!requested(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = notification(getString(R.string.wake_loading))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start microphone service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        active.value = true
        if (voiceOverlay == null) voiceOverlay = VoiceStatusOverlay(this, scope).also {
            it.start()
            it.show(VoiceStatusOverlay.Phase.Preparing, getString(R.string.wake_loading))
        }
        if (job == null) job = scope.launch { listenLoop() }
        return START_STICKY
    }

    private suspend fun listenLoop() {
        try {
            val spotter = KeywordSpotter(
                assetManager = assets,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "wake/encoder.onnx", decoder = "wake/decoder.onnx", joiner = "wake/joiner.onnx",
                        ),
                        tokens = "wake/tokens.txt", numThreads = 1, modelType = "zipformer2", modelingUnit = "cjkchar",
                    ),
                    keywordsFile = "wake/keywords.txt",
                    maxActivePaths = 4,
                    numTrailingBlanks = 1,
                ),
            )
            Log.i(TAG, "Wake-word model ready")
            try {
                while (scope.isActive) {
                    if (pendingVoice == null) notifyState(getString(R.string.wake_waiting), VoiceStatusOverlay.Phase.Waiting)
                    val preroll = try { listenOnce(spotter) } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Wake microphone capture failed; retrying", e)
                        null
                    }
                    if (preroll == null) {
                        if (!scope.isActive) break
                        notifyState(getString(R.string.wake_retrying), VoiceStatusOverlay.Phase.Error)
                        delay(2_000)
                        continue
                    }
                    Log.i(TAG, "Wake word detected; buffered ${preroll.size * 1000 / RATE} ms")
                    val wakeAt = SystemClock.elapsedRealtime()
                    notifyState(getString(R.string.wake_say_command), VoiceStatusOverlay.Phase.Listening)
                    transcribeAndSubmit(preroll, wakeAt)
                }
            } finally {
                spotter.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Wake listening failed", e)
            notifyState(getString(R.string.wake_failed), VoiceStatusOverlay.Phase.Error)
        } finally {
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission") // Checked before starting the service.
    private fun listenOnce(spotter: KeywordSpotter): ShortArray? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            Log.w(TAG, "Wake microphone has no valid buffer size: $min")
            return null
        }
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, RATE / 5))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "Wake microphone did not initialize")
            rec.release()
            return null
        }
        val firstStream = try { spotter.createStream() } catch (e: Exception) {
            rec.release()
            throw e
        }
        // Sherpa can discard the start of a word during its automatic silence reset.
        // Keep two streams one second apart so the other can still hear that word.
        val streams = mutableListOf(firstStream)
        var nextRotationAt = RATE.toLong()
        var nextToReplace = 0
        recorder = rec
        val buffer = ShortArray(RATE / 10)
        val recent = ShortArray(RATE * PREROLL_SECONDS)
        var cursor = 0
        var written = 0
        var samplesRead = 0L
        try {
            rec.startRecording()
            val recordingStarted = SystemClock.elapsedRealtime()
            while (scope.isActive) {
                if (samplesRead >= nextRotationAt) {
                    try {
                        val fresh = spotter.createStream()
                        if (streams.size < 2) streams.add(fresh)
                        else {
                            streams[nextToReplace].release()
                            streams[nextToReplace] = fresh
                            nextToReplace = 1 - nextToReplace
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not rotate wake stream", e)
                    }
                    nextRotationAt += RATE
                }
                val count = rec.read(buffer, 0, buffer.size)
                if (count <= 0) {
                    Log.w(TAG, "Wake microphone read failed: $count")
                    return null
                }
                samplesRead += count
                for (i in 0 until count) {
                    recent[cursor] = buffer[i]
                    cursor = (cursor + 1) % recent.size
                }
                written = minOf(written + count, recent.size)
                val waveform = FloatArray(count) { buffer[it] / 32768f }
                for ((index, stream) in streams.withIndex()) {
                    stream.acceptWaveform(waveform, RATE)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        if (spotter.getResult(stream).keyword == WAKE_WORD) {
                            val captured = if (written < recent.size) recent.copyOf(written)
                            else recent.copyOfRange(cursor, recent.size) + recent.copyOfRange(0, cursor)
                            val captureLagMs = SystemClock.elapsedRealtime() - recordingStarted - samplesRead * 1000 / RATE
                            val trimmed = trimPreroll(captured)
                            Log.i(TAG, "Wake capture lag=${captureLagMs}ms; stream=${index + 1}; preroll=${trimmed.size * 1000 / RATE}ms")
                            return trimmed
                        }
                    }
                }
            }
            return null
        } finally {
            recorder = null
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
            streams.forEach { it.release() }
        }
    }

    private suspend fun transcribeAndSubmit(preroll: ShortArray, wakeAt: Long) {
        val app = application as MikaApp
        if (app.voice.spec() !is SpeechEngineSpec.Provider && app.voice.spec() !is SpeechEngineSpec.Doubao) {
            Log.i(TAG, "Cloud speech recognition is not configured")
            notifyState(getString(R.string.wake_need_cloud), VoiceStatusOverlay.Phase.Error)
            delay(3000)
            return
        }
        notifyState(getString(R.string.wake_say_command), VoiceStatusOverlay.Phase.Listening)
        val session = withContext(Dispatchers.Main) { app.voice.startWithPreroll("zh-CN", preroll) }
        Log.i(TAG, "Cloud speech recording started")
        val terminal = CompletableDeferred<SpeechEvent>()
        val started = SystemClock.elapsedRealtime()
        val endpoint = VoiceCommandEndpoint(started)
        val collector = scope.launch {
            session.events.collect { event ->
                when (event) {
                    is SpeechEvent.Level -> endpoint.level(event.value, SystemClock.elapsedRealtime())
                    is SpeechEvent.Partial -> {
                        val words = commandAfterWake(event.stable + event.tail)
                        endpoint.partial(words, SystemClock.elapsedRealtime())
                        voiceOverlay?.transcript(words)
                    }
                    is SpeechEvent.SegmentFinal -> endpoint.providerSettled(commandAfterWake(event.text))
                    else -> Unit
                }
                if (event is SpeechEvent.Final || event is SpeechEvent.Failed) terminal.complete(event)
            }
        }
        try {
            var endedBy: VoiceCommandEndpoint.End? = null
            while (scope.isActive && !terminal.isCompleted) {
                delay(100)
                endedBy = endpoint.end(SystemClock.elapsedRealtime())
                if (endedBy != null) break
            }
            Log.i(TAG, "Cloud speech release after ${SystemClock.elapsedRealtime() - started}ms; reason=$endedBy; peak level=${endpoint.peak}")
            session.release()
            val releasedAt = SystemClock.elapsedRealtime()
            notifyState(getString(R.string.wake_transcribing), VoiceStatusOverlay.Phase.Transcribing)
            when (val result = withTimeoutOrNull(35_000) { terminal.await() }) {
                is SpeechEvent.Final -> {
                    Log.i(TAG, "Voice latency wake-to-record=${started - wakeAt}ms; recording=${releasedAt - started}ms; cloud-final=${SystemClock.elapsedRealtime() - releasedAt}ms")
                    val words = commandAfterWake(result.text)
                    Log.i(TAG, "Cloud speech completed; command length=${words.length}")
                    if (words.isNotEmpty()) {
                        notifyState(getString(R.string.wake_received, words.take(50)), VoiceStatusOverlay.Phase.Received)
                        submitVoiceCommand(words)
                    } else notifyState(getString(R.string.wake_no_match), VoiceStatusOverlay.Phase.Error)
                }
                else -> {
                    Log.w(TAG, "Cloud speech did not return a command: $result")
                    notifyState(getString(R.string.wake_transcribe_failed), VoiceStatusOverlay.Phase.Error)
                }
            }
        } finally {
            collector.cancel()
            session.cancel()
            delay(1500)
        }
    }

    private fun submitVoiceCommand(words: String) {
        val app = application as MikaApp
        val store = ConversationStore.shared(this)
        val prefs = getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)
        val explicitNewText = newVoiceTaskText(words)
        if (explicitNewText != null && explicitNewText.isEmpty()) {
            prefs.edit().putBoolean(NEW_TASK_NEXT, true).apply()
            notifyState(getString(R.string.wake_new_task_ready), VoiceStatusOverlay.Phase.Attention)
            return
        }
        val command = explicitNewText ?: words
        val forceNew = explicitNewText != null || prefs.getBoolean(NEW_TASK_NEXT, false)
        val savedId = prefs.getString(LAST_VOICE_CONVERSATION, null)
        val current = store.currentChat()
        val openId = (current as? CurrentChat.Open)?.id
        val candidates = listOfNotNull(openId, savedId).distinct()
        // An active question or task wins. A manual blank chat and “新任务” deliberately start fresh.
        val activeId = candidates.firstOrNull { app.taskState.run(it)?.state?.value is TaskState.WaitingInput }
            ?: candidates.firstOrNull { app.taskState.run(it)?.state?.value?.isActive() == true }
        val savedAvailable = savedId != null &&
            (app.taskState.run(savedId) != null || store.latestTurn(savedId) != null)
        val openFollowUp = openId?.let {
            val latest = store.latestTurn(it)
            isVoiceFollowUp(latest?.first, latest?.second ?: 0L, System.currentTimeMillis(), command)
        } == true
        val conversationId = voiceConversationId(current, savedId, activeId, savedAvailable, openFollowUp, forceNew)
        val run = conversationId?.let(app.taskState::run)
        val latest = if (run == null || run.state.value is TaskState.Terminal) conversationId?.let(store::latestTurn) else null
        val followUp = conversationId != null &&
            (conversationId == savedId || isVoiceFollowUp(latest?.first, latest?.second ?: 0L, System.currentTimeMillis(), command))
        val pending = when (val route = voiceRoute(conversationId, run?.key, run?.state?.value, followUp)) {
            VoiceConversationRoute.New -> {
                val fresh = store.newConversation()
                store.setCurrent(CurrentChat.Open(fresh))
                PendingVoice.Start(command, fresh)
            }
            is VoiceConversationRoute.Continue -> PendingVoice.Start(command, route.conversationId)
            is VoiceConversationRoute.Answer -> PendingVoice.Answer(command, route.runKey)
            is VoiceConversationRoute.Steer -> PendingVoice.Steer(command, route.conversationId, UUID.randomUUID().toString())
            VoiceConversationRoute.NeedsConfirmation -> {
                notifyState(getString(R.string.wake_manual_confirmation), VoiceStatusOverlay.Phase.Attention)
                return
            }
            VoiceConversationRoute.NeedsTakeover -> {
                notifyState(getString(R.string.wake_manual_takeover), VoiceStatusOverlay.Phase.Attention)
                return
            }
        }
        val routedId = when (pending) {
            is PendingVoice.Start -> pending.conversationId
            is PendingVoice.Steer -> pending.conversationId
            is PendingVoice.Answer -> conversationId
        }
        routedId?.let { prefs.edit().putString(LAST_VOICE_CONVERSATION, it)
            .putBoolean(NEW_TASK_NEXT, false).apply() }
        try {
            when (pending) {
                is PendingVoice.Start -> TaskService.start(this, pending.text, pending.conversationId)
                is PendingVoice.Answer -> TaskService.answer(this, pending.text, pending.runKey)
                is PendingVoice.Steer -> TaskService.voiceSteer(this, pending.text, pending.conversationId, pending.messageId)
            }
            pendingVoice = null
            routedId?.let { watchTask(it, command) }
            Log.i(TAG, "Voice command routed as ${pending.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Voice command needs a notification tap", e)
            pendingVoice = pending
            notifyState(getString(R.string.wake_tap_execute), VoiceStatusOverlay.Phase.Attention)
        }
    }

    private fun watchTask(conversationId: String, command: String) {
        taskWatch?.cancel()
        voiceOverlay?.taskStarting(command)
        val taskState = (application as MikaApp).taskState
        val oldTerminalKey = taskState.run(conversationId)?.takeIf { it.state.value is TaskState.Terminal }?.key
        taskWatch = scope.launch {
            var observedKey: String? = null
            var observedRun: com.wangzan101.mika.task.TaskRun? = null
            var stateWatch: Job? = null
            val seenRun = AtomicBoolean(false)
            val startTimeout = launch {
                delay(15_000)
                if (!seenRun.get()) voiceOverlay?.taskMissing()
            }
            try {
                taskState.runs.collect { runs ->
                    val run = runs.lastOrNull { it.conversationId == conversationId && it.key != oldTerminalKey }
                    if (run?.key == observedKey) return@collect
                    val ended = observedRun?.state?.value
                    stateWatch?.cancel()
                    if (ended is TaskState.Terminal) voiceOverlay?.taskState(ended)
                    else if (observedRun != null && run == null) voiceOverlay?.taskMissing()
                    observedRun = run
                    observedKey = run?.key
                    if (run != null) {
                        seenRun.set(true)
                        startTimeout.cancel()
                        stateWatch = launch { run.state.collect { voiceOverlay?.taskState(it) } }
                    }
                }
            } finally { stateWatch?.cancel(); startTimeout.cancel() }
        }
    }

    /** Silence outside the utterance costs real time on the streaming ASR connection. */
    private fun trimPreroll(samples: ShortArray): ShortArray {
        val frame = RATE / 10
        val levels = ArrayList<Double>((samples.size + frame - 1) / frame)
        for (start in samples.indices step frame) {
            val end = minOf(start + frame, samples.size)
            var energy = 0.0
            for (i in start until end) energy += samples[i].toDouble() * samples[i]
            levels.add(kotlin.math.sqrt(energy / (end - start)))
        }
        val noiseFloor = levels.sorted()[levels.size / 5]
        val activeThreshold = maxOf(200.0, noiseFloor * 2.5)
        var first = -1
        var last = -1
        for ((index, level) in levels.withIndex()) {
            val start = index * frame
            val end = minOf(start + frame, samples.size)
            if (level >= activeThreshold) {
                if (first < 0) first = start
                last = end
            }
        }
        if (first < 0) return samples
        return samples.copyOfRange(
            maxOf(0, first - RATE / 2),
            minOf(samples.size, maxOf(last + RATE / 2, samples.size - RATE * 2)),
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wake_channel), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.wake_channel))
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.wake_stop), stop).build())
        pendingVoice?.let { pending ->
            val action = when (pending) {
                is PendingVoice.Start -> TaskService.voiceAction(this, pending.text, pending.conversationId)
                is PendingVoice.Answer -> TaskService.voiceAnswerAction(this, pending.text, pending.runKey)
                is PendingVoice.Steer -> TaskService.voiceSteerAction(this, pending.text, pending.conversationId, pending.messageId)
            }
            builder.addAction(Notification.Action.Builder(null, getString(R.string.wake_run), action).build())
        }
        return builder.build()
    }

    private fun notifyState(message: String, phase: VoiceStatusOverlay.Phase) {
        getSystemService(NotificationManager::class.java).notify(ID, notification(message))
        voiceOverlay?.show(phase, message)
    }

    override fun onDestroy() {
        active.value = false
        job?.cancel()
        taskWatch?.cancel()
        voiceOverlay?.close()
        voiceOverlay = null
        scope.cancel()
        recorder?.let { try { it.stop() } catch (_: IllegalStateException) {} }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MikaWake"
        private const val PREROLL_SECONDS = 3
        private const val CHANNEL = "mika_voice_wake"
        private const val ID = 19001
        private const val RATE = 16000
        private const val WAKE_WORD = "你好米卡"
        private const val ACTION_STOP = "com.wangzan101.mika.voice.STOP_WAKE"
        private const val REQUEST_PREFS = "mika_voice_wake"
        private const val REQUESTED = "requested"
        private const val LAST_VOICE_CONVERSATION = "last_conversation"
        private const val NEW_TASK_NEXT = "new_task_next"
        val active = MutableStateFlow(false)

        private fun requested(context: Context): Boolean =
            context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE).getBoolean(REQUESTED, false)

        private fun setRequested(context: Context, enabled: Boolean) =
            context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE).edit().putBoolean(REQUESTED, enabled).apply()

        fun start(context: Context) {
            setRequested(context, true)
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            setRequested(context, false)
            if (active.value) context.startService(Intent(context, WakeWordService::class.java).setAction(ACTION_STOP))
        }

        /** Only resumes from a visible activity; no boot receiver starts microphone capture. */
        fun restoreIfRequested(context: Context) {
            if (requested(context) && !active.value &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                context.startForegroundService(Intent(context, WakeWordService::class.java))
            }
        }
    }
}
