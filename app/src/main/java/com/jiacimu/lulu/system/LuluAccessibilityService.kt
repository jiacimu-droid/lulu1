package com.jiacimu.lulu.system

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import java.util.concurrent.Executors
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
    private val passiveEventBudget = LuluAccessibilityEventBudget()

    override fun onServiceConnected() {
        passiveEventBudget.reset()
        instance = this
        mutableState.value = mutableState.value.copy(connected = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val source = event ?: return
        // AccessibilityService callbacks run on the application's MAIN
        // thread. Virtual apps can emit dozens of scroll/text/content events
        // per second; traversing the entire tree for every one causes ANRs.
        // Explicit role-owned observeDisplay() and owner screenshot requests
        // are NOT throttled by this passive feed.
        when (source.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> Unit
            else -> return
        }
        val packageName = source.packageName?.toString().orEmpty()
        if (!passiveEventBudget.shouldRead(packageName, SystemClock.uptimeMillis())) return
        val root = rootInActiveWindow ?: return
        // The event may originate from a different virtual display. Never
        // label the owner's main-screen text as that virtual app's content.
        val actualPackage = root.packageName?.toString().orEmpty()
        if (actualPackage.isBlank() || actualPackage != packageName) return
        mutableState.value = AccessibilitySnapshot(
            connected = true,
            packageName = actualPackage,
            windowTitle = source.contentDescription?.toString()?.take(140).orEmpty(),
            visibleText = root.collectVisibleText(maxNodes = 180, maxChars = 3_000),
            capturedAt = Instant.now(),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        passiveEventBudget.reset()
        if (instance === this) instance = null
        mutableState.value = AccessibilitySnapshot()
        super.onDestroy()
    }

    companion object {
        // Screenshot pixel conversion can allocate several MB; never run it on the UI thread.
        private val screenshotCopyExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "lulu-screenshot-copy").apply { isDaemon = true }
        }
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
                        screenshotCopyExecutor, object : TakeScreenshotCallback {
                            override fun onSuccess(result: ScreenshotResult) {
                                try {
                                    val buffer = result.hardwareBuffer
                                    // A cancelled capture still owns the incoming hardware buffer,
                                    // but must not allocate a full-size software bitmap.
                                    if (!cont.isActive) {
                                        buffer.close()
                                        return
                                    }
                                    val pixels = try {
                                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                            ?: error("截图像素不可读取")
                                        try { hardware.copy(Bitmap.Config.ARGB_8888, false)
                                            ?: error("截图像素无法复制") } finally { hardware.recycle() }
                                    } finally { buffer.close() }
                                    if (cont.isActive) {
                                        // Cancellation can win after resume but before the caller receives
                                        // the frame. The continuation owns recycling in that race.
                                        cont.resume(pixels, onCancellation = { pixels.recycle() })
                                    } else pixels.recycle()
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

        /** Reports ONLY the top real window on the character's independent
         * display. IME/assistive overlays are not task windows, but system
         * permission/picker dialogs are barriers even if the same package. */
        fun virtualWindowStatus(displayId: Int, targetPackage: String): JSONObject {
            val service = instance
            fun unknown(reason: String): JSONObject = JSONObject()
                .put("kind", "unknown").put("targetWindowVerified", false)
                .put("windowDiagnostic", reason)
            if (service == null) return unknown("accessibility_disconnected")
            if (Build.VERSION.SDK_INT < 30) return unknown("unsupported_sdk")
            if (displayId <= 0) return unknown("invalid_display")
            if (targetPackage.isBlank()) return unknown("target_unspecified")
            val window = service.windowsOnAllDisplays.get(displayId).orEmpty()
                .filterNot {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY ||
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                }.maxByOrNull { it.layer } ?: return unknown("no_window")
            val root = window.root
            val pkg = root?.packageName?.toString().orEmpty()
            val kind = LuluVirtualWindowPolicy.classify(targetPackage, pkg, window.type)
            val diagnostic = when {
                kind != LuluVirtualWindowPolicy.Kind.UNKNOWN -> ""
                root == null -> "root_unreadable"
                pkg.isBlank() -> "package_unreadable"
                else -> "window_identity_unknown"
            }
            return JSONObject().put("kind", kind.name.lowercase())
                .put("actualPackage", pkg.take(160))
                .put("windowType", window.type)
                .put("windowDiagnostic", diagnostic)
                .put("targetWindowVerified", kind == LuluVirtualWindowPolicy.Kind.TARGET)
        }

        /** Actual orientation-aware virtual display dimensions.
         * Avoid assuming 720×1280 when the target rotates or IME resizes. */
        private fun virtualDimensions(
            service: LuluAccessibilityService, displayId: Int,
        ): Pair<Int, Int>? {
            if (displayId <= 0) return null
            val screen = service.getSystemService(DisplayManager::class.java)
                ?.getDisplay(displayId) ?: return null
            val size = Point()
            screen.getRealSize(size)
            return if (size.x > 0 && size.y > 0) size.x to size.y else null
        }

        /** Set Unicode text ONLY in an editable, non-password node on the owned display. */
        /**
         * Deny tapping labeled irreversible/sensitive controls on the virtual
         * screen. This is a conservative guard, NOT a complete transaction
         * detector; unknown controls remain unverified, never auto-approved.
         */
        fun isSensitiveVirtualTap(
            displayId: Int, packageName: String, x: Int, y: Int,
        ): Boolean {
            val service = instance ?: return true
            val dimensions = virtualDimensions(service, displayId) ?: return true
            if (packageName.isBlank() || observeDisplay(displayId).packageName != packageName ||
                x !in 0..1000 || y !in 0..1000) return true
            val px = (dimensions.first - 1L) * x / 1000L
            val py = (dimensions.second - 1L) * y / 1000L
            val root = service.windowsOnAllDisplays.get(displayId).orEmpty()
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == packageName } ?: return true
            val risky = Regex(
                "(付款|支付|转账|立即购买|确认购买|购买|下单|购物车|加购|去结算|结算|发送|发布|提交|永久删除|清空|解绑|注销|授权支付|确认交易|" +
                    "buy|purchase|shopping.?cart|checkout|payment|pay|place.?order|submit.?order|transfer|send|publish|delete)",
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
                        node.contentDescription?.toString().orEmpty() + " " +
                        node.viewIdResourceName.orEmpty()
                    if (risky.containsMatchIn(label)) blocked = true
                    for (i in 0 until node.childCount) checkNode(node.getChild(i), depth + 1)
                }
            }
            checkNode(root, 0)
            return blocked
        }

        private fun safeEditable(node: AccessibilityNodeInfo): Boolean =
            node.isVisibleToUser && node.isEnabled && !node.isPassword &&
                (node.isEditable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                })

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
                if (depth > 80 || visited++ >= 1600 || node.isPassword) return
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

        /** Mika's descendant-label idea for clickable card containers.
         * Do not traverse private/password nodes or unbounded WebView trees. */
        private fun clickableDescendantLabel(container: AccessibilityNodeInfo): String {
            var inspected = 0
            fun find(node: AccessibilityNodeInfo, depth: Int): String {
                if (depth > 3 || inspected++ > 24 || !node.isVisibleToUser ||
                    node.isPassword) return ""
                if (depth > 0) {
                    val own = node.contentDescription?.toString().orEmpty()
                        .ifBlank { node.text?.toString().orEmpty() }.trim()
                    if (own.isNotBlank()) return own.take(90)
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    val found = find(child, depth + 1)
                    if (found.isNotEmpty()) return found
                }
                return ""
            }
            return find(container, 0)
        }

        private data class VirtualUiNode(
            val node: AccessibilityNodeInfo,
            val ref: String,
            val label: String,
            val resourceId: String,
            val fingerprintPart: String,
            val bounds: Rect,
            val descriptor: JSONObject,
        )

        /** Deterministic inspection of the ACTUAL app window on the separate
         * display. No stored AccessibilityNodeInfo escapes this call. The
         * owner screen, IME and system dialogs never become action targets. */
        private fun virtualUiNodes(
            service: LuluAccessibilityService, displayId: Int, packageName: String,
        ): List<VirtualUiNode> {
            if (displayId <= 0 || packageName.isBlank() ||
                observeDisplay(displayId).packageName != packageName) return emptyList()
            val (width, height) = virtualDimensions(service, displayId) ?: return emptyList()
            val collected = mutableListOf<VirtualUiNode>()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                // Follow Mika: invisible layout wrappers do not hide their visible children.
                // WeChat's real toolbar and editor may be nested much deeper than 25 levels.
                if (depth > 80 || visited++ >= 1600 || node.isPassword) return
                val rect = Rect().also(node::getBoundsInScreen)
                val usable = rect.width() >= 6 && rect.height() >= 6 &&
                    rect.right > 0 && rect.bottom > 0 &&
                    rect.left < width && rect.top < height
                val editable = safeEditable(node)
                val interactive = node.isClickable || node.isScrollable || editable
                if (node.isVisibleToUser && node.isEnabled &&
                    usable && interactive && collected.size < 64) {
                    // Never emit a search box's typed content as a control label.
                    val ownLabel = (if (editable) node.hintText?.toString()
                        ?: node.contentDescription?.toString()
                        else node.contentDescription?.toString()
                            ?: node.text?.toString()).orEmpty().trim()
                    val label = ownLabel.ifBlank {
                        if (node.isClickable && !editable)
                            clickableDescendantLabel(node) else ""
                    }.take(90)
                    val rid = node.viewIdResourceName.orEmpty().substringAfterLast('/').take(70)
                    // Include actual unnamed clickable icons as snapshot evidence.
                    // Tap eligibility remains controlled by LuluVirtualUiPolicy.
                    if (label.isNotBlank() || rid.isNotBlank() || node.isClickable) {
                        val ref = "e" + (collected.size + 1)
                        val x = (rect.centerX().coerceIn(0, width - 1) * 1000L /
                            (width - 1).coerceAtLeast(1)).toInt()
                        val y = (rect.centerY().coerceIn(0, height - 1) * 1000L /
                            (height - 1).coerceAtLeast(1)).toInt()
                        val role = node.className?.toString().orEmpty().substringAfterLast('.').take(60)
                        val descriptor = JSONObject()
                            .put("ref", ref).put("name", label).put("role", role)
                            .put("resourceId", rid).put("x", x).put("y", y)
                            .put("clickable", node.isClickable)
                            .put("editable", editable).put("scrollable", node.isScrollable)
                        val identity = listOf(ref, label, rid, role,
                            rect.flattenToString(), node.isClickable.toString(),
                            node.isEnabled.toString(), node.isFocused.toString(),
                            node.isChecked.toString(), node.isSelected.toString()).joinToString("|")
                        collected.add(VirtualUiNode(node, ref, label, rid, identity, Rect(rect), descriptor))
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
            }
            ownedVirtualRoots(service, displayId, packageName).forEach { visit(it, 0) }
            return collected
        }

        /** A read-only semantic UI reference, bound to the verified target app.
         * Fingerprint changes on node order, geometry, label, state or focus. */
        private fun virtualUiFingerprint(
            displayId: Int, packageName: String, nodes: List<VirtualUiNode>,
        ): String {
            val observed = observeDisplay(displayId)
            if (!observed.connected || observed.packageName != packageName) return ""
            // Mika hashes more than clickable controls. On a scrolling feed,
            // visually identical action buttons can point to a different post:
            // include the actual visible page text in the snapshot identity.
            val parts = nodes.map { it.fingerprintPart } +
                listOf("display:$displayId", "app:$packageName",
                    "page:" + observed.visibleText.take(5_500))
            return LuluVirtualUiPolicy.fingerprint(parts)
        }

        /** Give the character real top-of-conversation text rather than
         * making it guess the recipient from a scaled screenshot. Only this
         * verified virtual WeChat window contributes candidate titles. */
        private fun virtualChatHeaderTexts(
            service: LuluAccessibilityService, displayId: Int, packageName: String,
        ): JSONArray {
            if (packageName != LuluVirtualChatSendPolicy.WECHAT ||
                virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return JSONArray()
            val (_, height) = virtualDimensions(service, displayId) ?: return JSONArray()
            val labels = linkedSetOf<String>()
            // Mika's ScreenReader carries the actual AccessibilityWindowInfo.title.
            // A WeChat custom toolbar may expose its chat title at the WINDOW
            // level while its TextView is missing from the node tree.
            val topWindow = service.windowsOnAllDisplays.get(displayId).orEmpty()
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() == packageName }
                .maxByOrNull { it.layer }
            topWindow?.title?.toString()?.takeIf {
                LuluVirtualChatSendPolicy.windowTitleMatches(it, it.trim())
            }?.let(labels::add)
            LuluVirtualA11yTraversal.forEachVisible(
                roots = listOfNotNull(topWindow?.root),
                childCount = { it.childCount },
                childAt = { node, index -> node.getChild(index) },
                isProtected = { it.isPassword },
                isVisible = { it.isVisibleToUser },
            ) { node ->
                val r = Rect().also(node::getBoundsInScreen)
                if (r.top >= 0 && r.bottom <= height * 3 / 10 &&
                    !node.isEditable && r.width() > 0) {
                    listOf(node.text?.toString(), node.contentDescription?.toString())
                        .filterNotNull().map { it.trim() }
                        .filter { it.length in 1..80 && !it.contains('\\n') }
                        .forEach { labels.add(it) }
                }
            }
            return JSONArray().apply { labels.take(12).forEach { put(it) } }
        }

        fun virtualUiSnapshot(displayId: Int, packageName: String): JSONObject {
            val service = instance ?: return JSONObject()
            val nodes = virtualUiNodes(service, displayId, packageName)
            // A verified app root can expose readable composer text even when
            // WebView/custom icon buttons have no accessible name or view ID.
            // Keep a read-only snapshot token with [] controls rather than
            // misreporting the service as disconnected.
            val fingerprint = virtualUiFingerprint(displayId, packageName, nodes)
            if (fingerprint.isBlank()) return JSONObject()
            val elements = JSONArray()
            nodes.forEach { elements.put(it.descriptor) }
            return JSONObject()
                .put("fingerprint", fingerprint)
                .put("elements", elements)
                .put("chatHeaderTexts", virtualChatHeaderTexts(service, displayId, packageName))
        }

        /** Mika ClickFallback.pickTarget adapted for a single safe FIRST tap.
         * This only selects a candidate: execute still compares fresh tree and
         * uses one ACTION_CLICK; never repeats a possibly successful tap. */
        fun safeVirtualTapTarget(
            displayId: Int, packageName: String, x: Int, y: Int,
        ): JSONObject {
            val service = instance ?: return JSONObject()
            val dimensions = virtualDimensions(service, displayId) ?: return JSONObject()
            if (x !in 0..1000 || y !in 0..1000) return JSONObject()
            val nodes = virtualUiNodes(service, displayId, packageName)
            val fingerprint = virtualUiFingerprint(displayId, packageName, nodes)
            if (fingerprint.isBlank()) return JSONObject()
            val px = ((dimensions.first - 1L) * x / 1000L).toInt()
            val py = ((dimensions.second - 1L) * y / 1000L).toInt()
            val candidate = LuluMikaClickFallback.pickTarget(
                px, py, dimensions.first, dimensions.second, nodes.map { node ->
                    LuluMikaClickFallback.Candidate(
                        item = node.ref,
                        left = node.bounds.left.coerceAtLeast(0),
                        top = node.bounds.top.coerceAtLeast(0),
                        right = node.bounds.right.coerceAtMost(dimensions.first),
                        bottom = node.bounds.bottom.coerceAtMost(dimensions.second),
                        label = node.label,
                        resourceId = node.resourceId,
                        scrollable = node.node.isScrollable,
                        webView = node.node.className?.toString()
                            ?.contains("WebView", ignoreCase = true) == true,
                    )
                }
            )
            return if (candidate.isNullOrBlank()) JSONObject()
                else JSONObject().put("elementRef", candidate)
                    .put("fingerprint", fingerprint)
        }

        /** Click by verified snapshot reference only. No coordinate guessing,
         * repeated click fallback, or untrusted off-display IME clicks. */
        fun tapVirtualUiElement(
            displayId: Int, packageName: String, expectedFingerprint: String,
            elementRef: String, shoppingAllowed: Boolean = false,
        ): Boolean {
            val service = instance ?: return false
            val nodes = virtualUiNodes(service, displayId, packageName)
            if (nodes.isEmpty() ||
                virtualUiFingerprint(displayId, packageName, nodes) != expectedFingerprint)
                return false
            val chosen = nodes.singleOrNull { it.ref == elementRef } ?: return false
            val node = chosen.node
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
                !node.isClickable || node.isPassword) return false
            // A clickable container may have an empty name but dangerous child
            // labels; inspect its descendants before any irreversible tap.
            val content = StringBuilder()
            var visited = 0
            fun labels(n: AccessibilityNodeInfo, depth: Int) {
                if (visited++ >= 55 || depth > 7) return
                if (n.isPassword) { content.append(" password "); return }
                content.append('\n').append(n.text?.toString().orEmpty().take(100))
                content.append('\n').append(n.contentDescription?.toString().orEmpty().take(100))
                for (i in 0 until n.childCount) n.getChild(i)?.let { labels(it, depth + 1) }
            }
            labels(node, 0)
            if (!LuluVirtualUiPolicy.mayClick(
                    chosen.label, chosen.resourceId, content.toString(),
                    shoppingAllowed = shoppingAllowed,
                )) return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        /** Exact live chat-header verification on the owned virtual display.
         * Header evidence is not inferred from the model's screenshot description. */
        private fun virtualChatHeaderMatches(
            service: LuluAccessibilityService, displayId: Int, packageName: String,
            recipient: String, height: Int,
        ): Boolean {
            val width = virtualDimensions(service, displayId)?.first ?: return false
            // Inspect only the top verified WeChat application window. Never
            // read a covered/older chat behind another same-package window.
            val topWindow = service.windowsOnAllDisplays.get(displayId).orEmpty()
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName?.toString() == packageName }
                .maxByOrNull { it.layer } ?: return false
            if (LuluVirtualChatSendPolicy.windowTitleMatches(
                    topWindow.title?.toString(), recipient)) return true
            val titleBounds = mutableListOf<LuluVirtualChatSendPolicy.ComposerBounds>()
            LuluVirtualA11yTraversal.forEachVisible(
                roots = listOfNotNull(topWindow.root),
                childCount = { it.childCount },
                childAt = { node, index -> node.getChild(index) },
                isProtected = { it.isPassword },
                isVisible = { it.isVisibleToUser },
            ) { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                // Keep actual title inside the top toolbar, never chat history.
                if (bounds.top >= 0 && bounds.top <= height / 5 &&
                    bounds.bottom <= height / 3 &&
                    bounds.width() in 1..width &&
                    bounds.height() in 1..(height / 6).coerceAtLeast(1) &&
                    !node.isEditable && LuluVirtualChatSendPolicy.headerMatches(
                        node.text?.toString(), node.contentDescription?.toString(), recipient))
                    titleBounds.add(LuluVirtualChatSendPolicy.ComposerBounds(
                        bounds.left, bounds.top, bounds.right, bounds.bottom))
            }
            // Nested title wrappers are the same physical label; two different
            // visible header positions must remain ambiguous (no send).
            return LuluVirtualChatSendPolicy.uniqueHeaderTitle(titleBounds, height)
        }

        /** WeChat voice-composer recovery is a deliberately narrow navigation
         * action chosen by the role. It never types or clicks a send control.
         * A real press-to-talk element AND a unique small toggle immediately
         * left of it must be witnessed on this exact virtual display. */
        fun switchVirtualChatToText(
            displayId: Int, packageName: String, recipient: String,
            expectedFingerprint: String,
        ): JSONObject {
            fun result(accepted: Boolean, code: String, detail: String,
                clicked: Boolean = false, ready: Boolean = false): JSONObject =
                JSONObject().put("accepted", accepted)
                    .put("actionMethod", "wechat_voice_to_text_accessibility_node")
                    .put("errorCode", code)
                    .put("inputModeSwitchClicked", clicked)
                    .put("inputModeTextVerified", ready)
                    .put("taskCompleted", false).put("verification", detail)
            if (packageName != LuluVirtualChatSendPolicy.WECHAT ||
                !LuluVirtualChatSendPolicy.validRecipient(recipient))
                return result(false, "chat_target_invalid", "未选定合法的微信收件人")
            val service = instance ?: return result(false, "accessibility_disconnected",
                "无障碍服务没有连接")
            if (virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return result(false, "chat_window_unverified",
                    "没有核实此后台屏上的微信窗口，不操作")
            if (expectedFingerprint.isBlank() ||
                virtualUiSnapshot(displayId, packageName).optString("fingerprint") != expectedFingerprint)
                return result(false, "chat_snapshot_expired",
                    "微信页面已改变，请先观察后再选输入模式")
            val dimensions = virtualDimensions(service, displayId)
                ?: return result(false, "chat_display_unavailable", "后台屏实际尺寸不可用")
            if (!virtualChatHeaderMatches(service, displayId, packageName,
                    recipient, dimensions.second))
                return result(false, "chat_recipient_unverified",
                    "微信会话顶部没有唯一对应的收件人，未切换")
            val roots = ownedVirtualRoots(service, displayId, packageName)
            if (roots.flatMap(::visibleEditors).any { editor ->
                    LuluVirtualChatSendPolicy.chatEditorCandidate(
                        editor.className?.toString(), editor.hintText?.toString(),
                        editor.viewIdResourceName) }) {
                return result(true, "", "当前会话已经有可输入文字的编辑框，未重复切换",
                    ready = true)
            }
            val buttons = mutableListOf<AccessibilityNodeInfo>()
            val controls = mutableListOf<LuluVirtualChatSendPolicy.ComposerControl>()
            val voiceRegions = mutableListOf<LuluVirtualChatSendPolicy.ComposerBounds>()
            var visited = 0
            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 80 || visited++ >= 1600 || node.isPassword) return
                val rect = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && node.isEnabled &&
                    rect.width() > 0 && rect.height() > 0) {
                    val label = node.contentDescription?.toString().orEmpty()
                        .ifBlank { node.text?.toString().orEmpty() }
                    if (rect.top >= dimensions.second / 2 &&
                        LuluVirtualChatSendPolicy.isPressToTalk(label)) {
                        voiceRegions.add(LuluVirtualChatSendPolicy.ComposerBounds(
                            rect.left, rect.top, rect.right, rect.bottom))
                    }
                    if (node.isClickable) {
                        buttons.add(node)
                        controls.add(LuluVirtualChatSendPolicy.ComposerControl(
                            LuluVirtualChatSendPolicy.ComposerBounds(
                                rect.left, rect.top, rect.right, rect.bottom),
                            label, node.viewIdResourceName.orEmpty(), true))
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it, depth + 1) }
            }
            roots.forEach { scan(it, 0) }
            val voice = voiceRegions.distinct().singleOrNull()
                ?: return result(false, "chat_voice_composer_unverified",
                    "未找到唯一的底部按住说话输入区，不猜测按钮位置")
            val index = LuluVirtualChatSendPolicy.voiceToTextControl(
                voice, controls, dimensions.first, dimensions.second)
                ?: return result(false, "chat_keyboard_toggle_unverified",
                    "微信文字切换控件不可唯一核实；未触碰其他图标")
            val button = buttons[index]
            if (!button.refresh() || !button.isVisibleToUser || !button.isEnabled ||
                !button.isClickable)
                return result(false, "chat_keyboard_toggle_expired",
                    "文字切换控件已失效，未点击")
            // The final gate runs again AFTER scanning, because a system dialog
            // or another chat may replace the target while the tree is read.
            if (virtualWindowStatus(displayId, packageName).optString("kind") != "target" ||
                !virtualChatHeaderMatches(service, displayId, packageName,
                    recipient, dimensions.second))
                return result(false, "chat_changed_before_toggle",
                    "聊天界面发生变化，没有点击")
            val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return result(clicked, if (clicked) "" else "chat_keyboard_toggle_rejected",
                if (clicked) "已在核实的微信聊天界面点击一次文字模式图标；需重新观察确认输入框"
                else "微信没有接受文字模式切换，未尝试第二次点击",
                clicked = clicked)
        }

        /** A live, read-only editor check for the exact WeChat conversation.
         * Never use the owner's display or general search editor evidence. */
        fun virtualChatTextInputVisible(
            displayId: Int, packageName: String, recipient: String,
        ): Boolean {
            val service = instance ?: return false
            if (packageName != LuluVirtualChatSendPolicy.WECHAT ||
                !LuluVirtualChatSendPolicy.validRecipient(recipient) ||
                virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return false
            val dimensions = virtualDimensions(service, displayId) ?: return false
            if (!virtualChatHeaderMatches(service, displayId, packageName,
                    recipient, dimensions.second)) return false
            val editors = ownedVirtualRoots(service, displayId, packageName)
                .flatMap(::visibleEditors).distinctBy { node ->
                    val rect = Rect().also(node::getBoundsInScreen)
                    Triple(rect.centerX(), rect.centerY(), node.viewIdResourceName)
                }
            return editors.size == 1 && safeEditable(editors.single()) &&
                LuluVirtualChatSendPolicy.chatEditorCandidate(
                    editors.single().className?.toString(),
                    editors.single().hintText?.toString(),
                    editors.single().viewIdResourceName)
        }

        /** Resolve the role's explicit BACK request on the OWNED virtual
         * app page. Many mini-apps (such as an orders WebView) have an in-page
         * arrow but ignore Android's system edge-back gesture.
         *
         * Only one live, top-left toolbar node is clicked. An unnamed node is
         * considered solely if the real tree also has the adjacent wide
         * header/search control; with no such evidence, caller can instead
         * dispatch ONE traditional edge gesture. This never touches display 0,
         * never injects shell input, and never declares that navigation worked.
         */
        fun tapVirtualAppBackControl(
            displayId: Int, packageName: String, expectedFingerprint: String,
        ): JSONObject {
            fun receipt(attempted: Boolean, accepted: Boolean,
                code: String, detail: String): JSONObject = JSONObject()
                .put("attempted", attempted).put("accepted", accepted)
                .put("actionMethod", "accessibility_app_back_node")
                .put("errorCode", code).put("taskCompleted", false)
                .put("verification", detail)
            val service = instance ?: return receipt(true, false,
                "accessibility_disconnected", "无障碍未连接，没有操作虚拟屏")
            if (virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return receipt(true, false, "virtual_back_window_unverified",
                    "独立屏不是目标App窗口，没有点击返回或发送全局返回")
            val dimensions = virtualDimensions(service, displayId)
                ?: return receipt(true, false, "virtual_back_dimensions_missing",
                    "后台屏幕尺寸不可用，没有猜测按钮位置")
            if (expectedFingerprint.isBlank() ||
                virtualUiSnapshot(displayId, packageName).optString("fingerprint") != expectedFingerprint)
                return receipt(true, false, "virtual_back_snapshot_changed",
                    "后台App页面已改变，旧的返回目标无效；先重新观察")
            val buttons = mutableListOf<AccessibilityNodeInfo>()
            val hints = mutableListOf<LuluVirtualUiPolicy.AppBarControl>()
            var visited = 0
            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 26 || visited++ >= 620 ||
                    !node.isVisibleToUser || !node.isEnabled || node.isPassword) return
                val rect = Rect().also(node::getBoundsInScreen)
                if (rect.width() > 0 && rect.height() > 0 && node.isClickable) {
                    val label = node.contentDescription?.toString().orEmpty()
                        .ifBlank { node.text?.toString().orEmpty() }
                        .ifBlank { clickableDescendantLabel(node) }.trim().take(90)
                    hints.add(LuluVirtualUiPolicy.AppBarControl(
                        rect.left, rect.top, rect.right, rect.bottom, label,
                        node.viewIdResourceName.orEmpty(), clickable = true))
                    buttons.add(node)
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { scan(it, depth + 1) }
            }
            ownedVirtualRoots(service, displayId, packageName).forEach { scan(it, 0) }
            val selected = LuluVirtualUiPolicy.appBackControlIndex(
                dimensions.first, dimensions.second, hints)
                ?: return receipt(false, false, "virtual_app_back_control_missing",
                    "当前页面没有唯一可核实的左上角返回按钮；可尝试一次独立屏边缘返回")
            val node = buttons[selected]
            if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled ||
                !node.isClickable || node.isPassword)
                return receipt(true, false, "virtual_app_back_control_expired",
                    "返回按钮失效，未点击，也不会盲目补发边缘手势")
            if (virtualWindowStatus(displayId, packageName).optString("kind") != "target" ||
                virtualUiSnapshot(displayId, packageName).optString("fingerprint") != expectedFingerprint)
                return receipt(true, false, "virtual_app_back_page_changed",
                    "返回前页面已变化，拒绝点击旧目标")
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return receipt(true, clicked,
                if (clicked) "" else "virtual_app_back_click_rejected",
                if (clicked) "已单次点击目标App左上角实际返回控件；需核对新页面才算返回成功"
                else "目标App拒绝了左上角返回控件的点击；未重复点击或触碰主屏")
        }

        /** Count only outgoing-side text bubbles currently visible on the
         * owned virtual conversation screen. Used to distinguish an accepted
         * Send click from a new client-side message appearance. A count is NOT
         * server delivery confirmation and must never trigger another click. */
        fun virtualChatOutgoingTextCount(
            displayId: Int, packageName: String, recipient: String, message: String,
        ): Int {
            val service = instance ?: return -1
            if (packageName != LuluVirtualChatSendPolicy.WECHAT ||
                !LuluVirtualChatSendPolicy.validRecipient(recipient) ||
                virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return -1
            val dimensions = virtualDimensions(service, displayId) ?: return -1
            if (!virtualChatHeaderMatches(
                    service, displayId, packageName, recipient, dimensions.second)) return -1
            val positions = mutableListOf<Rect>()
            var scanned = 0
            fun scan(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 80 || scanned++ >= 1600 || node.isPassword) return
                val rect = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && !node.isEditable && node.text?.toString() == message &&
                    rect.top >= dimensions.second / 10 &&
                    rect.bottom <= dimensions.second * 9 / 10 &&
                    rect.right > dimensions.first * 3 / 5 &&
                    rect.width() > 0 && rect.height() > 0) positions.add(rect)
                for (i in 0 until node.childCount)
                    node.getChild(i)?.let { scan(it, depth + 1) }
            }
            ownedVirtualRoots(service, displayId, packageName).forEach { scan(it, 0) }
            return positions.distinctBy { it.centerX() / 12 to it.centerY() / 12 }.size
        }

        /** An explicit, owner-authorized WeChat send is a separate atomic
         * action, never a search Enter or a generic permissive tap. This makes
         * ONE attempt after verifying the actual conversation and readback.
         * ACTION_CLICK acceptance does not establish server delivery. */
        fun sendVirtualChatMessage(
            displayId: Int, packageName: String, recipient: String, message: String,
            expectedFingerprint: String, stillAuthorized: () -> Boolean,
        ): JSONObject {
            fun denied(reason: String): JSONObject = JSONObject()
                .put("accepted", false).put("messageSendClicked", false)
                .put("errorCode", reason).put("taskCompleted", false)
            if (packageName != LuluVirtualChatSendPolicy.WECHAT ||
                !LuluVirtualChatSendPolicy.validRecipient(recipient) ||
                !LuluVirtualChatSendPolicy.validMessage(message) || !stillAuthorized())
                return denied("chat_send_not_authorized")
            val service = instance ?: return denied("accessibility_disconnected")
            if (virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return denied("chat_window_unverified")
            val nodes = virtualUiNodes(service, displayId, packageName)
            if (expectedFingerprint.isBlank() ||
                virtualUiFingerprint(displayId, packageName, nodes) != expectedFingerprint)
                return denied("chat_snapshot_expired")
            val dimensions = virtualDimensions(service, displayId)
                ?: return denied("chat_display_unavailable")
            val roots = ownedVirtualRoots(service, displayId, packageName)
            fun uniqueRecipientInHeader(): Boolean = virtualChatHeaderMatches(
                service, displayId, packageName, recipient, dimensions.second)
            if (!uniqueRecipientInHeader()) return denied("chat_recipient_unverified")
                .put("observedChatHeaderTexts",
                    virtualChatHeaderTexts(service, displayId, packageName))
                .put("targetWindowVerified", true)
            val editors = roots.flatMap(::visibleEditors).distinctBy { node ->
                val rect = Rect().also(node::getBoundsInScreen)
                Triple(rect.centerX(), rect.centerY(), node.viewIdResourceName)
            }
            if (editors.size != 1) return denied(
                if (roots.any { root ->
                        root.collectVisibleText(maxNodes = 460, maxChars = 3_000)
                            .lineSequence().any(LuluVirtualChatSendPolicy::isPressToTalk)
                    }) "chat_voice_mode_requires_text_switch" else "chat_editor_ambiguous"
            )
            val editor = editors.single()
            if (!LuluVirtualChatSendPolicy.chatEditorCandidate(
                    editor.className?.toString(), editor.hintText?.toString(),
                    editor.viewIdResourceName) ||
                !safeEditable(editor)) return denied("chat_editor_unverified")
            val input = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
            }
            if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input))
                return denied("chat_input_rejected")
            // A failed readback leaves only an unsent draft; never click Send.
            val readback = if (editor.refresh() && editor.text?.toString() == message) true
                else ownedVirtualRoots(service, displayId, packageName)
                    .flatMap(::visibleEditors)
                    .filter { LuluVirtualChatSendPolicy.chatEditorCandidate(
                        it.className?.toString(), it.hintText?.toString(),
                        it.viewIdResourceName) }
                    .distinctBy { node ->
                        val rect = Rect().also(node::getBoundsInScreen)
                        rect.centerX() to rect.centerY()
                    }.singleOrNull()?.text?.toString() == message
            if (!readback)
                return denied("chat_input_unverified")
                    .put("messageInputReadbackVerified", false)
            if (!stillAuthorized() ||
                virtualWindowStatus(displayId, packageName).optString("kind") != "target" ||
                !uniqueRecipientInHeader())
                return denied("chat_changed_before_send")
                    .put("messageInputReadbackVerified", true)
            // The Send control can appear only AFTER text was entered. Locate
            // it again in the real app, not by stale reference or coordinate.
            // The generic read-only UI inventory exposes at most 40 nodes.
            // Long chat histories can fill that quota before the bottom send
            // button, so inspect the live target tree directly here.
            val editorBounds = Rect().also(editor::getBoundsInScreen)
            val composer = LuluVirtualChatSendPolicy.ComposerBounds(
                editorBounds.left, editorBounds.top, editorBounds.right, editorBounds.bottom)
            val buttons = mutableListOf<AccessibilityNodeInfo>()
            val buttonBounds = mutableListOf<LuluVirtualChatSendPolicy.ComposerBounds>()
            var inspected = 0
            fun locate(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 80 || inspected++ >= 1600 || node.isPassword) return
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && node.isEnabled &&
                    node.isClickable && bounds.centerY() > dimensions.second / 2) {
                    val label = node.contentDescription?.toString().orEmpty()
                        .ifBlank { node.text?.toString().orEmpty() }
                        .ifBlank { clickableDescendantLabel(node) }
                    val control = LuluVirtualChatSendPolicy.ComposerBounds(
                        bounds.left, bounds.top, bounds.right, bounds.bottom)
                    if (LuluVirtualChatSendPolicy.sendButton(label, node.viewIdResourceName) &&
                        LuluVirtualChatSendPolicy.sendControlNearEditor(
                            control, composer, dimensions.first, dimensions.second)) {
                        buttons.add(node)
                        buttonBounds.add(control)
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { locate(it, depth + 1) }
                }
            }
            ownedVirtualRoots(service, displayId, packageName).forEach { locate(it, 0) }
            val sendTarget = LuluVirtualChatSendPolicy.uniqueSendTarget(
                buttonBounds, dimensions.first, dimensions.second)
            if (sendTarget == null || !stillAuthorized() ||
                virtualWindowStatus(displayId, packageName).optString("kind") != "target" ||
                !uniqueRecipientInHeader())
                return denied("chat_send_button_or_context_unverified")
                    .put("messageInputReadbackVerified", true)
            val button = buttons[sendTarget]
            if (!button.refresh() || !button.isClickable || !button.isEnabled ||
                !button.isVisibleToUser)
                return denied("chat_send_button_expired")
                    .put("messageInputReadbackVerified", true)
            // Measure the current outbox before the irreversible click. A
            // newly visible bubble after the click is evidence of client UI
            // submission, never proof of remote/server delivery.
            val outgoingBefore = virtualChatOutgoingTextCount(
                displayId, packageName, recipient, message)
            // One click only; never retry an uncertain external side effect.
            val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return JSONObject().put("accepted", clicked)
                .put("messageInputReadbackVerified", true)
                .put("chatRecipientVerified", true)
                .put("messageSendClicked", clicked)
                .put("outgoingVisibleBefore", outgoingBefore)
                .put("messageDeliveryVerified", false)
                .put("taskCompleted", false)
                .put("errorCode", if (clicked) "" else "chat_send_click_unverified")
        }

        /** Mika-style target discovery based on the verified virtual app.
         * Only editor metadata/geometry, never entered text or display 0. */
        fun virtualSearchFieldStatus(displayId: Int, packageName: String): JSONObject {
            val result = JSONObject().put("uniqueSearchField", false)
                .put("focusedSearchField", false).put("searchEditorCount", 0)
            val service = instance ?: return result
            if (displayId <= 0 || packageName.isBlank()) return result
            val roots = ownedVirtualRoots(service, displayId, packageName)
            val editors = roots.flatMap(::visibleEditors)
            val searches = editors.filter(::searchEditor)
            val unique = LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                searches.size, editors.size - searches.size)
            result.put("searchEditorCount", searches.size)
                .put("uniqueSearchField", unique)
            if (unique) {
                val target = searches.single()
                result.put("focusedSearchField", roots.any { root ->
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::searchEditor) == true
                })
                val rect = Rect().also(target::getBoundsInScreen)
                if (rect.width() > 0 && rect.height() > 0) {
                    val size = virtualDimensions(service, displayId)
                    if (size != null) {
                        result.put("centerX", (rect.centerX().coerceIn(0, size.first - 1) *
                            1000L / (size.first - 1).coerceAtLeast(1)).toInt())
                            .put("centerY", (rect.centerY().coerceIn(0, size.second - 1) *
                                1000L / (size.second - 1).coerceAtLeast(1)).toInt())
                    }
                }
                result.put("hint", target.hintText?.toString().orEmpty().take(90))
            }
            return result
        }

        /** Semantic focus: only when exactly one safe search editor exists. */
        fun focusUniqueVirtualSearchEditor(displayId: Int, packageName: String): Boolean {
            val service = instance ?: return false
            if (displayId <= 0 || packageName.isBlank()) return false
            val roots = ownedVirtualRoots(service, displayId, packageName)
            val editors = roots.flatMap(::visibleEditors)
            val searches = editors.filter(::searchEditor)
            if (!LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                    searches.size, editors.size - searches.size)) return false
            val target = searches.single()
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.refresh()
            if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return ownedVirtualRoots(service, displayId, packageName).any { root ->
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::searchEditor) == true
            }
        }

        /** Explicit human-only field detection on this verified virtual App.
         * Password contents are never accessed or forwarded. */
        fun virtualHasPasswordField(displayId: Int, packageName: String): Boolean {
            val service = instance ?: return false
            if (displayId <= 0 || packageName.isBlank() ||
                observeDisplay(displayId).packageName != packageName) return false
            var inspected = 0
            fun hasPassword(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 24 || inspected++ > 450 || !node.isVisibleToUser) return false
                if (node.isPassword) return true
                return (0 until node.childCount).any { index ->
                    node.getChild(index)?.let { hasPassword(it, depth + 1) } == true
                }
            }
            return ownedVirtualRoots(service, displayId, packageName).any { hasPassword(it, 0) }
        }

        /** The only text the local type verifier can read: one focused,
         * owner-verified search field. Never put this text in UI node metadata. */
        fun readVirtualSearchFieldText(displayId: Int, packageName: String): String? {
            val service = instance ?: return null
            if (Build.VERSION.SDK_INT < 30 || displayId <= 0 || packageName.isBlank() ||
                observeDisplay(displayId).packageName != packageName) return null
            val roots = ownedVirtualRoots(service, displayId, packageName)
            val editors = roots.flatMap(::visibleEditors)
            val searches = editors.filter(::searchEditor)
            if (!LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                    searches.size, editors.size - searches.size)) return null
            val field = searches.single()
            if (!field.refresh() || !field.isFocused || !safeEditable(field))
                return null
            return if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        }

        /** Never type in an arbitrary focused editor on the virtual phone.
         * IMEs can shift focus to a comment/message box even after a search
         * attempt. Only ONE recognisable safe search input may receive text.
         * This also prevents a false readback from a different field. */
        fun typeOnDisplay(displayId: Int, packageName: String, text: String): Boolean {
            val service = instance ?: return false
            if (Build.VERSION.SDK_INT < 30 || displayId <= 0 || packageName.isBlank() ||
                text.length > 500) return false
            val roots = ownedVirtualRoots(service, displayId, packageName)
            if (roots.isEmpty() || observeDisplay(displayId).packageName != packageName)
                return false
            val editors = roots.flatMap(::visibleEditors)
            val searches = editors.filter(::searchEditor)
            if (!LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                    searches.size, editors.size - searches.size)) return false
            val field = searches.single()
            val currentlyFocused = roots.mapNotNull {
                it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            }.firstOrNull(::safeEditable)
            if (currentlyFocused != null && !searchEditor(currentlyFocused)) return false
            if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!field.refresh() || !safeEditable(field) || !searchEditor(field) ||
                !field.isFocused) return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        /** Readback is from that verified search field, not an unrelated
         * focused text input. Nothing is copied into the model's node list. */
        fun typedTextReadbackOnDisplay(displayId: Int, packageName: String, text: String): Boolean {
            val service = instance ?: return false
            if (displayId <= 0 || packageName.isBlank() || text.isEmpty() ||
                observeDisplay(displayId).packageName != packageName) return false
            val roots = ownedVirtualRoots(service, displayId, packageName)
            val editors = roots.flatMap(::visibleEditors)
            val searches = editors.filter(::searchEditor)
            return LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                searches.size, editors.size - searches.size) &&
                searches.singleOrNull()?.let { field ->
                    field.refresh() && field.isFocused && field.text?.toString() == text
                } == true
        }

        /** Enter may send a message. Only a truly focused search editor on the
         * verified virtual app can authorize it; an ambiguous editor cannot. */
        fun hasFocusedVirtualSearchEditor(displayId: Int, packageName: String): Boolean {
            // Enter is never authorized by silently re-focusing another field.
            val status = virtualSearchFieldStatus(displayId, packageName)
            return status.optBoolean("uniqueSearchField") &&
                status.optBoolean("focusedSearchField")
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

        /**
         * Mika ScreenAccessibility's official per-display gesture route.
         * Shell input is NOT the authorization authority for a role-owned swipe.
         * Only the verified target window on an independent display can receive
         * a gesture; no fallback to the owner's main screen is permitted.
         *
         * An Android onCompleted callback confirms gesture dispatch/completion,
         * not that the page actually scrolled. The session separately compares
         * fresh accessibility observations and image frames.
         */
        suspend fun swipeVirtualDisplay(
            displayId: Int, packageName: String,
            startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int,
        ): JSONObject {
            fun receipt(accepted: Boolean, errorCode: String, detail: String,
                dispatched: Boolean = false) = JSONObject()
                .put("accepted", accepted)
                .put("actionMethod", "accessibility_gesture_virtual_display")
                .put("gestureDispatched", dispatched)
                .put("gestureCompleted", accepted)
                .put("errorCode", errorCode)
                .put("taskCompleted", false)
                .put("verification", detail)

            val service = instance ?: return receipt(false, "accessibility_disconnected",
                "无障碍服务没有连接，本次没有向任何屏幕下发滑动")
            if (Build.VERSION.SDK_INT < 30 || displayId <= 0 || packageName.isBlank())
                return receipt(false, "virtual_gesture_display_invalid",
                    "Android版本或虚拟屏幕ID不支持指定屏手势，未滑动")
            val gate = virtualWindowStatus(displayId, packageName)
            if (!LuluVirtualWindowPolicy.allowsGestureOnDisplay(
                    displayId, gate.optString("kind")))
                return receipt(false, "virtual_gesture_window_unverified",
                    "滑动前虚拟屏的目标App窗口未核实，未触碰其他屏幕")
            val dimensions = virtualDimensions(service, displayId)
                ?: return receipt(false, "virtual_gesture_dimensions_missing",
                    "虚拟屏幕的实际尺寸无法核实，未滑动")
            val path = Path().apply {
                moveTo(LuluPhoneUiGeometry.pixel(startX, dimensions.first),
                    LuluPhoneUiGeometry.pixel(startY, dimensions.second))
                lineTo(LuluPhoneUiGeometry.pixel(endX, dimensions.first),
                    LuluPhoneUiGeometry.pixel(endY, dimensions.second))
            }
            val gesture = GestureDescription.Builder()
                .setDisplayId(displayId)
                .addStroke(GestureDescription.StrokeDescription(
                    path, 0, durationMs.coerceIn(180, 1600).toLong()))
                .build()
            return kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine<JSONObject> { cont ->
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(description: GestureDescription) {
                            if (cont.isActive) cont.resume(receipt(true, "",
                                "Android已完成独立虚拟屏上的手势，仍需核对页面是否真的滚动", true))
                        }
                        override fun onCancelled(description: GestureDescription) {
                            if (cont.isActive) cont.resume(receipt(false, "virtual_gesture_cancelled",
                                "Android取消了这次虚拟屏手势，结果未知；不自动重复滑动", true))
                        }
                    }
                    val sent = runCatching {
                        service.dispatchGesture(gesture, callback, Handler(Looper.getMainLooper()))
                    }.getOrDefault(false)
                    if (!sent && cont.isActive) cont.resume(receipt(false,
                        "virtual_gesture_dispatch_rejected",
                        "Android未接收虚拟屏手势，未使用主屏幕或Shell输入兜底"))
                }
            } ?: receipt(false, "virtual_gesture_timeout",
                "虚拟屏手势回执超时，可能已有局部滑动；先核对新画面，禁止盲目补发", true)
        }

        /**
         * A single stationary gesture on the OWNED virtual display. Never use
         * shell "input" or the active-display tapNormalized() fallback: neither
         * guarantees the owner's display 0 will remain untouched.
         */
        suspend fun tapVirtualDisplay(
            displayId: Int, packageName: String, x: Int, y: Int,
        ): JSONObject = swipeVirtualDisplay(
            displayId, packageName, x, y, x, y, 180,
        ).put("actionMethod", "accessibility_tap_virtual_display")

        /**
         * Only a focused search editor in this app may submit IME_ENTER.
         * Android API 30+ exposes this action on editable focused nodes.
         * Never fall back to shell keyevents: those fail with INJECT_EVENTS on
         * some devices and can target an unrelated focused screen.
         */
        fun submitVirtualSearch(displayId: Int, packageName: String): JSONObject {
            fun receipt(accepted: Boolean, code: String, detail: String) = JSONObject()
                .put("accepted", accepted)
                .put("displayId", displayId)
                .put("actionMethod", "accessibility_ime_enter_virtual_search")
                .put("errorCode", code)
                .put("taskCompleted", false)
                .put("verification", detail)
            val service = instance ?: return receipt(false, "accessibility_disconnected",
                "无障碍服务未连接；未向任何屏幕提交搜索")
            if (displayId <= 0 || Build.VERSION.SDK_INT < 30 ||
                virtualWindowStatus(displayId, packageName).optString("kind") != "target")
                return receipt(false, "search_window_unverified",
                    "独立虚拟屏的搜索窗口未核实，未提交")
            val roots = ownedVirtualRoots(service, displayId, packageName)
            val editors = roots.flatMap(::visibleEditors)
            val searches = editors.filter(::searchEditor)
            if (!LuluVirtualSearchFieldPolicy.canRecoverUniqueSearchField(
                    searches.size, editors.size - searches.size))
                return receipt(false, "search_focus_unverified", "没有唯一安全搜索框，未提交")
            val field = searches.single()
            if (!field.refresh() || !field.isFocused || !safeEditable(field))
                return receipt(false, "search_focus_unverified", "搜索框已失去焦点，未提交")
            if (field.actionList.none {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                })
                return receipt(false, "search_ime_action_missing",
                    "此应用搜索框没有暴露无障碍回车动作；可观察搜索按钮后点击，未发送输入事件")
            val sent = field.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return receipt(sent, if (sent) "" else "search_ime_action_rejected",
                if (sent) "已向独立屏搜索框提交一次IME动作；仍需观察结果页面"
                else "应用未接受搜索框的IME动作；未在主屏或Shell兜底重试")
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

/** Hard cap both node visits AND emitted text. Some WebViews and feed
 * pages expose thousands of unlabelled nodes: a limit on the number of unique
 * text lines alone does not bound main-thread scanning or allocations. */
private fun AccessibilityNodeInfo?.collectVisibleText(
    maxNodes: Int = 420, maxChars: Int = 6_000,
): String {
    val root = this ?: return ""
    val lines = LinkedHashSet<String>()
    var visited = 0
    var characters = 0
    fun add(value: CharSequence?) {
        if (value == null || characters >= maxChars) return
        // Avoid materializing multi-megabyte Spannable/web text attributes.
        val segment = value.subSequence(0, minOf(value.length, maxChars - characters + 1))
            .toString().trim()
        if (segment.isNotEmpty() && lines.add(segment)) {
            characters += segment.length + 1
        }
    }
    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 24 || visited++ >= maxNodes ||
            characters >= maxChars) return
        if (!node.isVisibleToUser) return
        add(node.text)
        add(node.contentDescription)
        for (index in 0 until node.childCount) {
            if (visited >= maxNodes || characters >= maxChars) break
            walk(node.getChild(index), depth + 1)
        }
    }
    walk(root, 0)
    return lines.joinToString("\n").take(maxChars)
}
