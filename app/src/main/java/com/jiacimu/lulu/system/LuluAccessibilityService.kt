package com.jiacimu.lulu.system

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Local accessibility bridge used by Lulu's phone-assistant tool layer.
 * It exposes only facts the OS has actually delivered and returns false when an action cannot run.
 */
class LuluAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        mutableState.value = mutableState.value.copy(connected = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (packageName.isBlank()) return
        val root = rootInActiveWindow
        mutableState.value = AccessibilitySnapshot(
            connected = true,
            packageName = packageName,
            windowTitle = event.contentDescription?.toString().orEmpty(),
            visibleText = root.collectVisibleText().take(6_000),
            capturedAt = Instant.now(),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        mutableState.value = AccessibilitySnapshot()
        super.onDestroy()
    }

    companion object {
        private var instance: LuluAccessibilityService? = null
        private val mutableState = MutableStateFlow(AccessibilitySnapshot())
        val state: StateFlow<AccessibilitySnapshot> = mutableState.asStateFlow()

        fun perform(action: LuluScreenAction): Boolean {
            val service = instance ?: return false
            val globalAction = when (action) {
                LuluScreenAction.Back -> GLOBAL_ACTION_BACK
                LuluScreenAction.Home -> GLOBAL_ACTION_HOME
                LuluScreenAction.Recents -> GLOBAL_ACTION_RECENTS
                LuluScreenAction.Notifications -> GLOBAL_ACTION_NOTIFICATIONS
                LuluScreenAction.QuickSettings -> GLOBAL_ACTION_QUICK_SETTINGS
            }
            return service.performGlobalAction(globalAction)
        }

        fun clickFirstText(text: String): Boolean {
            val service = instance ?: return false
            val clean = text.trim()
            if (clean.isBlank()) return false
            val nodes = service.rootInActiveWindow?.findAccessibilityNodeInfosByText(clean).orEmpty()
            return nodes.firstOrNull { it.isVisibleToUser && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } != null
        }
    }
}

data class AccessibilitySnapshot(
    val connected: Boolean = false,
    val packageName: String = "",
    val windowTitle: String = "",
    val visibleText: String = "",
    val capturedAt: Instant? = null,
)

enum class LuluScreenAction { Back, Home, Recents, Notifications, QuickSettings }

private fun AccessibilityNodeInfo?.collectVisibleText(): String {
    if (this == null) return ""
    val lines = LinkedHashSet<String>()
    fun walk(node: AccessibilityNodeInfo?) {
        if (node == null || lines.size >= 300) return
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(lines::add)
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let(lines::add)
        }
        for (index in 0 until node.childCount) walk(node.getChild(index))
    }
    walk(this)
    return lines.joinToString("\n")
}
