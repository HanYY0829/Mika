package com.wangzan101.mika.ui.theme

import androidx.compose.ui.graphics.Color
import com.wangzan101.mika.settings.ThemeAccent
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 强调色只换强调色那三项;每种颜色上的字不比默认蓝色难认,放在页面上也看得清。 */
class AccentPaletteTest {
    @Test fun blueIsTheExistingPalette() {
        assertSame(LightColors, palette(dark = false, accent = ThemeAccent.Blue))
        assertSame(DarkColors, palette(dark = true, accent = ThemeAccent.Blue))
    }

    @Test fun otherAccentsOnlyReplaceAccentColors() {
        for (dark in listOf(false, true)) {
            val base = if (dark) DarkColors else LightColors
            for (accent in ThemeAccent.entries) {
                val c = palette(dark, accent)
                val (color, on) = accentTone(accent, dark)
                assertEquals(color, c.accent)
                assertEquals(on, c.onAccent)
                // 淡底和原来同样的透明度,颜色跟着换
                assertEquals(base.accentSoft.alpha, c.accentSoft.alpha, 0.002f)
                assertEquals(color.copy(alpha = 1f), c.accentSoft.copy(alpha = 1f))
                assertEquals(base.bg, c.bg)
                assertEquals(base.surface, c.surface)
                assertEquals(base.ink, c.ink)
                assertEquals(base.ok, c.ok)
                assertEquals(base.warn, c.warn)
                assertEquals(base.bad, c.bad)
                assertEquals(base.series, c.series)
                assertEquals(dark, c.isDark)
            }
        }
    }

    @Test fun textOnEveryAccentIsAtLeastAsLegibleAsOnDefaultBlue() {
        for (dark in listOf(false, true)) {
            val blue = palette(dark, ThemeAccent.Blue)
            val floor = contrast(blue.accent, blue.onAccent)
            for (accent in ThemeAccent.entries) {
                val c = palette(dark, accent)
                val ratio = contrast(c.accent, c.onAccent)
                assertTrue("$accent dark=$dark: ${"%.2f".format(ratio)} < ${"%.2f".format(floor)}", ratio >= floor - 0.01)
            }
        }
    }

    @Test fun everyAccentStandsOutFromThePage() {
        for (dark in listOf(false, true)) {
            for (accent in ThemeAccent.entries) {
                val c = palette(dark, accent)
                // WCAG 对图形元素和大字的最低要求;链接、选中标记都是强调色画在页面背景上
                val ratio = contrast(c.accent, c.bg)
                assertTrue("$accent dark=$dark: ${"%.2f".format(ratio)}", ratio >= 3.0)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** WCAG 2 相对亮度(sRGB)。 */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }
}
