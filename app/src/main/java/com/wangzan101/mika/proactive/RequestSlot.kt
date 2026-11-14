package com.wangzan101.mika.proactive

/**
 * 判定和归纳共用的「请求位」:主动能力的后台请求一次只发一个。
 *
 * 两边各管各的在途请求的话,凑巧都到点时会同时各发一个:多占一条连接,预算也是各查各的。所以谁要发先看这个位子
 * 是不是被对方占着,占着就等;对方发完(或被取消)会叫等着的再看一遍([wakeOthers])——没有别的东西会来叫。
 * 不抢占:已经在发的发完为止。一个请求发完之后轮到谁:判定优先(提醒是用户看得见的)——判定发完先看自己还有没有
 * 要发的,再叫归纳;归纳发完先叫判定,再看自己。位子空着时两边同时到点,则是谁先看到谁先发,没有另排先后。
 *
 * 不是线程安全的:都在 ProactiveRuntime 那条串行协程上用。各自单独用(测试、或只开了一边)时每边一个自己的位子,互不相干。
 */
internal class RequestSlot {
    private var holder: Any? = null
    private val wakers = LinkedHashMap<Any, () -> Unit>()

    /** 位子被别人占着。 */
    fun heldByOther(who: Any): Boolean = holder != null && holder !== who

    fun take(who: Any) {
        check(!heldByOther(who)) { "another background request is in flight" }
        holder = who
    }

    /** 放开(不是自己占着的不动)。 */
    fun release(who: Any) {
        if (holder === who) holder = null
    }

    /** [wake]:别人放开位子之后叫 [who] 再看一遍。 */
    fun listen(who: Any, wake: () -> Unit) {
        wakers[who] = wake
    }

    fun unlisten(who: Any) {
        wakers.remove(who)
    }

    /** 叫除 [who] 以外等着的都再看一遍。 */
    fun wakeOthers(who: Any) {
        wakers.filterKeys { it !== who }.values.toList().forEach { it() }
    }
}
