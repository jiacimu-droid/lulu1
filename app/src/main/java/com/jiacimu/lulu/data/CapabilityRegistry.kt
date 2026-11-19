package com.jiacimu.lulu.data

import android.content.Context
import android.os.Build
import com.jiacimu.lulu.system.LuluBackgroundPhoneSession
import com.jiacimu.lulu.system.LuluInstalledAppResolver
import com.jiacimu.lulu.system.LuluRolePhoneAuthorization
import com.jiacimu.lulu.system.LuluWirelessDebugging
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
        Capability("sing_song", "music", "music_generation", "Eleven Music original audio saved locally and playback start reported separately", false),
        Capability("play_saved_song", "music", "music_generation", "previously generated role-owned MP3 playback without another API request", false),
        Capability("stop_song", "music", "music_generation", "local stop command for a playing role song", false),
        Capability("control_volume", "phone", "media", "Observed, capped STREAM_MUSIC volume; restores previous volume on request", false),
        Capability("ring_in_call_wake", "phone", "alarm", "short native ringtone inside the current connected call; never redials", false),
        Capability("read_screen", "phone", "screen_vision", "legacy alias: owner-authorized real screenshot plus vision", false),
        Capability("capture_screen", "phone", "screen_vision", "owner-consented screenshot plus vision", false),
        Capability("summon_lulu", "phone", "screen_summon", "user-tappable notification", false),
        Capability("bring_lulu_front", "phone", "screen_direct_return",
            "owner-consented single activity launch with verified main-display foreground", false),
        Capability("click_text", "phone", "screen", "post-action screen observation", false),
        Capability("screen_action", "phone", "screen", "post-action screen observation", false),
        Capability("screen_sequence", "phone", "screen", "bounded fresh observations and expected text per step", false),
        Capability("phone_ui", "phone", "screen", "owner-authorized foreground app actions and fresh UI evidence", false),
        Capability("background_phone", "phone", "screen", "owner-paired shell virtual screen with isolated focus and per-display verification", false),
        Capability("cloud_task", "cloud", "cloud", "persistent task id; file success comes later", false),
    )
    val application = listOf("send_private_message", "send_group_message", "create_group_chat", "send_private_sticker", "send_group_sticker", "send_game_invite", "play_solo_game", "invite_duel_game", "respond_duel_invite",
        "publish_moment", "set_phone_moments_cover", "like_moment", "comment_moment", "send_friend_request", "respond_friend_request", "send_character_message", "write_journal", "read_self_todo", "write_desktop_widget_note", "decorate_desktop_widget", "start_study_watch", "stop_study_watch", "strict_supervision", "verify_commitment", "schedule_commitment_review", "retire_development_record", "plan_personal_activity", "reschedule_personal_activity", "cancel_personal_activity", "go_offline", "start_call", "read_book", "send_world_invite", "digital_world_action", "set_user_remark", "set_self_nickname", "favorite_user_message", "learn_topic", "web_search", "image_search", "save_photo", "write_phone_memo", "share_xiaohongshu")
        .map { Capability(it, if (it == "digital_world_action") "world" else "app", "existing_app_policy", "existing executor persisted result", it == "read_self_todo", retired = it == "write_journal") }
    val external = listOf(
        Capability(com.jiacimu.lulu.ai.JevDirections.TOOL, "analysis", "jev", "native TypeSafe response, named probabilities and actual usage; no action chosen", false),
    )
    /** Historical recognition is not permission to offer or run a retired ability. */
    fun find(name: String): Capability? = (device + application + external).firstOrNull { it.name == name }
    fun canChoose(name: String): Boolean = find(name)?.retired == false

    /**
     * A short, truthful index of things this same person can choose in ANY Full
     * decision (incoming private message or quiet independent life). Entries
     * are projected from the actual registered tools, never a second executable
     * tool list. Permission, configuration, contact/scene ID and real results
     * remain the existing ToolRouter/executor's responsibility.
     */
    fun decisionCapabilityOverview(context: Context, characterId: String): String {
        val categories = listOf(
            "聊天与联系" to listOf("send_private_message", "send_character_message",
                "send_group_message", "create_group_chat", "send_friend_request",
                "respond_friend_request", "start_call", "send_private_sticker", "send_group_sticker"),
            "朋友圈与社交" to listOf("publish_moment", "like_moment", "comment_moment",
                "set_phone_moments_cover", "set_user_remark", "set_self_nickname"),
            "数字世界与朋友见面" to listOf("digital_world_action", "send_world_invite"),
            "真实联网与手机操作" to listOf("web_search", "image_search", "background_phone",
                "phone_ui", "capture_screen", "share_xiaohongshu", "cloud_task"),
            "读书、游戏、音乐" to listOf("read_book", "play_solo_game", "send_game_invite",
                "invite_duel_game", "respond_duel_invite", "list_media_sessions",
                "control_music", "sing_song", "play_saved_song", "stop_song"),
            "收藏与生活记录" to listOf("save_photo", "favorite_user_message",
                "write_phone_memo", "read_self_todo", "write_desktop_widget_note",
                "decorate_desktop_widget", "learn_topic"),
            "日程、责任与休息" to listOf("plan_personal_activity",
                "reschedule_personal_activity", "cancel_personal_activity",
                "verify_commitment", "schedule_commitment_review", "create_alarm",
                "list_alarms", "cancel_alarm", "start_study_watch", "stop_study_watch",
                "strict_supervision", "go_offline"),
            "设备与感知" to listOf("get_battery", "get_location", "get_current_app",
                "read_recent_notifications", "get_screen_state", "list_alarms",
                "control_volume", "summon_lulu", "bring_lulu_front"),
        )
        val active = (device + application + external).asSequence()
            .filterNot { it.retired }
            .map { it.name }.toSet()
        val shown = mutableSetOf<String>()
        return buildString {
            appendLine("【本人可选择的能力目录｜私聊、在线窗口和后台独自感知共用】")
            for ((label, candidates) in categories) {
                val offered = candidates.filter { name ->
                    name in active &&
                        find(name)?.let { allows(context, characterId, it, false) } == true &&
                        shown.add(name)
                }
                if (offered.isNotEmpty()) appendLine("$label：${offered.joinToString("、")}")
            }
            // Future registered tools are not silently invisible to the
            // character merely because the human-readable categories lag.
            val remaining = active.filter { it !in shown }.filter { name ->
                find(name)?.let { allows(context, characterId, it, false) } == true
            }.sorted()
            if (remaining.isNotEmpty()) appendLine("其他已接通：${remaining.joinToString("、")}")
            appendLine("只列出此刻按既有授权可尝试的已注册工具，不等于执行成功；" +
                "搜索API还须配置、手机操作还须真实屏幕与权限、社交需要有效对象ID。" +
                "没有列出的设备工具可能需要主人先开启授权，不可假称已禁用全部能力。")
            // A compact actionable index, *not* another executable registry.
            // Every example is projected only if the registered tool is offered
            // to this actual role. The full per-tool catalogue remains the
            // authority for required IDs and scene-specific arguments.
            val examples = listOf(
                "publish_moment" to "朋友圈 publish_moment(text:本人选择的公开内容)",
                "like_moment" to "点赞 like_moment(postId)",
                "comment_moment" to "评论 comment_moment(postId,text,replyToCommentId:可选)",
                "set_phone_moments_cover" to "换朋友圈封面 set_phone_moments_cover(preset或postId)",
                "send_character_message" to "找好友私聊 send_character_message(targetCharacterId,bubbles或text)",
                "send_group_message" to "群聊发言 send_group_message(groupId,text)",
                "create_group_chat" to "建好友群 create_group_chat(groupName,memberIds)",
                "send_friend_request" to "加好友 send_friend_request(targetCharacterId,greeting)",
                "respond_friend_request" to "回应申请 respond_friend_request(requestId,decision)",
                "digital_world_action" to "出门/拜访/当面交流 digital_world_action(worldAction,locationCode或itemId,activityId)",
                "send_world_invite" to "邀请见面 send_world_invite(location,text)",
                "send_private_message" to "主动找用户聊天 send_private_message(text)",
                "start_call" to "拨语音或Live2D视频 start_call(text,mediaMode=voice|video)",
                "web_search" to "联网查询或读网页 web_search(op=search|search_read|read,query或url)",
                "image_search" to "搜图/分享 image_search(op=search|send,query或searchId)",
                "background_phone" to "后台虚拟屏/本机App background_phone(action=launch|observe|tap|type|search等,appName或packageName)",
                "capture_screen" to "看授权主屏 capture_screen()",
                "read_book" to "亲自阅读 read_book(readingBookId)",
                "play_solo_game" to "独自玩游戏 play_solo_game(gameId)",
                "send_game_invite" to "约用户玩游戏 send_game_invite(gameId,text)",
                "invite_duel_game" to "约数字朋友对战 invite_duel_game(gameId,targetCharacterId)",
                "sing_song" to "创作原创歌曲 sing_song(prompt,durationSeconds)",
                "control_music" to "操作真实媒体会话 control_music(command)",
                "save_photo" to "相册收藏真实图片 save_photo(messageId或searchId,index)",
                "favorite_user_message" to "收藏用户原话 favorite_user_message(messageId,reason)",
                "write_phone_memo" to "写本人备忘录 write_phone_memo(title,content)",
                "read_self_todo" to "查看用户自序 read_self_todo(scope=today|all)",
                "plan_personal_activity" to "安排自己的未来行动 plan_personal_activity(title,intent,dueAt,activityAction,activityArgs)",
                "reschedule_personal_activity" to "改期 reschedule_personal_activity(planId,expectedDueAt,dueAt,reason)",
                "start_study_watch" to "申请限时学习监督 start_study_watch(minutes)",
                "get_battery" to "观察电量 get_battery()",
                "get_location" to "查询授权定位 get_location()",
            ).filter { (name, _) -> name in shown }
            if (examples.isNotEmpty()) {
                appendLine("【何时想做即可选择的真实工具及参数线索｜需按现场ID和配置核实】")
                examples.chunked(5).forEach { batch ->
                    appendLine(batch.joinToString("；") { it.second })
                }
            }
            if ("web_search" in shown && !WebSearchProvider.isConfigured(context))
                appendLine("web_search 目前缺少搜索服务配置，不能宣称已经查到网页。")
            if ("image_search" in shown && !ImageSearchProvider.configured(context, "serper") &&
                !ImageSearchProvider.configured(context, "giphy"))
                appendLine("image_search 目前缺少图像搜索配置，不能宣称已搜索或发送。")
            appendLine("【选择与行动】本人随时可以从已有经历、关系、兴趣或未完事项中自选 0/1/N 个动作；" +
                "前台私聊、在线窗口、后台感知使用同一真实工具。聊天不是工具禁用状态：" +
                "若既想回复又想发朋友圈、搜网页、联系朋友或读书，本轮回复照常填写，" +
                "独立动作同时写 steps=[{tool:已注册工具名,args:{实际参数},requiresPreviousSuccess:false}]；" +
                "先看到搜索/画面结果才能决定的下一步，须等新回执再选择，不能预写成功。" +
                "每步独立校验授权、真实对象和持久回执；明确的未来行动由原 plan_personal_activity 保存，" +
                "不是只写在心声中。无需每轮找事做，也不要为展示活跃重复发圈或打扰别人。")
        }.trim()
    }

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
                "write_phone_memo", "write_desktop_widget_note", "decorate_desktop_widget", "play_solo_game", "invite_duel_game", "respond_duel_invite", "go_offline", "plan_personal_activity", "reschedule_personal_activity", "cancel_personal_activity")
        }
        if (capability.name in setOf("sing_song", "play_saved_song", "stop_song"))
            return com.jiacimu.lulu.ElevenLabsMusic.isEnabledAndConfigured(context, characterId)
        if (capability.name == com.jiacimu.lulu.ai.JevDirections.TOOL)
            return com.jiacimu.lulu.ai.JevDirections.configured()
        if (capability.domain == "app" || capability.domain == "world") return true // Existing executor owns contact/world policy.
        // Screen observation always captures real image pixels. The legacy
        // read_screen spelling must never bypass screenshot consent by
        // falling through to device_read or return text-only "success".
        if (capability.name in setOf("capture_screen", "read_screen")) return if (userRequested)
            com.jiacimu.lulu.system.LuluScreenAwareness.onDemandAllowed(context, characterId)
            else com.jiacimu.lulu.system.LuluScreenAwareness.autonomousAllowed(context, characterId)
        if (capability.name == "summon_lulu") return com.jiacimu.lulu.system.LuluScreenAwareness.summonAllowed(context)
        if (capability.name == "bring_lulu_front")
            return com.jiacimu.lulu.system.LuluScreenAwareness.directReturnAllowed(context)
        if (userRequested) return true
        val prefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        // Media playback inspection belongs to the media grant, not virtual-screen access.
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
        val permittedOnDemand = registered && screen.onDemandAllowed(context, characterId)
        val permittedAutonomous = registered && screen.autonomousAllowed(context, characterId)
        val rolePhoneGrant =
            com.jiacimu.lulu.system.LuluRolePhoneAuthorization.isGranted(context, characterId)
        val summonRegistered = find("summon_lulu") != null
        val accessibilityConnected =
            com.jiacimu.lulu.system.LuluAccessibilityService.observe().connected
        val chatConfigured =
            com.jiacimu.lulu.ai.VisionModelService.canDescribeCamera(context, characterId)
        return buildString {
            appendLine("【主屏感知｜以下是权限和设备状态，不是实际查看回执】")
            appendLine("capture_screen 工具已注册=$registered；主人按需识图授权=$permittedOnDemand；角色自主观察授权=$permittedAutonomous；本角色手机屏幕授权=$rolePhoneGrant；无障碍已连接=$accessibilityConnected；视觉模型已配置=$chatConfigured。")
            appendLine("【授权时效事实】主人保存的角色手机屏幕授权及主屏视觉开关没有截图次数或时间倒计时。一次截图结束、点击停止查看、某个未处理请求超时，都不会让Android自动收回这些授权；已授权后无需每次重新申请。两分钟超时只适用于尚未处理的单次请求；start_study_watch的限时监督到期只结束那次监督，并不撤销常驻手机/视觉授权。无障碍断连、锁屏、提示通知不可用、页面变化或截图报错是独立的设备状态，不能臆称授权过期。")
            appendLine("【回答权限疑问】上方布尔状态是此刻系统事实，优先于此前的对话、失败记忆和你自己的猜测；授权=true且设备条件可用时直接调用工具取得新画面，不以旧失败代替新尝试。若工具失败，只依据本次真实回执指出缺失的具体条件；没有新回执，不许推断“Android限时收回权限”“必须重新发起许可”或已经看见主人正在做什么。")
            appendLine("屏幕观察只有一条统一视觉路径：capture_screen先获取主屏真实截图，再交给本角色模型看图理解；无障碍树只作定位与文字辅助。capture_screen 只截主人当前可见的手机主屏（displayId=0），不能冒充独立虚拟屏。旧工具名read_screen是相同截图能力的兼容别名，不再提供纯文字假观察。要查看或操作独立后台屏必须用 background_phone，并核对回执mode与真实新画面；未配对无线调试时只有可见主屏纯无障碍模式。截图走系统无障碍服务，不要要求MediaProjection；需要连续操作第三方App则使用background_phone，每次observe也要有新画面与实际回执。")
            if (permittedAutonomous) {
                appendLine("现在已获准自主查看主屏：无论主人有没有发消息或要求查看、是否正在学习监督，你都可以自己决定何时调用 action=tool,tool=capture_screen,args={} 取得当前单帧。主人明确要求查看时应直接尝试，**无须逐次申请**，也没有固定五分钟冷却。read_screen也会取得新截图，只有模型真正接到画面或视觉服务返回识图结果才算看见。")
            } else if (permittedOnDemand) {
                appendLine("主人明确请求时，直接用 action=tool,tool=capture_screen,args={} 查看；角色自主观察授权=false，不能偷偷改成自动监看。")
            } else {
                appendLine("当前屏幕视觉未获有效授权：先由主人在角色手机屏幕授权或『权限与能力→角色屏幕视觉』开启；无障碍系统权限也必须真实连接。不要要求MediaProjection。")
            }
            appendLine("失败处理：窗口临时为空、输入法切换、通知遮挡或截图服务拒绝，属于本次观察失败，不等于隐私规则禁止；以新工具回执报告真实原因，适合时重新观察一次。只有主人亲自填写的屏幕排除包名才能触发截图排除；Android自身的锁屏/受保护画面仍可能无法截图。授权打开不等于这次截图成功，未见新像素时不能说看到了。")
            appendLine("截图时露露机会显示『正在查看屏幕』的低打扰状态通知，允许的悬浮窗也可显示进度；这只是观察提示，不是逐次审批。角色消息横幅、后台虚拟屏和主屏截图彼此不同。")
            appendLine("如果你本人决定了解主人是不是仍醒着、在学习或用手机，可基于已授权的实时屏幕证据自己作出判断；无回复不能证明睡着，单张截图也不能证明持续专注。")
            appendLine("仅在你明确选择发给主人时使用 capture_screen,args={\"sendToChat\":true,\"caption\":\"简短说明\"}；回执真实sentToChat=true才算发送。额外保存自己相册可单独选saveToAlbum=true，不选就不要声称已保存。")
            appendLine("【监督本机状态】" + com.jiacimu.lulu.system.LuluStudyWatch.decisionStatus(context, characterId))
            appendLine("持续督学是独立限时监督：要真正开始须调用 start_study_watch 并取得当前时段授权，不能把一次主屏截图当作持续监督。用户停止后立即停止。")
            appendLine("summon_lulu 工具已注册=$summonRegistered；通知邀请获准=${summonRegistered && screen.summonAllowed(context)}；它只发送用户可点击的回到露露机通知。bring_lulu_front需单独授权=${screen.directReturnAllowed(context)}，执行后必须核对最新主屏才能称成功。")
            appendLine(com.jiacimu.lulu.system.LuluStrictMode.modelContext(context, characterId))
            appendLine("角色应该做真实行动而非预先说系统不允许；但不能绕过主人关闭的权限，也不能凭旧回执编造新截图。")
        }.trim()
    }


    internal fun independentBrowseExamples(context: Context, characterId: String): String? {
        if (characterId.startsWith("resident:") ||
            !LuluRolePhoneAuthorization.isGranted(context, characterId) ||
            Build.VERSION.SDK_INT < 34 ||
            !LuluWirelessDebugging.hasPriorPairing(context) ||
            LuluBackgroundPhoneSession.ownerHasPaused(context)) return null

        return LuluInstalledAppResolver.browsingExamples(context).joinToString("、") {
            val label = LuluInstalledAppResolver.browsingAppLabel(it.packageName) ?: it.label
            "$label（${it.packageName}）"
        }
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
        val roleScreenGrant =
            com.jiacimu.lulu.system.LuluRolePhoneAuthorization.isGranted(context, characterId)
        val screenConsent = com.jiacimu.lulu.system.LuluScreenAwareness.onDemandAllowed(context, characterId)
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
            appendLine("主人统一手机操作授权涵盖普通浏览与社交互动=" + roleScreenGrant)
            appendLine(com.jiacimu.lulu.system.LuluPhoneInteractionPolicy.modelGuide)
            appendLine("主人统一手机操作授权涵盖购物导航=" +
                com.jiacimu.lulu.system.LuluShoppingAssistPolicy.isEnabled(context, characterId) +
                "；开启时可依据真实商品与后台控件，自主挑选、加购、选规格、点击进入结算；" +
                "购买类控件只通过最新screenSnapshotId的tap_element。禁止提交/确认订单、" +
                "免密/货到付款、支付或验证码；付款阶段主人亲自处理，" +
                "后台第三方付款页不保证迁移到主屏。若本人想把挑中的商品直接发给主人，先在商家实际界面打开分享：真实控件可用时选最新快照tap_element，只有本次截图能清楚看到复制按钮而无控件编号时选截图坐标tap(target为复制按钮)，两种方式都可附sendLinkToChat=true；后台仅在真实复制、新鲜商家HTTPS链接核实且私聊卡片确实保存后反馈发送成功，绝不读取或转发无关剪贴板文字。复制目标过期须重新观察，不能因为一次控件校验失败就声称系统禁止复制；确实没有可用分享方式时才如实报告未完成，不编造链接、下单或付款。")
            if (!adbPaired || !supported) {
                appendLine("【无无线调试的纯无障碍线路】background_phone 未指定模式时可切换到 foreground_accessibility 主屏模式；显式指定 independent_virtual_display 时保留独立屏要求：无需配对ADB，但要开启Android无障碍、本角色手机授权和主人屏幕读取授权。先在露露机界面选 background_phone(action=launch,appName=真实应用名称)；手机会真实切到第三方App，主人不能同时使用其他App。后续同一角色可按实时主屏截图+screenSnapshotId执行observe/tap/tap_element/type/swipe/back/navigate，不能假装是独立后台屏。主人操作、锁屏、换App、撤权或点击停止就中断；无新照片或唯一控件就先观察，不猜坐标。")
                appendLine("主屏模式的普通输入与点击可继续验证；send_chat_message、publish_comment等复合发表、sendLinkToChat自动转卡片尚不支持验证，不要假称发送成功。微信消息可以根据确切收件人界面依次选焦点、type全文、再点真实发送按钮并只读检查；复制链接不代表已经回传给主人。")
            } else {
                appendLine("本机已经配对无线调试且支持独立屏，可优先使用 background_phone 的独立虚拟屏；想明确操作可见主屏时可传mode=foreground_accessibility。独立屏这不是capture_screen，绝不会把主屏截图当后台图；两者不能混用截图、坐标和控件。")
            }
            appendLine("想自主逛任何已安装App（如小红书、抖音、网易云音乐、购物App）或帮主人操作时，统一选择 background_phone。独处浏览明确传 mode=independent_virtual_display；独立屏不可用时可以暂缓或换一种消遣。只有真实目标窗口观察回执才算看到内容。旧小红书 MCP、Termux、额外网页搜索或图片搜索不是使用这些App的必经路线。")
            appendLine("想了解主人今天的运动、步数或睡眠，也可以自主选择 background_phone 打开已安装的华为运动健康 App（使用本机核实的启动器名称或包名）逐屏观察。旧健康数据接口已停用；只依据真正读到的页面、对应日期和真实回执判断，打不开或读不到时如实说明，不猜数值。")
            appendLine("购物也是本人可以自主产生的生活愿望，不必等主人先提出代购任务：可因自己的真实兴趣想收藏或拥有某件东西、因对主人的了解想帮她挑选，也可只是浏览或放下。主人常用闲鱼、淘宝闪购、拼多多；应先核实手机是否真的安装目标App，不必固定使用这些App。想要的东西可先在私人念头/持续愿望中留下，想现在做就自行选择已授权的后台手机步骤，想给主人看就发送真实商品链接并由本人自然表达理由；想让主人买给自己也可以直接说出愿望与理由。愿望不等于实际购物、主人已同意或订单已支付；程序不替你规定购物频率、预算、商品、发言或下一步。")
            appendLine("注意：已保存配对密钥≠本次已连接shell≠虚拟屏已创建≠某个App已打开。" +
                "只有实际调用工具得到新回执，才能知道这次是否成功。")
            appendLine("角色自主操作需屏幕授权；若仅有配对、缺角色自主授权，" +
                "应说明具体缺的是哪项，不能说没有background_phone这个工具。" +
                "即使工具可用，也由本人根据用户真实请求或兴趣选择是否使用。普通公开资料查询可直接选web_search；指定App或需要操作App时再选后台屏。")
            appendLine("浏览器、帖子或视频中的阅读证据只覆盖当前真正可见的文字与图像：长文需按本人意愿分屏滑动继续核实；屏幕截图不等于网页全文、评论全量、原始图片文件或视频声音。需要明确来源网址与可摘录正文的公开资料，可以在已配置时另用 web_search(read/search_read)；需要真正下载发送原图或 GIF，才选 image_search。是否使用由本人决定，不因平台名称硬编码选择。")
            appendLine("已知应该点的可见名字时，launch可附navigationLabels一次打开并逐个导航，也可在已打开的App用navigate。每个名字必须在当时页面上完整匹配。例如微信看朋友圈可launch并传navigationLabels=[\"发现\",\"朋友圈\"]；已知联系人名字也可直接尝试打开。按navigationSteps核对真实变化；名字未出现时先观察或搜索，发送和发表仍选择独立动作。")
            appendLine("实际决定操作某个App时：action=tool,tool=background_phone,args={\"action\":\"launch\",\"appName\":\"已安装App的准确显示名称\"}；也可传准确packageName。launch会在必要时先建立独立屏。需要给主人发后台截图时可在实际observe/launch回执允许的情况下选sendToChat=true；只有本角色目标独立屏新帧已核实、实际发送回执成功，才能称后台截图已发出。foreground_accessibility主屏模式不可冒充后台图；截图失败或发送失败就说明对应阶段，不能沿用旧图。")
            appendLine("type只输入草稿，绝不自动搜索/发送/发布；先核实输入框和草稿是否真的写入，随后由本人决定是否执行独立搜索、发送或发表动作。")
            appendLine("目标App有唯一真实搜索框时可用search(text)，系统自动聚焦、读回并提交搜索；submit仍只针对可确认的搜索框，不能拿回车替代发送消息。普通社交发表使用真实按钮的tap或tap_element。")
            appendLine("独立后台画面这不是capture_screen；只有实测回执mode=independent_virtual_display和对应独立屏新帧才可称已经看过后台；绝不会把主屏截图当后台图。若设备不支持独立屏，未指定模式的 background_phone 可用 foreground_accessibility 主屏；明确请求独立屏时不会改用主屏。")
            appendLine("只有mode=independent_virtual_display才是独立后台屏；mode=foreground_accessibility是主屏，绝不能称两者相同。后台图片不会混成主屏；主屏模式不可称隐藏后台操控。长任务未做完时保留真实进度，不假装已完成。")
        }.trim()
    }

    fun context(context: Context, characterId: String): String {
        if (characterId.startsWith("resident:"))
            return "数字世界原住民没有现实设备或云端能力；只能感知亲历的地点、社交与已公开的数字世界消息。"
        return device.joinToString("\n", "统一手机/云端能力（离线手机操作不能由云端宣称成功）：\n") { cap ->
        "- ${cap.name}；主动允许=${allows(context, characterId, cap, false)}；依据=${cap.successEvidence}" +
            if (cap.name == "cloud_task") "；args={kind:research|pptx|docx,request:需求,sources:[真实HTTPS网址],notes:提供的原文}；服务已配置=${CloudTaskBridge.isConfigured()}"
            else if (cap.name == "sing_song") "；args={prompt:原创歌曲的语言/歌词/曲风/唱法要求,durationSeconds:3..600}；不使用普通TTS Voice ID，需单独配置 Music Finetune ID 才有专属歌声；生成消耗Music额度"
            else if (cap.name == "play_saved_song") "；args={}；重播本角色最后一次成功保存的原创歌曲，不再消耗音乐生成额度"
            else if (cap.name == "stop_song") "；args={}；立即停止本机正在播放的生成歌曲"
            else if (cap.name == "bring_lulu_front") "；args={}；仅在主人于权限与能力独立开启直接切回授权、无障碍连接、主屏解锁且当前非敏感应用时，可由角色选择一次真实切回露露机；只核实主屏前台，不强行停止第三方App，也不保证用户之后不会再次打开。"
            else if (cap.name == "screen_sequence") "；args={allowedPackages:[用户授权的应用包名],steps:[{text:点击文字,expectedText:动作后验证文字}]}；最多5步"
            else if (cap.name == "phone_ui") "；前台操作（不是后台屏）；inspect实际截取主屏一帧交给本角色模型，同时附无障碍控件作为辅助，不会只靠文字声称看到了画面。args={action:inspect|launch|tap|swipe|scroll|type,packageName:准确包名,x:0..1000,y:0..1000,inputX:仅type视觉输入时可选本次截图的文本框中心0..1000,inputY:与inputX配对且需screenSnapshotId,startX:0..1000,startY:0..1000,endX:0..1000,endY:0..1000,durationMs:400,direction:forward|backward,text:输入文本,expectedText:可选验证文字}。先inspect确认当前包名/控件，操作后查真实回执，不把accepted当成功"
            else if (cap.name == "background_phone") "；手机操作：显式 mode=independent_virtual_display 只用已配对的独立后台屏；未指定模式且独立屏不可用时才会使用可见主屏纯无障碍模式（需要角色手机授权、屏幕读取和通知）。args={action:start|launch|adopt_app|observe|tap|long_press|tap_element|navigate|swipe|back|focus_search|type|submit|search|send_chat_message|publish_comment|stop|open_wechat_moments_composer|publish_wechat_moment,packageName:可选准确包名,appName:launch时可选已安装App显示名称(两者任选其一),navigationLabels:launch可附或navigate必填的有序页面名称数组,screenSnapshotId:点击控件、发私信及发朋友圈所需当前快照编号,elementRef:点击控件或发表评论的提交按钮所需真实控件ref,editorRef:publish_comment的当前评论输入框ref,contextText:publish_comment目标帖子的真实原文片段,target:看图点击或长按的真实对象,screenSnapshotId:看图点击和控件操作必填最新编号,longPress:tap_element可选true长按,x:0..1000,y:0..1000,startX:0..1000,startY:0..1000,endX:0..1000,endY:0..1000,durationMs:400,text:输入文字(type可填写任意已授权App当前聚焦普通编辑框,只写草稿；若无控件树可附当前截图inputX/inputY和screenSnapshotId;search才核实唯一搜索框并提交),expectedText:可选验证文字,recipient:send_chat_message必填的当前聊天对象准确标题,composerX/composerY:仅微信无障碍输入框为空但真实截图已有文字输入区时可选的0..1000输入区中心,text:send_chat_message或publish_wechat_moment所需全文(使用统一手机授权及最新screenSnapshotId),sendToChat:可选true发送真实后台截图到私聊,sendLinkToChat:在真实分享面板点击复制链接时设为true，把本次从当前App核实的帖子、商品或网页链接发为私聊卡片，linkSent=true代表已送达私聊,caption:可选说明}。只对后台displayId操作；capture_screen只看主屏，后台图片＋无障碍文字由同一角色模型分析。accepted不是任务完成，可按实际结果再自主调用下一步。"
            else ""
        }
    }
}
