package com.wangzan101.mika.calls

/** Correlates only the owner's outstanding request, never an arbitrary system phone call. */
internal object OutgoingCallMatch {
    fun matches(
        requestedNumber: String?,
        observedNumber: String?,
        direction: CallDirection?,
        accountMatches: Boolean,
        requestedToken: String?,
        observedToken: String?,
    ): Boolean {
        if (direction != CallDirection.OUTGOING || !accountMatches || requestedToken.isNullOrBlank() ||
            observedToken != requestedToken) return false
        if (requestedNumber == null || observedNumber == null ||
            CallPolicy.outgoingNumber(requestedNumber) == null || CallPolicy.outgoingNumber(observedNumber) == null) return false
        val requestedKey = CallPolicy.incomingMatchKey(requestedNumber) ?: return false
        return requestedKey == CallPolicy.incomingMatchKey(observedNumber)
    }
}
