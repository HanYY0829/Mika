package com.wangzan101.mika.settings

/**
 * 强调色(主按钮、选中、开关、用户消息、链接):默认蓝色,和深浅色([ThemeMode])任意搭配。
 * 不提供绿、橙、红:它们已经表示成功、需要你、出错。各色的取值见 `ui/theme/Colors.kt` 的 `palette`。
 */
enum class ThemeAccent {
    Blue, Indigo, Purple, Pink, Teal, Graphite;

    companion object {
        fun parse(s: String?): ThemeAccent = entries.firstOrNull { it.name == s } ?: Blue
    }
}
