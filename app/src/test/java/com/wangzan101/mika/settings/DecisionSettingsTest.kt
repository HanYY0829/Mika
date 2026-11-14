package com.wangzan101.mika.settings

import com.wangzan101.mika.core.decision.DecisionConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class DecisionSettingsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmKeyCipher { key }
    private fun settings() = DecisionSettings(tmp.root, cipher)
    private val config = DecisionConfig(enabled = true)

    @Test fun freshInstallIsOffAndDoesNotCreateAClientOrCredentials() {
        val s = settings()
        assertEquals(DecisionConfig(), s.state.value)
        assertEquals("", s.apiKey())
        assertNull(s.createModel())
        assertTrue(tmp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun encryptedRecordRoundTripsAndDisablingKeepsTheConfiguredKey() {
        val s = settings()
        s.save(config, "test-secret-key-123")
        val bytes = tmp.root.resolve("decision-model.bin").readText(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("test-secret-key-123"))
        assertFalse(bytes.contains(config.endpoint))
        assertFalse(bytes.contains(config.model))
        val restored = settings()
        assertEquals(config, restored.state.value)
        assertEquals("test-secret-key-123", restored.apiKey())
        assertNotNull(restored.createModel())
        restored.save(config.copy(enabled = false), restored.apiKey())
        assertFalse(settings().state.value.enabled)
        assertEquals("test-secret-key-123", settings().apiKey())
        assertNull(settings().createModel())
    }

    @Test fun invalidEnabledConfigurationsAreRejectedWithoutChangingSavedSettings() {
        val s = settings()
        s.save(config, "valid-key")
        val invalid = listOf(
            config.copy(endpoint = "http://example.com/v1/systemone") to "key",
            config.copy(endpoint = "https://user:secret@example.com/v1/systemone") to "key",
            config.copy(endpoint = "https://example.com/v1/systemone?key=secret") to "key",
            config.copy(endpoint = "https://example.com/v1/systemone#part") to "key",
            config.copy(endpoint = "") to "key",
            config.copy(model = " ") to "key",
            config.copy(model = "bad\nmodel") to "key",
            config to "",
            config to "key with spaces",
            config to "key\r\nheader",
            config to "秘密",
        )
        invalid.forEach { (c, k) ->
            try { s.save(c, k); fail("Invalid configuration was accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(config, s.state.value)
            assertEquals("valid-key", settings().apiKey())
        }
    }

    @Test fun disabledDraftCanBeSavedWithoutAKeyAndWhitespaceIsTrimmed() {
        val s = settings()
        s.save(DecisionConfig(endpoint = " https://example.com/v1/systemone ", model = " jev-test "), "")
        assertEquals("https://example.com/v1/systemone", settings().state.value.endpoint)
        assertEquals("jev-test", settings().state.value.model)
        assertNull(settings().createModel())
        assertTrue(DecisionSettings.valid(config.copy(endpoint = "http://127.0.0.1:8000/v1/systemone"), "key"))
    }

    @Test fun corruptRecordLostKeyAndCredentialsChangedAfterLoadingFailClosed() {
        val s = settings()
        s.save(config, "key")
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val lost = DecisionSettings(tmp.root, AesGcmKeyCipher { other })
        assertFalse(lost.state.value.enabled)
        assertEquals("", lost.apiKey())
        assertNull(lost.createModel())
        tmp.root.resolve("decision-model.bin").writeText("broken")
        assertFalse(settings().state.value.enabled)
        assertNull(s.createModel())
    }

    @Test fun failedWritePreservesThePreviousEncryptedRecordAndState() {
        var failEncryption = false
        val failing = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray {
                if (failEncryption) error("Encryption unavailable")
                return cipher.encrypt(plain)
            }
            override fun decrypt(data: ByteArray) = cipher.decrypt(data)
        }
        val s = DecisionSettings(tmp.root, failing)
        s.save(config, "old-key")
        failEncryption = true
        try { s.save(config.copy(model = "another-model"), "new-key"); fail() } catch (_: IllegalStateException) { }
        assertEquals(config, s.state.value)
        assertEquals("old-key", settings().apiKey())
        assertFalse(tmp.root.resolve("decision-model.bin.tmp").exists())
    }
}
