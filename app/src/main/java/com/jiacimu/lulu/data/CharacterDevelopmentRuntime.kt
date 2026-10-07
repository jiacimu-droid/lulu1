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
        val persona = CharacterRuntime.personaConstraintSnapshot(characterId)
        val sameDefinition = p.getString("persona:$characterId", "") == persona
        if (sameDefinition && now - p.getLong("attempt:$characterId", 0) < 3_600_000) return
        val timeline = SharedExperienceTimeline.all(characterId)
        // Chat volume must not push actual reading/game/other exposures out of reflection.
        val events = (timeline.filter { it.channel.startsWith("独自阅读") }.takeLast(16) +
            timeline.filter { it.isDevelopmentExposure() && it.evidenceKind != EventEvidenceKind.UserStatement &&
                !it.channel.startsWith("独自阅读") }.takeLast(24) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.UserStatement }.takeLast(20) +
            timeline.filter { it.evidenceKind == EventEvidenceKind.CharacterStatement }.takeLast(20))
            .distinctBy { it.id }.sortedBy { it.occurredAt }
        if (events.count { it.isDevelopmentExposure() } < 3) return
        val fingerprint = persona + "\n" + events.joinToString("|") { "${it.id}:${it.revision}" }
        if (fingerprint == p.getString("batch:$characterId", "")) return
        p.edit().putLong("attempt:$characterId", now).putString("persona:$characterId", persona).commit()
        val reply = LuluAiServices.gateway.generate(characterId,
            facts = "锁定人设，不可改写：$persona\n当前已有成长：${CharacterRuntime.developmentContext(characterId)}\n" +
                events.joinToString("\n") { "eventId=${it.id} revision=${it.revision} ${it.evidenceContent.take(600)}" },
            instruction = """
                从角色真实经历中反思可改变的兴趣、偏好、习惯和判断，不改写用户明确限定的人设字段。
                返回 JSON 数组，最多3项；没有可信变化就返回[]。每项含slot(稳定主题键)、kind(Interest/Preference/Habit/Judgment/RelationshipRoutine/VerifiedMethod)、content、evidenceIds、counterIds。
                Interest 是角色自己的兴趣，不是用户的爱好：从真实阅读、游戏、探索、见面等实际接触，加上角色确实表达的感受，判断是否开始喜欢、加深、改变方向或逐渐失去兴趣。至少引用3次不同经历；不要因为角色说了一句喜欢就凭空创建兴趣，不假装体验过正文里的虚构情节。
                同一兴趣的变化沿用同一个slot。新的反复体验足以说明方向改变时，可以更新或淡化旧兴趣，counterIds保留旧依据；最新反例晚于支持证据时不更新。兴趣内容写清当前倾向与变化原因，不靠随机数或固定轮换。
                长期习惯或方法也至少3次不同经历；其他类型存在反例时暂缓。所有ID必须来自输入；主观表达只证明感受，不证明行动成功。不强求每轮成长，经历不足时保持原状。
            """.trimIndent(),
            source = "增量成长反思", title = "角色成长", maxTokens = 1400,
            contextMode = CompanionContextMode.Isolated).getOrNull() ?: run {
                p.edit().putLong("attempt:$characterId", now - 3_300_000).commit()
                return
            }
        runCatching {
            val array = decodeMemoryResponseArray(reply.text)
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
        }.onFailure { p.edit().putLong("attempt:$characterId", now - 3_300_000).commit() }
    }
}
