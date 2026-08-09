package com.jiacimu.lulu.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Removes retired reward/features from persisted study data without erasing study progress. */
object StudyRemovedFeatureMigration {
    private const val PREFS_NAME = "lulu_study_complete"
    private const val STAR_WISH_PREFS_NAME = "lulu_star_wish"
    private const val STAR_WISH_STATE_KEY = "state_v1"

    fun migrate(context: Context) {
        migrateStudyState(context)
        migrateStarWishState(context)
    }

    private fun migrateStudyState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        listOf("state", "state_backup").forEach { key ->
            val raw = prefs.getString(key, null) ?: return@forEach
            val clean = runCatching { sanitizeStudy(JSONObject(raw)).toString() }.getOrNull() ?: return@forEach
            editor.putString(key, clean)
        }
        editor.apply()
    }

    private fun sanitizeStudy(root: JSONObject): JSONObject {
        root.put("schemaVersion", 7)
        root.remove("safeDrawUsedDate")
        root.optJSONObject("inventory")?.apply {
            remove("safePurpleTickets")
            remove("mysteryBoxes")
            remove("universalBlueFragments")
            remove("purpleFragments")
            remove("universalNormalFragments")
            remove("universalRareFragments")
            remove("universalEpicFragments")
            // 视频解锁卡和视频收藏已从考研奖励系统彻底退役。
            remove("videoCards")
            remove("unlockedVideos")
        }
        val sourceItems = root.optJSONArray("shopItems") ?: JSONArray()
        val cleanItems = JSONArray()
        for (index in 0 until sourceItems.length()) {
            val item = sourceItems.optJSONObject(index) ?: continue
            val reward = item.optString("reward")
            val title = item.optString("title")
            val obsolete = reward in setOf("SafePurpleTicket", "MysteryBox", "UniversalBlueFragment", "VideoCard") ||
                listOf("安全抽", "盲盒", "万能", "视频解锁").any(title::contains)
            if (!obsolete) cleanItems.put(item)
        }
        root.put("shopItems", cleanItems)
        root.put("shopDate", "")
        root.put("manualShopRefreshDate", "")
        return root
    }

    private fun migrateStarWishState(context: Context) {
        val prefs = context.getSharedPreferences(STAR_WISH_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(STAR_WISH_STATE_KEY, null) ?: return
        val clean = runCatching {
            JSONObject(raw).apply { remove("videos") }.toString()
        }.getOrNull() ?: return
        prefs.edit().putString(STAR_WISH_STATE_KEY, clean).apply()
    }
}
