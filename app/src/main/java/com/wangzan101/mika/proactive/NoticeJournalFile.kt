package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 通知日志(当日消息归纳用的通知原文,见 [com.wangzan101.mika.core.proactive.NoticeJournal])整份加密成
 * `proactive/journal.bin`(AES-GCM,正式用 `KeystoreKeyCipher("mika-proactive-journal")`),和话题池、联系人各用各的密钥。
 * 里面是通知的标题和正文,最多留两天。写临时文件后原子改名。
 *
 * 和话题池不同的一点:确实坏了的文件**直接删掉**,不改名另存——这是别人发来的原文,不留一份看不见、删不掉的副本。
 * 这会儿读不了的(密钥库忙、取钥出错)照常抛出,文件不动(哪些算坏见 [StoreCodec]);一直读不了、文件又已两天没写过的,删掉。
 */
class NoticeJournalFile(dir: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) : NoticeJournalStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    /** 读出来;没有文件、或文件坏了(已删)为 null。 */
    @Synchronized
    override fun load(): NoticeJournalData? {
        if (!file.exists()) return null
        val data = try {
            StoreCodec.read(file.readBytes(), cipher, NoticeJournalData.serializer(), VERSION)
        } catch (e: Exception) {
            // 这会儿读不了:文件不动,照常抛出。但原文只该留两天——这份文件两天没被写过,里面就全是过了期的,
            // 一直读不了也不能让它一直躺着:删掉
            if (clock() - file.lastModified() > NoticeJournal.KEEP_MS) wipe()
            throw e
        }
        if (data == null) wipe()
        return data
    }

    @Synchronized
    override fun save(data: NoticeJournalData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(NoticeJournalData.serializer(), data, VERSION))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 整份删掉(文件和临时文件);读不出来时也能删。 */
    @Synchronized
    override fun wipe() {
        for (f in listOf(file, tmp)) if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
    }

    companion object {
        /** 这份存盘现在的写法是第几版;读到更新的就不碰(见 [StoreCodec])。改了写法、旧代码读了会出错或丢东西时加一。 */
        const val VERSION = 1
        const val FILE = "journal.bin"
        const val KEY_ALIAS = "mika-proactive-journal"
    }
}
