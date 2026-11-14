package com.wangzan101.mika.core.export.markdown

import java.net.URLEncoder

/** 导出 Markdown 时的转义:普通文字当纯文本,链接目的地单独处理。 */
internal object MarkdownText {
    private val SPECIAL = setOf('\\', '`', '*', '_', '[', ']', '<', '>', '|', '#', '!')

    /** 用户原话、标题、说明这类纯文本:转义会被 Markdown 解释成结构或 HTML 的字符。 */
    fun plain(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch in SPECIAL) append('\\')
            append(ch)
        }
    }

    /** 行内代码:用比内容里最长反引号串更长的围栏,内容里的反引号不会提前结束。 */
    fun codeSpan(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(1, longest + 1))
        return "$fence$text$fence"
    }

    /** 只允许 http/https;用尖括号包住,免得地址里的括号/空格把链接提前结束。 */
    fun destination(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) return null
        val safe = trimmed.replace("<", "%3C").replace(">", "%3E").replace("\r", "").replace("\n", "%0A")
        return "<$safe>"
    }

    /** 包内资产路径:文件名按 UTF-8 百分号编码,括号等不会跳出链接。 */
    fun assetPath(fileName: String): String = "assets/" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")

    /** 工具输出可能含任意长度的反引号串;围栏至少比最长的一串多一个。 */
    fun codeFence(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longest + 1))
    }

    /**
     * 回答正文保留 Markdown,但围栏代码块以外不原样放行 HTML:
     * 模型输出可能被网页内容引导,导出的文件又会被别的渲染器打开。
     * 围栏只认 0–3 个空格缩进(CommonMark);行内代码里的 `<` 不处理,免得把代码显示改坏。
     */
    fun safeHtml(text: String): String {
        val out = StringBuilder(text.length)
        var fenceChar: Char? = null
        var fenceLength = 0
        for (line in text.lineSequence()) {
            val trimmed = line.dropWhile { it == ' ' }
            val indent = line.length - trimmed.length
            val current = fenceChar
            if (current == null) {
                val open = openingFence(trimmed, indent)
                if (open != null) {
                    fenceChar = open.first
                    fenceLength = open.second
                    out.append(line).append('\n')
                } else {
                    out.append(escapeInlineHtml(line)).append('\n')
                }
            } else if (closesFence(trimmed, indent, current, fenceLength)) {
                fenceChar = null
                fenceLength = 0
                out.append(line).append('\n')
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }

    private fun openingFence(trimmed: String, indent: Int): Pair<Char, Int>? {
        if (indent > 3 || trimmed.isEmpty()) return null
        val ch = trimmed[0]
        if (ch != '`' && ch != '~') return null
        var length = 0
        while (length < trimmed.length && trimmed[length] == ch) length++
        if (length < 3) return null
        // 反引号围栏的 info string 里不能再有反引号,否则它不是围栏,不能拿它来关闭转义。
        if (ch == '`' && '`' in trimmed.substring(length)) return null
        return ch to length
    }

    private fun closesFence(trimmed: String, indent: Int, ch: Char, length: Int): Boolean {
        if (indent > 3) return false
        var n = 0
        while (n < trimmed.length && trimmed[n] == ch) n++
        return n >= length && trimmed.substring(n).isBlank()
    }

    /** 行内代码里的 `<` 原样保留;代码以外的 `<` 转义,避免形成 HTML 标签。 */
    private fun escapeInlineHtml(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (line[i] == '`') {
                var run = 1
                while (i + run < line.length && line[i + run] == '`') run++
                val marker = "`".repeat(run)
                val close = line.indexOf(marker, i + run)
                if (close >= 0) {
                    out.append(line, i, close + run)
                    i = close + run
                    continue
                }
            }
            val ch = line[i]
            if (ch == '<') out.append("&lt;") else out.append(ch)
            i++
        }
        return out.toString()
    }
}
