package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

/** Decode explicitly chosen thought actions into the existing executor, never execute prose. */
internal object CharacterThoughtActionHandoff {
    const val runtimeGuidance = "落实填nextAction={tool,args}交原steps；稍后用plan_personal_activity；文字不执行。"
    const val guidance = "possibleNextStep/nextStep只是候选文字，不会执行。重新考虑旧下一步时，区分尚在考虑、等待具体条件、现在落实或未来排期；已决定的下一步不能又只写成候选文字。若已决定落实，在同轮nextAction填写{tool:真实工具,args:{}}（也可写在挂心、thoughts、motives或curiosity项内）；现在做选实际工具，稍后做选plan_personal_activity并填title、intent、dueAt、activityAction、activityArgs。程序将其交给原steps执行器，不能只写下一步便当作已安排；尚在考虑或等待则不填，不自动把所有念头变待办。具体如：现在真想发给用户一张表情包、且本轮已列出可用真实stickerId，可以选action=sticker并填写stickerId，或在当前回复的steps/nextAction明确选择tool=send_private_sticker,args={stickerId:准确ID}；只有已送达回执才算发过，没有图库或还在犹豫可继续想但不要声称已发。真正决定修改用户的私人联系人备注时，选action=user_remark与nickname，或明确选择tool=set_user_remark,args={nickname:新备注}；备注成功保存才可以说已改好。不要从心声里的动词自动派发工具，也不要为了证明主动性强制发送表情或更名。"

    /**
     * Validation of a PARTIALLY executable model decision, not a new decision maker.
     * A bad primary tool must be reported as a ToolRouter rejection, but may
     * coexist with independently authored registered steps. Dependent or
     * unknown side steps cannot rescue an otherwise invalid model output.
     */
    fun hasIndependentRegisteredStep(steps: JSONArray?): Boolean =
        (0 until (steps?.length() ?: 0)).any { index ->
            steps?.optJSONObject(index)?.let(AutonomousActionWireProtocol::normalizeStep)?.let { step ->
                !step.optBoolean("requiresPreviousSuccess", false) &&
                    CapabilityRegistry.find(step.optString("tool").trim()) != null
            } == true
        }

    fun actions(decision: JSONObject): JSONArray? {
        // Both names are accepted by the decision schema. When the model emits
        // both, neither is an alternative to the other: dropping sideActions
        // silently loses genuinely selected actions and their future schedules.
        val result = JSONArray()
        val primary = decision.optJSONArray("steps")
        // The top-level primary tool is executed separately. Explicit steps
        // may mirror it too, not just nextAction under a motive or concern.
        // Suppress only a fully credited mirror; conflicting owners must keep
        // their own execution/receipt rather than silently disappear.
        fun coveredByPrimary(step: JSONObject): Boolean {
            if (!AutonomousActionWireProtocol.isPrimaryToolChoice(decision)) return false
            val chosen = AutonomousActionWireProtocol.normalizeStep(decision)
            return sameAction(chosen, step) && causalLinks.all { key ->
                !step.has(key) || (chosen.has(key) &&
                    canonical(chosen.opt(key)) == canonical(step.opt(key)))
            }
        }
        for (index in 0 until (primary?.length() ?: 0)) {
            val raw = primary?.optJSONObject(index)
            if (raw == null) {
                result.put(primary?.opt(index) ?: JSONObject.NULL)
            } else {
                val step = AutonomousActionWireProtocol.normalizeStep(raw)
                if (!coveredByPrimary(step)) result.put(step)
            }
        }
        val secondary = decision.optJSONArray("sideActions")
        for (index in 0 until (secondary?.length() ?: 0)) {
            val raw = secondary?.optJSONObject(index)
            if (raw == null) {
                // Preserve the failed slot. Removing it would let the next
                // dependent action inherit an earlier, unrelated success.
                result.put(secondary?.opt(index) ?: JSONObject.NULL)
                continue
            }
            val step = AutonomousActionWireProtocol.normalizeStep(raw)
            if (coveredByPrimary(step)) continue
            // Ordered steps are a sequence, not a set. An intervening action
            // may change state or fail; collapsing a later identical tool into
            // an earlier slot would erase the model's chosen second attempt and
            // change which result a dependent step observes. Only adjacent
            // equivalent requests can share one execution/receipt.
            val previous = result.optJSONObject(result.length() - 1)
            if (previous != null && sameAction(previous, step)) mergeLinks(previous, step)
            else result.put(step)
        }
        fun add(owner: JSONObject?, concern: Boolean = false, motive: Boolean = false, inquiry: Boolean = false) {
            val chosen = owner?.optJSONObject("nextAction") ?: return
            val step = AutonomousActionWireProtocol.normalizeStep(chosen)
            if (inquiry && !step.has("curiosity")) {
                // This action is explicitly nested in THIS question. Carry
                // its authored identity to ToolRouter's existing result path.
                // Do not include nextAction recursively or infer new content.
                val question = JSONObject(owner.toString()).apply { remove("nextAction") }
                step.put("curiosity", question)
            }
            if (concern && step.optString("concernId").isBlank())
                owner.optString("threadId").takeIf(String::isNotBlank)?.let { step.put("concernId", it) }
            if (motive && step.optString("motiveId").isBlank() && step.optString("motiveRef").isBlank()) {
                val id = owner.optString("id")
                if (id.isNotBlank()) step.put("motiveId", id)
                else if (owner.optString("op") == "start")
                    owner.optString("ref").takeIf(String::isNotBlank)?.let { step.put("motiveRef", it) }
            }
            if (step.optString("tool") == "plan_personal_activity") {
                val args = step.optJSONObject("args") ?: JSONObject().also { step.put("args", it) }
                step.optString("motiveId").takeIf(String::isNotBlank)?.let {
                    if (args.optString("motiveId").isBlank()) args.put("motiveId", it)
                }
                step.optString("concernId").takeIf(String::isNotBlank)?.let {
                    if (args.optString("concernId").isBlank()) args.put("concernId", it)
                }
            }
            // A direct tool can suppress the mirrored nested action ONLY
            // when that exact primary receipt already carries the nested
            // causal owner. With two distinct open questions/motives, the
            // primary has no unique owner: never discard BOTH side actions
            // just because their executable tool and args look the same.
            if (AutonomousActionWireProtocol.isPrimaryToolChoice(decision)) {
                val primaryStep = AutonomousActionWireProtocol.normalizeStep(decision)
                if (sameAction(primaryStep, step) && causalLinks.all { key ->
                        !step.has(key) || (primaryStep.has(key) &&
                            canonical(primaryStep.opt(key)) == canonical(step.opt(key)))
                    }) return
            }
            // Nested choices also follow the authored execution order. A
            // repeated request after another action is a new attempt, not a
            // duplicate receipt of an earlier request.
            val previous = result.optJSONObject(result.length() - 1)
            if (previous != null && sameAction(previous, step)) mergeLinks(previous, step)
            else result.put(step)
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
        add(decision.optJSONObject("curiosity"), inquiry = true)
        val questions = decision.optJSONArray("curiosities")
        for (i in 0 until (questions?.length() ?: 0)) add(questions?.optJSONObject(i), inquiry = true)
        return result.takeIf { it.length() > 0 }
    }

    /**
     * A top-level tool is executed by the primary route, not OrderedCharacterActions.
     * When the same effect is also authored as a nested nextAction, retain its
     * explicit owner on that PRIMARY receipt instead of enqueuing it twice.
     * Conflicting or ambiguous owners remain separate, never guessed.
     */
    fun withPrimaryActionOwnership(decision: JSONObject): JSONObject {
        if (!AutonomousActionWireProtocol.isPrimaryToolChoice(decision)) return decision
        val primary = AutonomousActionWireProtocol.normalizeStep(decision)
        if (primary.optString("tool").isBlank()) return decision
        val nested = JSONArray()
        fun collect(
            owner: JSONObject?, concern: Boolean = false, motive: Boolean = false,
            inquiry: Boolean = false,
        ) {
            val raw = owner?.optJSONObject("nextAction") ?: return
            val step = AutonomousActionWireProtocol.normalizeStep(raw)
            if (inquiry && !step.has("curiosity")) {
                // The same explicit question link that actions() would attach
                // must be visible to the PRIMARY tool's actual receipt too.
                val question = JSONObject(owner.toString()).apply { remove("nextAction") }
                step.put("curiosity", question)
            }
            if (concern && step.optString("concernId").isBlank())
                owner.optString("threadId").takeIf(String::isNotBlank)?.let { step.put("concernId", it) }
            if (motive && step.optString("motiveId").isBlank() && step.optString("motiveRef").isBlank()) {
                owner.optString("id").takeIf(String::isNotBlank)?.let { step.put("motiveId", it) }
                    ?: owner.optString("ref").takeIf { owner.optString("op") == "start" && it.isNotBlank() }
                        ?.let { step.put("motiveRef", it) }
            }
            nested.put(step)
        }
        collect(decision)
        // Explicit steps/sideActions are another spelling of the same
        // primary effect. Carry their authored owner onto its real receipt
        // before the side-action decoder decides whether to suppress a mirror.
        for (field in listOf("steps", "sideActions")) {
            val declared = decision.optJSONArray(field)
            for (index in 0 until (declared?.length() ?: 0)) {
                declared?.optJSONObject(index)?.let { nested.put(AutonomousActionWireProtocol.normalizeStep(it)) }
            }
        }
        CharacterOpenConcernRuntime.proposals(decision.optJSONObject("appraisal"))
            .filter { it.optString("status", "open") !in setOf("settled", "dismissed") }
            .forEach { collect(it, concern = true) }
        val inner = decision.optJSONObject("innerLife")
        for (key in listOf("thoughts", "motives")) {
            val items = inner?.optJSONArray(key)
            for (index in 0 until (items?.length() ?: 0)) {
                val owner = items?.optJSONObject(index) ?: continue
                if (key == "motives" && owner.optString("op") in setOf("pause", "release", "achieve")) continue
                collect(owner, motive = key == "motives")
            }
        }
        collect(decision.optJSONObject("curiosity"), inquiry = true)
        val questions = decision.optJSONArray("curiosities")
        for (index in 0 until (questions?.length() ?: 0))
            collect(questions?.optJSONObject(index), inquiry = true)
        val candidates = (0 until nested.length()).mapNotNull(nested::optJSONObject)
            .filter { sameAction(primary, it) }
        if (candidates.isEmpty()) return decision
        // A primary receipt may legitimately belong to both a concern and a
        // motive. Validate compatibility against the ACCUMULATED ownership,
        // rather than treating different owner fields as competing actions.
        // A conflicting explicit value still cannot be assigned to the same
        // primary effect; preserve those nested steps for independent receipts.
        val result = JSONObject(decision.toString())
        // One primary tool result cannot be credited to two different open
        // questions just because both requested an identical tool+args.
        // Unlike a compatible concern+motive pair, different question IDs
        // have no unambiguous primary owner, so both stay independent.
        val competingQuestions = candidates.mapNotNull {
            it.optJSONObject("curiosity")?.let(::canonical)
        }.distinct().size > 1
        for (candidate in candidates) {
            if (competingQuestions && candidate.has("curiosity")) continue
            // An incompatible second owner does not invalidate the first
            // compatible owner already attached to this real primary receipt.
            // Keep the conflicting nested step for its own execution/receipt.
            if (!sameAction(AutonomousActionWireProtocol.normalizeStep(result), candidate))
                continue
            mergeLinks(result, candidate)
        }
        return result
    }

    private val causalLinks = listOf("motiveId", "motiveRef", "concernId", "experienceId", "curiosity", "learningApplication")

    /** Same executable request AND compatible explicitly authored ownership.
     * Conflicting links must not credit one question/goal with another's work. */
    private fun sameAction(left: JSONObject, right: JSONObject): Boolean =
        right.optString("tool").isNotBlank() &&
            left.optString("tool") == right.optString("tool") &&
            canonical(left.optJSONObject("args") ?: JSONObject()) ==
                canonical(right.optJSONObject("args") ?: JSONObject()) &&
            left.optBoolean("requiresPreviousSuccess", false) ==
                right.optBoolean("requiresPreviousSuccess", false) &&
            causalLinks.all { key ->
                !left.has(key) || !right.has(key) ||
                    canonical(left.opt(key)) == canonical(right.opt(key))
            }

    private fun mergeLinks(target: JSONObject, source: JSONObject) {
        (causalLinks + "reason").forEach { key ->
            if (!target.has(key) && source.has(key)) target.put(key, source.get(key))
        }
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
