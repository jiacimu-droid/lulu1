package com.jiacimu.lulu

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.jiacimu.lulu.ai.VisionModelService
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.CancellationException

/** Only photos from real user messages visible to this role and THIS turn. */
internal data class QqPreparedChatPhotos(
    val urls: List<String>,
    val count: Int,
    val failures: Int,
) {
    val promptNote: String get() = when {
        count == 0 -> ""
        urls.isEmpty() -> "用户发来了${count}张图片，但当前无法读取像素；只能理解用户实际提供的文字，不能编造图像内容。"
        failures > 0 -> "本轮用户发来${count}张图片，其中${urls.size}张已附到本次聊天模型请求；其余${failures}张未能读取，请勿声称看见。"
        else -> "本轮用户发来${count}张图片，像素已经准备附加到本次模型请求。请根据可见画面和文字回应，不得臆造未能辨认的小字。"
    }
}

internal object QqChatImageModelInput {
    private const val DIAG_PREFS = "lulu_chat_image_transport_v1"

    /** Diagnostic means "pixels prepared", NOT that an API provider interpreted them. */
    fun lastReadStatus(context: Context): String =
        context.applicationContext.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
            .getString("latest", "尚未记录到聊天图片准备结果").orEmpty()

    private fun recordRead(context: Context, characterId: String, count: Int,
                           prepared: Int, failure: String) {
        val at = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        val note = "$at · 角色=${characterId.take(20)} · 像素准备 $prepared/$count 张" +
            if (failure.isBlank()) "；模型是否真正识别仍取决于其视觉能力"
            else "；读取失败：${failure.take(95)}"
        context.applicationContext.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE)
            .edit().putString("latest", note).apply()
    }

    suspend fun prepare(
        context: Context, characterId: String, evidenceIds: Collection<String>,
        maxPhotos: Int = 2,
    ): QqPreparedChatPhotos {
        if (evidenceIds.isEmpty()) return QqPreparedChatPhotos(emptyList(), 0, 0)
        val requestedIds = evidenceIds.map { it.substringBefore(":group:") }.toSet()
        val messages = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { chat -> chat.groupChat?.members?.any { it.characterId == characterId } == true ||
                (chat.groupChat == null && chat.characterId == characterId) }
            .flatMap { chat -> MigratedDomainStores.chat.messages(chat.id).value.asSequence() }
            .filter { it.id in requestedIds && it.sender == LuluChatMessage.Sender.User }
            .distinctBy(LuluChatMessage::id)
            .sortedBy(LuluChatMessage::createdAt)
            .mapNotNull { decodeQqChatImage(it.content) }
            .filterNot { it.sticker }
            .toList().takeLast(maxPhotos.coerceIn(1, 3))
        if (messages.isEmpty()) return QqPreparedChatPhotos(emptyList(), 0, 0)

        val data = mutableListOf<String>()
        var failures = 0
        var failureDetail = ""
        for (image in messages) {
            try {
                VisionModelService.prepareChatImageDataUrl(context, image.imageUri)
                    .onSuccess { data += it }
                    .onFailure {
                        if (it is CancellationException) throw it
                        failures++
                        if (failureDetail.isBlank())
                            failureDetail = it.message.orEmpty().ifBlank { "图片来源不可读取" }
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
        recordRead(context, characterId, messages.size, data.size, failureDetail)
        return QqPreparedChatPhotos(data, messages.size, failures)
    }
}
