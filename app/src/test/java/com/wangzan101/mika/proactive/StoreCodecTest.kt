package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 四份加密存盘读不出来时:拿得出证据是坏了的才删(或另存),别的出错文件一概不动(见 [StoreCodec])。 */
class StoreCodecTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir get() = File(tmp.root, "proactive")

    private class Failing(val error: () -> Exception) : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray): ByteArray = throw error()
    }
    private val identity = object : KeyCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray) = data
    }

    /** 四份存盘:文件名,和拿一个 cipher 去读它。 */
    private val stores: List<Pair<String, (KeyCipher) -> Any?>> = listOf(
        DigestFile.FILE to { c -> DigestFile(tmp.root, c).load() },
        NoticeJournalFile.FILE to { c -> NoticeJournalFile(tmp.root, c).load() },
        TopicPoolFile.FILE to { c -> TopicPoolFile(tmp.root, c).load() },
        ContactPoolFile.FILE to { c -> ContactPoolFile(tmp.root, c).load() },
    )

    private fun seed(name: String, text: String = "encrypted bytes that are perfectly fine") =
        File(dir, name).apply { parentFile.mkdirs(); writeText(text) }

    // 取钥、解密这一步出的错,除了「认证没过」「短得不成密文」,都不说明密文坏了:照常抛出,文件原样留着
    @Test fun troubleWhileDecryptingLeavesEveryStoreAlone() {
        val troubles: List<() -> Exception> = listOf(
            { IOException("transient keystore load failure") },
            { java.security.KeyStoreException("busy") },
            { java.security.ProviderException("keystore daemon died") },
            { java.security.InvalidKeyException("user not authenticated") },
            { javax.crypto.IllegalBlockSizeException("keystore operation failed") },
            { javax.crypto.BadPaddingException("not a tag failure") },
            { IllegalStateException("cipher not initialised") },
        )
        for ((name, load) in stores) for (trouble in troubles) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(name)
            val what = "$name / ${trouble().javaClass.simpleName}"
            try {
                load(Failing(trouble))
                fail(what)
            } catch (e: Exception) {
                assertEquals(what, trouble().javaClass, e.javaClass)
            }
            assertTrue(what, f.exists())
            assertEquals(what, listOf(name), dir.list()!!.toList())
        }
    }

    // 解开了(认证过了)却读不懂:这是 Mika 自己写的,多半是别的版本的写法——照常抛出,文件不动
    @Test fun contentThatDecryptsButCannotBeReadIsKept() {
        for ((name, load) in stores) for (text in listOf("{broken", """{"topics":"not a list","days":7,"entries":3,"contacts":1,"notes":2}""")) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(name, text)
            try {
                load(identity)
                fail("$name / $text")
            } catch (e: kotlinx.serialization.SerializationException) {}
            assertEquals("$name / $text", listOf(name), dir.list()!!.toList())
            assertEquals(text, f.readText())
        }
    }

    // 拿得出证据的才算坏:认证没过、短得不成密文。原文和归纳直接删,话题和联系人另存一份、从空开始
    @Test fun provenCorruptionIsHandledPerStore() {
        val proofs: List<Pair<String, KeyCipher>> = listOf(
            "tag" to Failing { javax.crypto.AEADBadTagException("mac check failed") },
            "short" to Failing { IllegalArgumentException("ciphertext too short") },
        )
        for ((name, load) in stores) for ((why, cipher) in proofs) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(name, "{broken")
            val what = "$name / $why"
            val loaded = load(cipher)
            assertFalse(what, f.exists())
            val left = dir.list()!!.toList()
            if (name == TopicPoolFile.FILE || name == ContactPoolFile.FILE) {
                assertEquals(what, 1, left.size)
                assertTrue(what, left.single().startsWith("$name.broken-"))
            } else {
                assertEquals(what, emptyList<String>(), left)
            }
            if (name == NoticeJournalFile.FILE) assertNull(what, loaded)
        }
    }

    // 通知原文只该留两天:一直读不了、文件又已两天没写过(里面全是过期的)就删掉;还新着的不动
    @Test fun anUnreadableJournalDoesNotOutliveItsRetention() {
        var now = 1_800_000_000_000L
        val f = seed(NoticeJournalFile.FILE)
        f.setLastModified(now)
        val busy = Failing { java.security.KeyStoreException("busy") }
        fun load() = try {
            NoticeJournalFile(tmp.root, busy) { now }.load()
            fail()
        } catch (e: java.security.KeyStoreException) {}
        now += NoticeJournal.KEEP_MS
        load()
        assertTrue(f.exists())
        now += 1
        load()
        assertFalse(f.exists())
        // 归纳留得久,读不了多久都不删
        val d = seed(DigestFile.FILE)
        d.setLastModified(1L)
        try {
            DigestFile(tmp.root, busy).load()
            fail()
        } catch (e: java.security.KeyStoreException) {}
        assertTrue(d.exists())
    }

    // ---- 版本号 ----

    private val plainJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val topics = com.wangzan101.mika.core.proactive.TopicPoolData(
        topics = listOf(com.wangzan101.mika.core.proactive.ObservedTopic("k1", com.wangzan101.mika.core.proactive.TopicKind.Interest, "徒步", 1L, 2L)))
    private val contacts = com.wangzan101.mika.core.proactive.ContactPoolData(
        contacts = listOf(com.wangzan101.mika.core.proactive.ObservedContact("c1", "com.chat", "张三", 1L, 2L)))
    private val journal = com.wangzan101.mika.core.proactive.NoticeJournalData(
        sinceMs = 5L, entries = listOf(com.wangzan101.mika.core.proactive.NoticeEntry("com.chat", 6L, "t", text = "x")))
    private val digest = com.wangzan101.mika.core.proactive.DigestData(
        days = listOf(com.wangzan101.mika.core.proactive.DayDigest(20_000, 1L, listOf(com.wangzan101.mika.core.proactive.DigestLine("一句", apps = listOf("com.chat"))))))

    /** 每份存盘:文件名、加版本检查之前的写法写出来的内容(没有 version)、存一遍、读一遍。 */
    private class Case(val name: String, val old: String, val save: () -> Unit, val load: () -> Any?, val data: Any)
    private fun cases() = listOf(
        Case(TopicPoolFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.TopicPoolData.serializer(), topics),
            { TopicPoolFile(tmp.root, identity).save(topics) }, { TopicPoolFile(tmp.root, identity).load() }, topics),
        Case(ContactPoolFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.ContactPoolData.serializer(), contacts),
            { ContactPoolFile(tmp.root, identity).save(contacts) }, { ContactPoolFile(tmp.root, identity).load() }, contacts),
        Case(NoticeJournalFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.NoticeJournalData.serializer(), journal),
            { NoticeJournalFile(tmp.root, identity).save(journal) }, { NoticeJournalFile(tmp.root, identity).load() }, journal),
        Case(DigestFile.FILE, plainJson.encodeToString(com.wangzan101.mika.core.proactive.DigestData.serializer(), digest),
            { DigestFile(tmp.root, identity).save(digest) }, { DigestFile(tmp.root, identity).load() }, digest),
    )
    private fun root(name: String) = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, name).readText()) as kotlinx.serialization.json.JsonObject

    // 手机上现有的文件是加版本检查之前存的,里面没有 version:四份都照常读得出来,内容一样
    @Test fun filesWrittenBeforeTheVersionCheckStillLoad() {
        for (c in cases()) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            assertFalse(c.name, "\"version\"" in c.old)
            seed(c.name, c.old)
            assertEquals(c.name, c.data, c.load())
            assertEquals(c.name, c.old, File(dir, c.name).readText())
        }
    }

    // 存的时候最外层总写着版本号(数据类里的默认值序列化时会被省掉,不能靠它);存了再读内容一样
    @Test fun everyStoreWritesItsVersion() {
        for (c in cases()) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            c.save()
            assertEquals(c.name, kotlinx.serialization.json.JsonPrimitive(1), root(c.name)["version"])
            assertEquals(c.name, c.data, c.load())
        }
    }

    // 更新的版本写的文件:读的这一层不认得它的写法,不读进来、不改、不另存、不删——哪怕按现在的写法照样读得进来
    // (读进来再存一遍会按旧写法重写,新版本加的东西就没了)。
    // 用户自己要删的照删:「清空全部」、清掉某个 App 时读不出来的通知原文、关掉开关时的原文,以及原文两天没写过的那条规矩
    @Test fun aNewerVersionIsRefusedAndLeftUntouched() {
        for (c in cases()) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val body = (kotlinx.serialization.json.Json.parseToJsonElement(c.old) as kotlinx.serialization.json.JsonObject)
            val newer = kotlinx.serialization.json.JsonObject(
                body + ("version" to kotlinx.serialization.json.JsonPrimitive(2)) + ("newRequiredMeaning" to kotlinx.serialization.json.JsonPrimitive("preserve me")),
            ).toString()
            seed(c.name, newer)
            try {
                c.load()
                fail(c.name)
            } catch (e: NewerStoreVersion) {
                assertEquals(c.name, 2, e.found)
                assertEquals(c.name, 1, e.known)
            }
            assertEquals(c.name, listOf(c.name), dir.list()!!.toList())
            assertEquals(c.name, newer, File(dir, c.name).readText())
        }
    }

    // 版本号写得不成样子的当读不懂(文件不动);不比现在新的照常读
    @Test fun oddVersionValues() {
        val c = cases().first()
        val body = (kotlinx.serialization.json.Json.parseToJsonElement(c.old) as kotlinx.serialization.json.JsonObject)
        fun with(version: kotlinx.serialization.json.JsonElement) = kotlinx.serialization.json.JsonObject(body + ("version" to version)).toString()
        for (bad in listOf(kotlinx.serialization.json.JsonPrimitive("2"), kotlinx.serialization.json.JsonPrimitive(1.5), kotlinx.serialization.json.JsonArray(emptyList()))) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            val f = seed(c.name, with(bad))
            try {
                c.load()
                fail(bad.toString())
            } catch (e: kotlinx.serialization.SerializationException) {}
            assertTrue(bad.toString(), f.exists())
        }
        for (fine in listOf(0, 1)) {
            tmp.root.listFiles()?.forEach { it.deleteRecursively() }
            seed(c.name, with(kotlinx.serialization.json.JsonPrimitive(fine)))
            assertEquals(topics.topics, (c.load() as com.wangzan101.mika.core.proactive.TopicPoolData).topics)
        }
    }
}
