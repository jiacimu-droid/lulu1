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
    val frame = LuluBackgroundPhoneSession.ownerPreview(context, roleId)
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, PhoneBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.width(98.dp).height(126.dp)
                    .clip(RoundedCornerShape(14.dp)).background(PhoneCanvas),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    Image(picture!!.bitmap, contentDescription = "角色真实后台屏预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit)
                } else Icon(Icons.Outlined.PhoneAndroid, null,
                    tint = PhoneMuted, modifier = Modifier.size(34.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("正在看的屏幕", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = PhoneInk, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = PhoneMuted)
                }
                Text(
                    when {
                        picture != null -> inspectedRunningAppName(picture!!.frame.packageName)
                        busy -> "正在读取后台画面…"
                        unavailable -> "当前没有可查看的后台画面"
                        else -> "暂无画面"
                    },
                    color = if (picture != null) PhoneInk else PhoneMuted,
                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (picture != null) "更新于 ${picture!!.checkedAt}"
                    else "打开查看状态和原因",
                    fontSize = 12.sp, color = PhoneMuted,
                )
                Surface(shape = RoundedCornerShape(12.dp),
                    color = PhoneCanvas) {
                    Text("查看大图", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PhoneInk)
                }
            }
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
    var showTechnical by remember(roleId) { mutableStateOf(false) }
    // Only the owner can change this local permission. It is never inferred
    // from the model's declared intent, chat text or a third-party page.
    var shoppingEnabled by remember(roleId) {
        mutableStateOf(com.jiacimu.lulu.system.LuluShoppingAssistPolicy.isEnabled(context, roleId))
    }


    suspend fun refresh() {
        if (busy || saving) return
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
            val verifiedNow = LuluBackgroundPhoneSession.ownerPreview(context, roleId)
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
        containerColor = PhoneCanvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("后台画面", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                        Text("$roleName · 只读查看", fontSize = 12.sp, color = PhoneMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回查手机")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(picture?.let { inspectedRunningAppName(it.frame.packageName) }
                        ?: "当前画面", fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold, color = PhoneInk)
                    Text(status, fontSize = 13.sp, color = PhoneMuted, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                TextButton(enabled = !busy && !saving, onClick = {
                    scope.launch { refresh() }
                }) {
                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }

            Surface(Modifier.fillMaxWidth().weight(1f),
                color = Color(0xFF191B20), shape = RoundedCornerShape(22.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (picture != null) {
                        Image(
                            picture!!.bitmap, contentDescription = "后台屏幕截图，点按全屏放大",
                            modifier = Modifier.fillMaxSize().clickable { zoomOpen = true },
                            contentScale = ContentScale.Fit,
                        )
                        Surface(
                            onClick = { zoomOpen = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            color = Color.Black.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Fullscreen, null, tint = Color.White,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("放大查看", fontSize = 13.sp, color = Color.White)
                            }
                        }
                    } else {
                        Column(Modifier.padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.PhoneAndroid, null,
                                modifier = Modifier.size(47.dp), tint = Color.White.copy(alpha = 0.72f))
                            Text(if (busy) "正在读取画面" else "暂时看不到后台屏",
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                if (busy) "正在核实独立虚拟屏…"
                                else "需要角色已打开后台 App，且设备允许独立屏截图",
                                fontSize = 13.sp, color = Color.White.copy(alpha = 0.68f),
                            )
                            if (!busy) Button(onClick = { scope.launch { refresh() } }) {
                                Text("再试一次")
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("授权角色帮我选购", color = PhoneInk, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium)
                        Text("选商品、规格、购物车与进入结算；付款由你亲自完成",
                            color = PhoneMuted, fontSize = 11.sp)
                    }
                    Switch(checked = shoppingEnabled,
                        enabled = roleId.isNotBlank() && !roleId.startsWith("resident:"),
                        onCheckedChange = { allowed ->
                            val saved = com.jiacimu.lulu.system.LuluShoppingAssistPolicy.setEnabled(
                                context, roleId, allowed)
                            if (saved) shoppingEnabled = allowed
                            else status = "购物协助授权保存失败，权限未改变"
                        })
                }
                if (shoppingEnabled) {
                    Text("仍禁止支付、免密扣款、货到付款、提交/确认订单；" +
                        "第三方支付页可能无法直接转移到主屏，可在商家 App 的待付款订单继续。",
                        color = PhoneMuted, fontSize = 11.sp)
                }
            }

            Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                Text("每 3 秒更新画面", color = PhoneInk, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                Switch(checked = autoRefresh, enabled = !busy && picture != null,
                    onCheckedChange = { autoRefresh = it })
            }
            Button(
                enabled = picture != null && !busy && !saving,
                onClick = { scope.launch { saveCurrent() } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = RoundedCornerShape(15.dp),
            ) {
                Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (saving) "保存中…" else "保存这张图到私聊")
            }
            Text("主人保存的图片会单独标记，不代表角色主动分享。",
                fontSize = 12.sp, color = PhoneMuted)
            TextButton(onClick = { showTechnical = !showTechnical },
                modifier = Modifier.fillMaxWidth()) {
                Text(if (showTechnical) "收起技术信息" else "查看技术信息")
                Icon(if (showTechnical) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null)
            }
            if (showTechnical) {
                val current = picture?.frame
                Text(
                    if (current == null) "独立后台屏未核实；调试授权和连接请到角色设置查看。"
                    else "屏幕 ID ${current.displayId} · ${current.packageName}\n" +
                        "捕获时间 ${picture!!.checkedAt} · " +
                        (if (current.newerThanPreviousCapture) "新帧" else "当前帧"),
                    fontSize = 12.sp, color = PhoneMuted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
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
