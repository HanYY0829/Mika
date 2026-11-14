package com.wangzan101.mika.core.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 暂存目录与最终包之间的那一步:清单、校验和、zip。 */
object ExportPackage {
    const val MANIFEST = "manifest.json"

    /** 暂存目录里除清单以外的条目,按路径排序;链接不跟着走。 */
    fun contentEntries(root: File): List<ManifestEntry> = files(root)
        .filter { it.relativeTo(root).invariantSeparatorsPath != MANIFEST }
        .map { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            ManifestEntry(ExportNames.entryPath(path), file.length(), sha256(file))
        }

    fun writeManifest(root: File, manifest: ExportManifest) {
        root.mkdirs()
        File(root, MANIFEST).writeText(ExportManifestJson.encode(manifest))
    }

    /** 暂存目录整个打成 zip;清单必须已经写好。 */
    fun zip(root: File, out: File) {
        out.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
            for (file in files(root)) {
                val path = ExportNames.entryPath(file.relativeTo(root).invariantSeparatorsPath)
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** 单文件输出:暂存里除清单外只有一个文件时复制它。 */
    fun copySingle(root: File, out: File): Boolean {
        val single = files(root).filter { it.relativeTo(root).invariantSeparatorsPath != MANIFEST }
        if (single.size != 1) return false
        out.parentFile?.mkdirs()
        single.single().copyTo(out, overwrite = true)
        return true
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 只收普通文件;符号链接不跟,目录不递归进去再绕回来。 */
    private fun files(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
    }
}
