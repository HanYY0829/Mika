package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
class SmsLoginSaved(val version: Int = 2, val enabled: Boolean = false, val profiles: List<SmsLoginProfile> = emptyList())
data class SmsLoginConsent(val enabled: Boolean = false, val revision: Long = 0, val profiles: List<SmsLoginProfile> = emptyList())

/** Version-2 consent covers automatic final login. Legacy per-attempt consent never enables it. */
class SmsLoginSettings(private val file: File, private val cipher: KeyCipher = KeystoreKeyCipher("mika-sms-login-profiles")) {
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read(): SmsLoginConsent = runCatching {
        val bytes = cipher.decrypt(file.readBytes())
        try {
            val saved = Json.decodeFromString<SmsLoginSaved>(bytes.toString(Charsets.UTF_8))
            require(saved.version == 2 && saved.profiles.size <= 16 && saved.profiles.all { it.valid } && saved.profiles.map { it.app }.distinct().size == saved.profiles.size)
            SmsLoginConsent(saved.enabled, profiles = saved.profiles)
        } finally { bytes.fill(0) }
    }.getOrDefault(SmsLoginConsent())

    @Synchronized fun setEnabled(enabled: Boolean) = save(state.value.copy(enabled = enabled, revision = state.value.revision + 1))
    @Synchronized fun saveProfile(profile: SmsLoginProfile) {
        require(profile.valid)
        val profiles = state.value.profiles.filterNot { it.app == profile.app } + profile
        require(profiles.size <= 16)
        save(state.value.copy(profiles = profiles, revision = state.value.revision + 1))
    }
    @Synchronized fun removeProfile(app: String) = save(state.value.copy(profiles = state.value.profiles.filterNot { it.app == app }, revision = state.value.revision + 1))
    private fun save(next: SmsLoginConsent) {
        // Edits/removal immediately revoke an in-flight attempt, even if disk storage fails.
        val previous = state.value
        mutable.value = previous.copy(enabled = false, revision = next.revision)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        val bytes = Json.encodeToString(SmsLoginSaved(enabled = next.enabled, profiles = next.profiles)).toByteArray()
        try {
            tmp.writeBytes(cipher.encrypt(bytes))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            mutable.value = next
        } catch (e: Exception) { file.delete(); throw e }
        finally { bytes.fill(0); tmp.delete() }
    }
    /** Last check and one native dispatch are atomic with disable/edit/revoke. */
    @Synchronized fun dispatch(revision: Long, action: () -> Boolean): Boolean =
        state.value.enabled && state.value.revision == revision && action()
}
