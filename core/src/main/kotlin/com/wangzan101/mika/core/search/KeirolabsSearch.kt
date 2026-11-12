package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keirolabs 搜索:`POST https://api.keirolabs.cloud/api/v2/keiro`,`Authorization: Bearer`,请求体 `{"query","maxResults"}`。
 * 返回 `results[]`,每条有 title、url、snippet;其余字段(position、score、tags)不用。
 */
class KeirolabsSearch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey.trim()

    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val body = buildJsonObject {
            put("query", query)
            put("maxResults", n)
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").post(SearchHttp.json(body)).build()
        return SearchHttp.call(client, request) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The Keirolabs response has no results array.")
        return WebSearchResult.Success(results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), SearchHttp.text(o, "snippet"))
        }.take(count))
    }

    companion object {
        const val URL = "https://api.keirolabs.cloud/api/v2/keiro"
    }
}
