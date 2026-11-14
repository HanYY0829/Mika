package com.wangzan101.mika.core.decision

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionConfigTest {
    @Test fun defaultsAreOptInAndContainNoCredentials() {
        val config = DecisionConfig()
        assertFalse(config.enabled)
        assertTrue(DecisionConfig.isValidEndpoint(config.endpoint))
        assertTrue(DecisionConfig.isValidEndpoint(DecisionConfig.OPEN_ROUTER_ENDPOINT))
        assertFalse(Json.encodeToString(config).contains("apiKey", ignoreCase = true))
    }

    @Test fun endpointsAllowHttpsAndLocalServicesButNotEmbeddedCredentials() {
        listOf(
            "https://decision.example/v1/systemone",
            "http://localhost:8080/v1/systemone",
            "http://127.0.0.1:8080/v1/systemone",
            "http://[::1]:8080/v1/systemone",
            "http://192.168.1.5:8080/decide",
        ).forEach { assertTrue(it, DecisionConfig.isValidEndpoint(it)) }
        listOf(
            "", "not a url", "file:///tmp/model", "http://api.typesafe.ai/v1/systemone",
            "https://user:secret@decision.example/v1/systemone",
            "https://decision.example/v1/systemone?key=secret",
            "https://decision.example/v1/systemone#secret",
            "https://decision.example/v1/\nsystemone",
        ).forEach { assertFalse(it, DecisionConfig.isValidEndpoint(it)) }
    }
}
