package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CallContractsTest {
    @Test fun miniMaxSelectionCannotRouteIntoElevenAgent() {
        assertFalse(CallVoiceConfiguration.usesAgent("minimax", "agent"))
        assertFalse(CallVoiceConfiguration.usesAgent("elevenlabs", "direct"))
        assertFalse(CallVoiceConfiguration.usesAgent("elevenlabs", null))
        assertTrue(CallVoiceConfiguration.usesAgent("elevenlabs", "agent"))
    }
    @Test fun directElevenLabsCallsUseOnDeviceSttAndAgentIsExplicitlyExcluded() {
        assertTrue(CallVoiceConfiguration.requiresOnDeviceStt("elevenlabs", "direct"))
        assertTrue(CallVoiceConfiguration.requiresOnDeviceStt("elevenlabs", null))
        assertFalse(CallVoiceConfiguration.requiresOnDeviceStt("elevenlabs", "agent"))
        assertFalse(CallVoiceConfiguration.requiresOnDeviceStt("minimax", "direct"))
        assertFalse(CallVoiceConfiguration.requiresOnDeviceStt("system", "direct"))
    }
    @Test fun miniAsrKeepsRegionButDoesNotCarryTtsQueryOrPath() {
        assertEquals("https://api.minimaxi.com/v1/speech_to_text", CallVoiceConfiguration.miniAsrEndpoint("https://api.minimaxi.com/v1/t2a_v2?GroupId=123"))
        assertEquals("https://api.minimax.io/v1/speech_to_text", CallVoiceConfiguration.miniAsrEndpoint("https://api.minimax.io/v1/t2a_v2"))
    }
    @Test fun wavContainsActualPcmWithValidHeader() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = pcmWav(pcm)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, b.getInt(4)); assertEquals(16000, b.getInt(24))
        assertEquals(4, b.getInt(40)); assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test fun repeatingSameErrorHasNewIdAndOldDismissCannotHideIt() {
        val task = ChatReplyTaskManager.TaskContext("notice-test")
        task.reportError("余额不足")
        val old = ChatReplyTaskManager.state("notice-test").errorId
        task.reportError("余额不足")
        assertTrue(ChatReplyTaskManager.state("notice-test").errorId > old)
        ChatReplyTaskManager.clearError("notice-test", old)
        assertEquals("余额不足", ChatReplyTaskManager.state("notice-test").lastError)
    }
    @Test fun noticesExplainFailureAndHideCredentials() {
        assertTrue(chatErrorNotice("HTTP 402 insufficient balance").startsWith("账户余额或额度不足"))
        assertTrue(chatErrorNotice("no available channel").startsWith("没有可用模型渠道"))
        assertTrue(chatErrorNotice("HTTP 401 invalid api key").startsWith("账号密钥或访问权限不可用"))
        assertFalse(chatErrorNotice("Bearer secret-token sk-123456789abcdef").contains("secret-token"))
        assertFalse(chatErrorNotice("Bearer secret-token sk-123456789abcdef").contains("sk-123456789abcdef"))
    }
    @Test fun outputSelectionPrefersHeadsetsOverBuiltInSpeaker() {
        assertTrue(CallAudioRoute.headsetPriority(android.media.AudioDeviceInfo.TYPE_USB_HEADSET) < CallAudioRoute.headsetPriority(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(CallAudioRoute.headsetPriority(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) < CallAudioRoute.headsetPriority(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }
    @Test fun microphoneRouteMatchesHeadsetButKeepsPhoneMicForOutputOnlyHeadphones() {
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            CallAudioRoute.preferredMicrophoneType(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
            CallAudioRoute.preferredMicrophoneType(android.media.AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            CallAudioRoute.preferredMicrophoneType(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
            CallAudioRoute.preferredMicrophoneType(android.media.AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC,
            CallAudioRoute.preferredMicrophoneType(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC,
            CallAudioRoute.preferredMicrophoneType(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test fun newMessagePreservesLongContactGapAndFutureDatesAreIgnored() {
        val now = java.time.Instant.parse("2026-10-07T12:00:00Z")
        val old = now.minus(java.time.Duration.ofDays(30))
        val text = com.jiacimu.lulu.data.CompanionContactClock.describe(listOf(old, now, now.plusSeconds(100)), now)
        assertTrue(text.contains("未联系30天"))
        assertTrue(text.contains("当前新消息不会抹掉"))
        assertTrue(com.jiacimu.lulu.data.CompanionContactClock.describe(listOf(now.plusSeconds(10)), now).contains("没有可核实"))
        assertFalse(com.jiacimu.lulu.data.CompanionContactClock.describe(listOf(now), now).contains("两轮之间未联系"))
    }

    @Test fun streamingSpeechNeverReadsToolArgsOrPrivateThoughtsAndDoesNotRepeat() {
        val stream = CallReplyStream()
        assertTrue(stream.update("{\"action\":\"tool\",\"tool\":\"create_alarm\",\"args\":{\"text\":\"已设置。\"}}").isEmpty())
        assertEquals(listOf("第一句已经来了。"), stream.update("{\"action\":\"reply\",\"text\":\"第一句已经来了。第二"))
        assertTrue(stream.update("{\"action\":\"reply\",\"text\":\"第一句已经来了。第二").isEmpty())
        assertEquals(listOf("第二句也来了。"), stream.update("{\"action\":\"reply\",\"text\":\"第一句已经来了。第二句也来了。\",\"innerThought\":\"不能读出来\"}"))
        assertTrue(stream.finish("第一句已经来了。第二句也来了。").isEmpty())
        assertTrue(stream.update("{\"action\":\"reply\",\"text\":\"迟到内容。\"}").isEmpty())
        assertNull(CallReplyStream.replyTextPrefix("{\"innerThought\":\"secret\",\"text\":\"不要念\"}"))
    }
    @Test fun streamingDecoderHandlesIncompleteEscapesAndFinalMismatch() {
        try { CallReplyStream().finish("{\"action\":\"tool\",\"args\":{}}"); fail("must not read raw envelope") } catch (_: IllegalStateException) {}
        val cancelled = CallReplyStream()
        cancelled.cancel()
        assertTrue(cancelled.isFinished)
        assertTrue(cancelled.update("{\"action\":\"reply\",\"text\":\"迟到的一句。\"}").isEmpty())
        val whitespace = CallReplyStream()
        whitespace.update("{\"action\":\"reply\",\"text\":\"  完整一句话。")
        assertTrue(whitespace.finish("  完整一句话。").isEmpty())
        assertEquals("你好", CallReplyStream.replyTextPrefix("{\"action\":\"reply\",\"text\":\"你好\\u4"))
        val stream = CallReplyStream()
        stream.update("{\"action\":\"reply\",\"text\":\"已经播放的话。")
        try { stream.finish("完全不同"); fail("must reject changed delivered prefix") } catch (_: IllegalStateException) {}
    }

    @Test fun closedReplyTextSurvivesDamagedOptionalMetadataWithoutReadingPrivateFields() {
        val raw = "{\"action\":\"reply\",\"text\":\"你刚才那声‘爸爸’喊得字正腔圆的，我总得留两秒。\",\"innerThought\":\"还没写完"
        val text = CallReplyStream.completeReplyText(raw)
        assertEquals("你刚才那声‘爸爸’喊得字正腔圆的，我总得留两秒。", text)
        val stream = CallReplyStream()
        val chunks = stream.update(raw) + stream.finish(requireNotNull(text))
        assertEquals(text, chunks.joinToString(""))
        assertFalse(chunks.joinToString("").contains("innerThought"))
        assertEquals(text, CallReplyStream.completeReplyText("```json\n$raw"))
        assertNull(CallReplyStream.completeReplyText("{\"action\":\"reply\",\"text\":\"正文也没写完"))
        assertNull(CallReplyStream.completeReplyText("{\"action\":\"tool\",\"text\":\"不得念工具参数\"}"))
        assertNull(CallReplyStream.completeReplyText("{\"innerThought\":\"秘密\",\"text\":\"不得念心声\"}"))
    }

    @Test fun expressivePhoneWaitsForWholeEmotionalTurnInsteadOfIndependentSentenceSynthesis() {
        val stream = CallReplyStream()
        val first = "[softly] 是我不对。"
        val complete = first + "[hesitant] 我当时应该先听你说完。"
        val envelope = "{\"action\":\"reply\",\"text\":\"" + first + "\"}"
        assertTrue(stream.updateForSpeech(envelope, wholeTurn = true).isEmpty())
        assertEquals(listOf(complete), stream.finishForSpeech(complete, wholeTurn = true))
        assertEquals("是我不对。 我当时应该先听你说完。", VoicePerformance.plain(complete))
        val incremental = CallReplyStream()
        assertEquals(listOf(first), incremental.updateForSpeech(envelope, wholeTurn = false))
        assertEquals(listOf("[hesitant] 我当时应该先听你说完。"),
            incremental.finishForSpeech(complete, wholeTurn = false))
    }

    @Test fun callOpeningRunsOnceForBothCallDirectionsAndReconnectDoesNotRepeat() {
        val opening = CallOpeningTurn()
        assertFalse(opening.claim(""))
        assertTrue(opening.claim("outgoing"))
        assertFalse(opening.claim("outgoing"))
        assertTrue(opening.claim("incoming"))
        assertFalse(opening.claim("incoming"))
        assertTrue(CallOpeningTurn.prompt().contains("用户还没有说话"))
        assertTrue(CallOpeningTurn.prompt("想聊昨天那本书").contains("想聊昨天那本书"))
    }

}
