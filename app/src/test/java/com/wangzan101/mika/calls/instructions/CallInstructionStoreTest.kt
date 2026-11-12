package com.wangzan101.mika.calls.instructions

import org.junit.Assert.*
import org.junit.Test

class CallInstructionStoreTest {
    private class Backend : CallInstructionBackend {
        var value: String? = null
        var writable = true
        override fun read() = value
        override fun write(value: String): Boolean {
            if (writable) this.value = value
            return writable
        }
    }
    private fun rule(id: String = "a", expiry: Long? = null) =
        CallInstruction(id, "送货", "快递来电", "只询问包裹放置地点，不提供门禁", expiresAtMs = expiry)

    @Test fun explicitRulesSurviveRecreationAndExpireWithoutBeingDeleted() {
        val backend = Backend()
        val first = CallInstructionStore(backend, { 100L })
        first.save(rule(expiry = 200L))
        assertTrue(first.promptSnapshot().contains("包裹"))
        val afterRestart = CallInstructionStore(backend, { 200L })
        assertEquals("", afterRestart.promptSnapshot())
        assertEquals(1, afterRestart.items.value.size)
    }

    @Test fun failedSaveCannotBecomeAnActiveInstruction() {
        val backend = Backend()
        val store = CallInstructionStore(backend)
        store.save(rule())
        backend.writable = false
        assertThrows(IllegalStateException::class.java) { store.save(rule().copy(response = "修改后的交代")) }
        assertFalse(store.promptSnapshot().contains("修改后的交代"))
        assertNotNull(store.error.value)
    }

    @Test fun unreadableStorageIsNotReplacedWithEmptyRules() {
        val backend = Backend().apply { value = "bad json" }
        val store = CallInstructionStore(backend)
        assertFalse(store.load())
        assertThrows(IllegalStateException::class.java) { store.save(rule()) }
        assertEquals("bad json", backend.value)
    }

    @Test fun disabledAndDeletedRulesCannotReachTheNextCall() {
        val store = CallInstructionStore(Backend())
        store.save(rule())
        store.setEnabled("a", false)
        assertEquals("", store.promptSnapshot())
        store.setEnabled("a", true)
        assertTrue(store.promptSnapshot().isNotEmpty())
        store.remove("a")
        assertEquals("", store.promptSnapshot())
    }
}
