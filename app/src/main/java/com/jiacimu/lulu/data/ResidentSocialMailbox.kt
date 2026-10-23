package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** A durable character↔character inbox. Never alias these messages to the
 * real owner's QQ chat, and never let one character read another's inbox.
 * The original sender and provenance are recorded for later phone inspection.
 */
internal data class ResidentDirectMessage(
    val id: String, val fromId: String, val toId: String, val content: String,
    val createdAt: Instant, val author: String,
)

internal object ResidentSocialMailbox {
    private const val PREFS = "lulu_native_social_mail_v1"
    private const val LIMIT = 1200
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var messages: List<ResidentDirectMessage> = emptyList()
    private fun cursorKey(role: String) = "considered-incoming:$role"

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        messages = runCatching {
            val all = JSONArray(prefs?.getString("messages", "[]"))
            (0 until all.length()).mapNotNull { i ->
                val o = all.optJSONObject(i) ?: return@mapNotNull null
                val from = o.optString("fromId")
                val to = o.optString("toId")
                if (from.isBlank() || to.isBlank() || from == to) return@mapNotNull null
                ResidentDirectMessage(o.optString("id"), from, to,
                    o.optString("content"), Instant.parse(o.optString("createdAt")),
                    o.optString("author", "character"))
            }
        }.getOrDefault(emptyList())
    }

    fun contactsOf(characterId: String): Set<String> = synchronized(this) {
        messages.asSequence().filter { it.fromId == characterId || it.toId == characterId }
            .map { if (it.fromId == characterId) it.toId else it.fromId }.toSet()
    }

    /** A character knows only their own confirmed messages, not others' DMs. */
    fun inbox(characterId: String, max: Int = 12): List<ResidentDirectMessage> = synchronized(this) {
        messages.filter { it.fromId == characterId || it.toId == characterId }.takeLast(max.coerceIn(1, 60))
    }

    /**
     * An incoming letter is a new event, not a completed conversation.
     * The cursor is advanced only after the recipient's OWN successful decision;
     * an interrupted model call leaves the same letter available on the next wake.
     */
    internal fun unconsidered(incoming: List<ResidentDirectMessage>,
        throughId: String, max: Int = 8): List<ResidentDirectMessage> {
        val index = incoming.indexOfLast { it.id == throughId }
        return incoming.drop(if (index >= 0) index + 1 else 0).takeLast(max.coerceAtLeast(1))
    }

    @Synchronized fun pendingIncoming(role: String, max: Int = 8): List<ResidentDirectMessage> {
        val incoming = messages.filter { it.toId == role }
        return unconsidered(incoming, prefs?.getString(cursorKey(role), "").orEmpty(), max)
    }

    @Synchronized fun acknowledgeIncoming(role: String, messageId: String): Boolean {
        if (role.isBlank() || messages.none { it.id == messageId && it.toId == role }) return false
        return prefs?.edit()?.putString(cursorKey(role), messageId)?.commit() == true
    }

    /** Physical co-presence, a real meeting, or an existing two-way thread
     * permits first contact. No arbitrary messaging of invisible strangers. */
    fun canContact(from: String, to: String): Boolean {
        if (from.isBlank() || from == to ||
            MigratedDomainStores.characters.settings.value[to] == null) return false
        if (to in contactsOf(from)) return true
        if (CharacterSocialRelationship.knowsEachOther(from, to)) return true
        val a = DigitalWorldStore.locationOf(from)
        return a.isNotBlank() && a == DigitalWorldStore.locationOf(to) &&
            !a.startsWith("home:") && !a.startsWith("resident-home:")
    }

    fun visibleContacts(characterId: String): List<String> =
        MigratedDomainStores.characters.settings.value.keys.filter { it != characterId &&
            DigitalLifeProfileStore.isEnabled(it) && canContact(characterId, it) }.take(16)

    @Synchronized
    fun send(from: String, to: String, content: String,
        author: String = "character", at: Instant = Instant.now()): ResidentDirectMessage {
        require(author in setOf("character", "owner-on-behalf")) { "发送来源不合法" }
        val text = content.trim().take(650)
        require(text.isNotBlank() && canContact(from, to)) { "双方尚未真实认识或同处现场，不能凭空通信" }
        val item = ResidentDirectMessage(UUID.randomUUID().toString(), from, to, text, at, author)
        val updated = (messages + item).takeLast(LIMIT)
        val array = JSONArray()
        updated.forEach { msg ->
            array.put(JSONObject().put("id", msg.id).put("fromId", msg.fromId)
                .put("toId", msg.toId).put("content", msg.content)
                .put("createdAt", msg.createdAt.toString()).put("author", msg.author))
        }
        check(prefs?.edit()?.putString("messages", array.toString())?.commit() == true) {
            "居民私信尚未持久化，不能报告发送成功"
        }
        messages = updated
        val sender = MigratedDomainStores.characters.get(from).displayName
        val recipient = MigratedDomainStores.characters.get(to).displayName
        for (viewer in setOf(from, to)) {
            SharedExperienceTimeline.record(
                eventId = "native-mail-${item.id}-viewer-$viewer",
                characterId = viewer, channel = "数字人物私信",
                sessionId = "native-thread:${listOf(from, to).sorted().joinToString(":")}",
                source = if (author == "character") "character-message" else "owner-on-behalf",
                speaker = sender,
                content = "$sender 向 $recipient 发了私信：$text" +
                    if (author == "owner-on-behalf") "（实际由用户代发；对方暂未必知晓）" else "",
                occurredAt = at, triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation,
            )
        }
        // Native citizens have their own low-frequency daily decision. For a
        // protagonist, a genuine new letter is a meaningful wake opportunity,
        // not a timer that starts another empty paid reflection.
        if (author == "character" && !to.startsWith("resident:") &&
            DigitalLifeProfileStore.isEnabled(to)) {
            appContext?.let { context ->
                runCatching {
                    ProactivePerceptionScheduler.scheduleResidentMail(
                        context, to, "native-mail-${item.id}-viewer-$to")
                }.onFailure { android.util.Log.w("ResidentMail", "Mail wake scheduling failed", it) }
            }
        }
        return item
    }
}
