package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import com.jiacimu.lulu.games.LuluGames
import java.time.Instant

/** Small persistent physical state; prose cannot create resources or complete activities. */
internal object DigitalWorldActivityStateStore {
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("digital_activity_state_v1", Context.MODE_PRIVATE)
            LuluGames.initialize(context.applicationContext)
        }
    }

    @Synchronized
    fun contextFor(characterId: String): String {
        val p = prefs ?: return ""
        val current = runCatching { JSONObject(p.getString("activity:$characterId", "{}").orEmpty()) }.getOrDefault(JSONObject())
        return buildString {
            appendLine("随身物品：温水 ${p.getInt("drink:$characterId", 0)} 杯；原味饼干 ${p.getInt("snack:$characterId", 0)} 份。")
            if (current.has("summary")) appendLine("最近执行：${current.optString("summary")}；开始时间=${current.optString("startedAt")}；这是开始记录，不代表已经休息或睡了某个时长。")
            DigitalWorldStore.itemsAtLocation(characterId).filter {
                DigitalFurnitureCatalog.resolve(it).kind in setOf(DigitalFurnitureKind.TV, DigitalFurnitureKind.TABLE_LAMP, DigitalFurnitureKind.FLOOR_LAMP)
            }.forEach { appendLine("${it.name}开关：${if (p.getBoolean("power:${it.id}", false)) "开" else "关"}；电视尚未接入节目源。") }
            if (DigitalWorldStore.locationOf(characterId) == DigitalWorldPublicPlaces.CAFE) {
                appendLine("咖啡角自助供应台提供免费数字温水与原味饼干；order_drink/ order_snack 领取后才进入随身物品，消耗一份减少一份。")
            }
        }.trim()
    }

    @Synchronized
    fun apply(characterId: String, item: DigitalWorldItem?, activityId: String, summary: String, now: Instant): String {
        val p = checkNotNull(prefs) { "活动状态尚未初始化" }
        val editor = p.edit()
        val name = MigratedDomainStores.characters.get(characterId).displayName
        val result = when (activityId) {
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
            else -> summary
        }
        editor.putString("activity:$characterId", JSONObject()
            .put("activityId", activityId).put("itemId", item?.id.orEmpty())
            .put("location", DigitalWorldStore.locationOf(characterId))
            .put("startedAt", now.toString()).put("summary", result).toString())
        check(editor.commit()) { "活动状态保存失败" }
        return result
    }

    @Synchronized
    fun ongoingActivity(characterId: String): Pair<String, String>? {
        val activity = runCatching { JSONObject(prefs?.getString("activity:$characterId", "{}").orEmpty()) }.getOrNull() ?: return null
        if (activity.optString("location") != DigitalWorldStore.locationOf(characterId)) return null
        val id = activity.optString("activityId")
        if (id !in setOf("sit", "sit_on_bed", "lie_down", "rest", "nap", "sleep", "curl_up", "sit_at_desk", "sit_by_table", "sit_on_rug", "lie_on_rug", "hug_cushion", "cloud_sit", "cloud_rest", "reading_rest", "cafe_sit", "window_cafe", "courtyard_sit", "sit_arcade", "home_quiet_rest")) return null
        return activity.optString("itemId") to id
    }

    @Synchronized
    fun endActivity(characterId: String) {
        prefs?.edit()?.remove("activity:$characterId")?.apply()
    }

    @Synchronized
    fun clearCharacter(characterId: String) {
        prefs?.edit()?.remove("activity:$characterId")?.remove("drink:$characterId")?.remove("snack:$characterId")?.apply()
    }
}
