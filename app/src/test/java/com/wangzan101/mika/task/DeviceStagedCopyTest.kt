package com.wangzan101.mika.task

import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.core.workspace.SandboxError
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceStagedCopyTest {
    @Test fun copiesCompleteContents() = runBlocking {
        val output = ByteArrayOutputStream()
        val size = DeviceStagedCopy.copy("abc".byteInputStream(), output, { Attachments.MIN_FREE }, { 0L })
        assertEquals(3L, size)
        assertEquals("abc", output.toString("UTF-8"))
    }

    @Test fun checksWorkspaceLimitIncludingStagedBytes() = runBlocking {
        val error = try {
            DeviceStagedCopy.copy("abc".byteInputStream(), ByteArrayOutputStream(),
                { Attachments.MIN_FREE }, { Attachments.WORKSPACE_MAX - 2 })
            null
        } catch (e: SandboxError) { e }
        assertEquals("workspace_limit", error?.code)
    }

    @Test fun rejectsLowDeviceSpaceBeforeWriting() = runBlocking {
        val output = ByteArrayOutputStream()
        val error = try {
            DeviceStagedCopy.copy("abc".byteInputStream(), output, { Attachments.MIN_FREE - 1 }, { 0L })
            null
        } catch (e: SandboxError) { e }
        assertEquals("low_device_space", error?.code)
        assertEquals(0, output.size())
    }

    @Test fun propagatesCancellation() = runBlocking {
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")
        }
        try {
            DeviceStagedCopy.copy(input, ByteArrayOutputStream(), { Attachments.MIN_FREE }, { 0L })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
