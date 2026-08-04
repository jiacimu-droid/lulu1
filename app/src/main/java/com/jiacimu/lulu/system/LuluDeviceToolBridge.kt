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
        val connection = runCatching { LuluAiServices.connectionStore.resolveConnection(archiveId) }
            .getOrElse { return Result.failure(it) }
        val character = MigratedDomainStores.characters.get(characterId)
        val previousPresence = CompanionPresenceStore.current(characterId)
        val livedContext = SharedExperienceTimeline.recentContext(characterId, limit = 16, characterBudget = 4_800)
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val planner = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前时间：${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone))}")
                appendLine("当前时区：${zone.id}")
                appendLine("当前真实互动场景：$sceneContext")
                if (livedContext.isNotBlank()) appendLine("角色最近亲历的原始时间线：\n$livedContext")
                if (history.isNotBlank()) appendLine("最近对话：\n$history")
                previousPresence?.let { presence ->
                    appendLine("角色上一刻状态：${presence.statusText}；动作：${presence.gesture}；心情：${presence.mood}；没说出口：${presence.innerThought}")
                }
                appendLine("用户刚刚说：$userText")
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

                规则：
                - 用户询问设备真实状态、要求设置或取消闹钟、要求操作手机时必须用工具，不能凭空回答成功。
                - 位置工具返回的 readableAddress 才能作为可读地点使用；如果地址为空、stale=true 或 accuracyMeters 很大，必须说明只是大概位置，绝不能根据经纬度猜店铺、学校或建筑。
                - 时间表达必须根据当前时间换算成未来的完整 ISO 时间；不确定时间时直接自然追问，不要猜。
                - 屏幕操作只执行用户明确要求的动作。不要连续规划多步操作；一次只调用一个工具。
                - 与工具无关的普通聊天直接回复。
                - 必须意识到“当前真实互动场景”，并以身处该场景的角色身份自然反应；电话里可以意识到正在通话，群聊里可以意识到其他成员也在场。
                - innerThought 是角色没说出口的一瞬，不是分析报告、推理步骤或对话总结；没有真实内在反应可以留空，也不必把它写进 text。
                - gesture 只写角色此刻的微动作、姿态或神态，不要复述刚刚聊了什么，不要编造角色并不处于其中的现实场景。
                - statusText、gesture、innerThought、mood 必须服从角色人设，不能把所有角色统一写成温柔、害羞或黏人。
            """.trimIndent(),
            source = "聊天工具规划",
            title = title,
            temperature = 0.15,
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
                appendLine("用户刚刚说：$userText")
                appendLine("你请求调用工具：${plan.tool}")
                appendLine("工具真实执行结果：$toolResult")
            },
            instruction = """
                根据工具的真实执行结果，以角色本人符合人设的方式回复用户。
                必须继续保持当前真实互动场景，电话里用自然口语，群聊里知道其他成员在场。
                成功时可以自然确认；失败时必须如实说明失败原因，不能假装已经完成。
                对位置结果只能使用 readableAddress；地址为空、定位过旧或精度差时，必须明确说是大概位置，不得根据经纬度猜具体店铺、学校或建筑。
                只返回一个 JSON 对象，不要代码块：
                {"action":"reply","text":"角色根据真实结果说出的话","statusText":"简短状态","gesture":"执行后的动作神态","innerThought":"没说出口的第一人称心声，可为空","mood":"简短心情"}
                不要解释内部工具协议。text 应简洁自然。innerThought 不是推理步骤，gesture 不得编造未发生的工具结果或现实场景。
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
            else -> error("未知工具：$tool")
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
