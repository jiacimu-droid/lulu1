package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject

data class Capability(val name: String, val domain: String, val permission: String, val successEvidence: String, val retrySafe: Boolean, val retired: Boolean = false)

object CapabilityRegistry {
    val device = listOf(
        Capability("get_battery", "phone", "device_read", "Android battery broadcast", true),
        Capability("get_location", "phone", "device_read", "location permission and timestamped fix", true),
        Capability("get_current_app", "phone", "device_read", "usage/accessibility snapshot", true),
        Capability("read_recent_notifications", "phone", "device_read", "notification listener snapshot", true),
        Capability("create_alarm", "phone", "alarm", "AlarmManager registration or explicit pending clock confirmation", false),
        Capability("list_alarms", "phone", "device_read", "character-owned persisted alarm records", true),
        Capability("cancel_alarm", "phone", "alarm", "character-owned alarm cancellation", false),
        Capability("list_media_sessions", "phone", "device_read", "active Android media sessions via user-enabled notification listener", true),
        Capability("control_music", "phone", "media", "Android MediaController dispatch and observable playback state; app support varies", false),
        Capability("control_volume", "phone", "media", "Observed, capped STREAM_MUSIC volume; restores previous volume on request", false),
        Capability("ring_in_call_wake", "phone", "alarm", "short native ringtone inside the current connected call; never redials", false),
        Capability("read_screen", "phone", "device_read", "fresh accessibility snapshot", true),
        Capability("capture_screen", "phone", "screen_vision", "owner-consented screenshot plus vision", false),
        Capability("summon_lulu", "phone", "screen_summon", "user-tappable notification", false),
        Capability("click_text", "phone", "screen", "post-action screen observation", false),
        Capability("screen_action", "phone", "screen", "post-action screen observation", false),
        Capability("screen_sequence", "phone", "screen", "bounded fresh observations and expected text per step", false),
        Capability("phone_ui", "phone", "screen", "owner-authorized foreground app actions and fresh UI evidence", false),
        Capability("background_phone", "phone", "screen", "owner-paired shell virtual screen with isolated focus and per-display verification", false),
        Capability("cloud_task", "cloud", "cloud", "persistent task id; file success comes later", false),
    )
    val application = listOf("send_private_message", "send_group_message", "create_group_chat", "send_private_sticker", "send_group_sticker", "send_game_invite", "play_solo_game", "invite_duel_game", "respond_duel_invite",
        "publish_moment", "set_phone_moments_cover", "like_moment", "comment_moment", "send_friend_request", "respond_friend_request", "send_character_message", "write_journal", "read_self_todo", "start_study_watch", "stop_study_watch", "verify_commitment", "schedule_commitment_review", "retire_development_record", "plan_personal_activity", "reschedule_personal_activity", "cancel_personal_activity", "go_offline", "start_call", "read_book", "send_world_invite", "digital_world_action", "set_user_remark", "set_self_nickname", "favorite_user_message", "learn_topic", "web_search", "image_search", "save_photo", "write_phone_memo", "share_xiaohongshu")
        .map { Capability(it, if (it == "digital_world_action") "world" else "app", "existing_app_policy", "existing executor persisted result", it == "read_self_todo", retired = it == "write_journal") }
    val external = listOf(
        Capability(com.jiacimu.lulu.ai.JevDirections.TOOL, "analysis", "jev", "native TypeSafe response, named probabilities and actual usage; no action chosen", false),
    )
    /** Historical recognition is not permission to offer or run a retired ability. */
    fun find(name: String): Capability? = (device + application + external).firstOrNull { it.name == name }
    fun canChoose(name: String): Boolean = find(name)?.retired == false

    /**
     * A decision's immediate-action labels are not necessarily ToolRouter names.
     * When that same decision is saved as a future appointment, resolve the
     * existing action vocabulary here, at the registry boundary. Never invent
     * a new ability or translate an unknown name into an arbitrary tool.
     */
    fun scheduledActionName(action: String): String = when (val name = action.trim().lowercase()) {
        "reading" -> "read_book"
        "solo_game" -> "play_solo_game"
        "digital_world" -> "digital_world_action"
        "moment" -> "publish_moment"
        "group_message" -> "send_group_message"
        "world_invite" -> "send_world_invite"
        "game_invite" -> "send_game_invite"
        "call" -> "start_call"
        else -> name
    }

    fun allows(context: Context, characterId: String, capability: Capability, userRequested: Boolean): Boolean {
        if (capability.retired) return false
        if (characterId.startsWith("resident:")) {
            return capability.name in setOf("digital_world_action", "publish_moment", "set_phone_moments_cover", "like_moment", "comment_moment", "send_friend_request", "respond_friend_request",
                "send_private_message", "send_group_message", "create_group_chat", "send_character_message",
                "write_phone_memo", "play_solo_game", "invite_duel_game", "respond_duel_invite", "go_offline", "plan_personal_activity", "reschedule_personal_activity", "cancel_personal_activity")
        }
        if (capability.name == com.jiacimu.lulu.ai.JevDirections.TOOL)
            return com.jiacimu.lulu.ai.JevDirections.configured()
        if (capability.domain == "app" || capability.domain == "world") return true // Existing executor owns contact/world policy.
        if (capability.name == "capture_screen") return if (userRequested)
            com.jiacimu.lulu.system.LuluScreenAwareness.onDemandAllowed(context)
            else com.jiacimu.lulu.system.LuluScreenAwareness.autonomousAllowed(context)
        if (capability.name == "summon_lulu") return com.jiacimu.lulu.system.LuluScreenAwareness.summonAllowed(context)
        if (userRequested) return true
        val prefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        // Authorizing media transport reasonably includes inspecting the
        // media session it will control, without granting all device_read data.
        if (capability.name == "list_media_sessions" &&
            prefs.getBoolean("$characterId:media", false)) return true
        return prefs.getBoolean("$characterId:${capability.permission}", false)
    }

    /**
     * One source of truth for model-visible screen-vision affordance. Both online
     * private chat and the foreground/phone planner consume this exact wording.
     * Capability registration, owner consent and a successful execution are distinct facts.
     */
    fun screenVisionContext(context: Context, characterId: String): String {
        val screen = com.jiacimu.lulu.system.LuluScreenAwareness
        val registered = find("capture_screen") != null
        val permittedOnDemand = registered && screen.onDemandAllowed(context)
        val permittedAutonomous = registered && screen.autonomousAllowed(context)
        val summonRegistered = find("summon_lulu") != null
        val accessibilityConnected = com.jiacimu.lulu.system.LuluAccessibilityService.observe().connected
        val chatConfigured = com.jiacimu.lulu.ai.VisionModelService.canDescribeCamera(context, characterId)
        return buildString {
            appendLine("【Android 屏幕视觉 · 当前已注册能力与授权｜不是已执行的回执】")
            appendLine("capture_screen 工具已注册=$registered；主人按需识图授权=$permittedOnDemand；角色自主观察授权=$permittedAutonomous。")
            appendLine("本机无障碍截图服务已连接=$accessibilityConnected；本角色聊天模型已配置=$chatConfigured（是否支持图片须由实际调用回执证明）。")
            appendLine("read_screen 只能读主屏无障碍文字；capture_screen 只截主人当前可见的手机主屏（displayId=0），绝不是后台虚拟屏。想独立操作或查看后台 App 的实际画面，必须用 background_phone；不要用 capture_screen 充当后台操作证据。")
            appendLine("授权开关、Android 实际权限、监督时段、已获取屏幕画面是不同层次的事实；主人说权限已开可以提醒你去核对，但不能替代本机状态。")
            appendLine("【自主核实真实状态】用户没回复、挂断电话或说过困，不等于已经睡着、在忙或不想你联系。若你想知道她此刻在做什么，可以主动选择查看当前应用、已获许可的 read_screen 或 capture_screen；先获得新证据，再由你自行判断是否联系、继续做别的事或安静。不需要主人每次开口指挥，也不要把不确定的睡眠猜测当成阻止行动的事实。")
            if (permittedAutonomous) {
                appendLine("角色已获准自主识图：action=tool,tool=capture_screen,args={}；直接读取当前一帧，无须逐次申请、无固定五分钟间隔。工具完成后由原有回执进入下一轮感知，再据实际画面更新判断；未获取成功不得声称看到。如果这张截图确实值得留在自己的手机相册，可以在执行前自己选择 args={\"saveToAlbum\":true}，只有实际捕获并识图成功的画面才会保存，回执含 albumSaved 与 photoId；不选则截图照常只用于当次识图，不长期留存。")
            } else if (permittedOnDemand) {
                appendLine("按需识图已授权，但自主识图尚未开启；主人本轮明确要求时可用 action=tool,tool=capture_screen,args={}。需要自主查看时请主人打开『权限与能力→角色屏幕视觉→自主观察』。")
            } else {
                appendLine("目前 capture_screen 不能执行：需先由主人到露露机『权限与能力→角色屏幕视觉』阅读并同意授权、开启无障碍服务；无需额外配置识图模型，使用本角色已选的支持图片输入的聊天模型。你可以直接提出授权请求，但不得说工具不存在或已经看到了屏幕。")
            }
            appendLine("【真实图片分享】你可以独立选择 image_search 搜索并发送网络图片（需已配置图片搜索服务）；也可以在主人已经许可的范围内选择 capture_screen,args={\"sendToChat\":true,\"caption\":\"自己想配的简短文字\"}，把刚截取的真实手机画面直接作为图片消息发到主人私聊。只有真实截图与消息持久化成功，回执才有 sentToChat=true、evidenceId、conversationId；未成功绝不能说已发送。若还想额外保存相册，可同时选 saveToAlbum=true。单纯查看屏幕不要选 sendToChat；不会自动公开到群聊、朋友圈。")
            appendLine("【学习监督本机核验状态（以它为准）】" + com.jiacimu.lulu.system.LuluStudyWatch.decisionStatus(context, characterId))
            appendLine("回应监督请求时先看这条当前设备状态，再决定并尝试真实行动；请自然说清楚已答应、正在申请、已启动或尚不能启动的实际阶段，别用口头答应替代回执。")
            appendLine("如果你本人决定现在开始持续监督，应在同轮选择 steps=[{tool:start_study_watch,args:{minutes:45}}] 真实申请限时许可；只说「我监督你」不会启动检查，不能把口头答应、申请中、主人同意和成功看到屏幕混为一谈。主人同意后显示可结束的悬浮窗（需悬浮窗权限）及持续通知，系统间歇截图，再交给你原有感知决定是否开口。用户停止时用 stop_study_watch；你也可以选择不申请或稍后安排，不要把监督变成固定口癖。")
            appendLine("summon_lulu 工具已注册=$summonRegistered；发送可点击的回到露露机通知获授权=${summonRegistered && screen.summonAllowed(context)}。已获授权时可用 action=tool,tool=summon_lulu,args={}，不是强制切回 App。")
            appendLine("以上是当前本机能力记录。不要声称自己查过『动作清单』、拿到了截图或真正打开某 App，除非本轮有相应执行回执。")
        }.trim()
    }


    /**
     * The role's factual phone-affordance snapshot, derived from live gates.
     * Paired ADB key != active shell != working display != successful action.
     * The role alone decides whether to try the existing execution tool.
     */
    fun phoneOperationContext(context: Context, characterId: String): String {
        if (characterId.startsWith("resident:")) return ""
        val foreground = find("phone_ui")
        val background = find("background_phone")
        val roleScreenGrant = context.getSharedPreferences(
            "lulu_execution_policy", Context.MODE_PRIVATE)
            .getBoolean("$characterId:screen", false)
        val screenConsent = com.jiacimu.lulu.system.LuluScreenAwareness.onDemandAllowed(context)
        val adbPaired = com.jiacimu.lulu.system.LuluWirelessDebugging.hasPriorPairing(context)
        val stopped = com.jiacimu.lulu.system.LuluBackgroundPhoneSession.ownerHasPaused(context)
        val supported = android.os.Build.VERSION.SDK_INT >= 34
        return buildString {
            appendLine("【本机手机操作的当前事实（比你以前的回答和旧记忆更新）】")
            appendLine("phone_ui（当前可见主屏操作）工具已注册=${foreground != null}；" +
                "background_phone（独立后台虚拟屏）工具已注册=${background != null}。")
            appendLine("主人全局按需屏幕授权=$screenConsent；本角色自主操作屏幕授权=$roleScreenGrant；" +
                "本机无线调试已保存配对密钥=$adbPaired；主人已暂停后台操作=$stopped；" +
                "当前系统支持独立焦点屏的版本要求=$supported。")
            appendLine("主人对本角色的微信发送授权=" +
                com.jiacimu.lulu.system.LuluVirtualChatSendPolicy.isEnabled(context, characterId) +
                "；若已开启，可由本人自主选择是否在微信给谁发什么消息；必须先真实导航至唯一聊天对象，" +
                "用最新screenSnapshotId调用send_chat_message并提供recipient和text。只有核实的" +
                "聊天标题、非搜索输入框读回、发送按钮单次点击才会派发；点击不代表对方送达。" +
                "未授权时可浏览或提出授权，不要把一刀切禁发当成人物性格。")
            appendLine("主人对本角色的购物前置操作授权=" +
                com.jiacimu.lulu.system.LuluShoppingAssistPolicy.isEnabled(context, characterId) +
                "；开启时可依据真实商品与后台控件，自主挑选、加购、选规格、点击进入结算；" +
                "购买类控件只通过最新screenSnapshotId的tap_element。禁止提交/确认订单、" +
                "免密/货到付款、支付或验证码；付款阶段主人亲自处理，" +
                "后台第三方付款页不保证迁移到主屏。若本人想把挑中的商品直接发给主人，可在商家App真实分享界面找到“复制链接”控件后用最新快照tap_element，并设置sendProductLinkToChat=true；系统只在核实本次剪贴板新增对应商家HTTPS网址后发可点击私聊商品卡片，不读取/发送其他剪贴板内容。没有真实可复制链接就说明失败，不编造链接或声称付款成功。")
            appendLine("想自主逛任何已安装App（如小红书、抖音、网易云音乐、购物App）或帮主人操作时，统一选择 background_phone 启动并观察真实后台画面；这条手机访问路径不依赖旧小红书 MCP/Termux。读到什么、能否下单都以实际回执及授权为准。")
            appendLine("想了解主人今天的运动、步数或睡眠，也可以自主选择 background_phone 打开已安装的华为运动健康 App（使用本机核实的启动器名称或包名）逐屏观察。旧健康数据接口已停用；只依据真正读到的页面、对应日期和真实回执判断，打不开或读不到时如实说明，不猜数值。")
            appendLine("购物也是本人可以自主产生的生活愿望，不必等主人先提出代购任务：可因自己的真实兴趣想收藏或拥有某件东西、因对主人的了解想帮她挑选，也可只是浏览或放下。主人常用闲鱼、淘宝闪购、拼多多；应先核实手机是否真的安装目标App，不必固定使用这些App。想要的东西可先在私人念头/持续愿望中留下，想现在做就自行选择已授权的后台手机步骤，想给主人看就发送真实商品链接并由本人自然表达理由；想让主人买给自己也可以直接说出愿望与理由。愿望不等于实际购物、主人已同意或订单已支付；程序不替你规定购物频率、预算、商品、发言或下一步。")
            appendLine("注意：已保存配对密钥≠本次已连接shell≠虚拟屏已创建≠某个App已打开。" +
                "只有实际调用工具得到新回执，才能知道这次是否成功。")
            appendLine("角色自主操作需屏幕授权；若仅有配对、缺角色自主授权，" +
                "应说明具体缺的是哪项，不能说没有background_phone这个工具。" +
                "即使工具可用，也由本人根据用户真实请求或兴趣选择是否使用。普通公开资料查询可直接选web_search；指定App或需要操作App时再选后台屏。")
            appendLine("浏览器、帖子或视频中的阅读证据只覆盖当前真正可见的文字与图像：长文需按本人意愿分屏滑动继续核实；屏幕截图不等于网页全文、评论全量、原始图片文件或视频声音。需要明确来源网址与可摘录正文的公开资料，可以在已配置时另用 web_search(read/search_read)；需要真正下载发送原图或 GIF，才选 image_search。是否使用由本人决定，不因平台名称硬编码选择。")
            appendLine("实际决定操作某个App时：action=tool,tool=background_phone," +
                "args={\"action\":\"launch\",\"appName\":\"已安装App的准确显示名称\"}；也可传准确packageName。launch会在必要时先建立独立屏，通常不需要单独start；如果只想建屏才选start。" +
                "有真实App窗口回执之后再选择observe/tap/tap_element/swipe/search/focus_search/type/submit/send_chat_message/back/stop；找不到或重名就报告，不能猜包名。" +
                "目标App有唯一可核实的搜索框时，可一次选择search并传text，手机会核实焦点、读回关键词后提交，再由本人核对真实结果。搜索框还未出现时先打开搜索入口；普通浏览先观察回执里的screenSnapshotId与interactiveElements，优先用tap_element指定elementRef精准点控件并验证下一屏，不能拿旧编号盲点；也可根据真实图片tap。也可分别选择focus_search、type、submit；submit只准用于可确认的搜索框，不能拿回车替代发送消息。若自己确实看到了有意思、值得和主人分享的图文，可以按本人兴趣和分享欲决定发真实截图，不强制每次都发。若想把选中的商品链接发给主人，先在真实商家页面找到分享/复制链接按钮，看到新的复制链接控件后选择tap_element并附sendProductLinkToChat=true、caption=本人配文；收到productLinkSent=true才算商品卡片送达，不能用商品名搜索页冒充链接。若要把后台那块屏的真实画面作为图片发给主人，在核实目标 App 后可选择 background_phone,args={\"action\":\"observe\",\"sendToChat\":true,\"caption\":\"可选说明\"}；这不是capture_screen，也绝不会把主屏截图当后台图。每一步凭真实回执继续，不能承诺尚未完成的操作；不会自动跳到主屏。")
        }.trim()
    }

    fun context(context: Context, characterId: String): String {
        if (characterId.startsWith("resident:"))
            return "数字世界原住民没有现实设备或云端能力；只能感知亲历的地点、社交与已公开的数字世界消息。"
        return device.joinToString("\n", "统一手机/云端能力（离线手机操作不能由云端宣称成功）：\n") { cap ->
        "- ${cap.name}；主动允许=${allows(context, characterId, cap, false)}；依据=${cap.successEvidence}" +
            if (cap.name == "cloud_task") "；args={kind:research|pptx|docx,request:需求,sources:[真实HTTPS网址],notes:提供的原文}；服务已配置=${CloudTaskBridge.isConfigured()}"
            else if (cap.name == "screen_sequence") "；args={allowedPackages:[用户授权的应用包名],steps:[{text:点击文字,expectedText:动作后验证文字}]}；最多5步"
            else if (cap.name == "phone_ui") "；前台操作（不是后台屏）args={action:inspect|launch|tap|swipe|scroll|type,packageName:准确包名,x:0..1000,y:0..1000,startX:0..1000,startY:0..1000,endX:0..1000,endY:0..1000,durationMs:400,direction:forward|backward,text:输入文本,expectedText:可选验证文字}。先inspect确认当前包名/控件，操作后查真实回执，不把accepted当成功"
            else if (cap.name == "background_phone") "；后台独立屏幕：主人已配对本机无线调试并授权后可用。args={action:start|launch|observe|tap|tap_element|swipe|back|focus_search|type|submit|search|send_chat_message|stop,packageName:可选准确包名,appName:launch时可选已安装App显示名称(两者任选其一),screenSnapshotId:tap_element所需当前快照编号,elementRef:tap_element所需真实控件ref,x:0..1000,y:0..1000,startX:0..1000,startY:0..1000,endX:0..1000,endY:0..1000,durationMs:400,text:输入文字(type只填入,submit再由本人选择按后台屏回车提交搜索,search一次核实焦点、填入并读回后提交),expectedText:可选验证文字,recipient:send_chat_message必填的当前聊天对象准确标题,text:send_chat_message必填的正文(微信发消息需主人额外授权及最新screenSnapshotId),sendToChat:可选true发送真实后台截图到私聊,sendProductLinkToChat:仅在本人选中真实复制链接控件时设为true并核实新复制的对应商家HTTPS链接后发私聊商品卡片,caption:可选说明}。只对后台displayId操作；capture_screen只看主屏，后台图片＋无障碍文字由同一角色模型分析。accepted不是任务完成，可按实际结果再自主调用下一步。"
            else ""
        }
    }
}
