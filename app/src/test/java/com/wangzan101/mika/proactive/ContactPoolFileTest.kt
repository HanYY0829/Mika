package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.ContactType
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.settings.AesGcmKeyCipher
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContactPoolFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun file(k: javax.crypto.SecretKey = key) = ContactPoolFile(tmp.root, AesGcmKeyCipher { k }) { 1_761_200_000_000L }
    private val bin get() = File(tmp.root, "proactive/contacts.bin")

    private val data = ContactPoolData(
        contacts = listOf(
            ObservedContact(
                "bdeadbeef00000000", "com.chat", "王小明", 1L, 2L,
                typeVotes = mapOf(ContactType.Person to listOf(20_000L)),
                relationVotes = mapOf(ContactRelation.Friend to listOf(20_000L, 20_001L), ContactRelation.Unknown to listOf(20_002L)),
                days = listOf(20_000L, 20_001L, 20_002L), topics = listOf("k1"), userRelation = ContactRelation.Classmate,
            ),
        ),
        blocked = listOf("b0123456789abcdef"),
    )

    @Test fun roundTripOwnFileAndNoPlaintext() {
        assertEquals(ContactPoolData(), file().load())
        file().save(data)
        assertEquals(data, file().load())
        assertEquals(ContactRelation.Classmate, file().load().contacts.single().relation)
        // 和话题池各是各的文件、各用各的密钥
        assertFalse(File(tmp.root, "proactive/topics.bin").exists())
        assertNotEquals(TopicPoolFile.KEY_ALIAS, ContactPoolFile.KEY_ALIAS)
        val bytes = bin.readBytes()
        for (needle in listOf("王小明", "com.chat", "classmate").map { it.toByteArray(Charsets.UTF_8) }) {
            assertFalse(bytes.indices.any { i -> i + needle.size <= bytes.size && needle.indices.all { bytes[i + it] == needle[it] } })
        }
    }

    @Test fun brokenFileIsSetAsideAndStartsEmpty() {
        file().save(data)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(ContactPoolData(), file(other).load())
        assertFalse(bin.exists())
        assertEquals(1, File(tmp.root, "proactive").listFiles { f -> f.name.startsWith("contacts.bin.broken-") }.orEmpty().size)
        file().save(data)
        assertEquals(data, file().load())
    }

    // 用户删了东西时:另存的旧文件删掉,现在的那份不动
    @Test fun dropBrokenRemovesOnlyTheSetAsideCopies() {
        file().save(data)
        bin.writeBytes(byteArrayOf(1, 2, 3))
        file().load()
        file().save(data)
        val dir = File(tmp.root, "proactive")
        assertEquals(1, dir.listFiles { f -> f.name.startsWith("contacts.bin.broken-") }!!.size)
        file().dropBroken()
        assertEquals(listOf("contacts.bin"), dir.list()!!.toList())
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
        File(dir, "contacts.bin.tmp").writeBytes(byteArrayOf(9))
        file().wipe()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(ContactPoolData(), file().load())
    }
}
