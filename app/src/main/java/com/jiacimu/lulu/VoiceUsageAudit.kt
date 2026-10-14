package com.jiacimu.lulu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Local request attempts, not confirmed provider billing. Stores no speech text or secrets. */
internal object VoiceUsageAudit {
    private const val NAME = "lulu_cloud_voice_requests"
    private const val KEY = "attempts"

    /** Audio duration is approximate transmitted PCM; durationMs is session wall time.
     * Neither field is an ElevenLabs bill, which must be checked with the provider. */
    @Synchronized fun record(
        context: Context, provider: String, source: String, characters: Int, model: String,
        audioMs: Long = 0L, durationMs: Long = 0L,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val before = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val after = JSONArray()
        for (i in maxOf(0, before.length() - 79) until before.length()) after.put(before.opt(i))
        after.put(JSONObject().put("at", Instant.now().toString())
            .put("provider", provider.take(20)).put("source", source.take(40))
            .put("characters", characters.coerceAtLeast(0)).put("model", model.take(80))
            .put("audioMs", audioMs.coerceAtLeast(0)).put("durationMs", durationMs.coerceAtLeast(0)))
        prefs.edit().putString(KEY, after.toString()).apply()
    }

    fun recent(context: Context, max: Int = 20): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val saved = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return (saved.length() - 1 downTo maxOf(0, saved.length() - max.coerceIn(1, 80)))
            .mapNotNull(saved::optJSONObject).map { item ->
                val date = runCatching {
                    Instant.parse(item.optString("at")).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm:ss"))
                }.getOrDefault(item.optString("at"))
                val details = when {
                    item.optLong("durationMs") > 0L ->
                        "连接${item.optLong("durationMs") / 1000L}秒 · 上传PCM约${item.optLong("audioMs") / 1000L}秒"
                    item.optLong("audioMs") > 0L ->
                        "上传PCM约${item.optLong("audioMs") / 1000L}秒"
                    item.optInt("characters") > 0 -> "${item.optInt("characters")}字"
                    else -> "会话/控制请求"
                }
                "$date · ${item.optString("provider")} / ${item.optString("source")} · $details · ${item.optString("model")}"
            }
    }

    @Synchronized fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
