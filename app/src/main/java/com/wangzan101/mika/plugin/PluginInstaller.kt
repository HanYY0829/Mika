package com.wangzan101.mika.plugin

import android.content.ContentResolver
import android.net.Uri
import com.wangzan101.mika.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

/** 安装失败的原因编码;界面据此显示对应文案。 */
class PluginInstallException(
    val code: String,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code, cause)

object PluginErrors {
    const val INVALID_PACKAGE = "invalid_package"
    const val UNSAFE_PATH = "unsafe_path"
    const val TOO_LARGE = "too_large"
    const val MISSING_MANIFEST = "missing_manifest"
    const val INVALID_MANIFEST = "invalid_manifest"
    const val IO = "io"
}

/**
 * 把插件 zip 安全地装进 `files/plugins/<id>/`:
 * 防 zip-slip/绝对路径/反斜杠/超量解压,先解到 `.staging`,校验完再原子换入;
 * 同 id 升级时旧目录先移到 `.trash`,换入失败可回滚。
 */
class PluginInstaller(
    private val filesDir: File,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val cacheDir: File = File(filesDir.parentFile ?: filesDir, "cache"),
) {
    private val pluginsDir: File get() = File(filesDir, "plugins")

    /** SAF 选中的 zip:先复制到缓存,再走 [installZip]。复制/解析都在调用方的 IO 线程上。 */
    fun install(uri: Uri, resolver: ContentResolver): Result<PluginRecord> = runCatching {
        val dir = File(cacheDir, "plugin-install").apply { mkdirs() }
        val temp = File(dir, "${UUID.randomUUID()}.zip")
        try {
            val input = resolver.openInputStream(uri)
                ?: throw PluginInstallException(PluginErrors.IO, "cannot open the selected file")
            input.use { from -> temp.outputStream().use { from.copyTo(it) } }
            if (temp.length() > maxBytes) throw PluginInstallException(PluginErrors.TOO_LARGE, "zip is too large")
            installZipOrThrow(temp)
        } finally {
            temp.delete()
        }
    }

    /** 测试与内部使用的入口:直接装一个本地 zip 文件。 */
    fun installZip(zip: File): Result<PluginRecord> = runCatching { installZipOrThrow(zip) }

    private fun installZipOrThrow(zip: File): PluginRecord {
        pluginsDir.mkdirs()
        val token = UUID.randomUUID().toString()
        val staging = File(File(pluginsDir, STAGING), token)
        val trash = File(File(pluginsDir, TRASH), token)
        var manifest: PluginManifest? = null
        try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                if (entries.isEmpty()) throw PluginInstallException(PluginErrors.MISSING_MANIFEST, "zip is empty")
                if (entries.size > MAX_ENTRIES) throw PluginInstallException(PluginErrors.TOO_LARGE, "too many files")
                for (e in entries) checkName(e.name)
                val manifestEntry = entries.firstOrNull { it.name == MANIFEST }
                    ?: throw PluginInstallException(PluginErrors.MISSING_MANIFEST, "manifest.json missing at the root")
                val text = zf.getInputStream(manifestEntry).use { it.readLimited(MANIFEST_LIMIT) }
                val parsed = try {
                    PluginManifest.parse(text)
                } catch (e: Exception) {
                    throw PluginInstallException(PluginErrors.INVALID_MANIFEST, "manifest.json is not valid", e)
                }
                val problems = parsed.validate(appVersion)
                if (problems.isNotEmpty()) {
                    throw PluginInstallException(problems.first(), "manifest rejected: ${problems.joinToString()}")
                }
                manifest = parsed

                staging.mkdirs()
                val base = staging.canonicalPath + File.separator
                var total = 0L
                for (e in entries) {
                    val name = checkName(e.name)
                    if (e.isDirectory || e.name.endsWith("/")) {
                        File(staging, name).mkdirs()
                        continue
                    }
                    val out = File(staging, name).canonicalFile
                    if (!out.path.startsWith(base)) throw PluginInstallException(PluginErrors.UNSAFE_PATH, "entry escapes the package: ${e.name}")
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { from ->
                        out.outputStream().use { to ->
                            total += copyLimited(from, to, maxBytes - total)
                        }
                    }
                }
            }

            val m = manifest!!
            for (s in m.skills) {
                if (!File(staging, "${s.path}/SKILL.md").isFile) {
                    throw PluginInstallException(PluginErrors.INVALID_MANIFEST, "skill ${s.name} has no SKILL.md")
                }
            }

            val target = File(pluginsDir, m.id)
            val replaced = target.exists()
            if (replaced) {
                trash.parentFile?.mkdirs()
                if (!target.renameTo(trash)) throw PluginInstallException(PluginErrors.IO, "could not move the old version aside")
            }
            if (!staging.renameTo(target)) {
                if (replaced) trash.renameTo(target)
                throw PluginInstallException(PluginErrors.IO, "could not move the new version into place")
            }
            if (trash.exists()) trash.deleteRecursively()
            return PluginRecord(
                id = m.id,
                name = m.name,
                version = m.version,
                enabled = true,
                installedAt = System.currentTimeMillis(),
                skillNames = m.skills.map { it.name },
                description = m.description,
                permissions = m.permissions,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** 拒绝绝对路径、反斜杠、盘符、`.`/`..` 段;返回去掉结尾 `/` 的相对路径。 */
    private fun checkName(raw: String): String {
        if (raw.isEmpty() || raw.startsWith("/") || raw.startsWith("\\")) {
            throw PluginInstallException(PluginErrors.UNSAFE_PATH, "absolute path: $raw")
        }
        if (raw.contains('\\') || raw.contains(':')) {
            throw PluginInstallException(PluginErrors.UNSAFE_PATH, "unsafe path: $raw")
        }
        val name = raw.removeSuffix("/")
        if (name.isEmpty()) throw PluginInstallException(PluginErrors.UNSAFE_PATH, "empty path")
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw PluginInstallException(PluginErrors.UNSAFE_PATH, "path escape: $raw")
        }
        return name
    }

    private fun InputStream.readLimited(limit: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw PluginInstallException(PluginErrors.TOO_LARGE, "manifest is too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 读 [remaining] 字节以内;超出抛 [PluginErrors.TOO_LARGE]。返回实际读取字节数。 */
    private fun copyLimited(input: InputStream, output: java.io.OutputStream, remaining: Long): Long {
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > remaining) throw PluginInstallException(PluginErrors.TOO_LARGE, "package is too large")
            output.write(buf, 0, n)
        }
        return total
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 20L * 1024 * 1024
        const val MANIFEST_LIMIT = 256 * 1024
        const val MAX_ENTRIES = 2000
        const val MANIFEST = "manifest.json"
        private const val STAGING = ".staging"
        private const val TRASH = ".trash"
    }
}
