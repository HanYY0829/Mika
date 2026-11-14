package com.wangzan101.mika.ui.components

import android.graphics.Insets
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** The root can be offset below the status bar while retaining the full display height. */
@Composable
internal fun ActivitySizedDialog(
    onDismiss: () -> Unit,
    dismissOnClickOutside: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val windowManager = context.getSystemService(WindowManager::class.java)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // WindowMetrics 是安卓 11 起才有的;更早的系统用真实显示尺寸,系统栏留白取自 Compose 的兼容实现。
    val bounds: Rect
    val system: Insets
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = windowManager.currentWindowMetrics
        bounds = metrics.bounds
        system = metrics.windowInsets.getInsetsIgnoringVisibility(
            AndroidWindowInsets.Type.systemBars() or AndroidWindowInsets.Type.displayCutout(),
        )
    } else {
        @Suppress("DEPRECATION")
        val size = Point().also { windowManager.defaultDisplay.getRealSize(it) }
        bounds = Rect(0, 0, size.x, size.y)
        val bars = WindowInsets.systemBars
        system = Insets.of(
            bars.getLeft(density, layoutDirection),
            bars.getTop(density),
            bars.getRight(density, layoutDirection),
            bars.getBottom(density),
        )
    }
    var insets by remember { mutableStateOf(system) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        content(
            Modifier.onGloballyPositioned { coordinates ->
                val position = coordinates.positionOnScreen()
                if (position.x.isFinite() && position.y.isFinite()) {
                    val left = position.x.roundToInt()
                    val top = position.y.roundToInt()
                    insets = Insets.of(
                        (bounds.left + system.left - left).coerceAtLeast(0),
                        (bounds.top + system.top - top).coerceAtLeast(0),
                        (left + coordinates.size.width - bounds.right + system.right).coerceAtLeast(0),
                        (top + coordinates.size.height - bounds.bottom + system.bottom).coerceAtLeast(0),
                    )
                }
            }.padding(WindowInsets(insets.left, insets.top, insets.right, insets.bottom)
                .union(WindowInsets.ime).asPaddingValues()),
        )
    }
}
