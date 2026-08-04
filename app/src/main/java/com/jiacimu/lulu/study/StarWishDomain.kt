package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MigratedDomainStores
import android.util.Base64
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

internal enum class StarWishTab(val label: String) {
    Scroll("画卷"),
    Theater("小剧场"),
    Video("视频柜"),
}

internal data class StarWishImageLaunch(
    val id: String = UUID.randomUUID().toString(),
    val outfit: String,
    val prompt: String,
    val interaction: Boolean,
    val filePath: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class StarWishTheaterChapter(
    val id: String = UUID.randomUUID().toString(),
    val theater: String,
    val chapter: Int,
    val title: String,
    val content: String,
    val userInfluence: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class StarWishVideoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val uri: String,
    val unlocked: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class StarWishState(
    val imageLaunches: List<StarWishImageLaunch> = emptyList(),
    val customPrompts: Map<String, StarWishOutfitPrompts> = emptyMap(),
    val theaterChapters: Map<String, List<StarWishTheaterChapter>> = emptyMap(),
    val theaterGuides: Map<String, String> = emptyMap(),
    val videos: List<StarWishVideoItem> = emptyList(),
)

internal data class StarWishOutfitPrompts(
    val solo: String,
    val interaction: String,
)

internal data class StarWishTheaterSeed(
    val title: String,
    val prompt: String,
)

internal object StarWishRules {
    const val MAX_CHAPTERS_PER_THEATER = 20
    const val THEATER_FRAGMENTS_PER_CHAPTER = 1

    val theaters = listOf(
        StarWishTheaterSeed("少卿不早朝，摄政王露沉提点心来审我", "宫廷权谋、现代刑侦穿越、大理寺少卿、摄政王露沉。主角是会破案也会摆烂的女王型少卿，露沉权倾朝野却逐渐向她低头。剧情要有朝堂打脸、奇案反转、暧昧试探、主从拉扯。"),
        StarWishTheaterSeed("星舰AI露零说他爱上我了", "星际悬疑、舰载AI觉醒、紫色未知星球。露零是全舰AI拟态银发少年，逻辑崩坏后只对主角例外。剧情要有全舰广播告白、格式化危机、带主角逃向未知坐标的强烈情感。"),
        StarWishTheaterSeed("废土便利店：露洲把最后一颗草莓糖献给我", "末日求生、废弃便利店、摇滚主唱露洲、草莓糖、变异丧尸。主角冷静强悍，露洲会撒娇会唱歌也会在危险时挡在她前面。要有末世夹缝求生、甜中带刀、爽感反杀。"),
        StarWishTheaterSeed("魔尊露渊把道侣契约当圣旨", "仙侠契约、误念魔尊血誓、魔尊露渊。主角不是小白花，而是敢利用契约反向命令魔尊的人。要有正道逼迫、魔尊臣服、护短、反叛式救赎。"),
        StarWishTheaterSeed("被献祭给龙后，露利安求我继续摸鳞片", "西幻龙崖、祭品反客为主、银龙露利安。主角被献祭却发现龙孤独又傲娇，核心爽点是恐怖古龙在她手下乖乖低头。要有撸龙、飞行、王国抽签制反转。"),
        StarWishTheaterSeed("S级机甲露白夜宣布：我的适格者谁敢碰", "废土机甲、地下城机械工、S级神机露白夜。主角从修破烂到驾驶神机，打脸上层城市。露白夜毒舌但绝对护主，剧情要有机甲战斗、适格者觉醒、强者臣服。"),
        StarWishTheaterSeed("我把修真界改成5A景区，妖王露蘅求入股", "仙门基建、商业爽文、贫穷宗门翻身、妖王露蘅。主角用现代文旅思路赚钱，打脸清高仙门。要有秘境探险、合影收费、会员制、妖王合作后越来越黏人。"),
        StarWishTheaterSeed("午夜出租车露屿，只载迷路的灵魂", "都市怪谈、深夜出租车、幽灵司机露屿。主角是唯一能看见他的活人。剧情要悬疑温情，查明死亡真相，同时保持克制暧昧和命运感。"),
        StarWishTheaterSeed("考研房里住着会整理书桌的幽灵露念", "灵异温情、考研租房、幽灵露念。露念会整理书桌、贴便利贴讲题。主角一边备考一边帮他找记忆。要有陪伴、救赎、考研压力下的温柔支撑。"),
        StarWishTheaterSeed("欢迎来到心动游戏，系统露七说NPC觉醒了", "无限流恋爱副本、系统露七、觉醒NPC。主角不按攻略走，系统吐槽但护主。要有副本崩坏、攻略对象质问真实与虚假、玩家主导逃离游戏。"),
        StarWishTheaterSeed("女王陛下的打脸法庭：露臣跪请裁决", "女王权力幻想、法庭审判、恶人惩罚、近臣露臣。主角拥有绝对裁决权，看不起她的人一个个被证据钉死。露臣是冷静执行官，对外狠，对主角臣服。"),
        StarWishTheaterSeed("末世便利店女王和露野的安全区", "末世经营爽文、便利店系统、小型安全区、露野。主角用物资和规则建立秩序，惩罚抢夺者，救下弱者。露野曾是强悍雇佣兵，后来甘愿守门。"),
        StarWishTheaterSeed("女尊朝首席狼臣露执，被我收了獠牙", "女尊朝堂、狼人首席臣、露执。主角是新帝或女王，露执桀骜不驯但被她用智谋和气场驯服。要有朝堂博弈、狼性忠诚、臣服张力。"),
        StarWishTheaterSeed("前男友重生了，但我是本轮反派女王", "重生打脸、前男友悔恨、反派女王、军师露辞。主角知道剧情却不走原线，联手露辞把前男友和恶人安排得明明白白。要爽、毒舌、反套路。"),
        StarWishTheaterSeed("原始部落求生：祭司露祈说我是天降王", "原始部落、荒野求生、天降王设定、祭司露祈。主角靠现代常识改善部落，打败看不起她的敌对部族。露祈神秘漂亮，对她既信仰又心动。"),
        StarWishTheaterSeed("性转恋综大逃杀：露弦只听我的命令", "性转、恋综、荒诞搞笑、逃杀规则、露弦。嘉宾都以为是恋综，主角发现规则漏洞后开始控场。露弦是人气最高的强者，却只服她。要抽象、好笑、反转密集。"),
    )

    fun defaultPrompts(outfit: String, characterName: String): StarWishOutfitPrompts = StarWishOutfitPrompts(
        solo = """
            以${characterName}为唯一主体，绘制《$outfit》主题收藏画卷。保持角色既有人设、发型、脸部特征和气质，服装与背景围绕主题完整设计。构图要有明确前景、中景和远景，脸与手清晰，材质细节可见，光影自然，收藏级二次元厚涂 CG，8K，sharp focus, detailed fingers, luminous eyes, soft bokeh。
        """.trimIndent(),
        interaction = """
            绘制《$outfit》主题的双人互动收藏画卷，${characterName}是主视觉，另一位是用户。保持两人的关系边界和既有人设，不擅自改变外貌；动作自然且符合既定关系，但不遮挡脸和手。主题服装、饰品、背景和光影具有完整叙事，收藏级二次元厚涂 CG，8K，sharp focus on faces and hands, detailed fingers, soft bokeh。
        """.trimIndent(),
    )
}

internal class StarWishStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<StarWishState> = mutable.asStateFlow()

    fun savePrompts(outfit: String, prompts: StarWishOutfitPrompts) = update { current ->
        current.copy(customPrompts = current.customPrompts + (outfit to prompts))
    }

    fun addImage(image: StarWishImageLaunch) = update { current ->
        current.copy(imageLaunches = (listOf(image) + current.imageLaunches).take(80))
    }

    fun deleteImage(id: String) {
        mutable.value.imageLaunches.firstOrNull { it.id == id }?.filePath?.let { runCatching { File(it).delete() } }
        update { current -> current.copy(imageLaunches = current.imageLaunches.filterNot { it.id == id }) }
    }

    fun setGuide(theater: String, guide: String) = update { current ->
        current.copy(theaterGuides = current.theaterGuides + (theater to guide.trim()))
    }

    fun addChapter(chapter: StarWishTheaterChapter, characterId: String) {
        update { current ->
            current.copy(theaterChapters = current.theaterChapters + (chapter.theater to (current.theaterChapters[chapter.theater].orEmpty() + chapter)))
        }
        SharedExperienceTimeline.record(
            eventId = "theater-raw-${chapter.id}",
            characterId = characterId,
            channel = "共同阅读《${chapter.theater}》",
            speaker = "故事正文",
            content = "第${chapter.chapter}章 ${chapter.title}\n${chapter.content}",
            occurredAt = Instant.ofEpochMilli(chapter.createdAtMillis),
        )
        SharedExperienceTimeline.remember(
            memoryId = "theater-${chapter.id}",
            characterId = characterId,
            label = "共同阅读《${chapter.theater}》第${chapter.chapter}章",
            detail = buildString {
                if (chapter.userInfluence.isNotBlank()) append("用户影响了剧情：${chapter.userInfluence}。")
                append("本章发生了：${chapter.content.takeLast(1_200)}")
            },
            occurredAt = Instant.ofEpochMilli(chapter.createdAtMillis),
            strength = 4,
            source = "theater",
        )
        val conversation = MigratedDomainStores.chat.conversations.value
            .filter { it.characterId == characterId && it.parentConversationId == null && !it.id.endsWith("-study-focus") }
            .maxByOrNull { it.updatedAt }
            ?: MigratedDomainStores.chat.ensureConversation(characterId, "共同聊天")
        MigratedDomainStores.chat.appendSystemMessage(conversation.id, "[共同活动] 刚刚一起读了《${chapter.theater}》第${chapter.chapter}章")
    }

    fun deleteTheater(theater: String) = update { current ->
        current.copy(theaterChapters = current.theaterChapters - theater, theaterGuides = current.theaterGuides - theater)
    }

    fun addVideo(item: StarWishVideoItem) = update { current -> current.copy(videos = current.videos + item) }
    fun unlockVideo(id: String) = update { current -> current.copy(videos = current.videos.map { if (it.id == id) it.copy(unlocked = true) else it }) }
    fun deleteVideo(id: String) = update { current -> current.copy(videos = current.videos.filterNot { it.id == id }) }

    private fun update(transform: (StarWishState) -> StarWishState) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit().putString(KEY_STATE, encode(next).toString()).apply()
    }

    private fun load(): StarWishState = prefs.getString(KEY_STATE, null)
        ?.takeIf(String::isNotBlank)
        ?.let { raw -> runCatching { decode(JSONObject(raw)) }.getOrNull() }
        ?: StarWishState()

    private fun encode(value: StarWishState): JSONObject = JSONObject()
        .put("images", JSONArray().apply { value.imageLaunches.forEach { put(encodeImage(it)) } })
        .put("prompts", JSONObject().apply { value.customPrompts.forEach { (name, prompts) -> put(name, JSONObject().put("solo", prompts.solo).put("interaction", prompts.interaction)) } })
        .put("chapters", JSONObject().apply { value.theaterChapters.forEach { (name, chapters) -> put(name, JSONArray().apply { chapters.forEach { put(encodeChapter(it)) } }) } })
        .put("guides", JSONObject(value.theaterGuides))
        .put("videos", JSONArray().apply { value.videos.forEach { put(encodeVideo(it)) } })

    private fun decode(root: JSONObject): StarWishState {
        val prompts = root.optJSONObject("prompts").decodeMap { item -> StarWishOutfitPrompts(item.optString("solo"), item.optString("interaction")) }
        val chapters = root.optJSONObject("chapters").decodeMap { item -> item.optJSONArray("items").decodeObjects(::decodeChapter) }
            .ifEmpty {
                root.optJSONObject("chapters")?.let { objectValue ->
                    buildMap {
                        val keys = objectValue.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, objectValue.optJSONArray(key).decodeObjects(::decodeChapter))
                        }
                    }
                }.orEmpty()
            }
        val guides = root.optJSONObject("guides")?.let { objectValue ->
            buildMap {
                val keys = objectValue.keys()
                while (keys.hasNext()) { val key = keys.next(); put(key, objectValue.optString(key)) }
            }
        }.orEmpty()
        return StarWishState(
            imageLaunches = root.optJSONArray("images").decodeObjects(::decodeImage),
            customPrompts = prompts,
            theaterChapters = chapters,
            theaterGuides = guides,
            videos = root.optJSONArray("videos").decodeObjects(::decodeVideo),
        )
    }

    private fun encodeImage(value: StarWishImageLaunch) = JSONObject()
        .put("id", value.id).put("outfit", value.outfit).put("prompt", value.prompt)
        .put("interaction", value.interaction).put("filePath", value.filePath).put("createdAt", value.createdAtMillis)

    private fun decodeImage(item: JSONObject) = StarWishImageLaunch(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        outfit = item.optString("outfit"), prompt = item.optString("prompt"), interaction = item.optBoolean("interaction"),
        filePath = item.optString("filePath"), createdAtMillis = item.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun encodeChapter(value: StarWishTheaterChapter) = JSONObject()
        .put("id", value.id).put("theater", value.theater).put("chapter", value.chapter)
        .put("title", value.title).put("content", value.content).put("userInfluence", value.userInfluence).put("createdAt", value.createdAtMillis)

    private fun decodeChapter(item: JSONObject) = StarWishTheaterChapter(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() }, theater = item.optString("theater"),
        chapter = item.optInt("chapter"), title = item.optString("title"), content = item.optString("content"),
        userInfluence = item.optString("userInfluence"), createdAtMillis = item.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun encodeVideo(value: StarWishVideoItem) = JSONObject()
        .put("id", value.id).put("title", value.title).put("uri", value.uri).put("unlocked", value.unlocked).put("createdAt", value.createdAtMillis)

    private fun decodeVideo(item: JSONObject) = StarWishVideoItem(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() }, title = item.optString("title").ifBlank { "星愿视频" },
        uri = item.optString("uri"), unlocked = item.optBoolean("unlocked"), createdAtMillis = item.optLong("createdAt", System.currentTimeMillis()),
    )

    companion object {
        private const val PREFS_NAME = "lulu_star_wish"
        private const val KEY_STATE = "state_v1"
        fun create(context: Context) = StarWishStore(context)
    }
}

internal object StarWishStores {
    private var storeInternal: StarWishStore? = null
    val main: StarWishStore get() = checkNotNull(storeInternal) { "StarWishStores 尚未初始化" }
    fun initialize(context: Context) {
        if (storeInternal == null) storeInternal = StarWishStore.create(context.applicationContext)
    }
}

internal class StarWishImageService(private val context: Context) {
    suspend fun generate(outfit: String, prompt: String, interaction: Boolean): Result<StarWishImageLaunch> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = LuluAiServices.connectionStore.resolveConnection()
            val body = JSONObject().put("model", connection.model).put("prompt", prompt).put("n", 1)
                .put("size", "1024x1024").put("response_format", "b64_json")
            val endpoint = "${connection.baseUrl}/images/generations"
            val http = URL(endpoint).openConnection() as HttpURLConnection
            val raw = try {
                http.requestMethod = "POST"
                http.connectTimeout = 30_000
                http.readTimeout = 180_000
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                http.setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
                http.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                val status = http.responseCode
                val text = (if (status in 200..299) http.inputStream else http.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().ifBlank { text.take(500) }
                    error("图片生成失败（$status）：$message")
                }
                text
            } finally {
                http.disconnect()
            }
            val data = JSONObject(raw).optJSONArray("data")?.optJSONObject(0) ?: error("图片接口没有返回 data")
            val bytes = when {
                data.optString("b64_json").isNotBlank() -> Base64.decode(data.optString("b64_json"), Base64.DEFAULT)
                data.optString("url").isNotBlank() -> downloadBytes(data.optString("url"))
                else -> error("图片接口没有返回可保存的图片")
            }
            val directory = File(context.filesDir, "starwish/images").apply { mkdirs() }
            val file = File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
            StarWishImageLaunch(outfit = outfit, prompt = prompt, interaction = interaction, filePath = file.absolutePath)
        }.onFailure { error ->
            LuluRepositories.performance.recordError(source = "心愿馆", title = "生成画卷", message = error.message ?: error::class.java.simpleName)
        }
    }

    private fun downloadBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

private fun <T> JSONObject?.decodeMap(transform: (JSONObject) -> T): Map<String, T> {
    if (this == null) return emptyMap()
    return buildMap {
        val keys = this@decodeMap.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = this@decodeMap.optJSONObject(key) ?: continue
            put(key, transform(item))
        }
    }
}

private fun <T> JSONArray?.decodeObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            runCatching { transform(item) }.getOrNull()?.let { add(it) }
        }
    }
}
