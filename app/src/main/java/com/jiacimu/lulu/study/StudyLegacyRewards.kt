package com.jiacimu.lulu.study

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

enum class StudyLegacyShopReward {
    GameTicket, VideoCard, AnimeTicket, UniversalRare, UniversalEpic, RainbowFragment, TenDrawTicket,
}

data class StudyBlindBox(
    val id: String = UUID.randomUUID().toString(),
    val kudos: Int,
    val normalFragments: Int,
)

data class StudyLegacyShopItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val cost: Int,
    val reward: StudyLegacyShopReward,
    val amount: Int,
    val stock: Int,
    val purchased: Int = 0,
) {
    val remaining: Int get() = (stock - purchased).coerceAtLeast(0)
}

data class StudyLegacyRewardState(
    val kudos: Int = 0,
    val gameFragments: Int = 0,
    val videoFragments: Int = 0,
    val animeFragments: Int = 0,
    val normalFragments: Int = 0,
    val rareFragments: Int = 0,
    val epicFragments: Int = 0,
    val rainbowFragments: Int = 0,
    val universalRareFragments: Int = 0,
    val universalEpicFragments: Int = 0,
    val blindBoxes: List<StudyBlindBox> = emptyList(),
    val outfitFragments: Map<String, Int> = emptyMap(),
    val unlockedOutfits: Set<String> = emptySet(),
    val unlockedVideos: List<String> = emptyList(),
    val unlockedAnime: List<String> = emptyList(),
    val unlockedTheaters: Set<String> = emptySet(),
    val mediaUris: Map<String, String> = emptyMap(),
    val shopDate: String = LocalDate.now().toString(),
    val shopRefreshCount: Int = 0,
    val shopItems: List<StudyLegacyShopItem> = defaultLegacyShop(LocalDate.now()),
)

internal val legacyOutfitNames = listOf(
    "晨雾针织套", "月光衬衫套", "深蓝图书馆套", "灰调通勤套", "暖棕居家套",
    "雨夜风衣套", "白昼运动套", "墨色礼服套", "麦色秋日套", "冬夜围巾套",
    "海盐夏日套", "旧电影西装套", "星轨睡衣套",
)
internal val legacyTheaterNames = listOf("月光剧场·序章", "月光剧场·雨夜", "月光剧场·图书馆", "月光剧场·考前", "月光剧场·录取")
internal val legacyVideoNames = listOf("完成第一小时", "雨天自习室", "角色的监督留言", "深夜收尾", "周计划达成")
internal val legacyAnimeNames = listOf("番剧券·第一集", "番剧券·第二集", "番剧券·第三集", "番剧券·第四集", "番剧券·第五集")

class StudyLegacyRewardsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<StudyLegacyRewardState> = mutable.asStateFlow()

    fun syncDate(today: LocalDate = LocalDate.now()) = mutate { current ->
        if (current.shopDate == today.toString()) current else current.copy(
            shopDate = today.toString(),
            shopRefreshCount = 0,
            shopItems = defaultLegacyShop(today),
        )
    }

    fun rewardFocus(minutes: Int): String {
        val safe = minutes.coerceAtLeast(1)
        var message = ""
        mutate { current ->
            val fragmentRewards = safe / 5
            val boxCount = safe / 25
            val newBoxes = List(boxCount) {
                StudyBlindBox(kudos = Random.nextInt(6, 21), normalFragments = Random.nextInt(1, 4))
            }
            message = buildString {
                append("学习币 +$safe")
                if (fragmentRewards > 0) append("，普通碎片 +$fragmentRewards")
                if (boxCount > 0) append("，盲盒 +$boxCount")
            }
            current.copy(
                kudos = (current.kudos + safe).coerceAtMost(MAX_KUDOS),
                normalFragments = current.normalFragments + fragmentRewards,
                blindBoxes = current.blindBoxes + newBoxes,
            )
        }
        return message
    }

    fun openBlindBox(id: String): String {
        var message = "盲盒不存在"
        mutate { current ->
            val box = current.blindBoxes.firstOrNull { it.id == id } ?: return@mutate current
            message = "盲盒：学习币 +${box.kudos}，普通碎片 +${box.normalFragments}"
            current.copy(
                kudos = (current.kudos + box.kudos).coerceAtMost(MAX_KUDOS),
                normalFragments = current.normalFragments + box.normalFragments,
                blindBoxes = current.blindBoxes.filterNot { it.id == id },
            )
        }
        return message
    }

    fun convertNormalToOutfit(outfit: String): String {
        if (outfit !in legacyOutfitNames) return "未知服装套装"
        var message = "普通碎片不足"
        mutate { current ->
            val currentAmount = current.outfitFragments[outfit] ?: 0
            if (currentAmount >= OUTFIT_FRAGMENT_MAX) {
                message = "《$outfit》已经集齐"
                return@mutate current
            }
            if (current.normalFragments <= 0) return@mutate current
            val nextAmount = currentAmount + 1
            val unlocked = if (nextAmount >= OUTFIT_FRAGMENT_MAX) current.unlockedOutfits + outfit else current.unlockedOutfits
            message = "《$outfit》碎片 $nextAmount/$OUTFIT_FRAGMENT_MAX${if (nextAmount >= OUTFIT_FRAGMENT_MAX) "，画卷已解锁" else ""}"
            current.copy(
                normalFragments = current.normalFragments - 1,
                outfitFragments = current.outfitFragments + (outfit to nextAmount),
                unlockedOutfits = unlocked,
            )
        }
        return message
    }

    fun useUniversalRare(outfit: String): String = applyUniversal(outfit, epic = false)
    fun useUniversalEpic(outfit: String): String = applyUniversal(outfit, epic = true)

    private fun applyUniversal(outfit: String, epic: Boolean): String {
        if (outfit !in legacyOutfitNames) return "未知服装套装"
        var message = if (epic) "万能史诗碎片不足" else "万能稀有碎片不足"
        mutate { current ->
            val available = if (epic) current.universalEpicFragments else current.universalRareFragments
            if (available <= 0) return@mutate current
            val amount = current.outfitFragments[outfit] ?: 0
            if (amount >= OUTFIT_FRAGMENT_MAX) {
                message = "《$outfit》已经集齐"
                return@mutate current
            }
            val added = if (epic) 2 else 1
            val next = (amount + added).coerceAtMost(OUTFIT_FRAGMENT_MAX)
            val unlocked = if (next >= OUTFIT_FRAGMENT_MAX) current.unlockedOutfits + outfit else current.unlockedOutfits
            message = "《$outfit》碎片 $next/$OUTFIT_FRAGMENT_MAX"
            current.copy(
                universalRareFragments = current.universalRareFragments - if (epic) 0 else 1,
                universalEpicFragments = current.universalEpicFragments - if (epic) 1 else 0,
                outfitFragments = current.outfitFragments + (outfit to next),
                unlockedOutfits = unlocked,
            )
        }
        return message
    }

    fun redeemGameTicket(): String {
        var message = "还需要1张游戏畅玩券"
        mutate { current ->
            if (current.gameFragments <= 0) return@mutate current
            message = "已使用1张游戏畅玩券"
            current.copy(gameFragments = current.gameFragments - 1)
        }
        return message
    }

    fun redeemVideoCard(): String {
        var message = "还需要1张视频解锁卡"
        mutate { current ->
            if (current.videoFragments <= 0) return@mutate current
            val next = legacyVideoNames.firstOrNull { it !in current.unlockedVideos }
            if (next == null) {
                message = "视频已经全部解锁，卡片已保留"
                return@mutate current
            }
            message = "解锁视频《$next》"
            current.copy(videoFragments = current.videoFragments - 1, unlockedVideos = current.unlockedVideos + next)
        }
        return message
    }

    fun redeemAnimeTicket(): String {
        var message = "还需要1张番剧兑换券"
        mutate { current ->
            if (current.animeFragments <= 0) return@mutate current
            val next = legacyAnimeNames.firstOrNull { it !in current.unlockedAnime }
            if (next == null) {
                message = "番剧已经全部解锁，券已保留"
                return@mutate current
            }
            message = "解锁《$next》"
            current.copy(animeFragments = current.animeFragments - 1, unlockedAnime = current.unlockedAnime + next)
        }
        return message
    }

    fun unlockTheaterFromRainbow(): String {
        var message = "彩色碎片不足"
        mutate { current ->
            if (current.rainbowFragments <= 0) return@mutate current
            val next = legacyTheaterNames.firstOrNull { it !in current.unlockedTheaters }
            if (next == null) {
                message = "月光剧场已经全部解锁，彩碎已保留"
                return@mutate current
            }
            message = "解锁《$next》"
            current.copy(rainbowFragments = current.rainbowFragments - 1, unlockedTheaters = current.unlockedTheaters + next)
        }
        return message
    }

    fun buy(id: String): String {
        var message = "购买失败"
        mutate { current ->
            val item = current.shopItems.firstOrNull { it.id == id } ?: return@mutate current
            if (item.remaining <= 0) {
                message = "商品已售罄"
                return@mutate current
            }
            if (current.kudos < item.cost) {
                message = "学习币不足，还需要 ${item.cost - current.kudos}"
                return@mutate current
            }
            var next = current.copy(kudos = current.kudos - item.cost)
            next = when (item.reward) {
                StudyLegacyShopReward.GameTicket -> next.copy(gameFragments = next.gameFragments + item.amount)
                StudyLegacyShopReward.VideoCard -> next.copy(videoFragments = next.videoFragments + item.amount)
                StudyLegacyShopReward.AnimeTicket -> next.copy(animeFragments = next.animeFragments + item.amount)
                StudyLegacyShopReward.UniversalRare -> next.copy(universalRareFragments = next.universalRareFragments + item.amount)
                StudyLegacyShopReward.UniversalEpic -> next.copy(universalEpicFragments = next.universalEpicFragments + item.amount)
                StudyLegacyShopReward.RainbowFragment -> next.copy(rainbowFragments = next.rainbowFragments + item.amount)
                StudyLegacyShopReward.TenDrawTicket -> next
            }
            next = next.copy(shopItems = next.shopItems.map { if (it.id == id) it.copy(purchased = it.purchased + 1) else it })
            message = "购买成功：${item.title} ×${item.amount}"
            next
        }
        return message
    }

    fun refreshShop(today: LocalDate = LocalDate.now()): String {
        var message = "学习币不足"
        mutate { current ->
            val cost = 15 + current.shopRefreshCount * 5
            if (current.kudos < cost) return@mutate current
            message = "消耗 $cost 学习币刷新商店"
            current.copy(
                kudos = current.kudos - cost,
                shopRefreshCount = current.shopRefreshCount + 1,
                shopDate = today.toString(),
                shopItems = defaultLegacyShop(today.plusDays(current.shopRefreshCount.toLong() + 1)),
            )
        }
        return message
    }

    fun attachMedia(title: String, uri: String) {
        if (title.isBlank() || uri.isBlank()) return
        mutate { it.copy(mediaUris = it.mediaUris + (title to uri)) }
    }

    fun clearMedia(title: String) = mutate { it.copy(mediaUris = it.mediaUris - title) }

    private fun mutate(transform: (StudyLegacyRewardState) -> StudyLegacyRewardState) {
        mutable.update(transform)
        persist(mutable.value)
    }

    private fun persist(state: StudyLegacyRewardState) {
        prefs.edit().putString(KEY_STATE, encode(state).toString()).apply()
    }

    private fun load(): StudyLegacyRewardState = runCatching {
        val raw = prefs.getString(KEY_STATE, null) ?: return@runCatching StudyLegacyRewardState()
        decode(JSONObject(raw))
    }.getOrElse { StudyLegacyRewardState() }

    private fun encode(state: StudyLegacyRewardState): JSONObject = JSONObject()
        .put("kudos", state.kudos)
        .put("gameFragments", state.gameFragments)
        .put("videoFragments", state.videoFragments)
        .put("animeFragments", state.animeFragments)
        .put("normalFragments", state.normalFragments)
        .put("rareFragments", state.rareFragments)
        .put("epicFragments", state.epicFragments)
        .put("rainbowFragments", state.rainbowFragments)
        .put("universalRareFragments", state.universalRareFragments)
        .put("universalEpicFragments", state.universalEpicFragments)
        .put("blindBoxes", JSONArray().apply { state.blindBoxes.forEach { put(JSONObject().put("id", it.id).put("kudos", it.kudos).put("normal", it.normalFragments)) } })
        .put("outfitFragments", JSONObject(state.outfitFragments))
        .put("unlockedOutfits", JSONArray(state.unlockedOutfits.toList()))
        .put("unlockedVideos", JSONArray(state.unlockedVideos))
        .put("unlockedAnime", JSONArray(state.unlockedAnime))
        .put("unlockedTheaters", JSONArray(state.unlockedTheaters.toList()))
        .put("mediaUris", JSONObject(state.mediaUris))
        .put("shopDate", state.shopDate)
        .put("shopRefreshCount", state.shopRefreshCount)
        .put("shopItems", JSONArray().apply { state.shopItems.forEach { item -> put(JSONObject().put("id", item.id).put("title", item.title).put("subtitle", item.subtitle).put("cost", item.cost).put("reward", item.reward.name).put("amount", item.amount).put("stock", item.stock).put("purchased", item.purchased)) } })

    private fun decode(json: JSONObject): StudyLegacyRewardState = StudyLegacyRewardState(
        kudos = json.optInt("kudos"),
        gameFragments = json.optInt("gameFragments"),
        videoFragments = json.optInt("videoFragments"),
        animeFragments = json.optInt("animeFragments"),
        normalFragments = json.optInt("normalFragments"),
        rareFragments = json.optInt("rareFragments"),
        epicFragments = json.optInt("epicFragments"),
        rainbowFragments = json.optInt("rainbowFragments"),
        universalRareFragments = json.optInt("universalRareFragments"),
        universalEpicFragments = json.optInt("universalEpicFragments"),
        blindBoxes = buildList {
            val array = json.optJSONArray("blindBoxes") ?: JSONArray()
            for (index in 0 until array.length()) array.optJSONObject(index)?.let { add(StudyBlindBox(it.optString("id"), it.optInt("kudos"), it.optInt("normal"))) }
        },
        outfitFragments = json.optJSONObject("outfitFragments").toIntMap(),
        unlockedOutfits = json.optJSONArray("unlockedOutfits").toStringSet(),
        unlockedVideos = json.optJSONArray("unlockedVideos").toStringList(),
        unlockedAnime = json.optJSONArray("unlockedAnime").toStringList(),
        unlockedTheaters = json.optJSONArray("unlockedTheaters").toStringSet(),
        mediaUris = json.optJSONObject("mediaUris").toStringMap(),
        shopDate = json.optString("shopDate", LocalDate.now().toString()),
        shopRefreshCount = json.optInt("shopRefreshCount"),
        shopItems = buildList {
            val array = json.optJSONArray("shopItems") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val reward = runCatching { StudyLegacyShopReward.valueOf(item.optString("reward")) }.getOrNull() ?: continue
                add(StudyLegacyShopItem(item.optString("id"), item.optString("title"), item.optString("subtitle"), item.optInt("cost"), reward, item.optInt("amount", 1), item.optInt("stock", 1), item.optInt("purchased")))
            }
        }.ifEmpty { defaultLegacyShop(LocalDate.now()) },
    )

    private companion object {
        const val PREFS_NAME = "lulu_study_legacy_rewards"
        const val KEY_STATE = "state"
        const val MAX_KUDOS = 1000
        const val OUTFIT_FRAGMENT_MAX = 4
    }
}

private fun defaultLegacyShop(date: LocalDate): List<StudyLegacyShopItem> {
    val seed = date.toEpochDay().toInt()
    val all = listOf(
        StudyLegacyShopItem("game-$seed", "游戏畅玩券", "在收藏页兑换并跳转游戏 App", 60, StudyLegacyShopReward.GameTicket, 1, 2),
        StudyLegacyShopItem("video-$seed", "视频解锁卡", "解锁下一个收藏视频", 70, StudyLegacyShopReward.VideoCard, 1, 2),
        StudyLegacyShopItem("anime-$seed", "番剧兑换券", "解锁下一集番剧收藏", 55, StudyLegacyShopReward.AnimeTicket, 1, 2),
        StudyLegacyShopItem("rare-$seed", "万能稀有碎片", "为任意服装画卷补1枚碎片", 120, StudyLegacyShopReward.UniversalRare, 1, 1),
        StudyLegacyShopItem("epic-$seed", "万能史诗碎片", "为任意服装画卷补2枚碎片", 260, StudyLegacyShopReward.UniversalEpic, 1, 1),
        StudyLegacyShopItem("rainbow-$seed", "月光彩碎", "用于解锁下一幕月光剧场", 520, StudyLegacyShopReward.RainbowFragment, 1, 1),
        StudyLegacyShopItem("ten-$seed", "十连券", "旧版十连券商品", 80, StudyLegacyShopReward.TenDrawTicket, 1, 1),
    )
    return all.shuffled(Random(seed)).take(4)
}

private fun JSONObject?.toIntMap(): Map<String, Int> = buildMap { this@toIntMap?.keys()?.forEach { put(it, this@toIntMap.optInt(it)) } }
private fun JSONObject?.toStringMap(): Map<String, String> = buildMap { this@toStringMap?.keys()?.forEach { put(it, this@toStringMap.optString(it)) } }
private fun JSONArray?.toStringList(): List<String> = buildList { val array = this@toStringList ?: return@buildList; for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add) }
private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()

object StudyLegacyRewards {
    private var internal: StudyLegacyRewardsStore? = null
    val store: StudyLegacyRewardsStore get() = checkNotNull(internal) { "StudyLegacyRewards 尚未初始化" }
    fun initialize(context: Context) { if (internal == null) internal = StudyLegacyRewardsStore(context.applicationContext) }
}
