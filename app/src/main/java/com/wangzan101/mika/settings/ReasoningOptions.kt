package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ListedModel

/**
 * 思考档位。服务商列表写明档位的模型,四种协议(Chat Completions、Messages、generateContent、Responses)都照它给
 * (Anthropic 列表逐项写明每个模型支持的档位和思考方式,见 ModelCatalog;OpenAI 兼容协议另补官方文档写明的 `none`,见 [withDocumentedOff]);
 * 没写明时:Responses 一族给通用手动档位,OpenAI 兼容协议按预设里官方文档写明的档位给(见 [ProviderPreset.reasoningLevels])
 * ——两者都带提示,实际支持情况以服务端为准;其余协议不猜:各模型认的档位和写法不一样,猜错了每次请求都被拒。
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // 列表没说明档位时供手动选择,不声称每个模型都支持;界面会说明,默认始终不指定。
        private val manualLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            listed?.reasoningEfforts?.let { declared ->
                // 套餐接口的模型目录曾把服务端不接受的 ultra 列为可选。目录也是外部输入:
                // 这一条接入只展示请求层真正认识的档位,不能让坏元数据把每次调用都变成 HTTP 400。
                val levels = if (config.kind == ProviderConfig.Kind.ChatGpt) declared.filter(manualLevels::contains) else declared
                return ReasoningOptions(withDocumentedOff(config, listed, levels), fromProvider = true)
            }
            // 仍返回空选项,使已保存的旧档位能在界面恢复默认。
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return when (ProviderStore.adapterFor(config, listed?.endpoint).first) {
                ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    ReasoningOptions(manualLevels, fromProvider = false)
                // 预设里这家官方文档写明了几档就给几档;自定义地址对不上预设,照旧不猜
                ProviderConfig.Kind.OpenAiCompat ->
                    ProviderPresets.forBaseUrl(config.baseUrl)?.reasoningLevels
                        ?.takeIf { it.isNotEmpty() }?.let { ReasoningOptions(it, fromProvider = false) }
                ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini -> null
            }
        }

        /**
         * 列表写的是开着思考时可选的强度(DeepSeek 的 `effort.supported_levels` 是 low / high / max),
         * 官方文档另写了 `none` 关掉思考时补在最前面,免得列表一读到就关不掉思考。只补预设里写明的 `none`,不补别的档位。
         */
        private fun withDocumentedOff(config: ProviderConfig, listed: ListedModel, levels: List<String>): List<String> {
            if (levels.isEmpty() || OFF in levels) return levels
            // 按这个模型实际走的接口算:列表给它写了别的协议时不补
            if (ProviderStore.adapterFor(config, listed.endpoint).first != ProviderConfig.Kind.OpenAiCompat) return levels
            val documented = ProviderPresets.forBaseUrl(config.baseUrl)?.reasoningLevels.orEmpty()
            return if (OFF in documented) listOf(OFF) + levels else levels
        }

        private const val OFF = "none"
    }
}
