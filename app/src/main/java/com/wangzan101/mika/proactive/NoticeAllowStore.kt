package com.wangzan101.mika.proactive

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/**
 * 主动能力读哪些 App 的通知:包名白名单,自己一个 JSON 文件,整份原子替换。默认空:一个都不读。
 *
 * 文件读不出来(坏了、是更新的版本写的)按空的算——宁可少读;但这不等于用户一个都没选:[readable] 这时为 false,
 * 别处不能凭这份空名单去删已经攒下的东西(见 LearnRunner.poke)。用户下一次改名单时照常写,之后就读得出来了。
 *
 * 写不下去(存储满了之类)时往少读的一边靠:去掉一个 App 照样在内存里生效(这次运行里不再读它),加一个 App 不生效;
 * 两种都如实说没存下来([setAllowed] 返回 false),不抛出去。没存下来的「去掉」重启后要补做,见 [AllowEdits]。
 */
class NoticeAllowStore(private val file: File) {
    /** 名单是不是真的读出来了(没有文件算读出来了:就是空的)。 */
    @Volatile var readable: Boolean = true
        private set

    private val mutable = MutableStateFlow(load())
    val state: StateFlow<Set<String>> = mutable.asStateFlow()

    fun current(): Set<String> = mutable.value

    /**
     * 内存里的名单比文件里的少(有「去掉」没存下来):下次改名单时不管变没变都再写一遍。
     * 页面看这个提示用户再选一次——只要还没写成就一直提示,不看最近那一次改动成没成。
     */
    @Volatile var dirty = false
        private set

    /**
     * 读不读这个 App 的通知。返回是不是已经存下来了(名单本来就是这样、文件也对得上的,不用写,算存下来了)。
     * 没存下来时:去掉的照样在内存里去掉,加的不加(这个 App 本来就在名单里的不受影响,仍算成)。
     */
    @Synchronized fun setAllowed(pkg: String, allowed: Boolean): Boolean {
        val now = current()
        val next = if (allowed) now + pkg else now - pkg
        if (next == now && !dirty) return true
        if (persist(next)) return true
        if (!allowed) {
            mutable.value = next
            dirty = true
            return false
        }
        // 没写成的只是补写别的 App 的「去掉」:这个 App 本来就在名单里,文件里也在
        return next == now
    }

    @Synchronized fun clear(): Boolean {
        if (persist(emptySet())) return true
        mutable.value = emptySet()
        dirty = true
        return false
    }

    /** 整份写下去;成了才换内存里的那份。写不下去(存储满了、目录不可写)返回 false,内存里的不动。 */
    private fun persist(next: Set<String>): Boolean {
        try {
            file.parentFile?.mkdirs()
            val json = buildJsonObject {
                put("version", 1)
                put("allowed", JsonArray(next.sorted().map(::JsonPrimitive)))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            // 先落盘再替换:断电后不会留下一个空文件顶替原来的名单
            java.io.FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.io.IOException) {
            return false
        } catch (e: SecurityException) {
            return false
        }
        readable = true
        dirty = false
        mutable.value = next
        return true
    }

    private fun load(): Set<String> {
        if (!file.exists()) return emptySet()
        return runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == 1)
            o.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }.toSet()
        }.getOrElse {
            readable = false
            emptySet()
        }
    }
}

/** 用户选了「不读」、名单文件却没写成的 App(只有包名):存在偏好里,重启后先照办。 */
interface AllowRevokes {
    var allowRevoked: Set<String>
}

/**
 * 改名单的两步:写名单文件;「不读」没写成时把这个 App 记进 [AllowRevokes]。
 * 不记的话,这次运行里是不读了,可文件里还留着它,进程一重启又读起来,用户并不知道。
 *
 * 这一记只是尽力而为:名单写不下去多半是存储满了,那时偏好多半也写不下去(写没写成这里不知道)。
 * 真正兜底的是页面上那句「没存下来,请再选一次」——只要名单还没写成就一直在([NoticeAllowStore.dirty])。
 */
internal object AllowEdits {
    /** 读不读这个 App:返回名单文件存下来没有(见 [NoticeAllowStore.setAllowed])。 */
    fun set(store: NoticeAllowStore, revokes: AllowRevokes, pkg: String, allowed: Boolean): Boolean {
        val saved = store.setAllowed(pkg, allowed)
        val pending = revokes.allowRevoked
        val next = when {
            !allowed && !saved -> pending + pkg
            saved -> pending - pkg
            else -> pending
        }
        if (next != pending) revokes.allowRevoked = next
        return saved
    }

    /** 启动时:上次没存下来的「不读」先从内存里的名单去掉,再试着写一遍;写成了的不再记。要在读任何通知之前调。 */
    fun replay(store: NoticeAllowStore, revokes: AllowRevokes) {
        val pending = revokes.allowRevoked
        if (pending.isEmpty()) return
        val left = pending.filterNot { store.setAllowed(it, false) }.toSet()
        if (left != pending) revokes.allowRevoked = left
    }
}
