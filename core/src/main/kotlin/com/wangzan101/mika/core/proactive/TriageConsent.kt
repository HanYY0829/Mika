package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.model.ChatModel

/** 判定实际会用的服务商和模型(轻量模型,没有用主模型);[providerName] 只给界面说是哪一家。 */
data class TriageTarget(val providerId: String, val providerName: String, val model: String)

/**
 * 后台的模型请求是哪一种。回复有多长差得很远:判定、归纳话题回的是几条短的标记;分段、当日、周、月归纳回的是
 * 十几句、每句上百字的话([long])。建模型时据此定回复上限,不能一个上限管所有的。
 */
enum class ProactiveCall(val long: Boolean) {
    Triage(false), Learn(false), Notes(true), Day(true), Span(true),
}

/** 建好的模型和它用的服务商、模型:两样出自同一次读配置,发请求前就对着这个 [target] 查同意。 */
class TargetedModel(val target: TriageTarget, val model: ChatModel)

/** 用户同意把通知文字发给哪一家的哪个模型。只记服务商 id 和模型名,不含任何通知内容。 */
data class TriageConsent(val providerId: String, val model: String) {
    /** 实际要用的 [target] 是不是同意过的那个;没有模型时不算。 */
    fun covers(target: TriageTarget?): Boolean = target != null && target.providerId == providerId && target.model == model

    companion object {
        fun of(target: TriageTarget) = TriageConsent(target.providerId, target.model)

        /**
         * 总开关一动,同意跟着怎么变。关掉(或这个版本上用不了)就清掉:再打开要重新确认,不沿用上一次的。
         * 打开时用户在确认框里点了同意的([confirmed] 是确认框里写着的那一家、那个模型)记下它;
         * 打开时没有可确认的(还没配模型)保持 [current]——之后配好了,在页面上确认。
         */
        fun afterSwitch(on: Boolean, confirmed: TriageTarget?, current: TriageConsent?): TriageConsent? = when {
            !on -> null
            confirmed != null -> of(confirmed)
            else -> current
        }
    }
}

/** 后台的模型请求(判定和归纳)现在能不能发。 */
enum class TriageGate {
    Off,
    /** 开着,但没配模型。 */
    NoModel,
    /** 开着,但实际要用的服务商或模型和同意过的不一样:停下,等用户重新确认。 */
    NeedsConsent,
    Ready;

    companion object {
        fun of(on: Boolean, consent: TriageConsent?, target: TriageTarget?): TriageGate = when {
            !on -> Off
            target == null -> NoModel
            consent?.covers(target) != true -> NeedsConsent
            else -> Ready
        }
    }
}
