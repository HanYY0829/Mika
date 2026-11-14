package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import com.wangzan101.mika.core.cap.phone.sms.SmsLoginStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PhoneSmsLogin : PhoneTool {
    override val verb = Verb.SmsLogin
    override val risk = Risk.Write
    override val label = "短信验证码登录"
    override val sandboxCallable = false
    override val def = ToolDef("phone_sms_login", Prompts.tool("phone_sms_login"), mapOf(
        "app" to str("Exact Android package of the app the user asked to log into."),
    ), listOf("app"))
    override fun available(ctx: ToolContext): Boolean {
        val run = ctx.config.runSpec ?: return false
        return ctx[PhoneCapability.KEY]?.smsLogin != null && !run.readOnly && run.parent == null &&
            run.provenance.origin == com.wangzan101.mika.core.run.RunOrigin.Ui &&
            run.provenance.attendance != com.wangzan101.mika.core.run.Attendance.Unattended
    }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (!available(ctx)) return ToolExecution(buildJsonObject { put("ok", false); put("status", "unavailable") })
        val pkg = a.str("app", max = 200) ?: throw ArgError("app", "required")
        if (!PACKAGE_NAME_RE.matches(pkg)) throw ArgError("app", "must be an Android package name")
        val status = ctx.phone.smsLogin?.login(pkg) ?: SmsLoginStatus.UNAVAILABLE
        return ToolExecution(buildJsonObject {
            put("ok", status == SmsLoginStatus.LOGGED_IN)
            put("status", status.name.lowercase())
            put("message", "Only logged_in confirms the configured account on a verified landing page. Codes and SMS are never returned. If unverified or unsupported, report the actual status and offer manual takeover; do not retry through messages, notifications, shell or other services.")
        })
    }
}
