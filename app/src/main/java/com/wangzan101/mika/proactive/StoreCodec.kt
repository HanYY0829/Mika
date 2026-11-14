package com.wangzan101.mika.proactive

import com.wangzan101.mika.settings.KeyCipher
import java.io.IOException
import javax.crypto.AEADBadTagException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 这份存盘是更新的版本写的:现在的代码不认得它的写法。读的这一层原样留着它,不读进来、不改、不另存;换回新版本还读得出来。
 * (用户自己要删的照删:「清空全部」之类对读不出来的存盘是整份删,不分是为什么读不出来。)
 */
class NewerStoreVersion(val found: Int, val known: Int) : IOException("store written by a newer version: $found > $known")

/**
 * 主动能力四份加密存盘(话题、联系人、通知原文、各级归纳)共用的读法:分清「文件确实坏了」和「这会儿读不了」。
 *
 * 只有拿得出证据的才算坏:认证没过(密文被改过,或密钥换了、再也解不开)、短得不成一份密文。
 * 别的出错一律算这会儿读不了,照常抛出、文件不动:
 * - 密钥库忙、取钥时 IO 出错、没解锁……这些都不说明密文坏了;
 * - 解开了却读不懂的也不算坏:认证过了,说明这就是 Mika 自己写的那份,读不懂多半是别的版本写的
 *   (新版本加的枚举值、换过的写法),换回能读它的版本还读得出来,删了就没了。
 * 反过来(只认几种异常是「读不了」,其余都当坏文件删掉或另存)的话,一次取钥时的 IO 错就能删掉留两年的月归纳。
 *
 * 版本号:每份存盘最外层写着 `version`([write] 总是写,不靠数据类的默认值——默认值序列化时会被省掉)。
 * 读的时候先看它,再往数据类里读:比现在认得的新就抛 [NewerStoreVersion]。不先看的话,新版本的文件多半照样读得进来
 * (不认识的字段被忽略),下一次存盘就按旧写法重写一遍,新版本加的东西就没了。没写版本号的按 1 算。
 */
internal object StoreCodec {
    val json = Json { ignoreUnknownKeys = true }

    private const val VERSION = "version"

    /** 存盘用的明文:[data] 的内容,最外层带上 [version]。 */
    fun <T> write(serializer: SerializationStrategy<T>, data: T, version: Int): ByteArray {
        val body = json.encodeToJsonElement(serializer, data) as JsonObject
        return JsonObject(mapOf(VERSION to JsonPrimitive(version)) + (body - VERSION)).toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 读出来;文件确实坏了为 null(调用方删掉或另存)。这会儿读不了的照常抛出;
     * 版本比 [known] 新的抛 [NewerStoreVersion]。
     */
    fun <T> read(bytes: ByteArray, cipher: KeyCipher, serializer: DeserializationStrategy<T>, known: Int): T? {
        val plain = try {
            cipher.decrypt(bytes)
        } catch (e: AEADBadTagException) {
            return null
        } catch (e: IllegalArgumentException) {
            // 短得放不下 IV 和认证标签
            return null
        }
        // 读不懂的照常抛出(SerializationException),文件不动
        val root = json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject ?: throw SerializationException("store is not an object")
        val version = when (val v = root[VERSION]) {
            null -> 1
            else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw SerializationException("store version is not a number")
        }
        if (version > known) throw NewerStoreVersion(version, known)
        return json.decodeFromJsonElement(serializer, root)
    }
}
