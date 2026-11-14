package com.wangzan101.mika.proactive

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeAllowStoreTest {
    private fun tempFile() = File(Files.createTempDirectory("allow").toFile(), "proactive-allow.json")

    @Test fun emptyByDefault() = assertEquals(emptySet<String>(), NoticeAllowStore(tempFile()).current())

    @Test fun roundTrip() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        store.setAllowed("com.tencent.mm", true)
        store.setAllowed("com.example", true)
        store.setAllowed("com.example", false)
        assertEquals(setOf("com.tencent.mm"), store.state.value)
        assertEquals(setOf("com.tencent.mm"), NoticeAllowStore(f).current())
        assertEquals(false, File(f.parentFile, f.name + ".tmp").exists())
    }

    // 读不出来的名单按空的算(宁可少读),但要说得出「这是没读出来,不是用户一个都没选」:别处不能凭它去删东西
    @Test fun unreadableFileReadsAsEmptyAndSaysSo() {
        val f = tempFile()
        f.parentFile.mkdirs()
        assertEquals(true, NoticeAllowStore(f).readable)
        for (bad in listOf("{not json", "", """{"version":2,"allowed":["a"]}""")) {
            f.writeText(bad)
            val store = NoticeAllowStore(f)
            assertEquals(bad, emptySet<String>(), store.current())
            assertEquals(bad, false, store.readable)
        }
        // 真的是空的:读得出来
        f.writeText("""{"version":1,"allowed":[]}""")
        assertEquals(true, NoticeAllowStore(f).readable)
        // 用户改了名单:照常写,之后读得出来
        f.writeText("{not json")
        val store = NoticeAllowStore(f)
        store.setAllowed("com.example", true)
        assertEquals(true, store.readable)
        assertEquals(setOf("com.example"), NoticeAllowStore(f).current())
        assertEquals(true, NoticeAllowStore(f).readable)
    }

    // ---- 名单写不下去(存储满了之类):往少读的一边靠,并如实说没存下来 ----

    private class Revokes(override var allowRevoked: Set<String> = emptySet()) : AllowRevokes

    /** 让写入必定出错:临时文件的位置上放一个目录(不用改权限,也不用真把盘写满)。 */
    private fun breakWrites(f: File) = File(f.parentFile, f.name + ".tmp").mkdir()
    private fun mendWrites(f: File) = File(f.parentFile, f.name + ".tmp").delete()

    @Test fun failedRemovalStillStopsReadingAndIsReported() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertTrue(store.setAllowed("com.other", true))
        breakWrites(f)
        // 不抛出去;说没存下来;这次运行里已经不读它了,别的 App 不受影响
        assertFalse(store.setAllowed("com.chat", false))
        assertEquals(setOf("com.other"), store.current())
        // 文件里还是旧的:只靠内存的话,重启后又读它了
        assertEquals(setOf("com.chat", "com.other"), NoticeAllowStore(f).current())
        // 再选一次(名单看上去没变)也会重写;写得下去了就存下来
        mendWrites(f)
        assertTrue(store.setAllowed("com.chat", false))
        assertEquals(setOf("com.other"), NoticeAllowStore(f).current())
    }

    @Test fun failedGrantIsNotApplied() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        assertTrue(store.setAllowed("com.other", true))
        breakWrites(f)
        assertFalse(store.setAllowed("com.chat", true))
        assertEquals(setOf("com.other"), store.current())
        assertEquals(setOf("com.other"), NoticeAllowStore(f).current())
    }

    // 已经在名单里的 App 再设一次(只是改档位):名单没变,不写文件,写不下去也不碍事
    @Test fun unchangedListNeedsNoWrite() {
        val f = tempFile()
        val store = NoticeAllowStore(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertTrue(store.setAllowed("com.other", true))
        breakWrites(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertTrue(store.setAllowed("com.none", false))
        // 有一个「去掉」没存下来之后,别的 App 改档位仍然算成(它本来就在名单里,文件里也在)
        assertFalse(store.dirty)
        assertFalse(store.setAllowed("com.other", false))
        assertTrue(store.dirty)
        assertTrue(store.setAllowed("com.chat", true))
        assertEquals(setOf("com.chat"), store.current())
        // 那一个「去掉」还是没写成:仍然说得出来(页面据此一直提示),写成了才不说
        assertTrue(store.dirty)
        mendWrites(f)
        assertTrue(store.setAllowed("com.chat", true))
        assertFalse(store.dirty)
        assertEquals(setOf("com.chat"), NoticeAllowStore(f).current())
    }

    // 没存下来的「不读」记在偏好里:重启后先照办(文件里还留着它),写成了就不再记
    @Test fun unsavedRemovalSurvivesARestart() {
        val f = tempFile()
        val revokes = Revokes()
        val store = NoticeAllowStore(f)
        assertTrue(AllowEdits.set(store, revokes, "com.chat", true))
        assertTrue(AllowEdits.set(store, revokes, "com.other", true))
        assertTrue(revokes.allowRevoked.isEmpty())
        breakWrites(f)
        assertFalse(AllowEdits.set(store, revokes, "com.chat", false))
        assertEquals(setOf("com.chat"), revokes.allowRevoked)

        // 重启,还是写不下去:文件里有它,但内存里的名单没有;仍然记着
        val second = NoticeAllowStore(f)
        assertEquals(setOf("com.chat", "com.other"), second.current())
        AllowEdits.replay(second, revokes)
        assertEquals(setOf("com.other"), second.current())
        assertEquals(setOf("com.chat"), revokes.allowRevoked)
        assertTrue(second.dirty)

        // 再重启,写得下去了:补写进文件,不再记
        mendWrites(f)
        val third = NoticeAllowStore(f)
        AllowEdits.replay(third, revokes)
        assertEquals(setOf("com.other"), third.current())
        assertEquals(setOf("com.other"), NoticeAllowStore(f).current())
        assertTrue(revokes.allowRevoked.isEmpty())
    }

    // 「不读」没存下来之后用户又把它选回来:存成了才读,也不再记着要去掉它
    @Test fun grantingAgainClearsTheUnsavedRemoval() {
        val f = tempFile()
        val revokes = Revokes()
        val store = NoticeAllowStore(f)
        AllowEdits.set(store, revokes, "com.chat", true)
        breakWrites(f)
        assertFalse(AllowEdits.set(store, revokes, "com.chat", false))
        // 还写不下去:加不回来,仍然不读、仍然记着
        assertFalse(AllowEdits.set(store, revokes, "com.chat", true))
        assertEquals(emptySet<String>(), store.current())
        assertEquals(setOf("com.chat"), revokes.allowRevoked)
        mendWrites(f)
        assertTrue(AllowEdits.set(store, revokes, "com.chat", true))
        assertEquals(setOf("com.chat"), store.current())
        assertTrue(revokes.allowRevoked.isEmpty())
        val next = NoticeAllowStore(f)
        AllowEdits.replay(next, revokes)
        assertEquals(setOf("com.chat"), next.current())
    }
}
