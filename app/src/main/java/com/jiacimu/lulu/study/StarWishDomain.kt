package com.jiacimu.lulu.study

import android.content.Context
import android.util.Base64
import com.jiacimu.lulu.LuluRepositories
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
import java.util.UUID

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

internal data class StarWishChapterPlan(
    val id: String = UUID.randomUUID().toString(),
    val number: Int,
    val title: String,
    val outline: String,
    // Concrete scenes to execute rather than only a list of events.
    val spotlight: String = "",
    val sceneBeats: String = "",
    val relationshipBeat: String = "",
)

internal data class StarWishStoryLedger(
    val summary: String = "",
    val characters: String = "",
    val worldState: String = "",
    val relationships: String = "",
    val openThreads: String = "",
    val foreshadows: String = "",
    val keyItems: String = "",
    val hardFacts: String = "",
    val updatedThroughChapter: Int = 0,
    val evidenceOnly: Boolean = false,
    // Independent continuity anchors, updated from actually published prose.
    val chronology: String = "",
    val physicalStates: String = "",
    val itemTransitions: String = "",
)

internal data class StarWishStoryBible(
    val worldview: String = "",
    val overview: String = "",
    val hook: String = "",
    val highlights: String = "",
    val emotionalArc: String = "",
    val proseStyle: String = "",
    val cast: String = "",
    val characterArcs: String = "",
    val relationshipArc: String = "",
    val plotSpine: String = "",
    val mainLine: String = "",
    val hiddenLine: String = "",
    val foreshadows: String = "",
    val stagePlan: String = "",
    val endingDirection: String = "",
    val romanceAesthetics: String = "",
    val updatedThroughChapter: Int = 0,
    // A creative compass, not a requirement to insert romance or conflict.
    val experienceFocus: String = "",
    val appearanceDesign: String = "",
    val relationshipDynamics: String = "",
) {
    fun promptText(): String = buildString {
        if (experienceFocus.isNotBlank()) appendLine("【阅读体验优先级 / 想兑现的场面】：$experienceFocus")
        if (appearanceDesign.isNotBlank()) appendLine("【主要人物稳定视觉档案】：$appearanceDesign")
        if (relationshipDynamics.isNotBlank()) appendLine("【双向关系动力】：$relationshipDynamics")
        if (worldview.isNotBlank()) appendLine("世界观：$worldview")
        if (overview.isNotBlank()) appendLine("故事总纲：$overview")
        if (hook.isNotBlank()) appendLine("核心钩子：$hook")
        if (highlights.isNotBlank()) appendLine("核心看点：$highlights")
        if (emotionalArc.isNotBlank()) appendLine("情绪曲线：$emotionalArc")
        if (proseStyle.isNotBlank()) appendLine("文风执行：$proseStyle")
        if (cast.isNotBlank()) appendLine("人物与人设：$cast")
        if (characterArcs.isNotBlank()) appendLine("人物成长弧：$characterArcs")
        if (relationshipArc.isNotBlank()) appendLine("长期感情线：$relationshipArc")
        if (plotSpine.isNotBlank()) appendLine("故事脉络 / 主线：$plotSpine")
        if (mainLine.isNotBlank()) appendLine("明线：$mainLine")
        if (hiddenLine.isNotBlank()) appendLine("暗线：$hiddenLine")
        if (foreshadows.isNotBlank()) appendLine("伏笔系统：$foreshadows")
        if (stagePlan.isNotBlank()) appendLine("阶段高潮与节奏：$stagePlan")
        if (endingDirection.isNotBlank()) appendLine("结局方向：$endingDirection")
        if (romanceAesthetics.isNotBlank()) appendLine("感情与审美描写：$romanceAesthetics")
    }.trim()
}

internal data class StarWishState(
    val imageLaunches: List<StarWishImageLaunch> = emptyList(),
    val customPrompts: Map<String, StarWishOutfitPrompts> = emptyMap(),
    val theaterChapters: Map<String, List<StarWishTheaterChapter>> = emptyMap(),
    val theaterGuides: Map<String, String> = emptyMap(),
    val theaterPlans: Map<String, List<StarWishChapterPlan>> = emptyMap(),
    val theaterLedgers: Map<String, StarWishStoryLedger> = emptyMap(),
    val theaterBibles: Map<String, StarWishStoryBible> = emptyMap(),
    val theaterWorldBookIds: Map<String, Set<String>> = emptyMap(),
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
    const val MAX_CHAPTERS_PER_THEATER = 200

    // Public builds have no authored default stories or predefined cast.
    // Previously saved chapters/guides remain discoverable from local StarWishState.
    val theaters: List<StarWishTheaterSeed> = emptyList()

    fun defaultPrompts(outfit: String, characterName: String): StarWishOutfitPrompts = StarWishOutfitPrompts(
        solo = """
            以${characterName}为唯一主体，绘制《$outfit》主题收藏画卷。保持角色既有人设、发型、脸部特征和气质，服装与背景围绕主题完整设计。构图要有明确前景、中景和远景，脸与手清晰，材质细节可见，光影自然，收藏级二次元厚涂 CG，8K，sharp focus, detailed fingers, luminous eyes, soft bokeh。
        """.trimIndent(),
        interaction = """
            绘制《$outfit》主题的双人互动收藏画卷，${characterName}是主视觉，另一位是用户。保持两人的关系边界和既有人设，不擅自改变外貌；动作自然且符合既定关系，但不遮挡脸和手。主题服装、饰品、背景和光影具有完整叙事，收藏级二次元厚涂 CG，8K，sharp focus on faces and hands, detailed fingers, soft bokeh。
        """.trimIndent(),
    )
}

/** Removes private-author built-in theater records from old installs.
 * Only one-way fingerprints are shipped; no private story cast or prompts remain.
 */
internal object LegacyBuiltInTheaters {
    private val legacyTitleDigests = setOf(
        "df8384e8559c50a29abc8147d7fe50bb7299a5337b4c67400306f3628903c50b",
        "6cf6c46d8afd3acbc1c22eacae05bd8d37f1bbbbf83692dc5ef1ad3141020521",
        "f69322b1a31129ef442627e7d19ee14ea8a9a389f3234b6a96465fdf1e896c27",
        "601c546b3c83d6a69581b9b6bacb25676dd5f062e3b42fd5f4be365d68be3369",
        "723cff439a1e424f6d930d3b83d3917ce77db5334a9faf5ffbe0d6796232c843",
        "1e380002015fc2b47030a16ec64eb7243ba264d6e94fba05d5a584ca268ae0cd",
        "f0a6edc70b624e69f1f00e30493bcccb025fee220be02fbe67a1026c71a1b710",
        "85315a15e2e1d53155e5380d76b37522734c11655911b675405601eae8ad9bc2",
        "c20361c077d42ca8523c441d105cf2947f11b9225c487a81dddc216055747000",
        "7463fcc802f40611f73e93a2b81dca01896a7cf3d2cd56c5b73c25dae75e3936",
        "f816eba24ccf8a961ea25abaffc36b2cac11c241f05b9a3ee80820fa0b31301a",
        "4c99b567983f0d858fb4ed787805a7896ecb9baa00caa5663247d9b07c3b22ed",
        "be82eaa71b0f7aebfdeeae4330ab656fb7181b50c57d24180b306e9c55d0cfee",
        "3c09d8afcc6d3e4108bbadb9164c5585ac75da8f9b64108728e82d81681176a4",
        "527951adf50599c488aa10587987b6b52aa2b3d159b0b5d76df2e5e3e282344b",
        "a48a61c79a2a195374b6d9038507590d56a6073fd24cb5e090c0effce1ada0fa",
    )

    fun matches(title: String): Boolean {
        if (title.isBlank()) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(title.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest in legacyTitleDigests
    }

    fun purge(state: StarWishState): StarWishState {
        val titles = (state.theaterChapters.keys + state.theaterGuides.keys +
            state.theaterPlans.keys + state.theaterLedgers.keys +
            state.theaterBibles.keys + state.theaterWorldBookIds.keys)
            .filter(::matches).toSet()
        if (titles.isEmpty()) return state
        return state.copy(
            theaterChapters = state.theaterChapters - titles,
            theaterGuides = state.theaterGuides - titles,
            theaterPlans = state.theaterPlans - titles,
            theaterLedgers = state.theaterLedgers - titles,
            theaterBibles = state.theaterBibles - titles,
            theaterWorldBookIds = state.theaterWorldBookIds - titles,
        )
    }
}

internal class StarWishStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val stateFile = File(context.applicationContext.filesDir, "starwish/state_v2.json")
    private val chapterDraftPrefs = context.applicationContext.getSharedPreferences("lulu_theater_chapter_drafts_v1", Context.MODE_PRIVATE)
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

    fun setStoryPlan(theater: String, guide: String, plans: List<StarWishChapterPlan>) = update { current ->
        current.copy(
            theaterGuides = current.theaterGuides + (theater to guide.trim()),
            theaterPlans = current.theaterPlans + (theater to plans.mapIndexed { index, plan ->
                plan.copy(number = index + 1, title = plan.title.ifBlank { "第 ${index + 1} 章" })
            }),
        )
    }

    fun setLedger(theater: String, ledger: StarWishStoryLedger) = update { current ->
        current.copy(theaterLedgers = current.theaterLedgers + (theater to ledger))
    }

    fun setBible(theater: String, bible: StarWishStoryBible) = update { current ->
        current.copy(theaterBibles = current.theaterBibles + (theater to bible))
    }

    fun setTheaterWorldBookIds(theater: String, ids: Set<String>) = update { current ->
        current.copy(theaterWorldBookIds = current.theaterWorldBookIds + (theater to ids.filter(String::isNotBlank).toSet()))
    }

    fun addChapter(chapter: StarWishTheaterChapter) {
        update { current ->
            current.copy(theaterChapters = current.theaterChapters + (chapter.theater to (current.theaterChapters[chapter.theater].orEmpty() + chapter)))
        }
    }

    @Synchronized fun updateTheaterIfUnchanged(theater: String, expected: StarWishState, action: () -> Unit) {
        check(sameTheater(mutable.value, expected, theater)) { "故事设定或正文已变化，旧任务已停止，请重新生成" }
        action()
    }

    private fun sameTheater(a: StarWishState, b: StarWishState, theater: String): Boolean =
        a.theaterChapters[theater] == b.theaterChapters[theater] &&
        a.theaterGuides[theater] == b.theaterGuides[theater] &&
        a.theaterPlans[theater] == b.theaterPlans[theater] &&
        a.theaterBibles[theater] == b.theaterBibles[theater] &&
        a.theaterWorldBookIds[theater] == b.theaterWorldBookIds[theater]

    @Synchronized fun appendGeneratedChapter(chapter: StarWishTheaterChapter, expected: StarWishState) {
        val current = mutable.value
        check(sameTheater(current, expected, chapter.theater)) {
            "故事正文或规划已变化，本次旧结果已取消，请重新续写"
        }
        check(chapter.chapter == current.theaterChapters[chapter.theater].orEmpty().size + 1)
        addChapter(chapter)
    }

    @Synchronized fun setGeneratedLedger(theater: String, ledger: StarWishStoryLedger,
        expected: StarWishState, chapter: StarWishTheaterChapter) {
        val current = mutable.value
        if (current.theaterChapters[theater].orEmpty() != expected.theaterChapters[theater].orEmpty() + chapter ||
            current.theaterGuides[theater] != expected.theaterGuides[theater] ||
            current.theaterPlans[theater] != expected.theaterPlans[theater] ||
            current.theaterBibles[theater] != expected.theaterBibles[theater] ||
            current.theaterWorldBookIds[theater] != expected.theaterWorldBookIds[theater]) return
        setLedger(theater, ledger)
    }

    fun updateChapter(theater: String, chapterId: String, title: String, content: String) = update { current ->
        current.copy(
            theaterChapters = current.theaterChapters + (theater to current.theaterChapters[theater].orEmpty().map { chapter ->
                if (chapter.id == chapterId) chapter.copy(title = title.trim(), content = content.trim()) else chapter
            }),
            theaterLedgers = current.theaterLedgers - theater,
        )
    }

    fun deleteChaptersFrom(theater: String, chapterNumber: Int) {
        update { current ->
            val kept = current.theaterChapters[theater].orEmpty().filter { it.chapter < chapterNumber }
            current.copy(
                theaterChapters = current.theaterChapters + (theater to kept),
                theaterLedgers = current.theaterLedgers - theater,
            )
        }
        chapterDraftPrefs.edit().remove("chapter:$theater").apply()
    }

    fun deleteTheater(theater: String) {
        update { current ->
            current.copy(
                theaterChapters = current.theaterChapters - theater,
                theaterGuides = current.theaterGuides - theater,
                theaterPlans = current.theaterPlans - theater,
                theaterLedgers = current.theaterLedgers - theater,
                theaterBibles = current.theaterBibles - theater,
                theaterWorldBookIds = current.theaterWorldBookIds - theater,
            )
        }
        chapterDraftPrefs.edit().remove("chapter:$theater").apply()
    }

    @Synchronized
    private fun update(transform: (StarWishState) -> StarWishState) {
        val next = transform(mutable.value)
        persist(next)
        mutable.value = next
    }

    private fun load(): StarWishState {
        val existing = runCatching { stateFile.takeIf(File::isFile)?.readText() }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { decode(JSONObject(raw)) }.getOrNull() }
            ?: prefs.getString(KEY_STATE, null)
                ?.takeIf(String::isNotBlank)
                ?.let { raw -> runCatching { decode(JSONObject(raw)) }.getOrNull()?.also(::persist) }
            ?: StarWishState()
        val cleaned = LegacyBuiltInTheaters.purge(existing)
        if (cleaned != existing) persist(cleaned)
        return cleaned
    }

    private fun persist(value: StarWishState) {
        stateFile.parentFile?.mkdirs()
        val temporary = File(stateFile.parentFile, "${stateFile.name}.tmp")
        temporary.writeText(encode(value).toString())
        if (!temporary.renameTo(stateFile)) {
            stateFile.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun encode(value: StarWishState): JSONObject = JSONObject()
        .put("images", JSONArray().apply { value.imageLaunches.forEach { put(encodeImage(it)) } })
        .put("prompts", JSONObject().apply { value.customPrompts.forEach { (name, prompts) -> put(name, JSONObject().put("solo", prompts.solo).put("interaction", prompts.interaction)) } })
        .put("chapters", JSONObject().apply { value.theaterChapters.forEach { (name, chapters) -> put(name, JSONArray().apply { chapters.forEach { put(encodeChapter(it)) } }) } })
        .put("guides", JSONObject(value.theaterGuides))
        .put("plans", JSONObject().apply { value.theaterPlans.forEach { (name, plans) -> put(name, JSONArray().apply { plans.forEach { put(encodePlan(it)) } }) } })
        .put("ledgers", JSONObject().apply { value.theaterLedgers.forEach { (name, ledger) -> put(name, encodeLedger(ledger)) } })
        .put("bibles", JSONObject().apply { value.theaterBibles.forEach { (name, bible) -> put(name, encodeBible(bible)) } })
        .put("theaterWorldBookIds", JSONObject().apply {
            value.theaterWorldBookIds.forEach { (name, ids) ->
                put(name, JSONArray().apply { ids.forEach { id -> put(id) } })
            }
        })

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
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, objectValue.optString(key))
                }
            }
        }.orEmpty()
        val plans = root.optJSONObject("plans").decodeArrayMap(::decodePlan)
        val ledgers = root.optJSONObject("ledgers").decodeObjectMap(::decodeLedger)
        val bibles = root.optJSONObject("bibles").decodeObjectMap(::decodeBible)
        val theaterWorldBookIds = root.optJSONObject("theaterWorldBookIds")?.let { objectValue ->
            buildMap {
                val keys = objectValue.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val array = objectValue.optJSONArray(key) ?: continue
                    put(key, buildSet {
                        for (index in 0 until array.length()) {
                            val id = array.optString(index).trim()
                            if (id.isNotBlank()) add(id)
                        }
                    })
                }
            }
        }.orEmpty()
        return StarWishState(
            imageLaunches = root.optJSONArray("images").decodeObjects(::decodeImage),
            customPrompts = prompts,
            theaterChapters = chapters,
            theaterGuides = guides,
            theaterPlans = plans,
            theaterLedgers = ledgers,
            theaterBibles = bibles,
            theaterWorldBookIds = theaterWorldBookIds,
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

    private fun encodePlan(value: StarWishChapterPlan) = JSONObject()
        .put("id", value.id).put("number", value.number).put("title", value.title).put("outline", value.outline)
        .put("spotlight", value.spotlight).put("sceneBeats", value.sceneBeats)
        .put("relationshipBeat", value.relationshipBeat)

    private fun decodePlan(item: JSONObject) = StarWishChapterPlan(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        number = item.optInt("number"),
        title = item.optString("title"),
        outline = item.optString("outline"),
        spotlight = item.optString("spotlight"),
        sceneBeats = item.optString("sceneBeats"),
        relationshipBeat = item.optString("relationshipBeat"),
    )

    private fun encodeLedger(value: StarWishStoryLedger) = JSONObject()
        .put("summary", value.summary).put("characters", value.characters).put("worldState", value.worldState)
        .put("relationships", value.relationships).put("openThreads", value.openThreads)
        .put("foreshadows", value.foreshadows).put("keyItems", value.keyItems)
        .put("hardFacts", value.hardFacts)
        .put("chronology", value.chronology).put("physicalStates", value.physicalStates)
        .put("itemTransitions", value.itemTransitions)
        .put("updatedThroughChapter", value.updatedThroughChapter).put("evidenceOnly", value.evidenceOnly)

    private fun decodeLedger(item: JSONObject) = StarWishStoryLedger(
        summary = item.optString("summary"), characters = item.optString("characters"),
        worldState = item.optString("worldState"), relationships = item.optString("relationships"),
        openThreads = item.optString("openThreads"), foreshadows = item.optString("foreshadows"),
        keyItems = item.optString("keyItems"), hardFacts = item.optString("hardFacts"),
        chronology = item.optString("chronology"), physicalStates = item.optString("physicalStates"),
        itemTransitions = item.optString("itemTransitions"),
        updatedThroughChapter = item.optInt("updatedThroughChapter"),
        evidenceOnly = item.optBoolean("evidenceOnly", false),
    )

    private fun encodeBible(value: StarWishStoryBible) = JSONObject()
        .put("worldview", value.worldview).put("overview", value.overview)
        .put("hook", value.hook).put("highlights", value.highlights)
        .put("emotionalArc", value.emotionalArc).put("proseStyle", value.proseStyle)
        .put("cast", value.cast).put("characterArcs", value.characterArcs)
        .put("relationshipArc", value.relationshipArc).put("plotSpine", value.plotSpine)
        .put("mainLine", value.mainLine).put("hiddenLine", value.hiddenLine)
        .put("foreshadows", value.foreshadows).put("stagePlan", value.stagePlan)
        .put("endingDirection", value.endingDirection).put("romanceAesthetics", value.romanceAesthetics)
        .put("experienceFocus", value.experienceFocus).put("appearanceDesign", value.appearanceDesign)
        .put("relationshipDynamics", value.relationshipDynamics)
        .put("updatedThroughChapter", value.updatedThroughChapter)

    private fun decodeBible(item: JSONObject) = StarWishStoryBible(
        worldview = item.optString("worldview"), overview = item.optString("overview"),
        hook = item.optString("hook"), highlights = item.optString("highlights"),
        emotionalArc = item.optString("emotionalArc"), proseStyle = item.optString("proseStyle"),
        cast = item.optString("cast"), characterArcs = item.optString("characterArcs"),
        relationshipArc = item.optString("relationshipArc"), plotSpine = item.optString("plotSpine"),
        mainLine = item.optString("mainLine"), hiddenLine = item.optString("hiddenLine"),
        foreshadows = item.optString("foreshadows"), stagePlan = item.optString("stagePlan"),
        endingDirection = item.optString("endingDirection"), romanceAesthetics = item.optString("romanceAesthetics"),
        experienceFocus = item.optString("experienceFocus"),
        appearanceDesign = item.optString("appearanceDesign"),
        relationshipDynamics = item.optString("relationshipDynamics"),
        updatedThroughChapter = item.optInt("updatedThroughChapter"),
    )

    companion object {
        private const val PREFS_NAME = "lulu_star_wish"
        private const val KEY_STATE = "state_v1"
        fun create(context: Context) = StarWishStore(context)
    }
}

internal object StarWishStores {
    @Volatile private var storeInternal: StarWishStore? = null
    val main: StarWishStore get() = checkNotNull(storeInternal) { "StarWishStores 尚未初始化" }
    @Synchronized fun initialize(context: Context) {
        if (storeInternal == null) storeInternal = StarWishStore.create(context.applicationContext)
    }
}

internal class StarWishImageService(private val context: Context) {
    suspend fun generate(outfit: String, prompt: String, interaction: Boolean): Result<StarWishImageLaunch> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            check(prefs.getBoolean("image_enabled", false)) { "请先在设置中启用生图" }
            val baseUrl = prefs.getString("image_url", "").orEmpty().trim().trimEnd('/')
            val apiKey = prefs.getString("image_key", "").orEmpty().trim()
            val model = prefs.getString("image_model", "").orEmpty().trim()
            val size = prefs.getString("image_size", "1024x1024").orEmpty().trim().ifBlank { "1024x1024" }
            val negativePrompt = prefs.getString("image_negative_prompt", "").orEmpty().trim()
            check(baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()) { "请先完成独立生图接口与模型设置" }
            val body = JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
                .put("size", size).put("response_format", "b64_json")
            if (negativePrompt.isNotBlank()) body.put("negative_prompt", negativePrompt)
            val endpoint = "$baseUrl/images/generations"
            val http = URL(endpoint).openConnection() as HttpURLConnection
            val raw = try {
                http.requestMethod = "POST"
                http.connectTimeout = 30_000
                http.readTimeout = 180_000
                http.doOutput = true
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                http.setRequestProperty("Authorization", "Bearer $apiKey")
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

private fun <T> JSONObject?.decodeArrayMap(transform: (JSONObject) -> T): Map<String, List<T>> {
    if (this == null) return emptyMap()
    return buildMap {
        val keys = this@decodeArrayMap.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, this@decodeArrayMap.optJSONArray(key).decodeObjects(transform))
        }
    }
}

private fun <T> JSONObject?.decodeObjectMap(transform: (JSONObject) -> T): Map<String, T> {
    if (this == null) return emptyMap()
    return buildMap {
        val keys = this@decodeObjectMap.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            this@decodeObjectMap.optJSONObject(key)?.let { put(key, transform(it)) }
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
