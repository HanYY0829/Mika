package com.wangzan101.mika.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "plugins")

    private fun registry() = PluginRegistry(tmp.root, CoroutineScope(Dispatchers.Unconfined), appVersion = "0.1.33")

    private fun manifest(
        id: String,
        name: String = id,
        version: String = "1.0.0",
        skill: String? = "feishu",
    ) = """
        {"schema":1,"id":"$id","name":"$name","version":"$version","kind":"capability",
         "skills":[${skill?.let { """{"name":"$it","path":"skills/$it"}""" } ?: ""}]}
    """.trimIndent()

    private fun writePlugin(
        id: String,
        name: String = id,
        version: String = "1.0.0",
        skill: String? = "feishu",
        skillBody: String = "---\nname: feishu\ndisplay: 飞书\n---\n正文",
    ) {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "manifest.json").writeText(manifest(id, name, version, skill))
        if (skill != null) {
            File(dir, "skills/$skill").mkdirs()
            File(dir, "skills/$skill/SKILL.md").writeText(skillBody)
        }
    }

    @Test fun scansInstalledPlugins() {
        writePlugin("com.example.a", name = "A")
        writePlugin("com.example.b", name = "B", skill = null)
        val r = registry()
        assertEquals(listOf("com.example.a", "com.example.b"), r.plugins.value.map { it.id })
        assertTrue(r.plugins.value.all { it.enabled })
        assertTrue(File(root, "registry.json").isFile)
        assertFalse(File(root, "registry.json.tmp").exists())
    }

    @Test fun enabledFlagSurvivesRestart() {
        writePlugin("com.example.a")
        registry().setEnabled("com.example.a", false)
        assertFalse(registry().record("com.example.a")!!.enabled)
        writePlugin("com.example.b")
        assertTrue(registry().record("com.example.b")!!.enabled)
    }

    @Test fun uninstallRemovesDirectoryAndRecord() {
        writePlugin("com.example.a")
        val r = registry()
        assertTrue(r.uninstall("com.example.a"))
        assertNull(r.record("com.example.a"))
        assertFalse(File(root, "com.example.a").exists())
        assertEquals(emptyList<PluginRecord>(), registry().plugins.value)
        assertFalse(r.uninstall("com.example.missing"))
        assertFalse(r.uninstall("../escape"))
    }

    @Test fun reconcilesAddedAndRemovedDirectories() {
        writePlugin("com.example.a")
        registry()
        writePlugin("com.example.b")
        File(root, "com.example.a").deleteRecursively()
        assertEquals(listOf("com.example.b"), registry().plugins.value.map { it.id })
    }

    @Test fun ignoresDirectoryWhoseManifestIdDiffers() {
        writePlugin("com.example.a")
        File(root, "com.example.a/manifest.json").writeText(manifest("com.example.b"))
        assertTrue(registry().plugins.value.isEmpty())
    }

    @Test fun ignoresManifestThisVersionCannotLoad() {
        val dir = File(root, "com.example.tools").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"schema":1,"id":"com.example.tools","name":"T","version":"1.0.0","kind":"capability","tools":[{"name":"send"}]}""",
        )
        assertTrue(registry().plugins.value.isEmpty())
        assertTrue(dir.isDirectory) // 不删用户的目录,只是不加载
    }

    @Test fun reinstallingKeepsEnabledState() {
        writePlugin("com.example.a")
        val r = registry()
        r.setEnabled("com.example.a", false)
        r.install(PluginRecord("com.example.a", "A", "2.0.0", installedAt = 42))
        val record = r.record("com.example.a")!!
        assertFalse(record.enabled)
        assertEquals("2.0.0", record.version)
        writePlugin("com.example.a", version = "2.0.0")
        assertEquals("2.0.0", registry().record("com.example.a")!!.version)
    }

    @Test fun snapshotDoesNotFollowLaterEnableChanges() {
        writePlugin("com.example.a")
        val r = registry()
        val snapshot = r.skillsSnapshot()
        assertEquals(1, snapshot.entries().size)
        r.setEnabled("com.example.a", false)
        assertEquals(1, snapshot.entries().size)
        assertTrue(snapshot.entries().single().kind == "plugin")
        assertNotNull(snapshot.read(snapshot.entries().single(), "SKILL.md"))
        assertTrue(r.skillsProvider.entries().isEmpty())
    }
}
