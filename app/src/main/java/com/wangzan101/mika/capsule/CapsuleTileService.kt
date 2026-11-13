package com.wangzan101.mika.capsule

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

/** System-bound only; changes the existing capsule preference, never system permissions or tasks. */
class CapsuleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private val flags get() = FeatureFlags.get(this)

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            combine(flags.capsuleModeChanges(), flags.quitChanges(), ScreenAccessibility.instance) { mode, quit, service ->
                Triple(mode, quit, service != null)
            }.collect { (mode, quit, ready) -> refresh(mode, quit, ready) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::toggle) else toggle()
    }

    private fun toggle() {
        when (CapsuleTilePolicy.action(flags.capsuleMode(), ScreenAccessibility.instance.value != null, flags.quitRequested())) {
            CapsuleTileAction.TurnOff, CapsuleTileAction.TurnOn -> flags.toggleCapsule()
            CapsuleTileAction.OpenApp -> {
                Toast.makeText(this, tr(R.string.capsule_tile_setup), Toast.LENGTH_LONG).show()
                val intent = TaskService.openAppIntent(this)
                if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(
                    this, 7410, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            }
        }
        refresh(flags.capsuleMode(), flags.quitRequested(), ScreenAccessibility.instance.value != null)
    }

    private fun refresh(mode: CapsuleMode, quit: Boolean, ready: Boolean) {
        val tile = qsTile ?: return
        tile.label = tr(R.string.capsule_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.state = if (mode != CapsuleMode.Off && !quit && ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = tr(when {
            mode == CapsuleMode.Off -> R.string.capsule_mode_off
            quit || !ready -> R.string.capsule_tile_setup
            mode == CapsuleMode.DuringTasks -> R.string.capsule_mode_tasks
            else -> R.string.capsule_mode_always
        })
        tile.updateTile()
    }

    companion object {
        private fun component(context: Context) = ComponentName(context, CapsuleTileService::class.java)

        /** The manifest default is disabled, so users opt in before Android lists the tile. */
        fun isAvailable(context: Context): Boolean =
            context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

        fun setAvailable(context: Context, available: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (available) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (available) runCatching { TileService.requestListeningState(context, component(context)) }
        }
    }
}
