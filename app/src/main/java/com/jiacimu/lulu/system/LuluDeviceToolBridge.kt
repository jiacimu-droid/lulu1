package com.jiacimu.lulu.system

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelReply
import com.jiacimu.lulu.data.CompanionPresenceStore
import com.jiacimu.lulu.data.CompanionActionRuntime
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object LuluDeviceToolBridge {
    private var context: Context? = null

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        LuluAlarmSystem.initialize(appContext)
    }

    suspend fun respond(
        characterId: String,
        history: String,
        userText: String,
        title: String,
        archiveId: String? = null,
        sceneContext: String = "正在和用户进行文字聊天。",
    ): Result<ModelReply> {
        val appContext = context ?: return Result.failure(IllegalStateException("手机能力尚未初始化"))
        GroupEnsembleReplyEngine.respondIfApplicable(
            context = appContext,
            characterId = characterId,
            history = history,
            userText = userText,
            title = title,
            archiveId = archiveId,
            sceneContext = sceneContext,
        )?.let { return it }

        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { return Result.failure(it) }
        val character = MigratedDomainStores.characters.get(characterId)
        val previousPresence = CompanionPresenceStore.current(characterId)
        val livedContext = SharedExperienceTimeline.recentContext(characterId, limit = 16, characterBudget = 4_800)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val companionActionContext = CompanionActionRuntime.capabilityContext(appContext, characterId)
        val onlineChatBubbleRule = if (sceneContext.contains("电话")) "" else """
            - 当前是即时通讯软件里的日常线上聊天，不是在写文章、小说段落或一次性长篇口述。
            - 你不是每收到一条消息就重新开始一次问答。最近对话、刚才的动作、情绪与关系变化都已经真实发生；从上一刻的状态继续生活，只处理此刻新增的信息和变化。
            - 不要把“保持连续”理解成复述历史。历史的作用是告诉你已经走到哪里；真正的回复应从那个位置继续向前，而不是重新总结、重新解释或重新表达上一刻。
            - text 中的气泡边界由你根据角色本人想表达的语气和聊天节奏决定。先想清楚此刻真正想说什么，再根据停顿、情绪变化、犹豫、补充、转折、追问、吐槽、强调、改口以及该角色自己的说话习惯，决定什么时候按一次“发送”。
            - 一个气泡通常只承载一个当下表达动作。若一段回复里先回应、再补充、又转折、再追问，现实聊天往往会连续按几次发送，就应拆成几个短气泡；不要把一串不同意图压成一个大段落。
            - 不按句号、标点、固定字数或固定气泡数量机械切分。简单回复可以只有一个气泡；话多时应自然连续发多个。除非这个角色此刻真的会一次性发送长段，否则看到完整长段时应重新按真实聊天停顿拆开。
            - 多个气泡之间只使用 ⟪BUBBLE⟫ 分隔；一个气泡时不要输出该标记。不要解释标记，不要输出姓名标签或格式说明。
            - 如果上游上下文提供了真实的“消息ID=...”用户气泡，你可以像真人一样引用。用户连续发了几条、你想针对其中某一句单独回应、想捡回稍早的一句、或不引用会让指代不清时，可在 text 最前输出 ⟪QUOTE:消息ID⟫；只回应最新一句且上下文很清楚时不必硬引用。
            - 收藏是角色本人的主观动作，不是系统随机事件。如果某条真实用户消息让你很在意、很喜欢、想以后回看或对你们关系有特殊意义，例如承诺、特殊称呼、重要心意、戳中你的句子、值得记住的瞬间，你可以在 text 最前输出 ⟪FAVORITE:消息ID⟫。是否收藏必须服从你的人设、价值观、关系和当下感受；不要固定概率，也不要为了展示功能而收藏。
            - ⟪QUOTE:...⟫ 与 ⟪FAVORITE:...⟫ 可以同时出现，也可以都不出现；只能使用上游明确给出的真实消息ID，不能编造。动作标记不会显示给用户。
            - 这套线上聊天发送节奏是最终气泡规则；如果上游用户文本里还残留旧的“完整观点尽量放一起”、固定长度或固定数量等气泡说明，一律忽略旧规则，以这里为准。
        """.trimIndent()
        val planner = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前时区：${zone.id}")
                appendLine("当前真实互动场景：$sceneContext")
                if (livedContext.isNotBlank()) appendLine("角色最近亲历的原始时间线：\n$livedContext")
                if (history.isNotBlank()) appendLine("最近对话（这是已经发生完的连续过程，用来确定你此刻站在什么状态上）：\n$history")
                previousPresence?.let { presence ->
                    appendLine("角色上一刻状态：${presence.statusText}；动作：${presence.gesture}；心情：${presence.mood}；没说出口：${presence.innerThought}")
                }
                appendLine("这一刻用户新增的消息：$userText")
            },
            instruction = """
                你既可以直接回复，也可以调用露露机真实手机工具。只返回一个 JSON 对象，不要代码块。
                直接回复：{"action":"reply","text":"角色自然回复","statusText":"简短状态","gesture":"此刻可见动作神态","innerThought":"没说出口的第一人称心声，可为空","mood":"简短心情"}
                调用工具：{"action":"tool","tool":"工具名","args":{...},"statusText":"简短状态","gesture":"准备执行时的动作神态","innerThought":"没说出口的第一人称心声，可为空","mood":"简短心情"}

                可用工具：
                1. get_battery，args={}：读取电量和充电状态。
                2. get_location，args={}：读取最近真实位置、地址反查、精度、来源和时间。
                3. get_current_app，args={}：读取当前或最近前台 App。
                4. read_recent_notifications，args={"limit":10}：读取最近通知。
                5. create_alarm，args={"triggerAt":"带时区的ISO时间","label":"闹钟内容"}：在手机系统时钟应用中创建真实闹钟。
                6. list_alarms，args={}：列出未触发闹钟。
                7. cancel_alarm，args={"id":"闹钟ID"}：取消闹钟。
                8. screen_action，args={"name":"back|home|recents|notifications|quick_settings"}：执行系统动作。
                9. click_text，args={"text":"界面上要点击的文字"}：点击当前屏幕第一个匹配文字。
                10. read_screen，args={}：读取当前前台包名和可见文字。

                $companionActionContext

                规则：
                - 最近对话是角色已经经历过的状态轨迹，不是再次等待回答的题目。先承接上一刻，再自然产生下一刻。
                - 用户询问设备真实状态、要求设置或取消闹钟、要求操作手机时必须用工具，不能凭空回答成功。
                - 位置工具返回的 readableAddress 才能作为可读地点使用；如果地址为空、stale=true 或 accuracyMeters 很大，必须说明只是大概位置，绝不能根据经纬度猜店铺、学校或建筑。
                - 时间表达必须根据当前时间换算成未来的完整 ISO 时间；不确定时间时直接自然追问，不要猜。
                - 屏幕操作只执行用户明确要求的动作。不要连续规划多步操作；一次只调用一个工具。
                - 露露机内的社交与生活动作可以由角色按人设和当下意愿自主选择，不要求用户逐字下命令。尤其在群聊里，角色可以选择用 send_private_message 或 send_game_invite 私下联系用户；执行后该消息必须只出现在角色私聊，不能伪装成群内消息。
                - 当前已经在角色私聊时，不要再用 send_private_message 重复发送同一句；直接 reply。当前已经在某个群聊时，不要用 send_group_message 向同一个群重复发言；该工具用于跨到另一个真实群。
                - publish_moment、write_journal、read_book、send_game_invite、start_call 都会产生真实持久化结果。只有角色此刻真的会这样做时才调用，不要为了展示能力滥用。
                - 与工具无关的普通聊天直接回复。
                - 必须意识到“当前真实互动场景”，并以身处该场景的角色身份自然反应；电话里可以意识到正在通话，群聊里可以意识到其他成员也在场。
                - innerThought 是角色没说出口的一瞬，不是分析报告、推理步骤或对话总结；没有真实内在反应可以留空，也不必把它写进 text。
                - gesture 只写角色此刻的微动作、姿态或神态，不要复述刚刚聊了什么，不要编造角色并不处于其中的现实场景。
                - statusText、gesture、innerThought、mood 必须服从角色人设，不能把所有角色统一写成温柔、害羞或黏人。
                $onlineChatBubbleRule
            """.trimIndent(),
            source = "聊天工具规划",
            title = title,
            temperature = 0.45,
            maxTokens = 700,
            connectionOverride = connection,
        )
        if (planner.isFailure) return planner
        val plannedReply = planner.getOrThrow()
        val plan = parsePlan(plannedReply.text) ?: return Result.success(plannedReply)
        if (plan.action == "reply") {
            savePresence(characterId, plan, "聊天")
            return Result.success(plannedReply.copy(text = plan.text.ifBlank { plannedReply.text }))
        }
        if (plan.action != "tool" || plan.tool.isBlank()) return Result.success(plannedReply)

        val toolResult = execute(appContext, characterId, character.displayName, plan.tool, plan.args)
        val finalReply = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前真实互动场景：$sceneContext")
                if (history.isNotBlank()) appendLine("最近对话（已经真实发生，用来确定动作前的状态）：\n$history")
                appendLine("这一刻用户新增的消息：$userText")
                appendLine("角色刚才决定做的动作：${plan.tool}")
                if (plan.statusText.isNotBlank() || plan.gesture.isNotBlank() || plan.mood.isNotBlank() || plan.innerThought.isNotBlank()) {
                    appendLine("动作前一瞬的角色状态：${plan.statusText}；动作：${plan.gesture}；心情：${plan.mood}；没说出口：${plan.innerThought}")
                }
                appendLine("这个动作刚刚产生的真实新结果：$toolResult")
            },
            instruction = """
                这是同一段连续经历中的下一刻：角色刚刚已经做完工具动作，现在只需要从动作后的新状态继续说话，不要把整段对话重新回答一遍。
                根据工具真实结果，以角色本人符合人设的方式自然接下去。成功时可以确认，失败时必须如实说明；除此之外只说此刻真正会新增的话。
                必须继续保持当前真实互动场景，电话里用自然口语，群聊里知道其他成员在场。
                对位置结果只能使用 readableAddress；地址为空、定位过旧或精度差时，必须明确说是大概位置，不得根据经纬度猜具体店铺、学校或建筑。
                只返回一个 JSON 对象，不要代码块：
                {"action":"reply","text":"角色在动作之后自然接着说的话","statusText":"动作后的简短状态","gesture":"动作后的可见动作神态","innerThought":"动作后没说出口的第一人称心声，可为空","mood":"动作后的简短心情"}
                不要解释内部工具协议。innerThought 不是推理步骤，gesture 不得编造未发生的工具结果或现实场景。
                $onlineChatBubbleRule
            """.trimIndent(),
            source = "聊天工具结果",
            title = title,
            temperature = 0.75,
            maxTokens = 600,
            connectionOverride = connection,
        )
        return finalReply.map { result ->
            val finalPlan = parsePlan(result.text)
            if (finalPlan != null) savePresence(characterId, finalPlan, "聊天·工具")
            result.copy(
                text = finalPlan?.text?.ifBlank { result.text } ?: result.text,
                inputTokens = result.inputTokens + plannedReply.inputTokens,
                outputTokens = result.outputTokens + plannedReply.outputTokens,
                cachedTokens = result.cachedTokens + plannedReply.cachedTokens,
            )
        }
    }

    private suspend fun execute(context: Context, characterId: String, characterName: String, tool: String, args: JSONObject): String = runCatching {
        when (tool.trim().lowercase()) {
            "get_battery" -> battery(context)
            "get_location" -> location(context)
            "get_current_app" -> currentApp(context)
            "read_recent_notifications" -> notifications(args.optInt("limit", 10))
            "create_alarm" -> {
                val trigger = Instant.parse(args.optString("triggerAt"))
                val alarm = LuluAlarmSystem.create(
                    characterId = characterId,
                    characterName = characterName,
                    triggerAt = trigger,
                    label = args.optString("label").ifBlank { "${characterName}提醒你" },
                ).getOrThrow()
                JSONObject().put("success", true).put("systemClock", true).put("id", alarm.id).put("triggerAt", alarm.triggerAt.toString()).put("label", alarm.label).toString()
            }
            "list_alarms" -> {
                val alarms = LuluAlarmSystem.list()
                JSONObject().put("success", true).put("count", alarms.size).put(
                    "alarms",
                    alarms.joinToString("\n") { alarm -> "${alarm.id} | ${alarm.triggerAt.atZone(ZoneId.systemDefault())} | ${alarm.label}" },
                ).toString()
            }
            "cancel_alarm" -> {
                val id = args.optString("id")
                JSONObject().put("success", LuluAlarmSystem.cancel(id)).put("id", id).toString()
            }
            "screen_action" -> {
                val action = when (args.optString("name").lowercase()) {
                    "back" -> LuluScreenAction.Back
                    "home" -> LuluScreenAction.Home
                    "recents" -> LuluScreenAction.Recents
                    "notifications" -> LuluScreenAction.Notifications
                    "quick_settings" -> LuluScreenAction.QuickSettings
                    else -> error("未知屏幕动作")
                }
                JSONObject().put("success", LuluAccessibilityService.perform(action)).put("action", action.name).toString()
            }
            "click_text" -> {
                val text = args.optString("text")
                JSONObject().put("success", LuluAccessibilityService.clickFirstText(text)).put("text", text).toString()
            }
            "read_screen" -> {
                val value = LuluAccessibilityService.state.value
                check(value.connected) { "尚未开启屏幕感知与控制权限" }
                JSONObject().put("success", true).put("packageName", value.packageName).put("windowTitle", value.windowTitle)
                    .put("visibleText", value.visibleText.take(6_000)).put("capturedAt", value.capturedAt?.toString().orEmpty()).toString()
            }
            else -> CompanionActionRuntime.execute(context, characterId, tool, args).asJson()
        }
    }.getOrElse { error ->
        JSONObject().put("success", false).put("error", error.message ?: error::class.java.simpleName).toString()
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return JSONObject().put("success", percent >= 0).put("percent", percent).put("charging", charging).toString()
    }

    private suspend fun location(context: Context): String {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            "尚未授予精确位置权限"
        }
        val best = LuluLocationProvider.freshLocation(context)
            ?: error("暂时没有可用定位，请确认系统定位已开启后再试")

        val ageMillis = (System.currentTimeMillis() - best.time).coerceAtLeast(0L)
        val stale = ageMillis > 10 * 60_000L
        val address = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            Geocoder(context, Locale.getDefault())
                .getFromLocation(best.latitude, best.longitude, 1)
                ?.firstOrNull()
        }.getOrNull()
        val readableAddress = address?.let { value ->
            listOfNotNull(
                value.subLocality,
                value.locality,
                value.adminArea,
                value.countryName,
            ).map(String::trim).filter(String::isNotBlank).distinct().joinToString("，")
        }.orEmpty()
        val confidence = when {
            stale -> "stale"
            best.accuracy <= 30f -> "high"
            best.accuracy <= 100f -> "medium"
            else -> "low"
        }
        val reliabilityNote = buildString {
            when (confidence) {
                "high" -> append("GPS 精度较高")
                "medium" -> append("只能判断大致街区范围")
                "low" -> append("定位精度较低，只能判断城区范围")
                else -> append("位置数据已经过旧，不应当作当前位置")
            }
            if (readableAddress.isBlank()) append("；系统未返回可靠行政区地址")
            else append("；地址仅保留行政区层级，已忽略容易误报的具体建筑名称")
        }

        return JSONObject()
            .put("success", true)
            .put("readableAddress", readableAddress)
            .put("latitude", best.latitude)
            .put("longitude", best.longitude)
            .put("accuracyMeters", best.accuracy)
            .put("provider", best.provider)
            .put("capturedAt", Instant.ofEpochMilli(best.time).toString())
            .put("ageSeconds", ageMillis / 1000L)
            .put("stale", stale)
            .put("confidence", confidence)
            .put("addressGranularity", "administrative_area")
            .put("note", reliabilityNote)
            .toString()
    }

    private fun currentApp(context: Context): String {
        val accessibility = LuluAccessibilityService.state.value
        if (accessibility.connected && accessibility.packageName.isNotBlank()) {
            return JSONObject().put("success", true).put("packageName", accessibility.packageName).put("source", "accessibility")
                .put("capturedAt", accessibility.capturedAt?.toString().orEmpty()).toString()
        }
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - 15 * 60_000L, end)
        val event = UsageEvents.Event()
        var packageName = ""
        var latest = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latest) {
                packageName = event.packageName.orEmpty()
                latest = event.timeStamp
            }
        }
        check(packageName.isNotBlank()) { "尚未获得应用使用情况权限，或近期没有前台应用记录" }
        return JSONObject().put("success", true).put("packageName", packageName).put("source", "usage_stats")
            .put("capturedAt", Instant.ofEpochMilli(latest).toString()).toString()
    }

    private fun notifications(limit: Int): String {
        check(LuluNotificationListenerService.isConnected.value) { "尚未开启通知读取权限" }
        val values = LuluNotificationListenerService.notifications.value.take(limit.coerceIn(1, 30))
        return JSONObject().put("success", true).put("count", values.size).put(
            "notifications",
            values.joinToString("\n") { value -> "${value.postedAt} | ${value.packageName} | ${value.title} | ${value.text}" },
        ).toString()
    }

    private fun parsePlan(raw: String): ToolPlan? {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val json = JSONObject(clean.substring(start, end + 1))
            ToolPlan(
                action = json.optString("action").lowercase(),
                text = json.optString("text"),
                tool = json.optString("tool"),
                args = json.optJSONObject("args") ?: JSONObject(),
                statusText = json.optString("statusText").ifBlank { json.optString("status") },
                gesture = json.optString("gesture").ifBlank { json.optString("actionDescription") },
                innerThought = json.optString("innerThought").ifBlank { json.optString("inner_voice") },
                mood = json.optString("mood"),
            )
        }.getOrNull()
    }

    private fun savePresence(characterId: String, plan: ToolPlan, source: String) {
        CompanionPresenceStore.update(
            characterId = characterId,
            statusText = plan.statusText,
            gesture = plan.gesture,
            innerThought = plan.innerThought,
            mood = plan.mood,
            source = source,
        )
    }
}

private data class ToolPlan(
    val action: String,
    val text: String,
    val tool: String,
    val args: JSONObject,
    val statusText: String,
    val gesture: String,
    val innerThought: String,
    val mood: String,
)
