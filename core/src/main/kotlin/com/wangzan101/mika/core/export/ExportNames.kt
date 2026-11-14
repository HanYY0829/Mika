package com.wangzan101.mika.core.export

/** 文件名与包内路径的清洗;规则和现有分享/附件保持同一方向,但这里不依赖沙箱能力包。 */
object ExportNames {
    const val MAX_NAME = 120

    fun safeFileName(name: String, fallback: String = "attachment"): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '/'.code || cp == '\\'.code) continue
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt()) continue
            sb.appendCodePoint(cp)
        }
        val cleaned = sb.toString().trim().trimStart('.').ifEmpty { fallback }
        return fit(cleaned)
    }

    /** 超长时保留扩展名,按字符截,不切半个码点。 */
    fun fit(name: String): String {
        if (name.codePointCount(0, name.length) <= MAX_NAME) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        val room = (MAX_NAME - ext.codePointCount(0, ext.length)).coerceAtLeast(1)
        val end = base.offsetByCodePoints(0, minOf(room, base.codePointCount(0, base.length)))
        return base.substring(0, end).trimEnd() + ext
    }

    /** 同名时加「 (2)」「 (3)」……,扩展名留在最后。 */
    fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (used.add(candidate)) return candidate
            n += 1
        }
    }

    /** zip 条目只接受相对路径;拒绝绝对路径、反斜杠、`.`/`..` 段、盘符和空段。 */
    fun entryPath(path: String): String {
        require(path.isNotBlank()) { "empty entry path" }
        require(!path.startsWith("/") && !path.startsWith("\\")) { "absolute entry path: $path" }
        require('\\' !in path) { "backslash in entry path: $path" }
        require(':' !in path.substringBefore('/')) { "drive or scheme in entry path: $path" }
        for (part in path.split('/')) {
            require(part.isNotEmpty() && part != "." && part != "..") { "bad entry path: $path" }
        }
        return path
    }
}
