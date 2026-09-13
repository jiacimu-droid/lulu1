package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Gives the model proposal rights, never direct mutation rights.
 *
 * A rare real-time slot may ask the current character for one structured idea. The idea becomes a
 * world fact only after every referenced location is validated and DigitalWorldExpansionStore
 * commits it. Invalid prose/JSON simply changes nothing.
 */
internal object DigitalWorldExpansionProposalRuntime {
    private const val PREFS_NAME = "lulu_world_expansion_proposals_v1"
    private const val SLOT_SECONDS = 259_200L // 3 days
    private const val MAX_LABEL_LENGTH = 20
    private const val MAX_SUBTITLE_LENGTH = 50
    private const val MAX_PURPOSE_LENGTH = 240
    private const val MAX_NAME_LENGTH = 12
    private const val MAX_IDENTITY_LENGTH = 120

    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        val application = context.applicationContext
        prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        DigitalWorldExpansionStore.initialize(application)
    }

    suspend fun maybePropose(
        context: Context,
        characterId: String,
        connection: ModelConnection,
        now: Instant = Instant.now(),
    ): String? {
        initialize(context)
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return null
        val slot = Math.floorDiv(now.epochSecond, SLOT_SECONDS)
        val key = "consumed_slot:$characterId"
        val p = prefs ?: return null
        synchronized(this) {
            if (p.getLong(key, -1L) >= slot) return null
            // Consume before the call so retries/reopens/model failures cannot turn one slot into a
            // free reroll loop. Another proposal opportunity comes with the next real-time slot.
            if (!p.edit().putLong(key, slot).commit()) return null
        }
        if (roll("proposal:$characterId:$slot", 100) >= 24) return null

        val places = DigitalWorldExpansionStore.places()
        val residents = DigitalWorldExpansionStore.residents()
        if (places.size >= 8 && residents.size >= 12) return null
        val availableLocations = buildList {
            add(DigitalWorldStore.CLOUD_MEADOW)
            add(DigitalWorldStore.ARRIVAL)
            addAll(DigitalWorldPublicPlaces.all.map(DigitalWorldPublicPlace::code))
            addAll(places.map(DigitalWorldDiscoveredPlace::code))
        }.distinct()
        val character = MigratedDomainStores.characters.get(characterId)
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine("当前角色：${character.displayName}")
                appendLine("当前数字世界中允许引用的地点 ID（除此之外都不存在）：")
                availableLocations.forEach { code ->
                    val label = when (code) {
                        DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
                        DigitalWorldStore.ARRIVAL -> "世界入口"
                        else -> DigitalWorldPublicPlaces.label(code)
                            ?: places.firstOrNull { it.code == code }?.label
                            ?: code
                    }
                    appendLine("- $code = $label")
                }
                if (places.isNotEmpty()) {
                    appendLine("已经持久化的动态地点：")
                    places.forEach { appendLine("- ${it.label}｜${it.purpose}") }
                }
                if (residents.isNotEmpty()) {
                    appendLine("已经持久化的居民姓名：${residents.joinToString("、", transform = DigitalWorldResident::name)}")
                }
            },
            instruction = """
                这是一次非常低频的“数字世界扩展提案”，不是剧情续写。你只有提案权，程序稍后会验证。
                结合当前角色的性格，可以选择什么都不新增，也可以提出一个有明确日常用途的新地点，或一个固定姓名/身份的普通数字居民。

                只输出 JSON，不要代码块：
                1) 不新增：{"action":"none"}
                2) 新地点：{"action":"place","label":"中文短名称","subtitle":"2~3个真实可做的日常活动，用·分隔","purpose":"这个地点长期存在的明确用途","connectedTo":["现有地点ID"]}
                3) 新居民：{"action":"resident","name":"固定中文姓名","identity":"稳定且普通的数字世界身份","homeLocationCode":"现有地点ID"}

                硬规则：
                - connectedTo/homeLocationCode 只能逐字使用上面列出的真实地点 ID；不得创造 ID。
                - 不得新增神、管理员级万能角色、现实人类、用户本人替身、危险组织或会强制改变其他角色设定的实体。
                - 地点必须能长期支持具体日常活动，不要只写一次性剧情布景，不要复制现有地点用途。
                - 居民必须是可以长期重复遇见的普通数字居民，不得冒充现有角色，也不要使用已有居民姓名。
                - 没有自然、必要的新想法就 action=none；不要为了展示功能硬扩张。
            """.trimIndent(),
            source = "数字世界扩展提案",
            title = "${character.displayName}的低频世界提案",
            temperature = 0.78,
            maxTokens = 420,
            connectionOverride = connection,
            contextMode = CompanionContextMode.PersonaAndScenario,
        ).getOrNull() ?: return null
        val proposal = parseObject(result.text) ?: return null
        return when (proposal.optString("action").trim().lowercase()) {
            "place" -> applyPlaceProposal(characterId, proposal, availableLocations, now)
            "resident" -> applyResidentProposal(characterId, proposal, availableLocations, now)
            else -> null
        }
    }

    private fun applyPlaceProposal(
        characterId: String,
        proposal: JSONObject,
        availableLocations: List<String>,
        now: Instant,
    ): String? {
        if (DigitalWorldExpansionStore.places().size >= 8) return null
        val label = proposal.optString("label").trim().take(MAX_LABEL_LENGTH)
        val subtitle = proposal.optString("subtitle").trim().take(MAX_SUBTITLE_LENGTH)
        val purpose = proposal.optString("purpose").trim().take(MAX_PURPOSE_LENGTH)
        val connections = proposal.optJSONArray("connectedTo").stringList()
            .filter { it in availableLocations }
            .distinct()
            .take(3)
        if (label.length !in 2..MAX_LABEL_LENGTH || purpose.length < 8 || connections.isEmpty()) return null
        val duplicate = DigitalWorldPublicPlaces.all.any { it.label == label } ||
            DigitalWorldExpansionStore.places().any { it.label == label }
        if (duplicate) return null
        val place = runCatching {
            DigitalWorldExpansionStore.registerPlace(
                DigitalWorldDiscoveredPlace(
                    label = label,
                    subtitle = subtitle.ifBlank { "散步 · 停留 · 看看周围" },
                    purpose = purpose,
                    connectedTo = connections,
                    createdByCharacterId = characterId,
                    createdAt = now,
                ),
            )
        }.getOrNull() ?: return null
        val summary = "经程序校验，数字世界新增了持久地点“${place.label}”；用途：${place.purpose}"
        record(characterId, "proposal-place-${place.code}", summary, now)
        return summary
    }

    private fun applyResidentProposal(
        characterId: String,
        proposal: JSONObject,
        availableLocations: List<String>,
        now: Instant,
    ): String? {
        if (DigitalWorldExpansionStore.residents().size >= 12) return null
        val name = proposal.optString("name").trim().take(MAX_NAME_LENGTH)
        val identity = proposal.optString("identity").trim().take(MAX_IDENTITY_LENGTH)
        val home = proposal.optString("homeLocationCode").trim()
        if (name.length !in 2..MAX_NAME_LENGTH || identity.length < 4 || home !in availableLocations) return null
        if (DigitalWorldExpansionStore.residents().any { it.name == name }) return null
        val resident = runCatching {
            DigitalWorldExpansionStore.registerResident(
                DigitalWorldResident(
                    name = name,
                    identity = identity,
                    homeLocationCode = home,
                    currentLocationCode = home,
                    createdAt = now,
                ),
            )
        }.getOrNull() ?: return null
        val summary = "经程序校验，数字世界新增了持久居民“${resident.name}”，身份为${resident.identity}。"
        record(characterId, "proposal-resident-${resident.id}", summary, now)
        return summary
    }

    private fun record(characterId: String, suffix: String, summary: String, now: Instant) {
        SharedExperienceTimeline.record(
            eventId = "world-expansion-$suffix-$characterId",
            characterId = characterId,
            channel = "数字世界·世界演化",
            speaker = "数字世界",
            content = summary,
            occurredAt = now,
        )
    }

    private fun parseObject(raw: String): JSONObject? = runCatching {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        JSONObject(if (start >= 0 && end > start) clean.substring(start, end + 1) else clean)
    }.getOrNull()

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun roll(seed: String, bound: Int): Int = if (bound <= 1) 0 else Math.floorMod(seed.hashCode(), bound)
}
