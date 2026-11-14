package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Directory hints are search guidance, not access permissions. */
data class DeviceDirectory(
    val path: String, val name: String, val description: String, val source: String,
    val allowed: Boolean = false, val includeSubdirectories: Boolean = false,
)

data class AuthorizedDeviceDirectory(val path: String, val includeSubdirectories: Boolean)
data class DeviceDirectoryAuthorizationSnapshot(val revision: Long, val directories: List<AuthorizedDeviceDirectory>)

class DeviceDirectoryStore(private val prefs: SharedPreferences, private val userId: Int = 0) {
    private val syncState = synchronized(lock) { syncStates.getOrPut(prefs) { MutableStateFlow<Long?>(null) } }

    fun directories(): List<DeviceDirectory> = synchronized(lock) { read().first }

    /** An atomic, persisted policy snapshot. Only explicitly authorized entries are included. */
    fun authorizationSnapshot(): DeviceDirectoryAuthorizationSnapshot = synchronized(lock) {
        val (items, revision) = read()
        DeviceDirectoryAuthorizationSnapshot(revision, items.filter { it.allowed }.map {
            AuthorizedDeviceDirectory(it.path, it.includeSubdirectories)
        })
    }

    /** Process-local acknowledgement; null means no bridge has confirmed a policy. */
    fun syncedRevision() = syncState.asStateFlow()

    /** Call only after the bridge has applied this revision. Returns false for an outdated snapshot. */
    fun acknowledgeSynced(revision: Long): Boolean = synchronized(lock) {
        if (read().second != revision) return@synchronized false
        syncState.value = revision
        true
    }

    /** Call when the bridge is disconnected or its applied policy is unknown. */
    fun clearSync() = synchronized(lock) { syncState.value = null }

    fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY) trySend(directories())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(directories())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Only the user can add or change paths. A changed path needs a fresh authorization. */
    fun save(directory: DeviceDirectory, originalPath: String? = null) = synchronized(lock) {
        val path = directory.path.trim().trimEnd('/')
        require(validPath(path))
        require(directory.name.isNotBlank() && directory.name.length <= 100 && directory.description.length <= 1000)
        val (current, revision) = read()
        require(originalPath != null || current.size < 32)
        require(current.none { it.path == path && it.path != originalPath })
        val existing = originalPath?.let { original -> current.firstOrNull { it.path == original } ?: throw IllegalArgumentException("Directory not found") }
        val item = DeviceDirectory(path, directory.name.trim(), directory.description.trim(), "user",
            allowed = existing?.allowed == true && existing.path == path,
            includeSubdirectories = existing?.includeSubdirectories == true && existing.path == path)
        val next = if (existing == null) current + item else current.map { if (it.path == originalPath) item else it }
        writeIfChanged(current, next, revision)
    }

    fun setAuthorization(path: String, allowed: Boolean, includeSubdirectories: Boolean) = synchronized(lock) {
        val (current, revision) = read()
        require(current.any { it.path == path }) { "Directory not found" }
        require(!allowed || validPath(path)) { "Invalid directory path" }
        val next = current.map { if (it.path == path) it.copy(allowed = allowed, includeSubdirectories = allowed && includeSubdirectories) else it }
        writeIfChanged(current, next, revision)
    }

    /** A model can only annotate an existing path, never add or change one. */
    fun updateDescription(path: String, description: String): DeviceDirectory = synchronized(lock) {
        require(description.length <= 1000)
        val (current, revision) = read()
        val existing = current.firstOrNull { it.path == path } ?: throw NoSuchElementException("Directory hint not found")
        val updated = existing.copy(description = description.trim(), source = "mika")
        writeIfChanged(current, current.map { if (it.path == path) updated else it }, revision)
        updated
    }

    fun remove(path: String) = synchronized(lock) {
        val (current, revision) = read()
        writeIfChanged(current, current.filterNot { it.path == path }, revision)
    }

    private fun validPath(path: String): Boolean {
        val shared = "/storage/emulated/$userId"
        return path.length in 1..1024 && '\u0000' !in path &&
            path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
            (path == "/sdcard" || path.startsWith("/sdcard/") || path == shared || path.startsWith("$shared/"))
    }

    private fun read(): Pair<List<DeviceDirectory>, Long> {
        check(prefs !in failedWrites) { "Directory settings could not be saved; restart to reload them" }
        val saved = prefs.getString(KEY, null) ?: return defaults to 0L
        val root = Json.parseToJsonElement(saved)
        // Phase 1 stored a bare array, with no grants and no revision.
        if (root !is JsonArray) {
            val schema = root.jsonObject["schemaVersion"]?.jsonPrimitive?.int ?: 1
            check(schema == 1) { "Unsupported directory settings schema" }
        }
        val entries = if (root is JsonArray) root else root.jsonObject.getValue("directories").jsonArray
        val revision = if (root is JsonArray) 0L else root.jsonObject.getValue("revision").jsonPrimitive.long
        require(revision >= 0)
        return entries.map {
            val o = it.jsonObject
            DeviceDirectory(o.getValue("path").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
                o.getValue("description").jsonPrimitive.content, o.getValue("source").jsonPrimitive.content,
                root !is JsonArray && (o["allowed"]?.jsonPrimitive?.boolean ?: false),
                root !is JsonArray && (o["includeSubdirectories"]?.jsonPrimitive?.boolean ?: false))
        } to revision
    }

    private fun writeIfChanged(current: List<DeviceDirectory>, next: List<DeviceDirectory>, revision: Long) {
        if (current == next) return
        check(revision < Long.MAX_VALUE) { "Directory revision exhausted" }
        val data = buildJsonObject {
            put("schemaVersion", 1)
            put("revision", revision + 1)
            put("directories", buildJsonArray {
                next.forEach { d -> add(buildJsonObject {
                    put("path", d.path); put("name", d.name); put("description", d.description); put("source", d.source)
                    put("allowed", d.allowed); put("includeSubdirectories", d.includeSubdirectories)
                }) }
            })
        }
        // Invalidate before commit: listeners can run during commit. A failed commit may still
        // have changed SharedPreferences in memory, so fail closed until the process reloads.
        syncState.value = null
        val committed = try {
            prefs.edit().putString(KEY, data.toString()).commit()
        } catch (e: Exception) {
            failedWrites.add(prefs)
            throw IllegalStateException("Could not save directory settings", e)
        }
        if (!committed) {
            failedWrites.add(prefs)
            throw IllegalStateException("Could not save directory settings")
        }
    }

    companion object {
        private const val KEY = "directories"
        private val lock = Any()
        private val syncStates = WeakHashMap<SharedPreferences, MutableStateFlow<Long?>>()
        private val failedWrites = java.util.Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())
        private val defaults = listOf(
            DeviceDirectory("/sdcard/Download", "Download", "Files downloaded to the device may be here.", "preset"),
            DeviceDirectory("/sdcard/Documents", "Documents", "Documents saved on the device may be here.", "preset"),
        )
        fun get(context: Context) = DeviceDirectoryStore(
            context.applicationContext.getSharedPreferences("device_directory_hints", Context.MODE_PRIVATE),
            android.os.Process.myUid() / 100000,
        )
    }
}
