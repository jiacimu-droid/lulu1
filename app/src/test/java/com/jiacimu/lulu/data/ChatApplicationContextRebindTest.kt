package com.jiacimu.lulu.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** A long-lived chat Store must not write into an older Application's preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ChatApplicationContextRebindTest {
    @Test fun incomingReceiptIsDurableInTheCurrentApplicationAfterContextRebind() {
        val base = RuntimeEnvironment.getApplication() as Context
        fun isolatedApplication(): Context {
            val namespace = "chat-rebind-${UUID.randomUUID()}"
            return object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    base.getSharedPreferences("${namespace}-${name}", mode)
            }
        }

        val firstApplication = isolatedApplication()
        val secondApplication = isolatedApplication()
        val store = InMemoryLuluChatStore()
        store.initialize(firstApplication)
        val firstRoom = store.ensureConversation("first-${UUID.randomUUID()}", "第一位角色")
        store.appendSystemReceipt(firstRoom.id, "first-offer", "[共同活动] 第一通真实来电")

        // The same Kotlin Store can outlive a Robolectric Application instance.
        // Rebinding must discard the old preference handle, not merely replace
        // the context used for notifications.
        store.initialize(secondApplication)
        val secondRoom = store.ensureConversation("second-${UUID.randomUUID()}", "第二位角色")
        store.appendSystemReceipt(secondRoom.id, "second-offer", "[共同活动] 未接通的真实来电")

        val reopened = InMemoryLuluChatStore().apply { initialize(secondApplication) }
        assertEquals("second-offer",
            reopened.messages(secondRoom.id).value.single { it.id == "second-offer" }.id)
        assertFalse(reopened.conversations.value.any { it.id == firstRoom.id })
        // No application may silently acquire a different app's private history.
        val original = InMemoryLuluChatStore().apply { initialize(firstApplication) }
        assertEquals("first-offer",
            original.messages(firstRoom.id).value.single { it.id == "first-offer" }.id)
        assertFalse(original.conversations.value.any { it.id == secondRoom.id })
    }
}
