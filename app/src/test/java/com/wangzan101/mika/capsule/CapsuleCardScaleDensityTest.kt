package com.wangzan101.mika.capsule

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁定缩放用的 [Density] 构造:卡片缩放靠换 LocalDensity 实现,dp 和 sp 必须等比放大,
 * 哪个分量起作用、起多大,以这里的实测为准(Compose 的换算公式没有公开承诺)。
 */
class CapsuleCardScaleDensityTest {
    private val base = Density(2.75f, 1.3f)

    private fun dpRatio(zoomed: Density): Float = with(zoomed) { 100.dp.toPx() } / with(base) { 100.dp.toPx() }
    private fun spRatio(zoomed: Density): Float = with(zoomed) { 100.sp.toPx() } / with(base) { 100.sp.toPx() }

    @Test fun zoomScalesDpAndSpByTheSameFactor() {
        val zoomed = base.zoomed(1.3f)
        assertEquals(1.3f, dpRatio(zoomed), 0.001f)
        assertEquals(dpRatio(zoomed), spRatio(zoomed), 0.001f)
    }
}
