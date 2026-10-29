package com.jiacimu.lulu.data

/** xpzouying Go MCP returns plaintext login status while Node adapters use JSON. */
internal data class XhsMcpLoginStatus(
    val loggedIn: Boolean,
    val explicitlyLoggedOut: Boolean,
    val displayName: String = "",
    val detail: String = "",
) {
    companion object {
        fun failureMessage(tool: String, detail: String): String {
            val technical = detail.lowercase(java.util.Locale.ROOT)
            if (tool == "check_login_status" &&
                ("context deadline exceeded" in technical || "deadlineexceeded" in technical)) {
                return "MCP 服务端登录检查超时，登录状态未证实；请更新服务端登录检测后重试。" +
                    "这不是已退出登录的证明。详情：" + detail.take(180)
            }
            return "MCP 工具执行失败：" + detail.take(260)
        }

        fun fromToolText(raw: String): XhsMcpLoginStatus {
            val text = raw.trim().take(600)
            val json = runCatching { org.json.JSONObject(text) }.getOrNull()
            if (json != null) {
                val logged = json.optBoolean("isLoggedIn", json.optBoolean("is_logged_in"))
                val name = json.optString("nickname").ifBlank { json.optString("username") }
                return XhsMcpLoginStatus(
                    loggedIn = logged,
                    explicitlyLoggedOut = (json.has("isLoggedIn") || json.has("is_logged_in")) && !logged,
                    displayName = if (logged) name.take(80) else "",
                    detail = json.optString("message").take(140),
                )
            }
            val no = text.contains("未登录") || text.contains("尚未登录") ||
                text.contains("请使用 get_login_qrcode")
            val yes = !no && (text.contains("已登录") || text.contains("当前已处于登录状态"))
            val account = if (yes) Regex("(?m)用户名\\s*[:：]\\s*([^\\r\\n]+)")
                .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty().take(80) else ""
            return XhsMcpLoginStatus(yes, no, account, text)
        }
    }
}
