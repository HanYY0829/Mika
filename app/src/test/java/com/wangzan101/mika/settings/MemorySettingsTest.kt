package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemorySettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun learnSkillsOnByDefault() {
        assertTrue(MemorySettings(File(tmp.root, "memory.json")).switches.value.learnSkills)
    }

    @Test fun olderSettingsUpgradeOnceThenUserChoiceSticks() {
        val file = File(tmp.root, "memory.json")
        // 旧版本存下的:当时默认关
        file.writeText("""{"useMemory":true,"autoRemember":false,"learnSkills":false}""")
        val settings = MemorySettings(file)
        assertTrue(settings.switches.value.learnSkills)
        assertFalse(settings.switches.value.autoRemember)
        settings.update { it.copy(learnSkills = false) }
        assertFalse(MemorySettings(file).switches.value.learnSkills)
    }

    @Test fun rememberTasksOnByDefaultAndSticks() {
        val file = File(tmp.root, "memory.json")
        // 没存过 rememberTasks 的旧文件:默认开
        file.writeText("""{"useMemory":true,"autoRemember":true,"learnSkills":true}""")
        assertTrue(MemorySettings(file).switches.value.rememberTasks)
        // 用户关掉后保持关
        val settings = MemorySettings(file)
        settings.update { it.copy(rememberTasks = false) }
        assertFalse(MemorySettings(file).switches.value.rememberTasks)
    }
}
