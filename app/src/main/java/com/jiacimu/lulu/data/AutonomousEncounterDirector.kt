package com.jiacimu.lulu.data

import org.json.JSONObject

/** A director schedules opportunities; each actor still owns their actual response. */
internal object AutonomousEncounterDirector {
    data class Cue(val speakerId: String, val phase: Int, val direction: String)
    data class Plan(val idea: String = "", val moment: JSONObject? = null,
        val turns: List<Cue> = emptyList())

    /** Only approved visible segments cross to the next actor. Never serialize
     * the original decision, which also contains private emotion and goals. */
    fun publicContext(beats: List<AutonomousEncounterScript.Beat>, names: Map<String, String>): String =
        beats.joinToString("\n") { beat ->
            val segments = beat.turn.optJSONArray("segments")
            val text = (0 until (segments?.length() ?: 0)).mapNotNull { index ->
                segments?.optJSONObject(index)?.optString("text")?.trim()?.takeIf(String::isNotBlank)
            }.joinToString("；")
            "阶段${beat.phase} · ${names[beat.speakerId] ?: beat.speakerId}：$text"
        }

    fun contractError(json: JSONObject?, participants: List<String>, requireTurns: Boolean = false): String? {
        if (json == null) return "导演输出不是完整JSON"
        if (json.has("moment")) {
            val moment = json.optJSONObject("moment") ?: return "moment须为对象"
            val kind = moment.optString("kind")
            if (kind !in setOf("none", "social_stumble", "social_frame_crossing"))
                return "moment不是当前世界支持的小插曲类型"
            if (kind != "none" && (moment.optString("actorId") !in participants ||
                moment.optString("otherId") !in participants ||
                moment.optString("actorId") == moment.optString("otherId")))
                return "moment须引用两位不同的真实在场人物"
        }
        val raw = json.optJSONArray("turns") ?: return if (json.has("turns")) "turns须为数组"
            else if (requireTurns) "本次导演契约缺少turns，须给出具体出场顺序" else null
        // The previous scene already supported at most five public phases per
        // four actors. Use that same bound for paid, evidence-driven decisions.
        if (raw.length() > AutonomousEncounterScript.MAX_PHASES * AutonomousEncounterScript.MAX_PARTICIPANTS)
            return "turns超出本场既有公开片段预算"
        var previousPhase = 0
        val seen = hashSetOf<Pair<String, Int>>()
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: return "turns[$index]不是对象"
            val speaker = item.optString("speakerId")
            val value = item.opt("phase")
            if (speaker !in participants) return "turns[$index]引用了未在场的人物"
            if (value !is Number || value.toInt().toDouble() != value.toDouble() ||
                value.toInt() !in 1..AutonomousEncounterScript.MAX_PHASES)
                return "turns[$index].phase须为1至5的整数"
            val phase = value.toInt()
            if (phase < previousPhase || !seen.add(speaker to phase))
                return "turns必须按阶段前进，同一人物同一阶段不可重复"
            if (item.optString("direction").isBlank()) return "turns[$index]缺少具体场面机会"
            previousPhase = phase
        }
        return null
    }

    fun parse(json: JSONObject, participants: List<String>): Plan? {
        if (contractError(json, participants) != null) return null
        val ideas = mutableListOf<String>()
        json.optString("angle").trim().takeIf(String::isNotBlank)?.let(ideas::add)
        val options = json.optJSONArray("options")
        for (i in 0 until (options?.length() ?: 0))
            options?.optString(i)?.trim()?.takeIf(String::isNotBlank)?.let(ideas::add)
        val raw = json.optJSONArray("turns")
        val turns = (0 until (raw?.length() ?: 0)).map { index ->
            val item = raw!!.getJSONObject(index)
            Cue(item.getString("speakerId"), item.getInt("phase"), item.getString("direction"))
        }
        // Older adviser responses remain usable without inventing a turn plan.
        if (turns.isEmpty()) {
            val phases = json.optJSONArray("phases")
            for (i in 0 until (phases?.length() ?: 0)) {
                val item = phases?.optJSONObject(i) ?: continue
                val phase = item.optInt("phase")
                if (phase in 1..AutonomousEncounterScript.MAX_PHASES)
                    ideas += "阶段$phase：${item.optString("direction")}"
            }
        }
        val moment = json.optJSONObject("moment")?.takeIf {
            it.optString("kind") in setOf("social_stumble", "social_frame_crossing")
        }
        return Plan(ideas.joinToString("；"), moment, turns)
    }
}
