package com.jiacimu.lulu.data

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
    ): String {
        val id = "interaction-call-end-$callId"
        SharedExperienceTimeline.record(
            eventId = id,
            characterId = characterId,
            channel = "电话互动",
            speaker = "通话操作",
            content = callEndDescription(
                endedByCharacter, elapsedSeconds, confirmedUserSpeechCount, secondsSinceConfirmedSpeech,
            ),
            occurredAt = now,
            triggerExtraction = false,
            sessionId = callId,
            source = SOURCE,
            evidenceKind = EventEvidenceKind.Observation,
        )
        return id
    }
}
