package com.jiacimu.lulu.system

import android.content.Context

/**
 * Owner-given, per-character permission for reversible shopping navigation.
 * It is never inferred from a model prompt or from page contents. The model
 * still chooses each next action; a click is not proof of an order or payment.
 */
internal object LuluShoppingAssistPolicy {
    private const val PREFS = "lulu_virtual_shopping_owner_v1"

    fun isEnabled(context: Context, characterId: String): Boolean =
        characterId.isNotBlank() && !characterId.startsWith("resident:") &&
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(characterId, false)

    fun setEnabled(context: Context, characterId: String, enabled: Boolean): Boolean {
        if (characterId.isBlank() || characterId.startsWith("resident:")) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(characterId, enabled).commit()
    }

    // A prepayment merchant control can open an SKU picker, cart or checkout.
    // This does NOT authorize completing a charge or any binding order.
    // Only the chosen, named node qualifies; an enclosing card/prose doesn't.
    private val shoppingNavigation = Regex(
        "^(?:购物车|查看购物车|进入购物车|加入购物车|添加到购物车|放入购物车|加购|" +
            "选择规格|选规格|选择尺码|选尺码|去结算|前往结算|进入结算|结算|立即购买|去购买|购买|" +
            "add to cart|view cart|shopping cart|go to checkout|proceed to checkout|checkout)\\s*[（(]?(?:\\d+[）)]?)?$",
        RegexOption.IGNORE_CASE,
    )
    private val hardStop = Regex(
        "(支付|付款|扣款|转账|充值|免密|一键购|一键买|自动扣费|货到付款|提交订单|确认下单|确认订单|" +
            "确认购买|完成购买|确认交易|授权|验证码|密码|发送|发布|删除|退款|" +
            "payment|pay(?:_|\\b)|charge|transfer|one.?click|place.?order|submit.?order|" +
            "confirm.?order|confirm.?purchase|cash.?on.?delivery|password|otp|sms.?code|" +
            "publish|delete)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * May click only a positively identified *prepayment* shopping button.
     * Unknown one-click/pay-on-delivery modes and generic "确认/下一步" fail
     * closed. Child text must not add an irreversible action.
     */
    fun mayNavigate(label: String, resourceId: String, descendantText: String): Boolean {
        val title = LuluMikaHant.fold(label).trim()
        if (title.isBlank() || title.length > 40) return false
        if (!shoppingNavigation.matches(title)) return false
        if (hardStop.containsMatchIn(title) || hardStop.containsMatchIn(resourceId)) return false
        return descendantText.lineSequence()
            .map { it.trim() }
            .none { it.length in 1..48 && hardStop.containsMatchIn(it) }
    }
}
