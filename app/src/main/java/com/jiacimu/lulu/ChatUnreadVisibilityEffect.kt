package com.jiacimu.lulu

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jiacimu.lulu.data.ChatUnreadStore
import com.jiacimu.lulu.data.MigratedDomainStores

/**
 * Marks a conversation read only when its detail route is truly on screen and the Activity is
 * resumed. The chat detail composable intentionally stays alive behind other Lulu pages, so route
 * visibility must be explicit; otherwise background/hidden replies would be incorrectly auto-read.
 */
@Composable
internal fun ChatUnreadVisibilityEffect(
    conversationId: String,
    routeVisible: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val messages by MigratedDomainStores.chat.messages(conversationId).collectAsState()
    var activityResumed by remember(activity) {
        mutableStateOf(activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true)
    }

    LaunchedEffect(context) {
        ChatUnreadStore.initialize(context)
        // Migration baseline: preserve the user's current inbox state when the feature first appears,
        // rather than marking years of existing conversation history unread.
        MigratedDomainStores.chat.conversations.value.forEach { conversation ->
            ChatUnreadStore.ensureBaseline(
                conversationId = conversation.id,
                messages = MigratedDomainStores.chat.messages(conversation.id).value,
            )
        }
    }

    DisposableEffect(activity) {
        val lifecycle = activity?.lifecycle
        if (lifecycle == null) {
            activityResumed = routeVisible
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, _ ->
                activityResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            lifecycle.addObserver(observer)
            activityResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(
        conversationId,
        routeVisible,
        activityResumed,
        messages.size,
        messages.lastOrNull()?.id,
    ) {
        if (routeVisible && activityResumed) {
            ChatUnreadStore.markRead(conversationId, messages)
            // Keep the legacy persisted counter clear for backwards compatibility with older UI.
            MigratedDomainStores.chat.markConversationRead(conversationId)
        }
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}
