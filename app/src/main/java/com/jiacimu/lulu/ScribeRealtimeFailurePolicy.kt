package com.jiacimu.lulu

/**
 * No raw server payloads, credentials or stack traces in call UI.
 * Distinguish transport, account, protocol and resource failures instead of
 * telling the caller their network is bad for every WebSocket failure.
 */
internal object ScribeRealtimeFailurePolicy {
    fun serverEvent(kind: String): String = when (kind) {
        "auth_error", "scribe_auth_error", "unaccepted_terms" ->
            "ElevenLabs Realtime 未获授权：请检查 API Key、Scribe 使用权限或服务条款（$kind）"
        "quota_exceeded", "scribe_quota_exceeded_error" ->
            "ElevenLabs Scribe 使用额度不足（$kind）"
        "rate_limited", "throttled", "commit_throttled" ->
            "ElevenLabs Scribe 触发频率限制，请稍后重试（$kind）"
        "invalid_request", "input_error", "chunk_size_exceeded" ->
            "ElevenLabs Scribe 拒绝了音频或连接参数；这通常不是网速问题（$kind）"
        "insufficient_audio_activity" ->
            "ElevenLabs Scribe 因音频活动不足结束会话，请重新收音"
        "session_time_limit_exceeded" ->
            "ElevenLabs Scribe 会话达到服务端时长上限，请重新收音"
        "resource_exhausted", "queue_overflow", "transcriber_error" ->
            "ElevenLabs Scribe 服务端暂时繁忙（$kind），请稍后重试"
        else -> "ElevenLabs Scribe 返回识别服务错误（${kind.take(48)}）"
    }

    fun connectionFailure(httpCode: Int?, exceptionType: String): String = when (httpCode) {
        401, 403 -> "ElevenLabs Scribe 连接被拒绝：API Key 或使用权限错误（HTTP $httpCode）"
        400, 404, 422 -> "ElevenLabs Scribe 握手参数被拒绝（HTTP $httpCode）"
        402, 429 -> "ElevenLabs Scribe 额度或请求频率受限（HTTP $httpCode）"
        null -> "ElevenLabs Scribe 实时连接异常（${exceptionType.take(40)}）；可能是网络、服务端关闭或连接超时"
        else -> "ElevenLabs Scribe 连接失败（HTTP $httpCode）；请确认服务状态"
    }

    fun remoteClose(code: Int): String =
        "ElevenLabs Scribe 服务端关闭了实时连接（WebSocket $code）；可重新收音，不能仅凭此判断网络故障"

    fun sendFailure(exceptionType: String): String =
        "ElevenLabs Scribe 发送音频失败（${exceptionType.take(40)}）；可重新收音"
}
