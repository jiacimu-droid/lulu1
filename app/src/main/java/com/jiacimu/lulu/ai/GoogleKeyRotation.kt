package com.jiacimu.lulu.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val GOOGLE_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"

internal fun cleanGoogleKeys(keys: List<String>): List<String> =
    keys.map(String::trim).filter(String::isNotEmpty).distinct()

internal object GoogleKeyRotation {
    private val nextStartByChannel = ConcurrentHashMap<String, AtomicInteger>()
    private val rejectedKeyReasons = setOf(
        "API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_SERVICE_BLOCKED",
        "API_KEY_HTTP_REFERRER_BLOCKED", "API_KEY_IP_ADDRESS_BLOCKED",
        "API_KEY_ANDROID_APP_BLOCKED", "API_KEY_IOS_APP_BLOCKED",
    )

    suspend fun <T> request(
        channelId: String?,
        primaryKey: String,
        additionalKeys: List<String>,
        beforeRetry: () -> Unit = {},
        send: suspend (String) -> T,
    ): T {
        if (channelId == null) return send(primaryKey)
        val keys = cleanGoogleKeys(listOf(primaryKey) + additionalKeys)
        check(keys.isNotEmpty()) { "请填写 API 密钥" }
        if (keys.size == 1) return send(keys.single())
        currentCoroutineContext().ensureActive()
        val cursor = nextStartByChannel.computeIfAbsent(channelId) { AtomicInteger() }
        val start = Math.floorMod(cursor.getAndIncrement(), keys.size)
        for (offset in keys.indices) {
            currentCoroutineContext().ensureActive()
            if (offset > 0) beforeRetry()
            try {
                return send(keys[(start + offset) % keys.size])
            } catch (error: ModelHttpException) {
                val rejectedKey = error.status == 401 ||
                    (error.status in setOf(400, 403) && error.providerReason in rejectedKeyReasons)
                if (error.status != 429 && !rejectedKey) throw error
                if (offset == keys.lastIndex) throw ModelHttpException(
                    error.status,
                    "本次请求已试过全部 ${keys.size} 个 Google Key，最后收到 HTTP ${error.status}。" +
                        "请核对密钥权限或 Google 项目的用量和限额。",
                    error.providerCode,
                    error.providerReason,
                    keyRotationExhausted = true,
                )
            }
        }
        error("没有可使用的 Google Key")
    }
}
