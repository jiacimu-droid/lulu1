package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
* Long-term evaluation, phase 1: a weekly local census, not a judgment.
*
* What it counts comes only from the stored inner life, so it needs no
* new model call and no program guess about meaning: motive statuses,
* open/closed concerns, held/resolved/discarded/evolved thoughts (the
* status only exists since the reservoir batch), self-corrections,
* action receipts and retained emotion history. Week-over-week deltas
* come from stored snapshots, capped at 12 weeks.
*
* What it deliberately does NOT measure: contradiction rate, drift and
* cross-channel consistency all need semantic comparison, which a
* program must not fake with keywords or thresholds. Proactive reply
* rate is computed from stored proactive-send receipts matched against
* later user statements in the same timeline session (see
* ProactiveSendAttribution); the footer names what is still missing.
*/
object CharacterEvaluationLedger {
private const val PREFS = "lulu_character_evaluation_v1"
private const val MAX_SNAPSHOTS = 12
private const val WEEK_DAYS = 7L
private var prefs: android.content.SharedPreferences? = null

@Synchronized
fun initialize(context: Context) {
if (prefs!= null) return
prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

@Synchronized
fun clear(characterId: String) {
prefs?.edit()?.remove(characterId)?.apply()
}

private fun snapshots(characterId: String): JSONArray =
runCatching {
JSONObject(prefs?.getString(characterId, null)?: "{}")
.optJSONArray("snapshots")?: JSONArray()
}.getOrDefault(JSONArray())

private fun save(characterId: String, snapshots: JSONArray) {
prefs?.edit()?.putString(characterId, JSONObject().put("snapshots", snapshots).toString())?.apply()
}

/** Pure census from the stored inner life. No timeline, no model call. */
internal fun census(characterId: String, now: Instant): JSONObject {
val root = CharacterInnerLifeStore.snapshot(characterId)
val motives = root.optJSONArray("motives")?: JSONArray()
var active = 0
var paused = 0
var waiting = 0
var achieved = 0
var released = 0
for (i in 0 until motives.length()) {
when (motives.optJSONObject(i)?.optString("status")) {
"active" -> active++
"paused" -> paused++
"waiting_feedback" -> waiting++
"achieved" -> achieved++
"released" -> released++
}
}
val thoughts = root.optJSONArray("thoughts")?: JSONArray()
var held = 0
var resolved = 0
var discarded = 0
var evolved = 0
var oldestHeldDays: Long? = null
for (i in 0 until thoughts.length()) {
val item = thoughts.optJSONObject(i)?: continue
when (item.optString("status").ifBlank { "held"}) {
"held" -> {
held++
runCatching { Instant.parse(item.optString("at"))}.getOrNull()?.let { at ->
if (!at.isAfter(now)) {
val days = Duration.between(at, now).toDays()
oldestHeldDays = maxOf(oldestHeldDays?: 0, days)
}
}
}
"resolved" -> resolved++
"discarded" -> discarded++
"evolved" -> evolved++
}
}
val open = root.optJSONArray("openConcerns")?: JSONArray()
val closed = root.optJSONArray("closedConcerns")?: JSONArray()
val corrections = root.optJSONArray("corrections")?: JSONArray()
val decisions = root.optJSONArray("decisions")?: JSONArray()
var succeeded = 0
for (i in 0 until decisions.length()) {
if (decisions.optJSONObject(i)?.optBoolean("succeeded") == true) succeeded++
}
val emotionHistory = root.optJSONArray("emotionHistory")?: JSONArray()
val proactiveSummary = ProactiveSendAttribution.summarize(characterId)
return JSONObject()
.put("activeMotives", active).put("pausedMotives", paused)
.put("waitingMotives", waiting).put("achievedMotives", achieved)
.put("releasedMotives", released)
.put("heldThoughts", held).put("resolvedThoughts", resolved)
.put("discardedThoughts", discarded).put("evolvedThoughts", evolved)
.put("openConcerns", open.length()).put("closedConcerns", closed.length())
.put("corrections", corrections.length())
.put("decisions", decisions.length()).put("succeededDecisions", succeeded)
.put("emotionHistory", emotionHistory.length())
.put("proactiveSends", proactiveSummary.receipts.size)
.put("proactiveAttributable", proactiveSummary.attributable)
.put("proactiveReplied", proactiveSummary.replied)
.put("proactiveUnanswered", proactiveSummary.unanswered)
.put("proactiveUnattributed", proactiveSummary.unattributed)
.also { census ->
oldestHeldDays?.let { census.put("oldestHeldThoughtDays", it)}
}
}

/**
* Returns a report only when a new week rolls over (no snapshot yet,
* or the last snapshot is at least 7 days old). Otherwise returns
* null and stores nothing, so callers can invoke it on every
* perception turn without spamming. Returns null when prefs are not
* initialized.
*/
@Synchronized
fun weeklyReport(characterId: String, now: Instant = Instant.now()): String? {
if (prefs == null || characterId.isBlank()) return null
val history = snapshots(characterId)
val last = history.optJSONObject(history.length() - 1)
val lastAt = last?.optString("at")?.let { runCatching { Instant.parse(it)}.getOrNull()}
if (lastAt!= null && Duration.between(lastAt, now).toDays() < WEEK_DAYS) return null
val current = census(characterId, now).put("at", now.toString())
val next = JSONArray()
for (i in maxOf(0, history.length() - (MAX_SNAPSHOTS - 1)) until history.length()) {
next.put(history.opt(i))
}
next.put(current)
save(characterId, next)
return render(current, last)
}

private fun delta(current: Int, previous: JSONObject?, key: String): String {
if (previous == null) return "$current"
val old = previous.optInt(key, -1)
if (old < 0) return "$current"
val diff = current - old
return if (diff == 0) "$current（与上周持平）" else "$current（上周$old，${if (diff > 0) "+$diff" else "$diff"}）"
}

internal fun render(current: JSONObject, previous: JSONObject?): String = buildString {
appendLine("")
appendLine("在意事项：进行中${delta(current.optInt("activeMotives"), previous, "activeMotives")}件，" +
"暂缓${current.optInt("pausedMotives")}件，等反馈${current.optInt("waitingMotives")}件，" +
"已达成${current.optInt("achievedMotives")}件，已放下${current.optInt("releasedMotives")}件。")
appendLine("挂心事：还开着${delta(current.optInt("openConcerns"), previous, "openConcerns")}件，" +
"已结案${current.optInt("closedConcerns")}件。")
val age = if (current.has("oldestHeldThoughtDays")) "，最久约${current.optLong("oldestHeldThoughtDays")}天前" else ""
appendLine("琢磨事：还留着${delta(current.optInt("heldThoughts"), previous, "heldThoughts")}条$age；" +
"已解决${current.optInt("resolvedThoughts")}条，已放下${current.optInt("discardedThoughts")}条，" +
"已演变${current.optInt("evolvedThoughts")}条。")
appendLine("自我修正${current.optInt("corrections")}次；行动回执${current.optInt("decisions")}次，" +
"其中成功${current.optInt("succeededDecisions")}次；情绪历程保留${current.optInt("emotionHistory")}段。")
val proactiveAttributable = current.optInt("proactiveAttributable")
val proactiveReplied = current.optInt("proactiveReplied")
val proactiveRate = if (proactiveAttributable > 0) "（回复率约${proactiveReplied * 100 / proactiveAttributable}%）" else "（暂无可归属回执，算不出回复率）"
appendLine("主动消息：最近保留的主动发出${delta(current.optInt("proactiveSends"), previous, "proactiveSends")}条，" +
"其中可归属${delta(proactiveAttributable, previous, "proactiveAttributable")}条，用户随后回应${delta(proactiveReplied, previous, "proactiveReplied")}条$proactiveRate，" +
"未回应${current.optInt("proactiveUnanswered")}条；归属不明${current.optInt("proactiveUnattributed")}条不计入。")
append("暂未衡量：矛盾率、漂移、跨渠道一致性需要语义判断，程序不猜；" +
"主动回复率只按仍保留的回执算（回执与每轮感知决定共用最近 60 条，旧的会被顶掉），" +
"回应指同一对话里发出之后用户有说话，不读内容、不猜是不是针对哪一句；归属不明的回执不计入。")
}.trim()
}
