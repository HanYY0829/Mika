package com.wangzan101.mika.capsule

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleCardScaleTest {
    @Test fun defaultsToNormal() {
        assertEquals(CapsuleCardScale.Normal, CapsuleCardScale.DEFAULT)
        assertEquals(1f, CapsuleCardScale.DEFAULT.factor, 0f)
    }

    @Test fun unknownPercentFallsBackToNormal() {
        assertEquals(CapsuleCardScale.Normal, CapsuleCardScale.parse(101))
        assertEquals(CapsuleCardScale.Normal, CapsuleCardScale.parse(0))
        assertEquals(CapsuleCardScale.XLarge, CapsuleCardScale.parse(130))
    }

    @Test fun normalKeepsTodaySWidth() {
        // 标准档与现状一致:放得下就 322dp,窄屏上让到屏宽−24dp,极窄不低于 240dp
        assertEquals(322.dp, CapsuleCardScale.Normal.width(600))
        assertEquals(322.dp, CapsuleCardScale.Normal.width(360))
        assertEquals(296.dp, CapsuleCardScale.Normal.width(320))
        assertEquals(240.dp, CapsuleCardScale.Normal.width(250))
    }

    @Test fun scaledWidthNeverLeavesTheSamePixelBounds() {
        for (s in CapsuleCardScale.entries) {
            // 渲染宽 = 原始 dp × factor,换算后任意档位都落在和标准档同一个等效区间:
            // [240dp, 屏宽−24dp];超窄屏(屏宽−24 < 240)上和现状一样按下限 240dp 算
            for (screen in listOf(250, 320, 360, 411, 600, 840)) {
                val rendered = s.width(screen) * s.factor
                val upper = maxOf(screen - 24, 240).dp
                assert(rendered <= upper + 0.01.dp) { "$s on $screen -> $rendered" }
                assert(rendered >= 240.dp - 0.01.dp) { "$s on $screen -> $rendered" }
            }
        }
    }

    @Test fun wideScreenKeepsFullCardWidthWhenItFits() {
        // 600dp 宽的屏(折叠屏展开)上,130% 渲染 322×1.3=418.6dp,仍放得下 576dp,所以不压宽
        assertEquals(322.dp, CapsuleCardScale.XLarge.width(600))
        // 360dp 窄屏上压到屏宽−24dp 的等效
        assertEquals(336.dp, CapsuleCardScale.XLarge.width(360) * CapsuleCardScale.XLarge.factor)
    }
}
