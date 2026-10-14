package com.jiacimu.lulu

import android.content.Context
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
        else -> "本轮用户发来${count}张图片，像素已直接输入当前聊天模型。请先真正观察，再根据图片和配文自然回应；图片与配文是同一条用户消息。"
    }
}

internal object QqChatImageModelInput {
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
        for (image in messages) {
            try {
                VisionModelService.prepareChatImageDataUrl(context, image.imageUri)
                    .onSuccess { data += it }
                    .onFailure { if (it is CancellationException) throw it else failures++ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
        return QqPreparedChatPhotos(data, messages.size, failures)
    }
}
