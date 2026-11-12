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
                if (depth > 25 || visited++ >= 460) return
                if (!node.isVisibleToUser || !node.isEnabled || node.isPassword) return
                val rect = Rect().also(node::getBoundsInScreen)
                val usable = rect.width() >= 6 && rect.height() >= 6 &&
                    rect.right > 0 && rect.bottom > 0 &&
                    rect.left < width && rect.top < height
                val editable = safeEditable(node)
                val interactive = node.isClickable || node.isScrollable || editable
                if (usable && interactive && collected.size < 40) {
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
                    if (label.isNotBlank() || rid.isNotBlank()) {
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
            if (nodes.isEmpty()) return ""
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

        fun virtualUiSnapshot(displayId: Int, packageName: String): JSONObject {
            val service = instance ?: return JSONObject()
            val nodes = virtualUiNodes(service, displayId, packageName)
            if (nodes.isEmpty()) return JSONObject()
            val fingerprint = virtualUiFingerprint(displayId, packageName, nodes)
            if (fingerprint.isBlank()) return JSONObject()
            val elements = JSONArray()
            nodes.forEach { elements.put(it.descriptor) }
            return JSONObject()
                .put("fingerprint", fingerprint)
                .put("elements", elements)
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
            elementRef: String,
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
            if (!LuluVirtualUiPolicy.mayClick(chosen.label, chosen.resourceId, content.toString()))
                return false
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
