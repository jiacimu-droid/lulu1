package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Durable, private QQ-style friend application ledger.
 * Awareness of an application is NOT friendship, and a meeting is NOT consent.
 * This store holds the request and private system notices; MomentFriendAccess
 * remains the only authority for a confirmed link.
 */
internal data class CharacterFriendRequest(
    val id: String,
    val fromId: String,
    val toId: String,
    val greeting: String,
    val status: String,
    val createdAt: Instant,
    val resolvedAt: Instant? = null,
)

internal object CharacterFriendRequests {
    private const val PREFS = "lulu_character_friend_requests_v1"
    private var prefs: android.content.SharedPreferences? = null
    private var applicationContext: Context? = null

    @Synchronized fun initialize(context: Context) {
        applicationContext = context.applicationContext
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun all(): List<CharacterFriendRequest> = runCatching {
        val a = JSONArray(prefs?.getString("records", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { index ->
            val o = a.optJSONObject(index) ?: return@mapNotNull null
            CharacterFriendRequest(
                id = o.getString("id"), fromId = o.getString("fromId"),
                toId = o.getString("toId"), greeting = o.optString("greeting"),
                status = o.getString("status"),
                createdAt = Instant.parse(o.getString("createdAt")),
                resolvedAt = o.optString("resolvedAt").takeIf(String::isNotBlank)
                    ?.let(Instant::parse),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(all: List<CharacterFriendRequest>) {
        val a = JSONArray()
        all.forEach { r ->
            a.put(JSONObject().put("id", r.id).put("fromId", r.fromId)
                .put("toId", r.toId).put("greeting", r.greeting)
                .put("status", r.status).put("createdAt", r.createdAt.toString())
                .put("resolvedAt", r.resolvedAt?.toString().orEmpty()))
        }
        check(prefs?.edit()?.putString("records", a.toString())?.commit() == true) {
            "好友申请没有保存，不能报告成功"
        }
    }

    @Synchronized fun pendingFor(characterId: String): List<CharacterFriendRequest> =
        all().filter { it.toId == characterId && it.status == "pending" }

    @Synchronized fun historyFor(characterId: String): List<CharacterFriendRequest> =
        all().filter { it.fromId == characterId || it.toId == characterId }

    /** Resolve only notices concerning this role's actual stored request. */
    @Synchronized fun counterpartForNotice(characterId: String, evidenceId: String): String? =
        historyFor(characterId).firstOrNull { request ->
            listOf("requested", "accepted", "declined").any { kind ->
                evidenceId == "friend-request-${request.id}-$kind-$characterId"
            }
        }?.let { request -> if (request.fromId == characterId) request.toId else request.fromId }

    private fun notice(record: CharacterFriendRequest, kind: String, text: String, at: Instant) {
        val thread = "native-thread:" + listOf(record.fromId, record.toId).sorted().joinToString(":")
        listOf(record.fromId, record.toId).forEach { viewer ->
            SharedExperienceTimeline.record(
                eventId = "friend-request-${record.id}-$kind-$viewer",
                characterId = viewer, channel = "手机·好友消息", sessionId = thread,
                source = "phone:friend", speaker = "系统消息", content = text,
                occurredAt = at, triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation,
            )
        }
        // The sender already chose to act. The receiver needs a real chance
        // to inspect/respond, while acceptance/decline should notify the sender.
        // Native residents keep using their own cheaper daily decision route.
        val recipient = if (kind == "requested") record.toId else record.fromId
        if (!recipient.startsWith("resident:")) applicationContext?.let { app ->
            runCatching {
                ProactivePerceptionScheduler.scheduleSocialNoticeReview(
                    app, recipient, "friend-request-${record.id}-$kind-$recipient")
            }
        }
    }

    @Synchronized fun send(from: String, to: String, greeting: String = "",
        at: Instant = Instant.now()): CharacterFriendRequest {
        require(from.isNotBlank() && to.isNotBlank() && from != to &&
            to in MigratedDomainStores.characters.settings.value &&
            from in MigratedDomainStores.characters.settings.value &&
            ResidentSocialMailbox.canContact(from, to)) {
            "只允许向真正认识或同场相遇的数字人物申请好友"
        }
        require(!MomentFriendAccess.areFriends(from, to)) { "已经是好友" }
        require(all().none { it.status == "pending" &&
            setOf(it.fromId, it.toId) == setOf(from, to) }) { "双方已有待处理好友申请" }
        val request = CharacterFriendRequest(UUID.randomUUID().toString(), from, to,
            greeting.trim().take(180), "pending", at)
        save(all() + request)
        val fromName = MigratedDomainStores.characters.get(from).displayName
        val toName = MigratedDomainStores.characters.get(to).displayName
        notice(request, "requested", "$fromName 向 $toName 发送了好友申请" +
            request.greeting.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty(), at)
        return request
    }

    @Synchronized fun respond(receiver: String, requestId: String, accept: Boolean,
        at: Instant = Instant.now()): CharacterFriendRequest {
        val current = all()
        val original = current.firstOrNull {
            it.id == requestId && it.toId == receiver && it.status == "pending"
        } ?: error("没有发给当前角色的待处理申请")
        val resolved = original.copy(status = if (accept) "accepted" else "declined", resolvedAt = at)
        if (accept) check(MomentFriendAccess.setFriends(original.fromId, original.toId, true)) {
            "好友关系没有确认，申请未完成"
        }
        save(current.map { if (it.id == requestId) resolved else it })
        val nameA = MigratedDomainStores.characters.get(original.fromId).displayName
        val nameB = MigratedDomainStores.characters.get(original.toId).displayName
        notice(resolved, if (accept) "accepted" else "declined",
            if (accept) "$nameB 已同意 $nameA 的好友申请。你们已成为好友，现在可以开始聊天了。"
            else "$nameB 拒绝了 $nameA 的好友申请。", at)
        return resolved
    }
}
