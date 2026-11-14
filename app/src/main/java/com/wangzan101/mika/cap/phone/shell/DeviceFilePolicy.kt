package com.wangzan101.mika.cap.phone.shell

import java.io.File

/** The helper keeps canonical approved roots, independent of the paths supplied on each call. */
internal class DeviceFilePolicy internal constructor(private val roots: List<Root>) {
    internal data class Root(val path: String, val recursive: Boolean)

    fun allowsDirectory(actual: String): Boolean = roots.any { root ->
        actual == root.path || (root.recursive && actual.startsWith(root.path + "/"))
    }

    fun allowsFile(actual: String): Boolean = roots.any { root ->
        val parent = actual.substringBeforeLast('/', missingDelimiterValue = "")
        parent == root.path || (root.recursive && actual.startsWith(root.path + "/"))
    }

    fun allowsDescendants(actual: String): Boolean = roots.any { root ->
        root.recursive && (actual == root.path || actual.startsWith(root.path + "/"))
    }

    companion object {
        val EMPTY = DeviceFilePolicy(emptyList())

        /** Fail the entire update if any entry is invalid; no partial policy is installed. */
        fun create(paths: Array<out String>?, recursive: BooleanArray?, user: Int): DeviceFilePolicy {
            require(paths != null && recursive != null && paths.size == recursive.size && paths.size <= 32)
            val shared = File("/storage/emulated/$user").canonicalPath
            val roots = paths.mapIndexed { i, path ->
                require(path.length in 1..1024 && '\u0000' !in path && path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
                    (path == "/sdcard" || path.startsWith("/sdcard/") || path == "/storage/emulated/$user" || path.startsWith("/storage/emulated/$user/")))
                val prefix = if (path == "/sdcard" || path.startsWith("/sdcard/")) "/sdcard" else "/storage/emulated/$user"
                val expected = shared + path.removePrefix(prefix)
                val canonical = File(path).canonicalPath
                // The platform's /sdcard alias is expected; links below it must not redirect a grant.
                require(canonical == expected)
                Root(canonical, recursive[i])
            }
            return DeviceFilePolicy(roots)
        }
    }
}
