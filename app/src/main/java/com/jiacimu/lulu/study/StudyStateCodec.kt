package com.jiacimu.lulu.study

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

internal object StudyStateCodec {
    fun encode(state: StudyState): String = JSONObject()
        .put("schemaVersion", state.schemaVersion)
        .put("activeDate", state.activeDate)
        .put("profile", encodeProfile(state.profile))
        .put("inventory", encodeInventory(state.inventory))
        .put("tasks", JSONArray().apply { state.tasks.forEach { put(encodeTask(it)) } })
        .put("schedules", JSONArray().apply { state.schedules.forEach { put(encodeSchedule(it)) } })
        .put("planItems", JSONArray().apply { state.planItems.forEach { put(encodePlan(it)) } })
        .put("tips", JSONArray().apply { state.tips.forEach { put(encodeTip(it)) } })
        .put("events", JSONArray().apply { state.events.forEach { put(encodeEvent(it)) } })
        .put("achievements", JSONArray().apply { state.achievements.forEach { put(encodeAchievement(it)) } })
        .put("shopItems", JSONArray().apply { state.shopItems.forEach { put(encodeShop(it)) } })
        .put("shopDate", state.shopDate)
        .put("shopRefreshCount", state.shopRefreshCount)
        .put("drawsSinceNonNormal", state.drawsSinceNonNormal)
        .put("safeDrawUsedDate", state.safeDrawUsedDate)
        .put("superMomentAvailable", state.superMomentAvailable)
        .put("superMomentClaimedDate", state.superMomentClaimedDate)
        .put("pomodoro", encodePomodoro(state.pomodoro))
        .put("dailyStudyMinutes", encodeStringIntMap(state.dailyStudyMinutes))
        .put("dailyPomodoros", encodeStringIntMap(state.dailyPomodoros))
        .put("dailyVocabularyReviewed", encodeStringIntMap(state.dailyVocabularyReviewed))
        .toString()

    fun decode(raw: String): StudyState {
        val json = JSONObject(raw)
        val today = LocalDate.now()
        return StudyState(
            schemaVersion = json.optInt("schemaVersion", 3),
            activeDate = json.optString("activeDate", today.toString()),
            profile = decodeProfile(json.optJSONObject("profile")),
            inventory = decodeInventory(json.optJSONObject("inventory")),
            tasks = decodeArray(json.optJSONArray("tasks"), ::decodeTask).ifEmpty { defaultTasks(today) },
            schedules = decodeArray(json.optJSONArray("schedules"), ::decodeSchedule),
            planItems = decodeArray(json.optJSONArray("planItems"), ::decodePlan).ifEmpty { defaultPlanItems() },
            tips = decodeArray(json.optJSONArray("tips"), ::decodeTip).ifEmpty { defaultTips(today) },
            events = decodeArray(json.optJSONArray("events"), ::decodeEvent),
            achievements = decodeArray(json.optJSONArray("achievements"), ::decodeAchievement),
            shopItems = decodeArray(json.optJSONArray("shopItems"), ::decodeShop).ifEmpty { defaultShop(today) },
            shopDate = json.optString("shopDate", today.toString()),
            shopRefreshCount = json.optInt("shopRefreshCount"),
            drawsSinceNonNormal = json.optInt("drawsSinceNonNormal"),
            safeDrawUsedDate = json.optString("safeDrawUsedDate"),
            superMomentAvailable = json.optBoolean("superMomentAvailable"),
            superMomentClaimedDate = json.optString("superMomentClaimedDate"),
            pomodoro = decodePomodoro(json.optJSONObject("pomodoro")),
            dailyStudyMinutes = decodeStringIntMap(json.optJSONObject("dailyStudyMinutes")),
            dailyPomodoros = decodeStringIntMap(json.optJSONObject("dailyPomodoros")),
            dailyVocabularyReviewed = decodeStringIntMap(json.optJSONObject("dailyVocabularyReviewed")),
        )
    }

    private fun encodeProfile(value: StudyProfile) = JSONObject()
        .put("selectedCharacterId", value.selectedCharacterId)
        .put("praisePoints", value.praisePoints)
        .put("experience", value.experience)
        .put("streakDays", value.streakDays)
        .put("lastStudyDate", value.lastStudyDate)
        .put("lastSignInDate", value.lastSignInDate)
        .put("totalStudyMinutes", value.totalStudyMinutes)
        .put("totalPomodoros", value.totalPomodoros)
        .put("totalTasksCompleted", value.totalTasksCompleted)
        .put("totalDraws", value.totalDraws)
        .put("totalSignIns", value.totalSignIns)
        .put("vocabularyReviewed", value.vocabularyReviewed)
        .put("claimedLevels", JSONArray(value.claimedLevels.toList()))
        .put("sleepRewardDate", value.sleepRewardDate)
        .put("inactivityPenaltyDate", value.inactivityPenaltyDate)

    private fun decodeProfile(json: JSONObject?): StudyProfile = StudyProfile(
        selectedCharacterId = json?.optString("selectedCharacterId", "lulu") ?: "lulu",
        praisePoints = json?.optInt("praisePoints") ?: 0,
        experience = json?.optInt("experience") ?: 0,
        streakDays = json?.optInt("streakDays") ?: 0,
        lastStudyDate = json?.optString("lastStudyDate").orEmpty(),
        lastSignInDate = json?.optString("lastSignInDate").orEmpty(),
        totalStudyMinutes = json?.optInt("totalStudyMinutes") ?: 0,
        totalPomodoros = json?.optInt("totalPomodoros") ?: 0,
        totalTasksCompleted = json?.optInt("totalTasksCompleted") ?: 0,
        totalDraws = json?.optInt("totalDraws") ?: 0,
        totalSignIns = json?.optInt("totalSignIns") ?: 0,
        vocabularyReviewed = json?.optInt("vocabularyReviewed") ?: 0,
        claimedLevels = json?.optJSONArray("claimedLevels").toIntSet(),
        sleepRewardDate = json?.optString("sleepRewardDate").orEmpty(),
        inactivityPenaltyDate = json?.optString("inactivityPenaltyDate").orEmpty(),
    )

    private fun encodeInventory(value: StudyInventory) = JSONObject()
        .put("singleTickets", value.singleTickets)
        .put("tenTickets", value.tenTickets)
        .put("safePurpleTickets", value.safePurpleTickets)
        .put("mysteryBoxes", value.mysteryBoxes)
        .put("universalBlueFragments", value.universalBlueFragments)
        .put("blueFragments", encodeStringIntMap(value.blueFragments))
        .put("purpleFragments", value.purpleFragments)
        .put("entertainmentFragments", JSONObject().apply { value.entertainmentFragments.forEach { (key, amount) -> put(key.name, amount) } })
        .put("unlockedScrolls", JSONArray(value.unlockedScrolls))
        .put("unlockedVideos", JSONArray(value.unlockedVideos))
        .put("unlockedTheaters", JSONArray(value.unlockedTheaters))

    private fun decodeInventory(json: JSONObject?): StudyInventory {
        val entertainment = mutableMapOf<StudyEntertainmentKind, Int>()
        json?.optJSONObject("entertainmentFragments")?.let { objectJson ->
            objectJson.keys().forEach { key ->
                runCatching { StudyEntertainmentKind.valueOf(key) }.getOrNull()?.let { entertainment[it] = objectJson.optInt(key) }
            }
        }
        return StudyInventory(
            singleTickets = json?.optInt("singleTickets", 3) ?: 3,
            tenTickets = json?.optInt("tenTickets", 1) ?: 1,
            safePurpleTickets = json?.optInt("safePurpleTickets", 1) ?: 1,
            mysteryBoxes = json?.optInt("mysteryBoxes") ?: 0,
            universalBlueFragments = json?.optInt("universalBlueFragments") ?: 0,
            blueFragments = decodeStringIntMap(json?.optJSONObject("blueFragments")),
            purpleFragments = json?.optInt("purpleFragments") ?: 0,
            entertainmentFragments = entertainment,
            unlockedScrolls = json?.optJSONArray("unlockedScrolls").toStringList(),
            unlockedVideos = json?.optJSONArray("unlockedVideos").toStringList(),
            unlockedTheaters = json?.optJSONArray("unlockedTheaters").toStringList(),
        )
    }

    private fun encodeTask(value: StudyTask) = JSONObject()
        .put("id", value.id).put("title", value.title).put("date", value.date)
        .put("completed", value.completed).put("pomodoroTarget", value.pomodoroTarget)
        .put("pomodoroCompleted", value.pomodoroCompleted).put("source", value.source.name)
    private fun decodeTask(json: JSONObject) = StudyTask(
        id = json.optString("id"), title = json.optString("title"), date = json.optString("date"),
        completed = json.optBoolean("completed"), pomodoroTarget = json.optInt("pomodoroTarget", 1),
        pomodoroCompleted = json.optInt("pomodoroCompleted"), source = enumOrDefault(json.optString("source"), StudyTaskSource.User),
    )

    private fun encodeSchedule(value: StudyScheduleBlock) = JSONObject()
        .put("id", value.id).put("date", value.date).put("start", value.start).put("end", value.end)
        .put("title", value.title).put("completed", value.completed)
    private fun decodeSchedule(json: JSONObject) = StudyScheduleBlock(
        id = json.optString("id"), date = json.optString("date"), start = json.optString("start"),
        end = json.optString("end"), title = json.optString("title"), completed = json.optBoolean("completed"),
    )

    private fun encodePlan(value: StudyPlanItem) = JSONObject()
        .put("id", value.id).put("range", value.range.name).put("title", value.title).put("note", value.note).put("completed", value.completed)
    private fun decodePlan(json: JSONObject) = StudyPlanItem(
        id = json.optString("id"), range = enumOrDefault(json.optString("range"), StudyPlanRange.Weekly),
        title = json.optString("title"), note = json.optString("note"), completed = json.optBoolean("completed"),
    )

    private fun encodeTip(value: StudyTip) = JSONObject().put("id", value.id).put("text", value.text).put("date", value.date)
    private fun decodeTip(json: JSONObject) = StudyTip(json.optString("id"), json.optString("text"), json.optString("date"))
    private fun encodeEvent(value: StudyEvent) = JSONObject()
        .put("id", value.id).put("title", value.title).put("detail", value.detail).put("createdAt", value.createdAt.toEpochMilli())
    private fun decodeEvent(json: JSONObject) = StudyEvent(
        id = json.optString("id"), title = json.optString("title"), detail = json.optString("detail"),
        createdAt = Instant.ofEpochMilli(json.optLong("createdAt", System.currentTimeMillis())),
    )

    private fun encodeAchievement(value: StudyAchievement) = JSONObject()
        .put("id", value.id).put("title", value.title).put("description", value.description)
        .put("progress", value.progress).put("target", value.target)
        .put("rewardSingleTickets", value.rewardSingleTickets).put("rewardPraisePoints", value.rewardPraisePoints).put("claimed", value.claimed)
    private fun decodeAchievement(json: JSONObject) = StudyAchievement(
        id = json.optString("id"), title = json.optString("title"), description = json.optString("description"),
        progress = json.optInt("progress"), target = json.optInt("target", 1),
        rewardSingleTickets = json.optInt("rewardSingleTickets", 1), rewardPraisePoints = json.optInt("rewardPraisePoints", 20),
        claimed = json.optBoolean("claimed"),
    )

    private fun encodeShop(value: StudyShopItem) = JSONObject()
        .put("id", value.id).put("title", value.title).put("subtitle", value.subtitle).put("cost", value.cost)
        .put("reward", value.reward.name).put("amount", value.amount).put("stock", value.stock).put("purchased", value.purchased)
    private fun decodeShop(json: JSONObject) = StudyShopItem(
        id = json.optString("id"), title = json.optString("title"), subtitle = json.optString("subtitle"),
        cost = json.optInt("cost"), reward = enumOrDefault(json.optString("reward"), StudyShopReward.SingleTicket),
        amount = json.optInt("amount", 1), stock = json.optInt("stock", 1), purchased = json.optInt("purchased"),
    )

    private fun encodePomodoro(value: PomodoroState) = JSONObject()
        .put("selectedMinutes", value.selectedMinutes).put("remainingSeconds", value.remainingSeconds)
        .put("running", value.running).put("voiceEnabled", value.voiceEnabled).put("endAtEpochMillis", value.endAtEpochMillis)
    private fun decodePomodoro(json: JSONObject?) = PomodoroState(
        selectedMinutes = json?.optInt("selectedMinutes", 25) ?: 25,
        remainingSeconds = json?.optInt("remainingSeconds", 25 * 60) ?: 25 * 60,
        running = json?.optBoolean("running") ?: false,
        voiceEnabled = json?.optBoolean("voiceEnabled") ?: false,
        endAtEpochMillis = json?.optLong("endAtEpochMillis") ?: 0L,
    )

    private fun encodeStringIntMap(value: Map<String, Int>) = JSONObject().apply { value.forEach { (key, amount) -> put(key, amount) } }
    private fun decodeStringIntMap(json: JSONObject?): Map<String, Int> = buildMap {
        json?.keys()?.forEach { key -> put(key, json.optInt(key)) }
    }
    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String, default: T): T = runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    private fun <T> decodeArray(array: JSONArray?, mapper: (JSONObject) -> T): List<T> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) array.optJSONObject(index)?.let { runCatching { mapper(it) }.getOrNull()?.let(::add) }
    }
    private fun JSONArray?.toStringList(): List<String> = buildList {
        val array = this@toStringList ?: return@buildList
        for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }
    private fun JSONArray?.toIntSet(): Set<Int> = buildSet {
        val array = this@toIntSet ?: return@buildSet
        for (index in 0 until array.length()) add(array.optInt(index))
    }
}
