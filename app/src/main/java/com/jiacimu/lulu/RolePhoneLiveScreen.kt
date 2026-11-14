package com.jiacimu.lulu

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jiacimu.lulu.system.LuluBackgroundPhoneSession
import com.jiacimu.lulu.system.LuluScreenAwareness
import com.jiacimu.lulu.data.CompanionActionRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Base64

private val PhoneInk = Color(0xFF1D1D1F)
private val PhoneMuted = Color(0xFF73767C)
private val PhoneBorder = Color(0xFFE7E8EB)
private val PhoneCanvas = Color(0xFFF7F8FA)
private val ShortClock = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Display labels are presentation only, never used to select apps or determine permissions. */
internal fun inspectedRunningAppName(packageName: String): String = when (packageName) {
    "com.xingin.xhs" -> "小红书"
    "com.ss.android.ugc.aweme" -> "抖音"
    "com.tencent.mm" -> "微信"
    "com.tencent.mobileqq" -> "QQ"
    "com.android.chrome" -> "Chrome"
    else -> "后台应用"
}

private data class InspectedLivePicture(
    val frame: LuluBackgroundPhoneSession.OwnerPreviewFrame,
    val bitmap: ImageBitmap,
    val checkedAt: String,
)

private suspend fun loadInspectedPicture(
    context: android.content.Context, roleId: String,
): InspectedLivePicture {
    // The source is ALWAYS the owned displayId>0 frame, with before/after
    // package verification in ownerPreview. This must never fall back to
    // capture_screen or any model-synthesized screenshot.
    val frame = withContext(Dispatchers.IO) {
        LuluBackgroundPhoneSession.ownerPreview(context, roleId)
    }
    val image = withContext(Dispatchers.Default) {
        val encoded = frame.imageDataUrl.removePrefix("data:image/jpeg;base64,")
        val bytes = Base64.getDecoder().decode(encoded)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            ?: error("后台屏幕截图无法解码")
    }
    return InspectedLivePicture(frame, image, LocalTime.now().format(ShortClock))
}

/**
 * The first screen of 查手机 is already the owner's inspection surface:
 * make the real device status and last observed frame discoverable with one
 * glance. Opening the card navigates to the actual readable screen.
 *
 * A single on-entry screenshot is performed without AI/looping. The role's
 * choice of whether to share a picture is completely separate.
 */
@Composable
internal fun RolePhoneLiveEntry(roleId: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    var picture by remember(roleId) { mutableStateOf<InspectedLivePicture?>(null) }
    var busy by remember(roleId) { mutableStateOf(true) }
    var unavailable by remember(roleId) { mutableStateOf(false) }
    LaunchedEffect(roleId) {
        busy = true
        picture = try {
            if (roleId.isBlank() || roleId.startsWith("resident:")) null
            else loadInspectedPicture(context, roleId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        unavailable = picture == null
        busy = false
    }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(
                Modifier.width(64.dp).height(82.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1D21)),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    Image(picture!!.bitmap, contentDescription = "角色当前手机画面缩略图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit)
                } else Icon(Icons.Outlined.PhoneAndroid, null,
                    tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(27.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("正在使用", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        picture != null -> inspectedRunningAppName(picture!!.frame.packageName)
                        busy -> "正在查看…"
                        unavailable -> "暂时没有画面"
                        else -> "没有画面"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (picture != null) Text("最近更新 " + picture!!.checkedAt.substringBeforeLast(':'),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = "打开手机画面",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Owner's full-screen inspection route. No control/interaction on the virtual
 * device; refresh and save act on verified pixels only. The 3s auto-refresh is
 * opt-in, lifecycle bound and paused while zooming/looking elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RolePhoneLiveScreen(roleId: String, roleName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(
            androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> foreground = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP,
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var picture by remember(roleId) { mutableStateOf<InspectedLivePicture?>(null) }
    var status by remember(roleId) { mutableStateOf("正在取得真正的后台屏画面…") }
    var busy by remember(roleId) { mutableStateOf(false) }
    var saving by remember(roleId) { mutableStateOf(false) }
    var autoRefresh by remember(roleId) { mutableStateOf(false) }
    var zoomOpen by remember(roleId) { mutableStateOf(false) }
    var paused by remember(roleId) {
        mutableStateOf(LuluBackgroundPhoneSession.ownerHasPaused(context))
    }
    var testing by remember(roleId) { mutableStateOf(false) }
    var testDialog by remember(roleId) { mutableStateOf(false) }
    var testName by remember(roleId) { mutableStateOf("") }


    suspend fun refresh() {
        if (busy || saving) return
        paused = LuluBackgroundPhoneSession.ownerHasPaused(context)
        busy = true
        try {
            picture = loadInspectedPicture(context, roleId)
            status = "已更新 · ${picture!!.checkedAt}"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // Do not keep showing a stale or excluded app after consent or
            // display ownership changes: drop the image on *any* error.
            picture = null
            autoRefresh = false
            status = failure.message.orEmpty().take(180).ifBlank { "暂时无法查看后台画面" }
        } finally { busy = false }
    }

    LaunchedEffect(roleId) { refresh() }
    LaunchedEffect(roleId, foreground, autoRefresh, zoomOpen) {
        if (autoRefresh && foreground && !zoomOpen) {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(3_000)
                refresh()
            }
        }
    }

    suspend fun saveCurrent() {
        val selected = picture ?: return
        saving = true
        try {
            // Validate current source and owner permission AGAIN before
            // sending the deliberately selected frame. If the virtual display
            // was switched/revoked, the old frame is never shared.
            val verifiedNow = withContext(Dispatchers.IO) {
                LuluBackgroundPhoneSession.ownerPreview(context, roleId)
            }
            check(verifiedNow.displayId == selected.frame.displayId &&
                verifiedNow.packageName == selected.frame.packageName &&
                !LuluBackgroundPhoneSession.ownerHasPaused(context) &&
                LuluScreenAwareness.onDemandAllowed(context) &&
                !LuluScreenAwareness.isExcluded(context, selected.frame.packageName)) {
                "后台应用或查看权限已改变，请重新获取画面"
            }
            val result = withContext(Dispatchers.Default) {
                val raw = Base64.getDecoder().decode(
                    selected.frame.imageDataUrl.removePrefix("data:image/jpeg;base64,"))
                BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    ?: error("图片数据解码失败")
            }
            try {
                val sent = CompanionActionRuntime.sendCapturedScreenshot(
                    context, roleId, result, "主人保存的后台屏截图",
                    selected.frame.packageName, sourceDisplay = "virtual")
                status = if (sent.success) "已保存到私聊（主人操作）" else sent.summary
            } finally { result.recycle() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            status = failure.message.orEmpty().take(150).ifBlank { "保存失败" }
        } finally { saving = false }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("$roleName 的屏幕", fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回查手机")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(picture?.let { inspectedRunningAppName(it.frame.packageName) }
                        ?: "此刻的画面", fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text(if (busy) "正在读取…" else status,
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (picture != null) {
                    Text(picture!!.checkedAt.substringBeforeLast(':'),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            // The actual verified virtual screen is the visual centre, not a
            // settings dashboard. Tapping it only zooms; it never taps the App.
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(30.dp),
                color = Color(0xFF16181D),
            ) {
                Box(Modifier.fillMaxSize().padding(7.dp),
                    contentAlignment = Alignment.Center) {
                    if (picture != null) {
                        Image(
                            bitmap = picture!!.bitmap,
                            contentDescription = "角色正在使用的手机画面，点按可放大查看",
                            modifier = Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(24.dp))
                                .clickable { zoomOpen = true },
                            contentScale = ContentScale.Fit,
                        )
                        Surface(
                            onClick = { zoomOpen = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(alpha = 0.78f),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Fullscreen, null, tint = Color.White,
                                    modifier = Modifier.size(19.dp))
                                Text("放大", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    } else {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Outlined.PhoneAndroid, null,
                                modifier = Modifier.size(42.dp),
                                tint = Color.White.copy(alpha = 0.65f))
                            Text(if (busy) "正在打开画面" else "暂时没有可看的屏幕",
                                color = Color.White, fontSize = 16.sp,
                                fontWeight = FontWeight.Medium)
                            if (!busy) TextButton(onClick = { scope.launch { refresh() } }) {
                                Text("重新查看", color = Color.White)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { scope.launch { refresh() } },
                    enabled = !busy && !saving,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新当前屏幕")
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("连续查看", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Switch(checked = autoRefresh,
                        enabled = !busy && picture != null,
                        onCheckedChange = { autoRefresh = it })
                }
                Button(
                    enabled = picture != null && !busy && !saving,
                    onClick = { scope.launch { saveCurrent() } },
                    shape = RoundedCornerShape(15.dp),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (saving) "保存中" else "存到聊天", fontSize = 13.sp,
                        maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy && !testing && roleId.isNotBlank() &&
                        !roleId.startsWith("resident:"),
                    onClick = { testDialog = true },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("自测打开应用") }
                OutlinedButton(
                    enabled = !busy && !testing,
                    onClick = {
                        scope.launch {
                            if (paused) {
                                LuluBackgroundPhoneSession.resumeByOwner(context)
                                paused = LuluBackgroundPhoneSession.ownerHasPaused(context)
                                status = "已允许新后台任务"
                            } else {
                                try {
                                    LuluBackgroundPhoneSession.stop(context)
                                    paused = LuluBackgroundPhoneSession.ownerHasPaused(context)
                                    picture = null
                                    autoRefresh = false
                                    status = "后台操作已暂停"
                                } catch (error: Exception) {
                                    status = "停止失败：" + error.message.orEmpty().take(110)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text(if (paused) "允许后台操作" else "暂停后台操作") }
            }

        }
    }
    if (testDialog) {
        AlertDialog(
            onDismissRequest = { if (!testing) testDialog = false },
            title = { Text("自测后台应用") },
            text = {
                OutlinedTextField(
                    value = testName, onValueChange = { testName = it.take(80) },
                    label = { Text("应用名称") },
                    placeholder = { Text("例如：抖音") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !testing && testName.trim().isNotEmpty(),
                    onClick = {
                        val app = testName.trim()
                        testDialog = false
                        testing = true
                        scope.launch {
                            try {
                                val id = "owner-virtual-test-" + java.util.UUID.randomUUID()
                                val started = org.json.JSONObject(
                                    com.jiacimu.lulu.data.ToolRouter.execute(
                                        context, roleId, "background_phone",
                                        org.json.JSONObject().put("action", "start"),
                                        requestId = id + ":start", userRequested = true))
                                if (!started.optBoolean("success")) {
                                    status = "无法开启后台屏：" +
                                        started.optString("error").ifBlank {
                                            started.optString("verification", started.optString("status"))
                                        }.take(110)
                                } else {
                                    val launched = org.json.JSONObject(
                                        com.jiacimu.lulu.data.ToolRouter.execute(
                                            context, roleId, "background_phone",
                                            org.json.JSONObject().put("action", "launch")
                                                .put("appName", app)
                                                .put("sendToChat", true)
                                                .put("caption", "主人后台屏自测 · " + app),
                                            requestId = id + ":launch", userRequested = true))
                                    if (launched.optBoolean("success")) {
                                        refresh()
                                        status = if (launched.optBoolean("sentToChat"))
                                            "已核实并发至私聊"
                                        else "画面已启动，截图未发送：" +
                                            launched.optString("shareError").take(90)
                                    } else {
                                        status = "自测失败：" +
                                            launched.optString("error").ifBlank {
                                                launched.optString("verification", launched.optString("status"))
                                            }.take(110)
                                    }
                                }
                            } catch (error: Exception) {
                                status = "自测失败：" + error.message.orEmpty().take(110)
                            } finally { testing = false }
                        }
                    },
                ) { Text("打开并截图") }
            },
            dismissButton = {
                TextButton(onClick = { testDialog = false }) { Text("取消") }
            },
        )
    }
    if (zoomOpen && picture != null) {
        RolePhoneZoomDialog(picture!!.bitmap) { zoomOpen = false }
    }
}

/** One unscaled, high-resolution frame at a time; no image generation or API. */
@Composable
private fun RolePhoneZoomDialog(bitmap: ImageBitmap, onClose: () -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFF090A0D)),
            contentAlignment = Alignment.Center) {
            Image(
                bitmap = bitmap,
                contentDescription = "后台虚拟屏全屏截图，双指缩放和拖动查看",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, translation, factor, _ ->
                            zoom = (zoom * factor).coerceIn(1f, 6f)
                            pan = if (zoom <= 1f) Offset.Zero else {
                                val mx = size.width * (zoom - 1f) / 2f
                                val my = size.height * (zoom - 1f) / 2f
                                Offset(
                                    (pan.x + translation.x).coerceIn(-mx, mx),
                                    (pan.y + translation.y).coerceIn(-my, my),
                                )
                            }
                        }
                    }
                    .pointerInput(bitmap) {
                        detectTapGestures(onDoubleTap = {
                            zoom = if (zoom > 1f) 1f else 2.5f
                            pan = Offset.Zero
                        })
                    }
                    .graphicsLayer(
                        scaleX = zoom, scaleY = zoom,
                        translationX = pan.x, translationY = pan.y,
                    ),
            )
            Row(
                Modifier.align(Alignment.TopEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(onClick = { zoom = 1f; pan = Offset.Zero },
                    shape = RoundedCornerShape(50), color = Color(0xFF2A2C30)) {
                    Text("还原", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = Color.White, fontSize = 14.sp)
                }
                Surface(onClick = onClose, shape = RoundedCornerShape(50),
                    color = Color(0xFF2A2C30)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭全屏",
                        modifier = Modifier.padding(12.dp), tint = Color.White)
                }
            }
            Text("双指缩放 · 拖动查看 · 双击切换",
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
        }
    }
}
