package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import com.jiacimu.lulu.games.LuluGames
import java.time.Duration
import java.time.Instant

internal object DigitalWorldActivityStateStore {
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    private val ongoingActivityIds = setOf(
        "sit", "sit_on_bed", "lie_down", "rest", "nap", "sleep", "curl_up", "sit_at_desk",
        "sit_by_table", "sit_on_rug", "lie_on_rug", "hug_cushion", "cloud_sit", "cloud_rest",
        "reading_rest", "cafe_sit", "window_cafe", "courtyard_sit", "sit_arcade", "home_quiet_rest",
    )

    @Synchronized
    fun initialize(context: Context) {
        val application = context.applicationContext
        appContext = application
        if (prefs == null) {
            prefs = application.getSharedPreferences("digital_activity_state_v1", Context.MODE_PRIVATE)
            LuluGames.initialize(application)
        }
        DigitalWorldExpansionStore.initialize(application)
        DigitalWorldEvolutionRuntime.initialize(application)
        RealityWorldWindowRuntime.initialize(application)
    }

    /** Minimal truthful living state for non-world chat: no furniture/tool catalog
     * or reality-news feed. Active rest/reading and elapsed time still carry over.
     */
    @Synchronized
    fun briefContextFor(characterId: String): String {
        val p = prefs ?: return ""
        settleTimedActivity(characterId, Instant.now())
        val current = runCatching {
            JSONObject(p.getString("activity:$characterId", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        val appearance = runCatching {
            JSONObject(p.getString("appearance:$characterId", "{}").orEmpty())
        }.getOrDefault(JSONObject())
        return buildString {
            if (current.has("summary")) {
                val status = current.optString("status").ifBlank { "active" }
                append("当前生活活动(${status})：${current.optString("summary").take(230)}")
                if (status == "active") append("；计划结束=${current.optString("plannedEndAt")}")
                else append("；结果=${current.optString("outcome").take(150)}")
                appendLine()
            }
            if (appearance.has("updatedAt")) {
                appendLine("目前仪容：${appearance.optString("summary").take(100)}")
            }
        }.trim()
    }

    @Synchronized
    fun contextFor(characterId: String): String {
        val p = prefs ?: return ""
        settleTimedActivity(characterId, Instant.now())
        // World growth is tied to persisted real-time slots, not to prompt/model call count.
        DigitalWorldEvolutionRuntime.maybeEvolve(characterId)
        val current = runCatching { JSONObject(p.getString("activity:$characterId", "{}").orEmpty()) }.getOrDefault(JSONObject())
        val appearance = runCatching { JSONObject(p.getString("appearance:$characterId", "{}").orEmpty()) }.getOrDefault(JSONObject())
        val character = MigratedDomainStores.characters.get(characterId)
        return buildString {
            appendLine("随身物品：温水 ${p.getInt("drink:$characterId", 0)} 杯；原味饼干 ${p.getInt("snack:$characterId", 0)} 份。")
            if (current.has("summary")) {
                val status = current.optString("status").ifBlank { "active" }
                when (status) {
                    "active" -> appendLine(
                        "当前活动：${current.optString("summary")}；开始时间=${current.optString("startedAt")}；计划自然结束=${current.optString("plannedEndAt")}。在程序结算前只能说活动正在进行，不能擅自增加已经休息/睡眠的时长。",
                    )
                    "paused" -> appendLine(
                        "暂停中的活动：${current.optString("summary")}；开始=${current.optString("startedAt")}；暂停=${current.optString("pausedAt")}；原因=${current.optString("outcome")}。暂停不等于已经完成。",
                    )
                    else -> appendLine(
                        "最近完成：${current.optString("summary")}；开始=${current.optString("startedAt")}；结束=${current.optString("endedAt")}；结果=${current.optString("outcome").ifBlank { "活动已结束" }}。",
                    )
                }
            }
            if (appearance.has("updatedAt")) {
                appendLine("当前仪容状态：${appearance.optString("summary").ifBlank { "刚整理过自己的仪容" }}；更新时间=${appearance.optString("updatedAt")}。")
            }
            DigitalWorldStore.itemsAtLocation(characterId).filter {
                DigitalFurnitureCatalog.resolve(it).kind in setOf(DigitalFurnitureKind.TV, DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.FLOOR_LAMP)
            }.forEach { appendLine("${it.name}开关：${if (p.getBoolean("power:${it.id}", false)) "开" else "关"}；电视尚未接入节目源。") }
            if (DigitalWorldStore.locationOf(characterId) == DigitalWorldPublicPlaces.CAFE) {
                appendLine("咖啡角自助供应台提供免费数字温水与原味饼干；order_drink/order_snack 领取后才进入随身物品，消耗一份减少一份。")
            }
            val expansionContext = DigitalWorldExpansionStore.contextFor()
            if (expansionContext.isNotBlank()) {
                appendLine()
                appendLine(expansionContext)
            }
            val driveContext = DigitalLifeDriveContext.promptSection(characterId, character.displayName)
            if (driveContext.isNotBlank()) {
                appendLine()
                appendLine(driveContext)
            }
            val realityContext = RealityWorldWindowRuntime.contextFor(characterId)
            if (realityContext.isNotBlank()) {
                appendLine()
                appendLine(realityContext)
            }
        }.trim()
    }

    @Synchronized
    fun apply(characterId: String, item: DigitalWorldItem?, activityId: String, summary: String, now: Instant): String {
        val p = checkNotNull(prefs) { "活动状态尚未初始化" }
        settleTimedActivity(characterId, now)
        // Starting a different activity closes the previous one at the real transition time.
        val previous = runCatching { JSONObject(p.getString("activity:$characterId", "{}").orEmpty()) }.getOrNull()
        if (previous?.optString("status") == "active" && previous.optString("activityId") != activityId) {
            finishActivity(characterId, now, "角色转去做另一件事，上一项活动在此刻结束")
        }

        val editor = p.edit()
        val name = MigratedDomainStores.characters.get(characterId).displayName
        val result = if (activityId.startsWith("reality_")) {
            RealityWorldWindowRuntime.executeActivity(characterId, activityId, now)
        } else when (activityId) {
            "watch_tv", "watch_tv_from_sofa", "change_channel" -> error("电视尚未接入节目源，不能记录观看了节目")
            "browse_games", "choose_arcade" -> "${name}查看了游戏馆中的记忆配对入口；尚未开始对局。"
            "check_scoreboard" -> {
                val records = LuluGames.store.state.value.records.filter { it.characterId == characterId }.takeLast(5)
                if (records.isEmpty()) "${name}查看了自己的游戏记录，目前还没有已完成的对局。"
                else "${name}查看了自己的游戏记录：" + records.joinToString("；") { "${it.title}，${it.score} 分，${it.createdAt}" }
            }
            "watch_game" -> error("没有可核验的现场游戏过程；可以自己玩游戏馆中的记忆配对")
            "play_table_game" -> error("请通过游戏馆的真实游戏开始对局")
            "read_at_desk", "quiet_read", "window_read", "reading_notes" -> error("阅读需要指定阅读 App 中的真实书目与正文")
            "order_drink", "order_snack" -> {
                require(DigitalWorldStore.locationOf(characterId) == DigitalWorldPublicPlaces.CAFE) { "需要先到咖啡角的自助供应台" }
                val drink = activityId == "order_drink"
                val key = "${if (drink) "drink" else "snack"}:$characterId"
                require(p.getInt(key, 0) < 100) { "随身同类物品已满" }
                editor.putInt(key, p.getInt(key, 0) + 1)
                "${name}从自助供应台领取了${if (drink) "一杯温水" else "一份原味饼干"}，放入随身物品。"
            }
            "slow_drink", "have_snack" -> {
                val drink = activityId == "slow_drink"
                val key = "${if (drink) "drink" else "snack"}:$characterId"
                val count = p.getInt(key, 0)
                require(count > 0) { "随身没有${if (drink) "饮品" else "食物"}，需要先领取" }
                editor.putInt(key, count - 1)
                "$name${if (drink) "喝完一杯随身温水" else "吃完一份随身原味饼干"}；还剩 ${count - 1} 份。"
            }
            "turn_on_tv", "toggle_lamp" -> {
                val target = requireNotNull(item) { "需要指定当前地点的物品" }
                val on = activityId == "turn_on_tv" || !p.getBoolean("power:${target.id}", false)
                editor.putBoolean("power:${target.id}", on)
                "$name${if (on) "打开" else "关闭"}了“${target.name}”${if (activityId == "turn_on_tv") "，目前没有接入节目" else ""}。"
            }
            "check_time" -> "${name}查看了当前时间：$now。"
            "water_plant", "tend_plant" -> {
                val target = requireNotNull(item)
                editor.putString("tended:${target.id}", now.toString())
                "${name}照料了“${target.name}”；照料时间已记录。"
            }
            "fix_appearance" -> {
                val target = requireNotNull(item) { "整理仪容需要当前地点存在可用的镜子" }
                require(DigitalFurnitureCatalog.resolve(target).kind == DigitalFurnitureKind.MIRROR) { "整理仪容需要站在真实存在的镜子前" }
                val appearanceSummary = summary.trim().ifBlank { "$name 对着“${target.name}”整理了头发和衣着。" }
                editor.putString(
                    "appearance:$characterId",
                    JSONObject().put("summary", appearanceSummary).put("mirrorItemId", target.id).put("updatedAt", now.toString()).toString(),
                )
                appearanceSummary
            }
            else -> summary
        }
        val ongoing = activityId in ongoingActivityIds
        val plannedEndAt = if (ongoing) now.plus(activityDuration(activityId)) else null
        editor.putString(
            "activity:$characterId",
            JSONObject()
                .put("activityId", activityId)
                .put("itemId", item?.id.orEmpty())
                .put("location", DigitalWorldStore.locationOf(characterId))
                .put("status", if (ongoing) "active" else "completed")
                .put("startedAt", now.toString())
                .put("plannedEndAt", plannedEndAt?.toString() ?: JSONObject.NULL)
                .put("pausedAt", JSONObject.NULL)
                .put("endedAt", if (ongoing) JSONObject.NULL else now.toString())
                .put("summary", result)
                .put("outcome", if (ongoing) "" else "动作已执行并保存结果")
                .toString(),
        )
        check(editor.commit()) { "活动状态保存失败" }
        return result
    }

    @Synchronized
    fun ongoingActivity(characterId: String): Pair<String, String>? {
        settleTimedActivity(characterId, Instant.now())
        val activity = runCatching { JSONObject(prefs?.getString("activity:$characterId", "{}").orEmpty()) }.getOrNull() ?: return null
        if (activity.optString("location") != DigitalWorldStore.locationOf(characterId)) return null
        if (activity.optString("status").ifBlank { "active" } != "active") return null
        val id = activity.optString("activityId")
        if (id !in ongoingActivityIds) return null
        return activity.optString("itemId") to id
    }

    @Synchronized
    fun pauseActivity(characterId: String, reason: String = "活动暂时停下", now: Instant = Instant.now()) {
        val p = prefs ?: return
        settleTimedActivity(characterId, now)
        val activity = runCatching { JSONObject(p.getString("activity:$characterId", "{}").orEmpty()) }.getOrNull() ?: return
        if (activity.optString("status") != "active") return
        activity.put("status", "paused")
            .put("pausedAt", now.toString())
            .put("outcome", reason.take(300))
        p.edit().putString("activity:$characterId", activity.toString()).commit()
    }

    @Synchronized
    fun endActivity(characterId: String) {
        finishActivity(characterId, Instant.now(), "活动自然结束或角色转入下一项行动")
    }

    private fun finishActivity(characterId: String, now: Instant, outcome: String) {
        val p = prefs ?: return
        val activity = runCatching { JSONObject(p.getString("activity:$characterId", "{}").orEmpty()) }.getOrNull() ?: return
        if (!activity.has("activityId") || activity.optString("status") == "completed") return
        activity.put("status", "completed")
            .put("endedAt", now.toString())
            .put("outcome", outcome.take(300))
        p.edit().putString("activity:$characterId", activity.toString()).commit()
    }

    private fun settleTimedActivity(characterId: String, now: Instant) {
        val p = prefs ?: return
        val activity = runCatching { JSONObject(p.getString("activity:$characterId", "{}").orEmpty()) }.getOrNull() ?: return
        if (activity.optString("status") != "active") return
        val planned = activity.optString("plannedEndAt")
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return
        if (planned.isAfter(now)) return
        activity.put("status", "completed")
            .put("endedAt", planned.toString())
            .put("outcome", "达到该活动的程序时长后自然结束；结束时间由世界状态结算，不由模型猜测")
        p.edit().putString("activity:$characterId", activity.toString()).commit()
    }

    private fun activityDuration(activityId: String): Duration = when (activityId) {
        "sleep" -> Duration.ofHours(2)
        "nap" -> Duration.ofMinutes(45)
        "rest", "cloud_rest", "reading_rest", "home_quiet_rest", "lie_down", "lie_on_rug", "curl_up" -> Duration.ofMinutes(30)
        else -> Duration.ofMinutes(20)
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        prefs?.edit()
            ?.remove("activity:$characterId")
            ?.remove("appearance:$characterId")
            ?.remove("drink:$characterId")
            ?.remove("snack:$characterId")
            ?.apply()
    }
}
