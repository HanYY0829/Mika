package com.wangzan101.mika.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingCallMatchTest {
    @Test fun `outgoing request matches complete mainland mobile representations in either direction`() {
        val representations = listOf("13812345678", "+8613812345678", "008613812345678")
        representations.forEach { requested -> representations.forEach { observed ->
            assertTrue("Same mobile: $requested / $observed", match(requested, observed))
        } }
    }

    @Test fun `area coded landlines match only their complete national and international representations`() {
        for (representations in listOf(
            listOf("01012345678", "+861012345678", "00861012345678"),
            listOf("02812345678", "+862812345678", "00862812345678"),
            listOf("03711234567", "+863711234567", "00863711234567"),
        )) representations.forEach { requested -> representations.forEach { observed ->
            assertTrue("Same area-coded landline: $requested / $observed", match(requested, observed))
        } }
    }

    @Test fun `number equivalence never overrides account token or direction ownership`() {
        val requested = "13812345678"
        val observed = "+8613812345678"
        for (direction in listOf(CallDirection.INCOMING, null)) {
            assertFalse(OutgoingCallMatch.matches(requested, observed, direction, true, "request-a", "request-a"))
        }
        assertFalse(OutgoingCallMatch.matches(requested, observed, CallDirection.OUTGOING, false, "request-a", "request-a"))
        for ((expected, actual) in listOf("request-a" to "request-b", "request-a" to null,
            "request-a" to "", null to null, "" to "", " " to " ")) {
            assertFalse(OutgoingCallMatch.matches(requested, observed, CallDirection.OUTGOING, true, expected, actual))
        }
        assertFalse(OutgoingCallMatch.matches(requested, observed, CallDirection.INCOMING, false, "request-a", "request-b"))
    }

    @Test fun `exact ordinary numbers keep their existing ownership matching requirement`() {
        assertTrue(match("13812345678", "13812345678"))
        assertTrue(match("+14155552671", "+14155552671"))
        assertFalse(OutgoingCallMatch.matches("+14155552671", "+14155552671", CallDirection.OUTGOING, false, "request-a", "request-a"))
        assertFalse(OutgoingCallMatch.matches("+14155552671", "+14155552671", CallDirection.OUTGOING, true, "request-a", null))
    }

    @Test fun `partial numbers international guesses controls and unavailable identities fail closed`() {
        for ((requested, observed) in listOf(
            "13812345678" to "3812345678", "13812345678" to "12345678", "13812345678" to "+8613812345679",
            "01012345678" to "12345678", "01012345678" to "02812345678", "01012345678" to "+861012345679",
            "13812345678" to "8613812345678", "13812345678" to "+113812345678",
            "+14155552671" to "0014155552671", "+14155552671" to "4155552671", "+14155552671" to "+4414155552671",
            "13812345678" to "*13812345678#", "13812345678" to "13812345678,1", "13812345678" to "13812345678;1",
            "112" to "112", "" to "", "unknown" to "unknown", null to null, "13812345678" to null, null to "13812345678",
        )) assertFalse("Must not bind: $requested / $observed", match(requested, observed))
    }

    private fun match(requested: String?, observed: String?): Boolean = OutgoingCallMatch.matches(
        requested, observed, CallDirection.OUTGOING, true, "request-a", "request-a",
    )
}
