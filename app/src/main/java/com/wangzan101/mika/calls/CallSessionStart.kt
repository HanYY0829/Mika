package com.wangzan101.mika.calls

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The caller adopts the session in connect(), so cancellation/answer failure retains its cleanup owner. */
internal suspend fun <T> startCallSession(
    direction: CallDirection,
    connect: suspend () -> T,
    awaitCarrier: suspend () -> Unit,
): T {
    currentCoroutineContext().ensureActive()
    if (direction == CallDirection.OUTGOING) {
        awaitCarrier()
        currentCoroutineContext().ensureActive()
        val session = connect()
        currentCoroutineContext().ensureActive()
        return session
    }
    val session = connect()
    currentCoroutineContext().ensureActive()
    awaitCarrier()
    currentCoroutineContext().ensureActive()
    return session
}
