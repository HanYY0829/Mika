package com.wangzan101.mika.capsule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme

/** A genuinely half-width window: the invisible half cannot block the app underneath. */
@Composable
fun CapsuleEdgeContent(side: DockSide, alpha: Float, onReveal: () -> Unit) {
    val colors = MikaTheme.colors
    Box(Modifier.width(20.dp).padding(vertical = 8.dp).height(40.dp)
        .testTag("capsule-edge").clipToBounds().alpha(alpha)
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.capsule_edge_reveal), onClick = onReveal),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(if (side == DockSide.Left) 0f else size.width, size.height / 2)
            drawCircle(colors.glass, radius = size.height / 2, center = center)
            drawCircle(colors.ink2, radius = size.height / 2 - 1.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
        }
        Icon(MikaIcons.Chevron, stringResource(R.string.capsule_edge_reveal),
            Modifier.size(12.dp).graphicsLayer { rotationZ = if (side == DockSide.Right) 180f else 0f }, tint = colors.ink)
    }
}
