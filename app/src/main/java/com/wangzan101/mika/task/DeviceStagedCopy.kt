package com.wangzan101.mika.task

import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.core.workspace.SandboxError
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Copy to private storage before publishing into the workspace. */
internal object DeviceStagedCopy {
    private const val CHECK_EVERY = 16L shl 20

    suspend fun copy(input: InputStream, output: OutputStream, freeBytes: () -> Long, workspaceBytes: () -> Long): Long {
        fun checkRoom(staged: Long) {
            if (freeBytes() < Attachments.MIN_FREE) throw SandboxError("low_device_space", "Not enough free device storage.", true)
            val used = workspaceBytes()
            if (used < 0 || used > Attachments.WORKSPACE_MAX - staged) {
                throw SandboxError("workspace_limit", "The workspace storage limit would be exceeded.", true)
            }
        }
        checkRoom(0)
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        var lastCheck = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            size += n
            if (size > Attachments.MAX_BYTES) throw SandboxError("too_large", "The device file is too large.", true)
            output.write(buffer, 0, n)
            if (size - lastCheck >= CHECK_EVERY) {
                checkRoom(size)
                lastCheck = size
            }
        }
        currentCoroutineContext().ensureActive()
        checkRoom(size)
        return size
    }
}
