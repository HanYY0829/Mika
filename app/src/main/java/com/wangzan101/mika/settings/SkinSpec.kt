package com.wangzan101.mika.settings

/** 背景图片的模糊程度。 */
enum class SkinBlur {
    Off, Light, Strong;

    companion object {
        fun parse(s: String?): SkinBlur = entries.firstOrNull { it.name == s } ?: Light
    }
}

/**
 * 背景图片(皮肤)的设置。图片本身在 `files/skin/`(见 `ui/theme/SkinImage.kt`),这里只是怎么显示它。
 *
 * - [version]:0 是没有图(纯色底),每换一张图换一个新值,界面据此重新读图。
 * - [scrim]:盖在图上的页面底色遮罩,百分比,限定在 [MIN_SCRIM]–[MAX_SCRIM],文字靠它看得清。
 * - [focus]:图片裁剪时露出的位置,-1 最上、0 居中、1 最下。
 * - [accentFromImage] 打开且导入时取到了颜色([hue],色相 0–360)时,强调色跟着图片走,不用设置里选的。
 */
data class SkinSpec(
    val version: Long = 0L,
    val scrim: Int = DEFAULT_SCRIM,
    val blur: SkinBlur = SkinBlur.Light,
    val focus: Float = 0f,
    val accentFromImage: Boolean = true,
    val hue: Float? = null,
) {
    val active: Boolean get() = version != 0L

    /** 此刻要用图片的颜色做强调色时是它的色相,否则为 null。 */
    val accentHue: Float? get() = hue.takeIf { active && accentFromImage }

    companion object {
        const val MIN_SCRIM = 75
        const val MAX_SCRIM = 92
        const val DEFAULT_SCRIM = 82
    }
}
