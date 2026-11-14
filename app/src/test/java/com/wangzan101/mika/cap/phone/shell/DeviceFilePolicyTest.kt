package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.*
import org.junit.Test

class DeviceFilePolicyTest {
    @Test fun directOnlyDoesNotAllowNestedOrPrefixCollisions() {
        val policy = DeviceFilePolicy(listOf(DeviceFilePolicy.Root("/storage/emulated/0/Download", false)))
        assertTrue(policy.allowsDirectory("/storage/emulated/0/Download"))
        assertTrue(policy.allowsFile("/storage/emulated/0/Download/a.txt"))
        assertFalse(policy.allowsDirectory("/storage/emulated/0/Download/sub"))
        assertFalse(policy.allowsFile("/storage/emulated/0/Download/sub/a.txt"))
        assertFalse(policy.allowsFile("/storage/emulated/0/Downloads/a.txt"))
        assertFalse(policy.allowsDescendants("/storage/emulated/0/Download"))
    }

    @Test fun recursiveAccessIsBoundedToApprovedRoot() {
        val policy = DeviceFilePolicy(listOf(DeviceFilePolicy.Root("/storage/emulated/0/Download", true)))
        assertTrue(policy.allowsDirectory("/storage/emulated/0/Download/sub"))
        assertTrue(policy.allowsFile("/storage/emulated/0/Download/sub/a.txt"))
        assertTrue(policy.allowsDescendants("/storage/emulated/0/Download/sub"))
        assertFalse(policy.allowsDirectory("/storage/emulated/0"))
        assertFalse(policy.allowsFile("/storage/emulated/10/Download/a.txt"))
        assertFalse(DeviceFilePolicy.EMPTY.allowsDirectory("/storage/emulated/0/Download"))
    }
}
