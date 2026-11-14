package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicKind
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.settings.AesGcmKeyCipher
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TopicPoolFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun file(k: javax.crypto.SecretKey = key) = TopicPoolFile(tmp.root, AesGcmKeyCipher { k }) { 1_761_200_000_000L }
    private val bin get() = File(tmp.root, "proactive/topics.bin")

    private val data = TopicPoolData(
        topics = listOf(
            ObservedTopic("k1", TopicKind.Shopping, "跑鞋", 1L, 2L, mapOf("com.shop" to listOf(20_000L, 20_001L)), opened = 3, dismissed = 1),
            ObservedTopic("k7", TopicKind.Interest, "Diabetes", 3L, 4L, mapOf("com.health" to listOf(20_002L)), sensitive = true),
        ),
    )

    @Test fun roundTripAndNoPlaintext() {
        assertEquals(TopicPoolData(), file().load())
        file().save(data)
        assertEquals(data, file().load())
        val bytes = bin.readBytes()
        for (needle in listOf("跑鞋", "com.shop", "Diabetes").map { it.toByteArray(Charsets.UTF_8) }) {
            assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
        }
    }

    @Test fun brokenFileIsSetAsideAndPoolStartsEmpty() {
        file().save(data)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(TopicPoolData(), file(other).load())
        assertFalse(bin.exists())
        val aside = File(tmp.root, "proactive").listFiles { f -> f.name.startsWith("topics.bin.broken-") }.orEmpty()
        assertEquals(1, aside.size)
        // 之后照常存取
        file().save(data)
        assertEquals(data, file().load())
        bin.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(TopicPoolData(), file().load())
        assertTrue(File(tmp.root, "proactive").listFiles { f -> f.name.startsWith("topics.bin.broken-") }!!.size == 2)
    }

    // 早先的文件里还有一个序号(后来 id 改成随机的,不再有):照常读出来,旧的 id 原样留着
    @Test fun oldFileWithASequenceNumberStillLoads() {
        val old = """{"version":1,"seq":7,"topics":[{"id":"k7","kind":"interest","label":"Hiking","firstSeenMs":3,"lastSeenMs":4}]}"""
        bin.parentFile!!.mkdirs()
        bin.writeBytes(AesGcmKeyCipher { key }.encrypt(old.toByteArray(Charsets.UTF_8)))
        assertEquals(TopicPoolData(topics = listOf(ObservedTopic("k7", TopicKind.Interest, "Hiking", 3L, 4L))), file().load())
    }

    // 用户删了东西时:另存的旧文件删掉,现在的那份不动
    @Test fun dropBrokenRemovesOnlyTheSetAsideCopies() {
        file().save(data)
        bin.writeBytes(byteArrayOf(1, 2, 3))
        file().load()
        file().save(data)
        val dir = File(tmp.root, "proactive")
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("topics.bin.broken-") }!!.size)
        file().dropBroken()
        assertEquals(listOf("topics.bin"), dir.list()!!.toList())
        assertEquals(data, file().load())
        // 没有可删的也不出错
        file().dropBroken()
    }

    // 「清空全部」:文件、临时文件、另存的旧文件都删掉;目录还没有时也不出错
    @Test fun wipeRemovesEverything() {
        file().wipe()
        file().save(data)
        bin.writeBytes(byteArrayOf(1, 2, 3))
        file().load()
        file().save(data)
        val dir = File(tmp.root, "proactive")
        File(dir, "topics.bin.tmp").writeBytes(byteArrayOf(9))
        file().wipe()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(TopicPoolData(), file().load())
    }
}
