package com.wangzan101.mika.core.decision

import com.wangzan101.mika.core.net.LocalHosts
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Public settings without credentials. The app persists these with the key in an encrypted record. */
@Serializable
data class DecisionConfig(
    val enabled: Boolean = false,
    val endpoint: String = TYPE_SAFE_ENDPOINT,
    val model: String = TYPE_SAFE_MODEL,
) {
    companion object {
        const val TYPE_SAFE_ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        const val OPEN_ROUTER_ENDPOINT = "https://openrouter.ai/api/v1/systemone"
        const val TYPE_SAFE_MODEL = "jev-1.14.0"
        const val OPEN_ROUTER_MODEL = "typesafe/jev-1.14"

        /** Accept a complete endpoint, with cleartext HTTP limited to local services. */
        fun isValidEndpoint(endpoint: String): Boolean = endpointUrl(endpoint) != null

        internal fun endpointUrl(endpoint: String): HttpUrl? {
            val text = endpoint.trim()
            if (text.any { it.isWhitespace() || it.isISOControl() }) return null
            return text.toHttpUrlOrNull()?.takeIf {
                it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
                    (it.isHttps || LocalHosts.isLocal(it.host))
            }
        }
    }
}
