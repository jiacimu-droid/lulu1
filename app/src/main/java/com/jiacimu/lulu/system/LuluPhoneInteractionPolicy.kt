package com.jiacimu.lulu.system

/** The existing phone grant permits ordinary UI interaction, including social
 * buttons. An outward effect forbids an automatic second click, not the first.
 * This is a physical permission boundary, never a replacement for role choice. */
internal object LuluPhoneInteractionPolicy {
    val modelGuide = """
        【统一手机操作】主人本角色虚拟屏幕总授权涵盖普通浏览、输入和社交互动：可由你自主评论、回复、私信、发朋友圈、点赞、收藏、关注及选取素材。内容、对象、按钮、先后和是否继续都由你本人根据真实页面决定；不因App名称、按钮写着发送/发表、或无障碍树为空而认定工具不可用。
        有当前控件编号时用tap_element(screenSnapshotId,elementRef)；长按同一控件可附longPress=true。只有图片没有控件时用tap(x,y,screenSnapshotId,target)，或long_press(x,y,screenSnapshotId,target,durationMs可选)，坐标0..1000，target写本次图片里真实要按的对象。执行前会复核同一独立屏/目标App和目标位置的当前像素；走错可返回或选择别的入口，不需预写每个App的固定路线。
        点击后若进入其他App或系统相册/文件选择器，回执会给actualPackage；你可明确选择adopt_app(packageName=actualPackage)，本机会先核实它确实已在同一独立屏前台、未被排除且非安全验证页，然后重新观察再选择，不会搬到或点击主屏。系统权限弹窗仍需主人处理。
        所有App文字框共用type(text)。若无可访问编辑节点，可从真实截图选输入框中心，附inputX/inputY和screenSnapshotId，使用同一独立屏的定向Unicode输入。type只输入草稿，绝不自动搜索/发送/发布；随后看实际画面确认自己要表达的全文，再独立点击真实发送/发表按钮。不要因为专用微信标题OCR或评论editorRef不可用，就放弃已有真实图像支持的通用路径。专用send_chat_message、publish_comment和微信朋友圈工具仍兼容，但不是使用这些App的必经路线。
        一次点击只走一个通道。发送、发表、关注、点赞等可能已经生效时，不因缺少回执自动补点或重发；重新看页面，根据真实内容判断下一步。dispatchAttempted只代表尝试派发，accepted只代表设备接收，outwardEffectUnverified表示尚无业务成功证明；新帧、按钮变色也不能凭空证明服务器送达。没有结果应说明未知，可继续只读核实。
        普通点击和长按不能付款、下单确认、删除数据或处理密码/验证码；这些仍由现有权限和真实窗口门禁约束。付款阶段主人亲自处理。主屏、其他人物和隐私排除App不属于本次虚拟屏操作。
    """.trimIndent()

    private val hard = Regex(
        "付款|支付|转账|充值|扣款|免密|货到付款|提交订单|确认订单|确认下单|确认购买|确认交易|" +
            "验证码|密码|永久删除|删除|清空|注销|解绑|" +
            "payment|pay(?:_|\\b)|transfer|charge|place.?order|submit.?order|confirm.?order|" +
            "confirm.?purchase|cash.?on.?delivery|password|passcode|captcha|otp|delete|erase|remove|logout",
        RegexOption.IGNORE_CASE,
    )
    private val shopping = Regex("购买|下单|加购|购物车|结算|规格|buy|purchase|cart|checkout", RegexOption.IGNORE_CASE)

    fun hardBlocked(label: String, resourceId: String = ""): Boolean =
        hard.containsMatchIn(label + " " + resourceId.substringAfterLast('/'))

    fun mayInteract(label: String, resourceId: String, descendants: String,
        shoppingAllowed: Boolean): Boolean {
        if (hardBlocked(label, resourceId)) return false
        if (shopping.containsMatchIn(label + " " + resourceId) &&
            !(shoppingAllowed && LuluShoppingAssistPolicy.mayNavigate(label, resourceId, descendants))) return false
        return descendants.lineSequence().map(String::trim)
            .none { it.length in 1..60 && hardBlocked(it) }
    }

    /** Keep enough of the 13-second helper reply deadline for IPC and cleanup. */
    const val MAX_HOLD_MS = 5_000
}
