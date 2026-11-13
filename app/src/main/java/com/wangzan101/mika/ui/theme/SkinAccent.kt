package com.wangzan101.mika.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 从背景图片里取强调色:先找图上最主要的鲜艳颜色的色相([hue]),
 * 再按深浅色调出一个上面放白字和放在页面上都看得清的颜色([tone])。纯计算,不碰安卓。
 */
object SkinAccent {
    /** 鲜艳的像素至少占这么多,才算图上有主色;不到就当是灰图,不跟着图片取色。 */
    private const val MIN_SHARE = 0.05
    private const val BUCKETS = 36

    /** [pixels] 是 ARGB。图基本是灰的(或全透明)时为 null。 */
    fun hue(pixels: IntArray): Float? {
        val weight = DoubleArray(BUCKETS)
        val count = IntArray(BUCKETS)
        var total = 0
        for (p in pixels) {
            if ((p ushr 24 and 0xFF) < 128) continue
            total++
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val s = if (hi == 0f) 0f else (hi - lo) / hi
            if (s < 0.25f || hi < 0.2f) continue
            val bucket = (hueOf(r, g, b, hi, lo) / (360f / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
            weight[bucket] += (s * hi).toDouble()
            count[bucket]++
        }
        if (total == 0) return null
        // 相邻三格合起来比,正好落在两格交界的颜色不会被分散
        fun at(i: Int) = (i + BUCKETS) % BUCKETS
        val best = (0 until BUCKETS).maxBy { weight[at(it - 1)] + weight[it] + weight[at(it + 1)] }
        val share = (count[at(best - 1)] + count[best] + count[at(best + 1)]).toDouble() / total
        if (share < MIN_SHARE) return null
        // 三格的加权平均,以中间一格为准处理 0/360 的接缝
        var sum = 0.0
        var w = 0.0
        for (d in -1..1) {
            val i = at(best + d)
            sum += (best + d + 0.5) * (360.0 / BUCKETS) * weight[i]
            w += weight[i]
        }
        return (((sum / w) % 360 + 360) % 360).toFloat()
    }

    /**
     * 用色相 [hue] 做强调色,返回(强调色, 上面的文字色)。
     * 上面白字的对比度不低于默认蓝色,和页面背景的对比度不低于 3:1;实在调不出来时用默认蓝色。
     */
    fun tone(hue: Float, dark: Boolean): Pair<Color, Color> {
        val base = if (dark) DarkColors else LightColors
        val floor = contrast(base.accent, base.onAccent)
        // 从亮往暗找第一个白字够清楚的;深色下再要求比页面背景亮得够
        var l = if (dark) 0.70f else 0.60f
        while (l >= 0.15f) {
            val c = Color.hsl(hue, 0.7f, l)
            if (contrast(c, Color.White) >= floor && contrast(c, base.bg) >= 3.0) return c to Color.White
            l -= 0.01f
        }
        return base.accent to base.onAccent
    }

    private fun hueOf(r: Float, g: Float, b: Float, hi: Float, lo: Float): Float {
        val d = hi - lo
        if (d == 0f) return 0f
        val h = when (hi) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return (h + 360f) % 360f
    }
}

/** WCAG 2 对比度。 */
internal fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** WCAG 2 相对亮度(sRGB)。 */
internal fun luminance(c: Color): Double {
    fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}
