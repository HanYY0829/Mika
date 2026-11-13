package com.wangzan101.mika.selfcheck

import java.util.Locale

/**
 * 自检认自检页上的文字时用的比法。读屏给的文字不一定和页面上写的一字不差:按钮默认会被系统主题显示成全大写,
 * 读屏读到的就是大写;空白也可能多一个、少一个,或者换成不断行空格。所以先把两边都规整一遍再比:
 * 前后空白去掉、中间的空白并成一个普通空格、大小写不分。
 */
internal object SelfCheckText {
    private val SPACES = Regex("[\\s\\p{Z}]+")

    /**
     * 把任意十进制数字变体统一成 ASCII 数字:阿拉伯-印度数字(٠-٩)、扩展阿拉伯-印度数字(۰-۹)、全角数字等。
     * 读屏给的文字在阿拉伯语等语言里会把数字按本地写法报回来,而页面模板和别处代码按 ASCII 数字算,不统一就认不上。
     */
    fun digitsToAscii(s: String): String = buildString(s.length) {
        for (c in s) {
            val d = Character.digit(c, 10)
            append(if (d >= 0) ('0' + d) else c)
        }
    }

    fun normalize(s: String): String = digitsToAscii(s).replace(SPACES, " ").trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    fun contains(text: String, part: String): Boolean = normalize(text).contains(normalize(part))

    /**
     * 带一个数字的文字(「点了 %d 次」「计时 %d」「第 %d 行」)。[template] 是把数字换成 [sentinel] 后的整句;
     * 认的时候数字前后两段照上面的比法比,中间只能是数字。
     */
    class Numbered(template: String, sentinel: String) {
        private val parts = normalize(template).split(normalize(sentinel))

        fun matches(s: String): Boolean {
            if (parts.size != 2) return false
            val (head, tail) = parts
            val n = normalize(s)
            if (n.length <= head.length + tail.length || !n.startsWith(head) || !n.endsWith(tail)) return false
            return n.substring(head.length, n.length - tail.length).all(Char::isDigit)
        }
    }
}
