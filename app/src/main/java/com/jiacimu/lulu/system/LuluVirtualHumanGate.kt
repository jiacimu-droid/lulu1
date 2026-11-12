package com.jiacimu.lulu.system

/** Diagnostic gating of Mika ScreenDiagnosis on Lulu's reading flows.
 * Article content is untrusted page DATA, not a login/payment control.
 * Only short, actionable, semantically identifiable controls can trigger
 * human takeover by text; a real password input is detected separately. */
internal object LuluVirtualHumanGate {
    data class Control(val name: String, val role: String, val resourceId: String,
                       val clickable: Boolean, val editable: Boolean)

    private val actionableRole = Regex("(button|edittext|textfield|input)", RegexOption.IGNORE_CASE)
    private val actionableId = Regex("(captcha|verify|sms.?code|otp|password|pay|payment|checkout|login)", RegexOption.IGNORE_CASE)
    private val securityText = Regex(
        "(获取验证码|发送验证码|短信验证码|输入验证码|安全验证|人机验证|滑块验证|拖动滑块|" +
            "确认支付|确认付款|立即支付|支付密码|输入密码支付|验证码|verify you are human|" +
            "not a robot|payment password|confirm payment|pay now|get code|send code|verification code)",
        RegexOption.IGNORE_CASE,
    )

    /** A news card reading "验证码" is NOT proof a security gate opened.
     * Avoid identifying arbitrary long page text as a button title. */
    fun securityLabels(controls: List<Control>): List<String> = controls
        .asSequence()
        .filter { it.clickable || it.editable }
        .filter {
            actionableRole.containsMatchIn(it.role) ||
                actionableId.containsMatchIn(it.resourceId)
        }
        .map { LuluMikaHant.fold(it.name).trim() }
        .filter { it.length in 1..44 && securityText.containsMatchIn(it) }
        .take(12).toList()
}
