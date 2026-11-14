package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.settings.KeyCipher
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 当日消息归纳攒下的句子(见 [com.wangzan101.mika.core.proactive.DigestPool])整份加密成 `proactive/digest.bin`
 * (AES-GCM,正式用 `KeystoreKeyCipher("mika-proactive-digest")`)。句子是模型照着通知写的,说的是别人发来的事:
 * 和通知日志一样,确实坏了的文件直接删掉,不改名另存;这会儿读不了的(密钥库忙、取钥出错)照常抛出,文件不动——
 * 哪些算坏见 [StoreCodec]。
 */
class DigestFile(dir: File, private val cipher: KeyCipher) : DigestStore {
    private val poolDir = File(dir, "proactive")
    private val file = File(poolDir, FILE)
    private val tmp = File(poolDir, "$FILE.tmp")

    @Synchronized
    override fun load(): DigestData {
        if (!file.exists()) return DigestData()
        val bytes = file.readBytes() // 读不了是 IO 故障,照常抛出
        return StoreCodec.read(bytes, cipher, DigestData.serializer(), VERSION) ?: run {
            wipe()
            DigestData()
        }
    }

    @Synchronized
    override fun save(data: DigestData) {
        poolDir.mkdirs()
        val bytes = cipher.encrypt(StoreCodec.write(DigestData.serializer(), data, VERSION))
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun wipe() {
        for (f in listOf(file, tmp)) if (f.exists() && !f.delete()) throw IOException("could not delete ${f.name}")
    }

    companion object {
        /** 这份存盘现在的写法是第几版;读到更新的就不碰(见 [StoreCodec])。改了写法、旧代码读了会出错或丢东西时加一。 */
        const val VERSION = 1
        const val FILE = "digest.bin"
        const val KEY_ALIAS = "mika-proactive-digest"
    }
}
