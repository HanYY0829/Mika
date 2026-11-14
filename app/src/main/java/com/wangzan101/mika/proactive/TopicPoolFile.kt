package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 归纳出来的话题池,整份加密成 `proactive/topics.bin`(AES-GCM,正式用 `KeystoreKeyCipher("mika-proactive-topics")`)。
 * 里面只有话题名、种类、计数和时间,没有通知原文。照 [com.wangzan101.mika.memory.EncryptedMemoryFile] 的做法:
 * 写临时文件后原子改名;确实坏了就改名另存,从空开始;这会儿读不了的(密钥库忙、取钥出错)照常抛出,文件不动(哪些算坏见 [StoreCodec])。
 * 另存的旧文件界面上看不到、也读不回来,只是留个底:用户删任何东西时一并删掉([dropBroken]),「清空全部」时整份删([wipe])。
 */
class TopicPoolFile(dir: File, private val cipher: KeyCipher, private val clock: () -> Long = System::currentTimeMillis) : TopicPoolStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    /** 读出来;没有文件为空池。坏文件另存后也是空池。 */
    @Synchronized
    override fun load(): TopicPoolData {
        if (!file.exists()) return TopicPoolData()
        val bytes = file.readBytes() // 读不了是 IO 故障,照常抛出
        return StoreCodec.read(bytes, cipher, TopicPoolData.serializer(), VERSION) ?: run {
            moveAside()
            TopicPoolData()
        }
    }

    @Synchronized
    override fun save(data: TopicPoolData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(TopicPoolData.serializer(), data, VERSION))
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
        const val FILE = "topics.bin"
        const val KEY_ALIAS = "mika-proactive-topics"
    }
}
