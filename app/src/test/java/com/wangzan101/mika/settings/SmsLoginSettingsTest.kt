package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SmsLoginSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = AesGcmKeyCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 42 }, "AES") }
    @Test fun defaultsOffAndCorruptionFailsClosed() {
        val file = File(tmp.root, "sms")
        assertFalse(SmsLoginSettings(file, cipher).state.value.enabled)
        file.writeText("true or broken data")
        assertFalse(SmsLoginSettings(file, cipher).state.value.enabled)
    }
    @Test fun encryptedConsentPersistsAndOldAttemptsCannotResumeAfterReenable() {
        val file = File(tmp.root, "sms")
        val store = SmsLoginSettings(file, cipher)
        store.setEnabled(true)
        val revision = store.state.value.revision
        assertTrue(SmsLoginSettings(file, cipher).state.value.enabled)
        assertTrue(store.dispatch(revision) { true })
        store.setEnabled(false)
        assertFalse(SmsLoginSettings(file, cipher).state.value.enabled)
        assertFalse(store.dispatch(revision) { error("disabled must not dispatch") })
        store.setEnabled(true)
        assertFalse(store.dispatch(revision) { error("old consent must not revive") })
        assertFalse(file.readBytes().contentEquals("enabled-v1".toByteArray()))
    }
    @Test fun legacyConsentDoesNotAuthorizeAutomaticSubmission() {
        val file = File(tmp.root, "legacy")
        file.writeText("enabled-v1")
        assertFalse(SmsLoginSettings(file, cipher).state.value.enabled)
    }
    @Test fun profilesAreEncryptedBoundAndRemovalRevokesOldDispatch() {
        val file = File(tmp.root, "profiles")
        val store = SmsLoginSettings(file, cipher)
        val app = "com.example.login"
        val p = com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile(app, "a".repeat(64), "10690001", "示例服务", "00000007788", "7788",
            "$app:id/account", "$app:id/otp", "$app:id/send", "$app:id/login", "$app:id/logged", "欢迎回来", "$app:id/who")
        store.saveProfile(p)
        store.setEnabled(true)
        val revision = store.state.value.revision
        val loaded = SmsLoginSettings(file, cipher)
        assertEquals(p.account, loaded.state.value.profiles.single().account)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(p.account))
        store.removeProfile(app)
        assertTrue(store.state.value.profiles.isEmpty())
        assertFalse(store.dispatch(revision) { error("removed profile must revoke") })
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertFalse(SmsLoginSettings(file, cipher).state.value.enabled)
    }
    @Test fun failedSaveStillRevokesInMemory() {
        val file = File(tmp.root, "sms")
        val store = SmsLoginSettings(file, cipher)
        store.setEnabled(true)
        val revision = store.state.value.revision
        File(file.path + ".tmp").mkdir()
        runCatching { store.setEnabled(false) }
        assertFalse(store.state.value.enabled)
        assertFalse(store.dispatch(revision) { error("revoked") })
    }
    @Test fun disablingReturnsOnlyAfterAnAlreadyDispatchedOperationAndBlocksAllLaterOnes() {
        val store = SmsLoginSettings(File(tmp.root, "sms"), cipher)
        store.setEnabled(true)
        val revision = store.state.value.revision
        val dispatched = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = thread {
            store.dispatch(revision) { dispatched.countDown(); release.await(5, TimeUnit.SECONDS); true }
        }
        assertTrue(dispatched.await(5, TimeUnit.SECONDS))
        val disabling = thread { store.setEnabled(false); finished.countDown() }
        assertFalse(finished.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        worker.join(); disabling.join()
        assertFalse(store.dispatch(revision) { error("must not dispatch after off returned") })
    }
}
