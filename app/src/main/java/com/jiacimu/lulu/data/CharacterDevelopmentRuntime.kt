package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.CompanionContextMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant

/** Incremental reflection is detached from real-time response and limited to one batch per hour. */
object CharacterDevelopmentRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences("lulu_development_reflection", android.content.Context.MODE_PRIVATE)
    }

    fun request(characterId: String) { scope.launch { mutex.withLock { reflect(characterId) } } }

    private suspend fun reflect(characterId: String) {
        val p = prefs ?: return
        val now = Instant.now().toEpochMilli()
        if (now - p.getLong("attempt:$characterId", 0) < 3_600_000) return
        val events = SharedExperienceTimeline.recentEvents(characterId, 80).filter {
            it.evidenceKind in setOf(EventEvidenceKind.UserStatement, EventEvidenceKind.Observation, EventEvidenceKind.ToolResult)
        }
        if (events.size < 3) return
        val fingerprint = events.joinToString("|") { "${it.id}:${it.revision}" }
        if (fingerprint == p.getString("batch:$characterId", "")) return
        p.edit().putLong("attempt:$characterId", now).commit()
        val persona = CharacterRuntime.personaConstraintSnapshot(characterId)
        val reply = LuluAiServices.gateway.generate(characterId,
            facts = "锁定人设，不可改写：$persona\n当前已有成长：${CharacterRuntime.developmentContext(characterId)}\n" +
                events.joinToString("\n") { "eventId=${it.id} revision=${it.revision} ${it.evidenceContent.take(600)}" },
            instruction = "只提出有依据且不违背锁定人设的增量偏好/习惯/判断，不生成日记或虚构经历。返回 JSON 数组，最多3项；无证据返回[]。每项含slot(稳定主题键)、kind(Preference/Habit/Judgment/RelationshipRoutine/VerifiedMethod)、content、evidenceIds(原始ID数组)、counterIds(反例ID数组)。相同主题沿用已有slot；长期习惯或方法至少3次不同经历；有反例保持不变。",
            source = "增量成长反思", title = "角色成长", maxTokens = 700,
            contextMode = CompanionContextMode.Isolated).getOrNull() ?: return
        runCatching {
            val array = JSONArray(reply.text.removePrefix("```json").removeSuffix("```").trim())
            for (i in 0 until minOf(array.length(), 3)) {
                val item = array.getJSONObject(i)
                fun ids(key: String): List<String> {
                    val a = item.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                CharacterDevelopmentStore.applyProposal(characterId, item.getString("slot"),
                    DevelopmentKind.valueOf(item.getString("kind")), item.getString("content"),
                    ids("evidenceIds"), ids("counterIds"), persona)
            }
            p.edit().putString("batch:$characterId", fingerprint).commit()
        }
    }
}
