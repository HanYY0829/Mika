package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 归纳出来的联系人,整份加密成 `proactive/contacts.bin`(AES-GCM,正式用 `KeystoreKeyCipher("mika-proactive-contacts")`),
 * 和话题池([TopicPoolFile])分开放、各用各的密钥。里面有别人的显示名(通知标题)、类型和关系的票、日子、连着的话题 id,
 * 还有删掉后「不再记录」的身份 id;没有消息原文。照 [com.wangzan101.mika.memory.EncryptedMemoryFile] 的做法:
 * 写临时文件后原子改名;确实坏了就改名另存,从空开始;这会儿读不了的(密钥库忙、取钥出错)照常抛出,文件不动(哪些算坏见 [StoreCodec])。
 * 另存的旧文件和 [TopicPoolFile] 的一样,用户删东西时一并删掉。
 */
class ContactPoolFile(dir: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) : ContactPoolStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    /** 读出来;没有文件为空的。坏文件另存后也是空的。 */
    @Synchronized
    override fun load(): ContactPoolData {
        if (!file.exists()) return ContactPoolData()
        val bytes = file.readBytes() // 读不了是 IO 故障,照常抛出
        return StoreCodec.read(bytes, cipher, ContactPoolData.serializer(), VERSION) ?: run {
            moveAside()
            ContactPoolData()
        }
    }

    @Synchronized
    override fun save(data: ContactPoolData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(ContactPoolData.serializer(), data, VERSION))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** 删掉另存的旧文件(`$FILE.broken-…`)。 */
    @Synchronized
    override fun dropBroken() {
        poolDir.listFiles { f -> f.name.startsWith("$FILE.broken-") }.orEmpty().forEach(::delete)
    }

    /** 整份删掉:文件、临时文件、另存的旧文件。 */
    @Synchronized
    override fun wipe() {
        delete(file)
        delete(tmp)
        dropBroken()
    }

    private fun delete(f: File) {
        if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
    }

    private fun moveAside() {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(clock())
        var target = File(poolDir, "$FILE.broken-$stamp")
        var n = 1
        while (target.exists()) target = File(poolDir, "$FILE.broken-$stamp-${n++}")
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** 这份存盘现在的写法是第几版;读到更新的就不碰(见 [StoreCodec])。改了写法、旧代码读了会出错或丢东西时加一。 */
        const val VERSION = 1
        const val FILE = "contacts.bin"
        const val KEY_ALIAS = "mika-proactive-contacts"
    }
}
