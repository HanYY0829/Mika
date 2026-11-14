package com.wangzan101.mika.cap.phone.shell

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Paths

/** Small Binder replies; paths are interpreted by the helper in its own mount namespace. */
internal object ShellFiles {
    private const val MAX_PATH = 4096
    private const val MAX_ENTRIES = 128

    private fun valid(path: String?) = path != null && path.startsWith('/') && path.length <= MAX_PATH && '\u0000' !in path
    private fun result(status: String) = Bundle().apply { putString("status", status) }

    private fun inside(path: String, root: String) = path == root || path.startsWith("$root/")

    private fun allowedPath(path: String, user: Int): Boolean =
        inside(path, "/sdcard") || inside(path, "/storage/emulated/$user")

    private fun checkedRoot(path: String, user: Int): String {
        if (!allowedPath(path, user)) throw SecurityException("outside shared storage")
        val root = java.io.File("/storage/emulated/$user").canonicalPath
        if (!inside(java.io.File(path).canonicalPath, root)) throw SecurityException("outside shared storage")
        return root
    }

    private fun actualFd(fd: java.io.FileDescriptor, root: String): String = ParcelFileDescriptor.dup(fd).use { copy ->
        val actual = java.io.File("/proc/self/fd/${copy.fd}").canonicalPath
        if (!inside(actual, root)) throw SecurityException("outside shared storage")
        actual
    }

    private fun checkFd(fd: java.io.FileDescriptor, root: String) { actualFd(fd, root) }

    fun list(path: String?, afterName: String?, limit: Int, user: Int, policy: DeviceFilePolicy): Bundle {
        if (!valid(path) || limit !in 1..MAX_ENTRIES ||
            (afterName != null && (afterName.length > MAX_PATH || '/' in afterName || '\u0000' in afterName))) return result("invalid")
        return try {
            val root = checkedRoot(path!!, user)
            if (!policy.allowsDirectory(java.io.File(path).canonicalPath)) return result("denied")
            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) return result("not_directory")
                if (!policy.allowsDirectory(actualFd(fd, root))) return result("denied")
                ParcelFileDescriptor.dup(fd).use { dir ->
                    val directory = "/proc/self/fd/${dir.fd}"
                    // Retain only the smallest limit+1 names after the cursor, even for huge directories.
                    val selected = java.util.PriorityQueue<String>(compareByDescending { it })
                    Files.newDirectoryStream(Paths.get(directory)).use { stream ->
                        for (entry in stream) {
                            val name = entry.fileName.toString()
                            if (afterName != null && name <= afterName) continue
                            selected.add(name)
                            if (selected.size > limit + 1) selected.poll()
                        }
                    }
                    val ordered = selected.sorted()
                    val names = ArrayList(ordered.take(limit))
                    val stats = names.map { name ->
                        try { Os.lstat(Paths.get(directory, name).toString()) } catch (_: ErrnoException) { null }
                    }
                    val types = stats.map { stat ->
                        when {
                            stat != null && OsConstants.S_ISREG(stat.st_mode) -> 1
                            stat != null && OsConstants.S_ISDIR(stat.st_mode) -> 2
                            else -> 0
                        }
                    }
                    result("ok").apply {
                        putStringArrayList("names", names)
                        putIntArray("types", types.toIntArray())
                        putLongArray("sizes", stats.map { it?.st_size?.takeIf { size -> size >= 0 } ?: -1L }.toLongArray())
                        putLongArray("modified", stats.map { it?.st_mtime?.takeIf { time -> time in 0..Long.MAX_VALUE / 1000 }?.times(1000) ?: -1L }.toLongArray())
                        putString("next", if (ordered.size > limit) names.last() else null)
                    }
                }
            } finally { Os.close(fd) }
        } catch (e: Exception) {
            result(error(e))
        }
    }

    fun search(path: String?, name: String?, extension: String?, after: Long, before: Long, depth: Int, budget: Int, timeout: Int, user: Int, policy: DeviceFilePolicy): Bundle {
        if (!valid(path) || depth !in 0..10 || budget !in 1..5000 || timeout !in 1..5000 ||
            (name?.length ?: 0) > 255 || (extension?.length ?: 0) > 255 || after < -1 || before < -1 ||
            (after >= 0 && before >= 0 && after > before)) return result("invalid")
        val start = android.os.SystemClock.elapsedRealtime()
        val matches = ArrayList<String>()
        var visited = 0
        var reason: String? = null
        return try {
            val root = checkedRoot(path!!, user)
            if (!policy.allowsDirectory(java.io.File(path).canonicalPath)) return result("denied")
            val base = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(base).st_mode)) return result("not_directory")
                val boundary = actualFd(base, root)
                if (!policy.allowsDirectory(boundary)) return result("denied")
                val effectiveDepth = if (policy.allowsDescendants(boundary)) depth else 0
                fun walk(fd: java.io.FileDescriptor, level: Int) {
                    ParcelFileDescriptor.dup(fd).use { dir ->
                        val prefix = "/proc/self/fd/${dir.fd}"
                        Files.newDirectoryStream(Paths.get(prefix)).use { stream ->
                            for (entry in stream) {
                                if (android.os.SystemClock.elapsedRealtime() - start >= timeout) { reason = "timeout"; return }
                                if (visited >= budget) { reason = "max_entries"; return }
                                visited++
                                val child = entry.toString()
                                val stat = Os.lstat(child)
                                val filename = entry.fileName.toString()
                                if (OsConstants.S_ISREG(stat.st_mode) &&
                                    (name.isNullOrEmpty() || filename.contains(name, ignoreCase = true)) &&
                                    (extension.isNullOrEmpty() || filename.endsWith("." + extension.trimStart('.'), ignoreCase = true)) &&
                                    (after < 0 || stat.st_mtime * 1000 >= after) && (before < 0 || stat.st_mtime * 1000 <= before)) {
                                    val resolved = java.io.File(child).canonicalPath
                                    if (!inside(resolved, boundary) || !policy.allowsFile(resolved)) throw SecurityException("outside authorized search directory")
                                    matches.add(resolved)
                                    if (matches.size >= 100) { reason = "max_results"; return }
                                }
                                if (OsConstants.S_ISDIR(stat.st_mode)) {
                                    if (level >= effectiveDepth) { reason = reason ?: "max_depth"; continue }
                                    val next = Os.open(child, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
                                    try {
                                        checkFd(next, boundary)
                                        if (!OsConstants.S_ISDIR(Os.fstat(next).st_mode)) throw SecurityException("directory changed")
                                        walk(next, level + 1)
                                    } finally { Os.close(next) }
                                    if (reason != null && reason != "max_depth") return
                                }
                            }
                        }
                    }
                }
                walk(base, 0)
                result("ok").apply { putStringArrayList("paths", matches); putInt("visited", visited); putBoolean("truncated", reason != null); putString("reason", reason) }
            } finally { Os.close(base) }
        } catch (e: Exception) { result(error(e)) }
    }

    fun open(path: String?, user: Int, policy: DeviceFilePolicy): Bundle {
        if (!valid(path)) return result("invalid")
        return try {
            val root = checkedRoot(path!!, user)
            val target = java.io.File(path).canonicalPath
            if (!policy.allowsFile(target) && !policy.allowsDirectory(target)) return result("denied")
            if (!OsConstants.S_ISREG(Os.stat(path).st_mode)) return result("not_file")
            // Recheck the opened object; NONBLOCK prevents a substituted FIFO from blocking.
            val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            try {
                if (!policy.allowsFile(actualFd(fd, root))) return result("denied")
                if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return result("not_file")
                result("ok").apply { putParcelable("fd", ParcelFileDescriptor.dup(fd)) }
            } finally {
                Os.close(fd)
            }
        } catch (e: Exception) {
            result(error(e))
        }
    }

    private fun error(e: Exception): String = when (e) {
        is DirectoryIteratorException -> error(e.cause ?: e)
        is NoSuchFileException -> "not_found"
        is AccessDeniedException, is SecurityException -> "denied"
        is NotDirectoryException -> "not_directory"
        is ErrnoException -> when (e.errno) {
            OsConstants.ENOENT -> "not_found"
            OsConstants.EACCES, OsConstants.EPERM -> "denied"
            OsConstants.ENOTDIR -> "not_directory"
            else -> "io_error"
        }
        is IOException -> "io_error"
        else -> "io_error"
    }
}
