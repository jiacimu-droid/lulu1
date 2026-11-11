package com.jiacimu.lulu.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.QqChatPhotoAttachmentStore
import com.jiacimu.lulu.decodeQqChatImage
import com.jiacimu.lulu.system.LuluScreenAwareness
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CharacterScreenShareTest {
    private fun initializedContext(): Context {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        return context
    }

    @Test fun realScreenshotIsDurableImageBubbleInTheCorrectPrivateChat() = runBlocking {
        val context = initializedContext()
        val role = MigratedDomainStores.characters.create(
            "截图分享甲-" + UUID.randomUUID(), "喜欢把新发现分享出去")
        val other = MigratedDomainStores.characters.create(
            "截图分享乙-" + UUID.randomUUID(), "另一位角色")
        val otherChat = MigratedDomainStores.chat.ensureConversation(
            other.characterId, other.displayName)
        val frame = Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888)
        try {
            frame.eraseColor(android.graphics.Color.CYAN)
            val delivered = CompanionActionRuntime.sendCapturedScreenshot(
                context, role.characterId, frame, "你看这个页面", "com.example.reader")
            assertTrue(delivered.summary, delivered.success)
            assertEquals(1, delivered.deliveredMessageIds.size)
            val ownChatId = requireNotNull(delivered.conversationId)
            val ownMessage = MigratedDomainStores.chat.messages(ownChatId).value
                .single { it.id == delivered.evidenceId }
            assertEquals(LuluChatMessage.Sender.Character, ownMessage.sender)
            val image = requireNotNull(decodeQqChatImage(ownMessage.content))
            assertEquals("你看这个页面", image.caption)
            assertTrue(image.imageDescription.contains("真实手机屏幕截图"))
            assertFalse(image.sticker)
            assertEquals("", image.sourceUrl)
            val persisted = File(requireNotNull(Uri.parse(image.imageUri).path))
            assertTrue(persisted.isFile && persisted.length() > 0L)
            assertEquals("qq-role-captures", persisted.parentFile.name)
            // A sent screenshot remains readable even without an album copy.
            assertTrue(com.jiacimu.lulu.RolePhotoAlbumStore
                .list(context, role.characterId).none { it.uri == image.imageUri })
            assertTrue(MigratedDomainStores.chat.messages(otherChat.id).value
                .none { it.id == ownMessage.id })
            assertTrue(QqChatPhotoAttachmentStore.deleteIfOwned(context, image.imageUri))
            assertFalse(persisted.exists())
        } finally {
            frame.recycle()
        }
    }

    @Test fun noOwnerConsentDoesNotSendScreenshotEvenWhenModelRequestsShare() = runBlocking {
        val context = initializedContext()
        val role = MigratedDomainStores.characters.create(
            "拒绝截图分享-" + UUID.randomUUID(), "守住隐私边界")
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, false).commit()
        val before = MigratedDomainStores.chat.conversations.value.size
        val response = JSONObject(LuluScreenAwareness.capture(
            context, userRequested = true, characterId = role.characterId,
            sendToChat = true, chatCaption = "未经授权不得发送"))
        assertFalse(response.optBoolean("success"))
        assertFalse(response.optBoolean("sentToChat"))
        assertEquals("waiting_user", response.optString("status"))
        assertEquals(before, MigratedDomainStores.chat.conversations.value.size)
    }

    @Test fun studyWatchCannotTurnPrivateObservationIntoChatShare() = runBlocking {
        val context = initializedContext()
        val role = MigratedDomainStores.characters.create(
            "督学截图隔离-" + UUID.randomUUID(), "谨慎")
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, true).commit()
        val receipt = JSONObject(LuluScreenAwareness.capture(
            context, userRequested = true, characterId = role.characterId,
            sessionId = "study-watch-session", sendToChat = true))
        assertFalse(receipt.optBoolean("success"))
        assertFalse(receipt.optBoolean("sentToChat"))
    }
}
