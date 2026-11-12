package com.jiacimu.lulu.system

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Local accessibility bridge used by Lulu's phone-assistant and perception layers.
 * Screen state is a readable context source only; screen changes no longer wake the AI runtime.
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
            windowTitle = event?.contentDescription?.toString().orEmpty(),
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

        /** Obtain one Android 11+ screen frame; this service never stores or uploads it. */
        suspend fun captureBitmap(): Bitmap = suspendCancellableCoroutine { cont ->
            val service = instance
            if (service == null || Build.VERSION.SDK_INT < 30) {
                cont.resumeWithException(IllegalStateException("无障碍服务未连接或系统版本不支持"))
                return@suspendCancellableCoroutine
            }
            Handler(Looper.getMainLooper()).post {
                if (!cont.isActive) return@post
                try {
                    service.takeScreenshot(Display.DEFAULT_DISPLAY,
                        ContextCompat.getMainExecutor(service), object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                try {
                                    val buffer = result.hardwareBuffer
                                    val pixels = try {
                                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                            ?: error("截图像素不可读取")
                                        try { hardware.copy(Bitmap.Config.ARGB_8888, false)
                                            ?: error("截图像素无法复制") } finally { hardware.recycle() }
                                    } finally { buffer.close() }
                                    if (cont.isActive) cont.resume(pixels) else pixels.recycle()
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resumeWithException(e)
                                }
                            }
                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resumeWithException(
                                    IllegalStateException("屏幕受保护或系统拒绝截图：" + errorCode))
                            }
                        })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

        fun observe(): AccessibilitySnapshot {
            val service = instance ?: return AccessibilitySnapshot()
            val root = service.rootInActiveWindow
            return AccessibilitySnapshot(connected = true, packageName = root?.packageName?.toString().orEmpty(),
                visibleText = root.collectVisibleText().take(6_000), capturedAt = Instant.now()).also { mutableState.value = it }
        }

        /**
         * The virtual display has a different Android display ID.
         * Never mix its windows with rootInActiveWindow (owner's main screen).
         * A missing/ambiguous window produces an unverified observation.
         */
        fun observeDisplay(displayId: Int): AccessibilitySnapshot {
            val service = instance ?: return AccessibilitySnapshot()
            if (android.os.Build.VERSION.SDK_INT < 30 || displayId <= 0) return AccessibilitySnapshot()
            val all = service.windowsOnAllDisplays
            // An IME may own input focus on the SAME virtual display while
            // the app's search editor is open. Treating that keyboard as the
            // app's top window makes searches fail package verification.
            // Mika's ScreenReader likewise excludes IME/accessibility overlays.
            // Do NOT skip TYPE_SYSTEM: a permission/payment dialog must still
            // interrupt normal app operation rather than click underneath it.
            val windows = all.get(displayId).orEmpty().filterNot {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY ||
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
            }
            // Prefer the highest real content window, not an app window
            // underneath a system permission/payment dialog. The IME is the
            // sole intentional focus-owning window ignored above.
            val window = windows.maxByOrNull { it.layer }
            val root = window?.root
            return AccessibilitySnapshot(
                connected = root != null,
                packageName = root?.packageName?.toString().orEmpty(),
                visibleText = root.collectVisibleText().take(6_000),
                capturedAt = Instant.now(),
            )
        }

        /** Set Unicode text ONLY in an editable, non-password node on the owned display. */
        /**
         * Deny tapping labeled irreversible/sensitive controls on the virtual
         * screen. This is a conservative guard, NOT a complete transaction
         * detector; unknown controls remain unverified, never auto-approved.
         */
        fun isSensitiveVirtualTap(
            displayId: Int, packageName: String, x: Int, y: Int,
            screenWidth: Int, screenHeight: Int,
        ): Boolean {
            val service = instance ?: return true
            if (displayId <= 0 || screenWidth <= 0 || screenHeight <= 0 ||
                x !in 0..1000 || y !in 0..1000) return true
            val px = (screenWidth - 1L) * x / 1000L
            val py = (screenHeight - 1L) * y / 1000L
            val root = service.windowsOnAllDisplays.get(displayId).orEmpty()
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == packageName } ?: return true
            val risky = Regex(
                "(付款|支付|转账|立即购买|确认购买|下单|发送|发布|提交|永久删除|清空|解绑|注销|授权支付|确认交易)",
                RegexOption.IGNORE_CASE,
            )
            var blocked = false
            var visited = 0
            fun checkNode(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 28 || visited++ >= 400) return
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.contains(px.toInt(), py.toInt()) && node.isVisibleToUser) {
                    if (node.isPassword) blocked = true
                    val label = node.text?.toString().orEmpty() + " " +
                        node.contentDescription?.toString().orEmpty()
                    if (risky.containsMatchIn(label)) blocked = true
                    for (i in 0 until node.childCount) checkNode(node.getChild(i), depth + 1)
                }
            }
            checkNode(root, 0)
            return blocked
        }

        private fun safeEditable(node: AccessibilityNodeInfo): Boolean =
            node.isVisibleToUser && node.isEnabled && node.isEditable && !node.isPassword

        private fun searchEditor(node: AccessibilityNodeInfo): Boolean =
            safeEditable(node) && LuluVirtualSearchFieldPolicy.isSearchEditor(
                node.className?.toString(), node.hintText?.toString(),
                node.viewIdResourceName)

        /** Scan only the verified app's actual accessibility tree, never the IME
         * or the owner's display 0. This is a focus recovery, not a text guess. */
        private fun visibleEditors(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val editors = mutableListOf<AccessibilityNodeInfo>()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 28 || visited++ >= 450) return
                if (safeEditable(node)) editors.add(node)
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { visit(it, depth + 1) }
                }
            }
            visit(root, 0)
            return editors
        }

        private fun uniqueSearchEditor(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val editors = visibleEditors(root)
            val searches = editors.filter(::searchEditor)
            return searches.singleOrNull()?.takeIf {
                LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                    searches.size, editors.size - searches.size)
            }
        }

        private fun ownedVirtualRoots(
            service: LuluAccessibilityService, displayId: Int, packageName: String,
        ): List<AccessibilityNodeInfo> = service.windowsOnAllDisplays.get(displayId).orEmpty()
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }.mapNotNull { it.root }
            .filter { it.packageName?.toString() == packageName }

        fun typeOnDisplay(displayId: Int, packageName: String, text: String): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 30 || displayId <= 0 || packageName.isBlank() ||
                text.isEmpty() || text.length > 500) return false
            for (root in ownedVirtualRoots(service, displayId, packageName)) {
                val current = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                // Mika recovers focus on the actual editor. Lulu only does this
                // when there is a single unambiguous search input in the app.
                val field = current?.takeIf(::safeEditable)
                    ?: uniqueSearchEditor(root)?.also {
                        it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    } ?: continue
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            return false
        }

        /** ACTION_SET_TEXT being accepted is not proof the application kept it. */
        fun typedTextReadbackOnDisplay(displayId: Int, packageName: String, text: String): Boolean {
            val service = instance ?: return false
            if (displayId <= 0 || packageName.isBlank() || text.isEmpty()) return false
            return ownedVirtualRoots(service, displayId, packageName).any { root ->
                val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.takeIf(::safeEditable)
                val field = focus ?: uniqueSearchEditor(root)
                field?.text?.toString() == text
            }
        }

        /** Enter may send a message. Only a truly focused search editor on the
         * verified virtual app can authorize it; an ambiguous editor cannot. */
        fun hasFocusedVirtualSearchEditor(displayId: Int, packageName: String): Boolean {
            val service = instance ?: return false
            if (displayId <= 0 || packageName.isBlank()) return false
            return ownedVirtualRoots(service, displayId, packageName).any { root ->
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && safeEditable(focused))
                    return@any searchEditor(focused)
                val unique = uniqueSearchEditor(root) ?: return@any false
                // A field can lose focus when the IME reopens. Restore focus
                // only to a verified unique SEARCH editor; never synthesize Enter
                // unless the device confirms it currently owns input focus.
                unique.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                unique.refresh()
                if (!unique.isFocused) unique.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val refreshed = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: unique.takeIf { it.refresh() && it.isFocused }
                refreshed != null && searchEditor(refreshed)
            }
        }

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

        /** Normalized 0..1000 coordinates for the currently visible display only. */
        fun tapNormalized(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            val metrics = service.resources.displayMetrics
            val path = Path().apply {
                moveTo(LuluPhoneUiGeometry.pixel(x, metrics.widthPixels),
                    LuluPhoneUiGeometry.pixel(y, metrics.heightPixels))
            }
            return service.dispatchGesture(
                GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0, 120)).build(),
                null, null)
        }

        fun swipeNormalized(
            startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long,
        ): Boolean {
            val service = instance ?: return false
            val metrics = service.resources.displayMetrics
            val path = Path().apply {
                moveTo(LuluPhoneUiGeometry.pixel(startX, metrics.widthPixels),
                    LuluPhoneUiGeometry.pixel(startY, metrics.heightPixels))
                lineTo(LuluPhoneUiGeometry.pixel(endX, metrics.widthPixels),
                    LuluPhoneUiGeometry.pixel(endY, metrics.heightPixels))
            }
            return service.dispatchGesture(
                GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0, durationMs)).build(),
                null, null)
        }

        /** No clipboard tricks; only a focused non-password editable node. */
        fun typeIntoFocusedField(value: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            if (!node.isEditable || node.isPassword || !node.isVisibleToUser) return false
            val extras = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, extras)
        }

        fun scrollVisible(direction: String): Boolean {
            val service = instance ?: return false
            val action = when (direction) {
                "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> error("direction应为forward或backward")
            }
            val root = service.rootInActiveWindow ?: return false
            var first: AccessibilityNodeInfo? = null
            fun scan(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 32 || first != null) return
                if (node.isScrollable && node.isVisibleToUser &&
                    node.actionList.any { it.id == action }) {
                    first = node
                    return
                }
                for (i in 0 until node.childCount) scan(node.getChild(i), depth + 1)
            }
            scan(root, 0)
            return first?.performAction(action) == true
        }

        /** Bounded control hints. Password content is never exposed. */
        fun controls(): JSONArray {
            val result = JSONArray()
            val service = instance ?: return result
            val root = service.rootInActiveWindow ?: return result
            val metrics = service.resources.displayMetrics
            var visited = 0
            fun normalize(position: Int, extent: Int): Int =
                if (extent > 0) (position.toLong() * 1000 / extent)
                    .toInt().coerceIn(0, 1000) else 0
            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 30 || result.length() >= 70 || visited++ >= 320) return
                if (node.isVisibleToUser &&
                    (node.isClickable || node.isScrollable || node.isEditable)) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    result.put(JSONObject()
                        .put("text", if (node.isPassword) "" else node.text?.toString()?.take(100).orEmpty())
                        .put("description", if (node.isPassword) "" else node.contentDescription?.toString()?.take(80).orEmpty())
                        .put("bounds1000", JSONArray()
                            .put(normalize(rect.left, metrics.widthPixels))
                            .put(normalize(rect.top, metrics.heightPixels))
                            .put(normalize(rect.right, metrics.widthPixels))
                            .put(normalize(rect.bottom, metrics.heightPixels)))
                        .put("clickable", node.isClickable)
                        .put("scrollable", node.isScrollable)
                        .put("editable", node.isEditable && !node.isPassword))
                }
                for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
            }
            walk(root, 0)
            return result
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
