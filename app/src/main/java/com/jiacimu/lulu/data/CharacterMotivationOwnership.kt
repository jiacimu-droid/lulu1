package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * One standard for subjective motives, distinct from concern threads and executable promises.
 *
 * CharacterInnerLifeStore.motives owns every NEW persistent subjective goal.
 * CharacterLifeStore.intention is a compatibility executor for OLD goals only:
 * it retains pending work, reviews and receipts, but new model suggestions do not
 * create a second independent goal in that older single-goal store.
 */
internal object CharacterMotivationOwnership {
    fun canonicalInnerLife(legacyIntention: JSONObject?, innerLife: JSONObject?): JSONObject? {
        if (legacyIntention == null) return innerLife
        // A transition belongs exclusively to an existing old intention.
        if (legacyIntention.optString("disposition").isNotBlank() ||
            legacyIntention.optString("id").isNotBlank()) return innerLife
        val aim = legacyIntention.optString("aim").trim().take(180)
        val why = legacyIntention.optString("motive").trim().take(160)
        if (aim.isBlank() || why.isBlank() ||
            CharacterStaleGoalPolicy.isGenericOrientation(aim)) return innerLife
        val existing = innerLife?.optJSONArray("motives") ?: JSONArray()
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (item.optString("op") == "start" &&
                sameCharacterMotive(item.optString("aim"), aim)) return innerLife
        }
        // Put this first so legacy imports survive the three-change cap.
        val proposals = JSONArray().put(
            JSONObject().put("op", "start").put("aim", aim).put("why", why)
                .put("priority", 2).put("reason", "模型提出的本人长期意愿"),
        )
        for (i in 0 until minOf(existing.length(), 2)) proposals.put(existing.opt(i))
        return JSONObject(innerLife?.toString() ?: "{}").put("motives", proposals)
    }

    /** Only existing single-goal records can still be updated for compatibility. */
    fun updateLegacyIfPresent(characterId: String, proposal: JSONObject?, now: Instant = Instant.now()) {
        if (proposal == null) return
        if (CharacterLifeStore.state(characterId).optJSONObject("intention") != null)
            CharacterLifeStore.consider(characterId, proposal, now)
    }
}
