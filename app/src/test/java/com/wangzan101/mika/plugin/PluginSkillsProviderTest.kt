package com.wangzan101.mika.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginSkillsProviderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root get() = File(tmp.root, "plugins")

    private fun plugin(
        id: String,
        skills: String = """[{"name":"feishu","path":"skills/feishu","display":"飞书通知"}]""",
    ): PluginRecord {
        val dir = File(root, id).apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"schema":1,"id":"$id","name":"$id","version":"1.0.0","kind":"capability","skills":$skills}""",
        )
        return PluginRecord(id, id, "1.0.0", enabled = true, skillNames = listOf("feishu"))
    }

    private fun writeSkill(id: String, name: String, text: String) {
        File(root, "$id/skills/$name").mkdirs()
        File(root, "$id/skills/$name/SKILL.md").writeText(text)
    }

    private fun provider(vararg records: PluginRecord) = PluginSkillsProvider({ records.toList() }, root)

    @Test fun onlyEnabledPluginsAreListed() {
        val a = plugin("com.example.a")
        val b = plugin("com.example.b")
        writeSkill("com.example.a", "feishu", "---\nname: feishu\n---\nA")
        writeSkill("com.example.b", "feishu", "---\nname: feishu\n---\nB")
        val entries = provider(a, b.copy(enabled = false)).entries()
        assertEquals(listOf("com.example.a/feishu"), entries.map { it.name })
        assertEquals("plugin", entries.single().kind)
    }

    @Test fun frontMatterBecomesSearchableMetadata() {
        val p = plugin("com.example.feishu")
        writeSkill(
            "com.example.feishu", "feishu",
            "---\nname: feishu\ndisplay: 飞书\ndescription: 发消息\naliases: [fs, lark]\napps: [飞书]\ndomains: [open.feishu.cn]\n---\n正文",
        )
        val info = provider(p).entries().single()
        assertEquals("飞书通知", info.display) // manifest 的 display 优先
        assertEquals("发消息", info.description)
        assertEquals(listOf("fs", "lark"), info.aliases)
        assertEquals(listOf("飞书", "open.feishu.cn"), info.terms)
        assertEquals(listOf("SKILL.md"), info.files)
        assertEquals("com.example.feishu/feishu", info.name)
    }

    @Test fun readReturnsBodyWithoutFrontMatter() {
        val p = plugin("com.example.feishu")
        writeSkill("com.example.feishu", "feishu", "---\nname: feishu\n---\n正文内容")
        val prov = provider(p)
        val info = prov.entries().single()
        assertEquals("正文内容", prov.read(info, "SKILL.md"))
        assertNull(prov.read(info, "references/x.md"))
    }

    @Test fun brokenSkillIsSkippedWithoutDroppingThePlugin() {
        val p = plugin(
            "com.example.feishu",
            skills = """[{"name":"missing","path":"skills/missing"},{"name":"blank","path":"skills/blank"},{"name":"ok","path":"skills/ok"}]""",
        )
        File(root, "com.example.feishu/skills/blank").mkdirs()
        File(root, "com.example.feishu/skills/blank/SKILL.md").writeText("---\nname: blank\n---\n   ")
        writeSkill("com.example.feishu", "ok", "---\nname: ok\n---\n好的")
        assertEquals(listOf("com.example.feishu/ok"), provider(p).entries().map { it.name })
    }

    @Test fun skillPathOutsideSkillsIsIgnored() {
        val p = plugin(
            "com.example.feishu",
            skills = """[{"name":"feishu","path":"skills/../outside"}]""",
        )
        File(tmp.root, "outside").mkdirs()
        File(tmp.root, "outside/SKILL.md").writeText("---\nname: feishu\n---\n外面")
        assertTrue(provider(p).entries().isEmpty())
    }

    @Test fun duplicateSkillNamesAcrossPluginsStayNamespaced() {
        val a = plugin("com.example.a")
        val b = plugin("com.example.b")
        writeSkill("com.example.a", "feishu", "---\nname: feishu\n---\nA")
        writeSkill("com.example.b", "feishu", "---\nname: feishu\n---\nB")
        assertEquals(
            listOf("com.example.a/feishu", "com.example.b/feishu"),
            provider(a, b).entries().map { it.name },
        )
    }
}
