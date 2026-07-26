package com.jiacimu.lulu.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Removes fields introduced by the discarded legacy-economy migration without erasing study progress. */
object StudyRemovedFeatureMigration {
    private const val PREFS_NAME = "lulu_study_complete"

    fun migrate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        listOf("state", "state_backup").forEach { key ->
            val raw = prefs.getString(key, null) ?: return@forEach
            val clean = runCatching { sanitize(JSONObject(raw)).toString() }.getOrNull() ?: return@forEach
            editor.putString(key, clean)
        }
        editor.apply()
    }

    private fun sanitize(root: JSONObject): JSONObject {
        root.put("schemaVersion", 4)
        root.remove("safeDrawUsedDate")
        root.remove("safeDrawUsedDate")
        root.optJSONObject("inventory")?.apply {
            remove("safePurpleTickets")
            remove("mysteryBoxes")
            remove("universalBlueFragments")
            remove("purpleFragments")
            remove("universalNormalFragments")
            remove("universalRareFragments")
            remove("universalEpicFragments")
        }
        val sourceItems = root.optJSONArray("shopItems") ?: JSONArray()
        val cleanItems = JSONArray()
        for (index in 0 until sourceItems.length()) {
            val item = sourceItems.optJSONObject(index) ?: continue
            val reward = item.optString("reward")
            val title = item.optString("title")
            val obsolete = reward in setOf("SafePurpleTicket", "MysteryBox", "UniversalBlueFragment") ||
                listOf("安全抽", "盲盒", "万能").any(title::contains)
            if (!obsolete) cleanItems.put(item)
        }
        root.put("shopItems", cleanItems)
        root.put("shopDate", "")
        root.put("manualShopRefreshDate", "")
        return root
    }
}
