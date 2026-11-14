package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Test

class DeviceDirectoryStoreTest {
    @Test fun presetsAndUserChangesPersist() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val store = DeviceDirectoryStore(prefs)
        assertEquals(listOf("/sdcard/Download", "/sdcard/Documents"), store.directories().map { it.path })
        store.save(DeviceDirectory("/sdcard/QQ", "QQ", "QQ files", "user"))
        val updated = store.updateDescription("/sdcard/QQ", "Received files")
        assertEquals("mika", updated.source)
        val saved = DeviceDirectoryStore(prefs).directories().single { it.path == "/sdcard/QQ" }
        assertEquals("Received files", saved.description)
        store.remove("/sdcard/QQ")
        assertFalse(DeviceDirectoryStore(prefs).directories().any { it.path == "/sdcard/QQ" })
    }

    @Test fun migrationAuthorizationAndRevisionSurviveReload() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putString("directories", """[{"path":"/sdcard/Old","name":"Old","description":"hint","source":"user","allowed":true}]""").commit()
        val store = DeviceDirectoryStore(prefs)
        assertEquals(0L, store.authorizationSnapshot().revision)
        assertTrue(store.authorizationSnapshot().directories.isEmpty())
        store.setAuthorization("/sdcard/Old", true, true)
        val snapshot = DeviceDirectoryStore(prefs).authorizationSnapshot()
        assertEquals(1L, snapshot.revision)
        assertEquals(listOf(AuthorizedDeviceDirectory("/sdcard/Old", true)), snapshot.directories)
        store.updateDescription("/sdcard/Old", "updated")
        assertEquals(snapshot.directories, store.authorizationSnapshot().directories)
        store.save(DeviceDirectory("/sdcard/Old", "Renamed", "hint", "user"), "/sdcard/Old")
        assertEquals(snapshot.directories, store.authorizationSnapshot().directories)
        store.setAuthorization("/sdcard/Old", false, true)
        assertTrue(store.authorizationSnapshot().directories.isEmpty())
        store.setAuthorization("/sdcard/Old", true, false)
        assertEquals(listOf(AuthorizedDeviceDirectory("/sdcard/Old", false)), store.authorizationSnapshot().directories)
        store.save(DeviceDirectory("/sdcard/New", "New", "hint", "user", allowed = true), "/sdcard/Old")
        assertTrue(store.authorizationSnapshot().directories.isEmpty())
        assertFalse(store.directories().single().allowed)
    }

    @Test fun schemaVersionIsWrittenAndUnknownVersionsFailClosed() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putString("directories", """{"revision":7,"directories":[]}""").commit()
        val store = DeviceDirectoryStore(prefs)
        assertEquals(7L, store.authorizationSnapshot().revision)
        store.save(DeviceDirectory("/sdcard/New", "New", "", "user"))
        assertTrue(prefs.getString("directories", null)!!.contains("\"schemaVersion\":1"))
        prefs.edit().putString("directories", """{"schemaVersion":2,"revision":9,"directories":[]}""").commit()
        assertThrows(IllegalStateException::class.java) { store.authorizationSnapshot() }
        assertThrows(IllegalStateException::class.java) { store.save(DeviceDirectory("/sdcard/X", "X", "", "user")) }
    }

    @Test fun syncAcknowledgementRequiresCurrentRevision() {
        val store = DeviceDirectoryStore(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertNull(store.syncedRevision().value)
        val old = store.authorizationSnapshot().revision
        assertTrue(store.acknowledgeSynced(old))
        store.setAuthorization("/sdcard/Download", true, false)
        assertNull(store.syncedRevision().value)
        assertFalse(store.acknowledgeSynced(old))
        val current = store.authorizationSnapshot().revision
        assertTrue(store.acknowledgeSynced(current))
        store.clearSync()
        assertNull(store.syncedRevision().value)
    }

    @Test fun concurrentStoresDoNotLoseUpdates() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val first = DeviceDirectoryStore(prefs)
        val second = DeviceDirectoryStore(prefs)
        val threads = listOf(
            Thread { repeat(100) { first.updateDescription("/sdcard/Download", "download $it") } },
            Thread { repeat(100) { second.updateDescription("/sdcard/Documents", "documents $it") } },
        )
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(200L, first.authorizationSnapshot().revision)
        assertEquals(listOf("download 99", "documents 99"), first.directories().map { it.description })
    }

    @Test fun failedCommitFailsClosedEvenIfPreferencesWereMutated() {
        val base = FeatureFlagsCapsuleTest.MemoryPrefs()
        val prefs = object : android.content.SharedPreferences by base {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = base.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val store = DeviceDirectoryStore(prefs)
        assertThrows(IllegalStateException::class.java) { store.setAuthorization("/sdcard/Download", true, true) }
        assertThrows(IllegalStateException::class.java) { DeviceDirectoryStore(prefs).authorizationSnapshot() }
        assertNull(store.syncedRevision().value)
    }

    @Test fun modelCannotAddOrChangeDirectoryByEditingDescription() {
        val store = DeviceDirectoryStore(FeatureFlagsCapsuleTest.MemoryPrefs())
        val before = store.directories()
        assertThrows(NoSuchElementException::class.java) { store.updateDescription("/sdcard/unknown", "x") }
        assertEquals(before, store.directories())
        assertThrows(IllegalArgumentException::class.java) { store.save(DeviceDirectory("/sdcard/Download", "duplicate", "", "user")) }
        for (path in listOf("/data/private", "/sdcard/../private", "/sdcard/a//b", "/sdcard/./a", "/storage/emulated/10/Documents")) {
            assertThrows(IllegalArgumentException::class.java) {
                store.save(DeviceDirectory(path, "invalid", "", "user"))
            }
        }
    }
}
