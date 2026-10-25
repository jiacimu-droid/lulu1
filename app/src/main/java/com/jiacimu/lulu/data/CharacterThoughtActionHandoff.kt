package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/** Decode explicitly chosen thought actions into the existing executor, never execute prose. */
internal object CharacterThoughtActionHandoff {
    const val runtimeGuidance = "落实填nextAction={tool,args}交原steps；稍后用plan_personal_activity；文字不执行。"
    const val guidance = "possibleNextStep/nextStep只是候选文字，不会执行。重新考虑旧下一步时，区分尚在考虑、等待具体条件、现在落实或未来排期；已决定的下一步不能又只写成候选文字。若已决定落实，在同轮nextAction填写{tool:真实工具,args:{}}（也可写在挂心、thoughts、motives或curiosity项内）；现在做选实际工具，稍后做选plan_personal_activity并填title、intent、dueAt、activityAction、activityArgs。程序将其交给原steps执行器，不能只写下一步便当作已安排；尚在考虑或等待则不填，不自动把所有念头变待办。"

    fun actions(decision: JSONObject): JSONArray? {
        // Both names are accepted by the decision schema. When the model emits
        // both, neither is an alternative to the other: dropping sideActions
        // silently loses genuinely selected actions and their future schedules.
        val result = JSONArray()
        val primary = decision.optJSONArray("steps")
        for (index in 0 until (primary?.length() ?: 0)) {
            val raw = primary?.optJSONObject(index)
            result.put(if (raw == null) primary?.opt(index) else
                AutonomousActionWireProtocol.normalizeStep(raw))
        }
        val secondary = decision.optJSONArray("sideActions")
        for (index in 0 until (secondary?.length() ?: 0)) {
            val step = secondary?.optJSONObject(index)?.let(AutonomousActionWireProtocol::normalizeStep) ?: continue
            val duplicate = (0 until result.length()).mapNotNull(result::optJSONObject)
                .firstOrNull { existing ->
                    // Empty tool names are not real action identities. Two
                    // malformed selected steps must each retain an independent
                    // failure receipt rather than disappearing as duplicates.
                    step.optString("tool").isNotBlank() &&
                        existing.optString("tool") == step.optString("tool") &&
                        canonical(existing.optJSONObject("args") ?: JSONObject()) ==
                            canonical(step.optJSONObject("args") ?: JSONObject()) &&
                        existing.optBoolean("requiresPreviousSuccess") ==
                            step.optBoolean("requiresPreviousSuccess")
                }
            if (duplicate == null) result.put(JSONObject(step.toString()))
            else listOf("motiveId", "concernId", "experienceId", "reason", "curiosity").forEach { key ->
                if (!duplicate.has(key) && step.has(key)) duplicate.put(key, step.get(key))
            }
        }
        fun add(owner: JSONObject?, concern: Boolean = false, motive: Boolean = false) {
            val chosen = owner?.optJSONObject("nextAction") ?: return
            val step = AutonomousActionWireProtocol.normalizeStep(chosen)
            if (concern && step.optString("concernId").isBlank())
                owner.optString("threadId").takeIf(String::isNotBlank)?.let { step.put("concernId", it) }
            if (motive && step.optString("motiveId").isBlank())
                owner.optString("id").takeIf(String::isNotBlank)?.let { step.put("motiveId", it) }
            if (step.optString("tool") == "plan_personal_activity") {
                val args = step.optJSONObject("args") ?: JSONObject().also { step.put("args", it) }
                step.optString("motiveId").takeIf(String::isNotBlank)?.let {
                    if (args.optString("motiveId").isBlank()) args.put("motiveId", it)
                }
                step.optString("concernId").takeIf(String::isNotBlank)?.let {
                    if (args.optString("concernId").isBlank()) args.put("concernId", it)
                }
            }
            fun same(other: JSONObject): Boolean = step.optString("tool").isNotBlank() &&
                other.optString("tool") == step.optString("tool") &&
                canonical(other.optJSONObject("args") ?: JSONObject()) ==
                    canonical(step.optJSONObject("args") ?: JSONObject())
            // A direct tool or explicit steps already owns this effect. Do not execute it twice.
            if (decision.optString("action") == "tool" &&
                same(AutonomousActionWireProtocol.normalizeStep(decision))) return
            val existing = (0 until result.length()).mapNotNull(result::optJSONObject).firstOrNull(::same)
            if (existing != null) {
                listOf("motiveId", "concernId", "experienceId", "reason", "curiosity").forEach { key ->
                    if (!existing.has(key) && step.has(key)) existing.put(key, step.get(key))
                }
            } else result.put(step)
        }
        add(decision)
        CharacterOpenConcernRuntime.proposals(decision.optJSONObject("appraisal"))
            .filter { it.optString("status", "open") !in setOf("settled", "dismissed") }
            .forEach { add(it, concern = true) }
        val inner = decision.optJSONObject("innerLife")
        listOf("thoughts", "motives").forEach { key ->
            val items = inner?.optJSONArray(key)
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items?.optJSONObject(i) ?: continue
                if (key == "motives" && item.optString("op") in setOf("pause", "release", "achieve")) continue
                add(item, motive = key == "motives")
            }
        }
        add(decision.optJSONObject("curiosity"))
        val questions = decision.optJSONArray("curiosities")
        for (i in 0 until (questions?.length() ?: 0)) add(questions?.optJSONObject(i))
        return result.takeIf { it.length() > 0 }
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
