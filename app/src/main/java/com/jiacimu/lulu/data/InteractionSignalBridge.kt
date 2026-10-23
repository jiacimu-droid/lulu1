package com.jiacimu.lulu.data

import com.jiacimu.lulu.Live2DTouchZones

import java.time.Instant

/**
 * Authoritative cues from interactions, not interpretations of the user's mind.
 * The same observation -> appraisal -> inner-life -> action pipeline can respond
 * to calls today and other user actions later, without trigger-specific scripts.
 */
internal object InteractionSignalBridge {
    const val SOURCE = "interaction-signal"

    /**
     * "Not transcribed" is a technical observation. It does not prove the user
     * was intentionally silent, sleeping, upset or away from the device.
     */
    fun callEndDescription(
        endedByCharacter: Boolean,
        elapsedSeconds: Long,
        confirmedUserSpeechCount: Int,
        secondsSinceConfirmedSpeech: Long,
    ): String = buildString {
        append(if (endedByCharacter) "这通电话由角色结束。" else "这通电话由用户点击挂断结束。")
        append(" 通话约${elapsedSeconds.coerceAtLeast(0)}秒。")
        if (confirmedUserSpeechCount == 0) {
            append(" 通话中没有成功收到用户的语音转写文本。")
        } else {
            append(" 已成功接收用户发言${confirmedUserSpeechCount}轮；")
            append(" 距最后一次成功收到用户发言约${secondsSinceConfirmedSpeech.coerceAtLeast(0)}秒。")
        }
        append(" 没有识别到语音不等于用户确实没有说话；")
        append(" 不能由挂断单独推断生气、冷落、睡着、设备故障或任何确定的动机。")
    }

    fun callQuietDescription(
        secondsSinceUserActivity: Long,
        microphoneMuted: Boolean,
    ): String = buildString {
        append("电话仍然接通，过去约${secondsSinceUserActivity.coerceAtLeast(0)}秒没有确认新的用户语音内容；")
        append(if (microphoneMuted) "用户麦克风处于静音状态。" else "用户麦克风未静音，但语音识别没有确认新内容。")
        append(" 此为设备观察，不证明用户真的沉默或发生了任何心理变化。")
    }

    fun recordCallStart(characterId: String, callId: String, incomingReason: String, now: Instant = Instant.now()) {
        val id = "interaction-call-start-$callId"
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).isNotEmpty()) return
        SharedExperienceTimeline.record(eventId = id, characterId = characterId, channel = "电话互动",
            speaker = "通话操作", content = "电话刚刚真实接通，用户尚未新增发言。" +
                incomingReason.takeIf(String::isNotBlank)?.let { "角色的来电缘由：${it.take(200)}。" }.orEmpty(),
            occurredAt = now, triggerExtraction = false, sessionId = callId, source = SOURCE,
            evidenceKind = EventEvidenceKind.Observation)
    }

    /**
     * One actual, connected session can record that video mode was selected.
     * This does NOT claim a real rendered Live2D frame or any view of the
     * user's camera. The separate renderer callback provides that evidence.
     */
    fun recordVideoSelected(characterId: String, callId: String, now: Instant = Instant.now()) {
        if (characterId.isBlank() || callId.isBlank()) return
        val id = "interaction-call-video-selected-$callId"
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).isNotEmpty()) return
        SharedExperienceTimeline.record(
            eventId = id, characterId = characterId, channel = "电话互动",
            speaker = "视频通话操作",
            content = "这次已接通的电话切换到 Live2D 视频模式；角色的数字形象正尝试在用户手机上显示。尚未证明画面成功渲染，更没有自动获得用户摄像头画面。",
            occurredAt = now, triggerExtraction = false, sessionId = callId,
            source = SOURCE, evidenceKind = EventEvidenceKind.Observation,
        )
    }

    /**
     * Called only after the actual Android native frame has appeared while
     * THIS call is connected, never from a static portrait or WebGL probe.
     */
    fun recordVideoRendered(characterId: String, callId: String, now: Instant = Instant.now()) {
        if (characterId.isBlank() || callId.isBlank()) return
        val id = "interaction-call-video-rendered-$callId"
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).isNotEmpty()) return
        SharedExperienceTimeline.record(
            eventId = id, characterId = characterId, channel = "电话互动",
            speaker = "视频画面回执",
            content = "这通电话已实际成功显示角色本人的 Live2D 数字形象，用户在手机上看到了角色动态视频画面；不是只有声音的语音电话。是否看到用户本人取决于用户另行开启摄像头共享，显示数字形象并不表示角色看见了用户。",
            occurredAt = now, triggerExtraction = false, sessionId = callId,
            source = SOURCE, evidenceKind = EventEvidenceKind.Observation,
        )
    }

    /**
     * Calls keep their original ID, audio transcript, delivery receipts and
     * memory lifecycle while moving between video and voice. A mode switch
     * is just an observed event in that same timeline, not a second call.
     */
    fun recordCallMediaSwitch(
        characterId: String, callId: String, fromVideo: Boolean,
        toVideo: Boolean, now: Instant = Instant.now(),
    ) {
        if (characterId.isBlank() || callId.isBlank() || fromVideo == toVideo) return
        val id = "interaction-call-media-switch-$callId-${java.util.UUID.randomUUID()}"
        SharedExperienceTimeline.record(
            eventId = id, characterId = characterId, channel = "电话互动",
            speaker = "通话模式切换",
            content = if (toVideo)
                "同一通仍在进行的电话，用户刚把语音模式切换为 Live2D 视频模式；视频画面是否成功显示以独立画面回执为准。"
            else
                "同一通仍在进行的电话，用户刚把 Live2D 视频模式切换为语音模式；并没有重新拨号，之前的通话内容和视频经历仍属于这一通电话。",
            occurredAt = now, triggerExtraction = false, sessionId = callId,
            source = SOURCE, evidenceKind = EventEvidenceKind.Observation,
        )
    }

    /** Describe genuine screen contacts, never assert that the role felt a
     * physical touch or infer the owner's emotion or relationship intention.
     */
    fun avatarContactDescription(region: String, gesture: String, count: Int,
        force: Float? = null, durationMillis: Long? = null): String? {
        if (count < 1) return null
        if (force != null && (!force.isFinite() || force !in .1f..1f)) return null
        if (durationMillis != null && durationMillis < 0) return null
        val place = Live2DTouchZones.label(region) ?: return null
        val action = when (gesture) {
            "tap" -> "用手轻点"
            "hold" -> "用手掌按住"
            "stroke" -> "用手掌抚摸"
            "stroke-left" -> "用手掌向左抚摸"
            "stroke-right" -> "用手掌向右抚摸"
            "stroke-up" -> "用手掌向上抚摸"
            "stroke-down" -> "用手掌向下抚摸"
            "kiss" -> "用嘴唇亲吻"
            "kick" -> "用脚轻踢" // Historical descriptions only; no new kick can be recorded.
            "slap" -> "用手掌拍击"
            "slap-left" -> "从右向左轻拍"
            "slap-right" -> "从左向右轻拍"
            else -> return null
        }
        return "用户在屏幕上${action}了数字形象的${place}" +
            (if (force != null) "（力度${if (force < .4f) "轻" else if (force > .75f) "重" else "中"}）" else "") +
            (if (count > 1) "，共${count}次" else "") +
            (if (durationMillis != null && durationMillis > 0)
                "，手势总时长${String.format(java.util.Locale.ROOT, "%.1f", durationMillis / 1000.0)}秒" else "")
    }

    /** Preserve EVERY distinct touched region in a burst, not just the last
     * region with an inaccurate total count. One batch is one original event.
     */
    fun recordAvatarTouches(characterId: String, callId: String,
        contacts: List<Pair<String, String>>, now: Instant = Instant.now(),
        forces: List<Float> = emptyList(), durationsMillis: List<Long> = emptyList()): String? {
        if (!hasRenderedVideo(characterId, callId) || contacts.isEmpty()) return null
        if (forces.isNotEmpty() && forces.size != contacts.size) return null
        if (durationsMillis.isNotEmpty() && durationsMillis.size != contacts.size) return null
        val counts = linkedMapOf<Triple<String, String, Float?>, Int>()
        val durations = linkedMapOf<Triple<String, String, Float?>, Long>()
        for ((index, contact) in contacts.withIndex()) {
            val (region, gesture) = contact
            val force = forces.getOrNull(index)
            val duration = durationsMillis.getOrNull(index)
            if (gesture == "kick" ||
                avatarContactDescription(region, gesture, 1, force, duration) == null) return null
            val key = Triple(region, gesture, force)
            counts[key] = (counts[key] ?: 0) + 1
            if (duration != null) durations[key] = (durations[key] ?: 0L) + duration
        }
        val descriptions = counts.mapNotNull { (key, n) ->
            avatarContactDescription(key.first, key.second, n, key.third, durations[key])
        }
        if (descriptions.isEmpty()) return null
        val fact = "这通 Live2D 视频里发生了真实的屏幕触摸："+
            descriptions.joinToString("；") +
            "。这是手机接收的真实手指动作，不等于角色实体被触摸；" +
            "不替角色推断情绪或规定回应。"
        val id = "interaction-call-touch-$callId-${java.util.UUID.randomUUID()}"
        SharedExperienceTimeline.record(
            eventId = id, characterId = characterId, channel = "电话互动",
            speaker = "真实触摸操作", content = fact,
            occurredAt = now, triggerExtraction = false,
            sessionId = callId, source = SOURCE,
            evidenceKind = EventEvidenceKind.Observation,
        )
        return id
    }

    /** Existing callers may submit repeated contacts of the same type. */
    fun recordAvatarTouch(characterId: String, callId: String, region: String,
        gesture: String, count: Int, now: Instant = Instant.now()): String? {
        if (count < 1) return null
        return recordAvatarTouches(characterId, callId,
            List(count) { region to gesture }, now)
    }

    /** Whether THIS call has actually rendered video, not merely opened a UI. */
    fun hasRenderedVideo(characterId: String, callId: String): Boolean =
        characterId.isNotBlank() && callId.isNotBlank() &&
            SharedExperienceTimeline.eventsByIds(characterId,
                listOf("interaction-call-video-rendered-$callId")).isNotEmpty()

    fun hasSelectedVideo(characterId: String, callId: String): Boolean =
        characterId.isNotBlank() && callId.isNotBlank() &&
            SharedExperienceTimeline.eventsByIds(characterId,
                listOf("interaction-call-video-selected-$callId")).isNotEmpty()

    fun callMediaSummary(characterId: String, callId: String, endsInVideo: Boolean): String {
        if (!hasSelectedVideo(characterId, callId)) return ""
        val visible = hasRenderedVideo(characterId, callId)
        return if (visible) {
            if (endsInVideo) "这通电话实际使用过 Live2D 视频画面，结束时仍在视频模式。"
            else "这通电话中曾真实显示过 Live2D 视频画面，结束时已回到语音模式；仍是同一通电话。"
        } else {
            "这通电话曾开启 Live2D 视频模式，但没有确认视频画面成功显示；不能冒充已经视频见面。"
        }
    }

    fun recordCallQuiet(
        characterId: String,
        callId: String,
        observationId: String,
        secondsSinceUserActivity: Long,
        microphoneMuted: Boolean,
        now: Instant = Instant.now(),
    ) {
        // A repeated pulse of the same silent interval is continued reflection,
        // not another user action or another source of emotional evidence.
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(observationId)).isNotEmpty()) return
        SharedExperienceTimeline.record(
            eventId = observationId,
            characterId = characterId,
            channel = "电话互动",
            speaker = "通话观察",
            content = callQuietDescription(secondsSinceUserActivity, microphoneMuted),
            occurredAt = now,
            triggerExtraction = false,
            sessionId = callId,
            source = SOURCE,
            evidenceKind = EventEvidenceKind.Observation,
        )
    }

    fun recordCallEnd(
        characterId: String,
        callId: String,
        endedByCharacter: Boolean,
        elapsedSeconds: Long,
        confirmedUserSpeechCount: Int,
        secondsSinceConfirmedSpeech: Long,
        now: Instant = Instant.now(),
        endsInVideo: Boolean = false,
    ): String {
        val id = "interaction-call-end-$callId"
        SharedExperienceTimeline.record(
            eventId = id,
            characterId = characterId,
            channel = "电话互动",
            speaker = "通话操作",
            content = callEndDescription(
                endedByCharacter, elapsedSeconds, confirmedUserSpeechCount, secondsSinceConfirmedSpeech,
            ) + callMediaSummary(characterId, callId, endsInVideo),
            occurredAt = now,
            triggerExtraction = false,
            sessionId = callId,
            source = SOURCE,
            evidenceKind = EventEvidenceKind.Observation,
        )
        return id
    }
}
