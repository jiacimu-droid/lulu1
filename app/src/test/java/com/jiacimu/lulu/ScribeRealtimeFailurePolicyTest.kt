package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class ScribeRealtimeFailurePolicyTest {
    @Test fun invalidAudioAndQuotaDoNotPretendToBeBadNetwork() {
        assertTrue(ScribeRealtimeFailurePolicy.serverEvent("input_error").contains("连接参数"))
        assertTrue(ScribeRealtimeFailurePolicy.serverEvent("quota_exceeded").contains("额度"))
        assertTrue(ScribeRealtimeFailurePolicy.serverEvent("insufficient_audio_activity").contains("音频活动不足"))
    }

    @Test fun handshakeAndRemoteClosureRemainDiagnosable() {
        assertTrue(ScribeRealtimeFailurePolicy.connectionFailure(401, "x").contains("HTTP 401"))
        assertTrue(ScribeRealtimeFailurePolicy.connectionFailure(null, "SocketTimeoutException")
            .contains("SocketTimeoutException"))
        assertTrue(ScribeRealtimeFailurePolicy.remoteClose(1006).contains("1006"))
    }
}
