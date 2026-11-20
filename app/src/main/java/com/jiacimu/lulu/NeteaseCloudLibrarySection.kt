package com.jiacimu.lulu

import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private val cloudRose = Color(0xFFA87288)
private val cloudInk = Color(0xFF29242B)

/**
 * Real account & cloud library, separate from the owner-created Lulu lists.
 * Website cookies never enter ChatGPT, the companion model, or local prefs.
 * Only an explicit owner tap selects a cloud song for the shared player.
 */
@Composable
internal fun NeteaseCloudLibrarySection(room: SharedMusicRoom?) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<NeteaseCloudProfile?>(null) }
    var userIdDraft by rememberSaveable { mutableStateOf("") }
    var loadedUid by remember { mutableStateOf("") }
    var lists by remember { mutableStateOf<List<NeteaseCloudList>>(emptyList()) }
    var selected by remember { mutableStateOf<NeteaseCloudList?>(null) }
    var ids by remember { mutableStateOf<List<String>>(emptyList()) }
    var songs by remember { mutableStateOf<List<NeteaseCloudSong>>(emptyList()) }
    var pageEnd by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableIntStateOf(0) }
    var shownLists by remember { mutableIntStateOf(24) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var loginVisible by remember { mutableStateOf(false) }

    fun loadLists(uid: String) {
        if (loading) return
        loading = true
        notice = "正在读取网易云歌单…"
        scope.launch {
            try {
                val remote = NeteaseCloudAccount.lists(uid)
                loadedUid = uid
                lists = remote
                selected = null
                ids = emptyList()
                songs = emptyList()
                pageEnd = 0
                skipped = 0
                shownLists = 24
                notice = if (remote.isEmpty()) "暂时没有读取到可访问的歌单" else
                    "已读取 ${remote.size} 张歌单，点击歌单查看歌曲"
            } catch (t: Exception) {
                notice = "读取失败：${t.message ?: "网易云接口暂不可用"}"
            } finally {
                loading = false
            }
        }
    }
    fun verify() {
        if (loading) return
        loading = true
        notice = "正在核实网页登录状态…"
        scope.launch {
            try {
                val me = NeteaseCloudAccount.verifyLogin()
                profile = me
                if (me == null) {
                    notice = "尚未获得网易云网页授权。可以尝试网页登录，或使用主页 ID 读取公开歌单。"
                } else {
                    userIdDraft = me.uid
                    val remote = NeteaseCloudAccount.lists(me.uid)
                    loadedUid = me.uid
                    lists = remote
                    selected = null
                    ids = emptyList()
                    songs = emptyList()
                    pageEnd = 0
                    skipped = 0
                    shownLists = 24
                    notice = if (remote.isEmpty()) "已连接 ${me.nickname}，但没有读到可访问的歌单"
                        else "已连接 ${me.nickname}，同步 ${remote.size} 张歌单"
                }
            } catch (t: Exception) {
                notice = "核实或同步失败：${t.message ?: "网络异常"}。可以稍后重试。"
            } finally {
                loading = false
            }
        }
    }
    fun loadPage(target: List<String>, start: Int, fresh: Boolean) {
        if (loading) return
        loading = true
        notice = "正在读取第 ${start + 1} 首起的歌曲资料…"
        scope.launch {
            try {
                val last = (start + 80).coerceAtMost(target.size)
                val result = NeteaseCloudAccount.tracks(target.subList(start, last))
                if (fresh) songs = result.tracks else songs = songs + result.tracks
                pageEnd = last
                skipped += result.missing
                notice = if (result.missing > 0)
                    "本页有 ${result.missing} 首未返回详情，未猜测替换为其他歌曲"
                else "已读取 $pageEnd / ${target.size} 首"
            } catch (t: Exception) {
                notice = "歌曲读取失败：${t.message ?: "网络异常"}，可重试"
            } finally {
                loading = false
            }
        }
    }
    fun openList(list: NeteaseCloudList) {
        if (loading) return
        selected = list
        ids = emptyList()
        songs = emptyList()
        pageEnd = 0
        skipped = 0
        loading = true
        notice = "读取「${list.name}」的完整歌曲 ID…"
        scope.launch {
            try {
                val obtained = NeteaseCloudAccount.trackIds(list.id)
                ids = obtained
                loading = false
                if (obtained.isNotEmpty()) loadPage(obtained, 0, fresh = true)
                else notice = "这张歌单目前没有可读取的歌曲"
            } catch (t: Exception) {
                notice = "无法读取歌单：${t.message ?: "网络异常"}"
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Only verify an existing first-party session. No cookie is copied to
        // any app-owned persistent storage.
        verify()
    }

    Card(shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE7E0E5))) {
        Column(Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Cloud, null, tint = cloudRose, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("网易云账号 · 云端歌单", color = cloudInk, fontWeight = FontWeight.SemiBold)
                    Text(if (profile != null) "已连接：${profile?.nickname}（${profile?.uid}）"
                        else "未连接网页版账号", color = Color.Gray, fontSize = 11.sp)
                }
                TextButton(onClick = { loginVisible = true }) {
                    Text(if (profile == null) "去登录" else "切换账号")
                }
            }
            Text("在网易云官网页面完成登录。露露机不接收密码；只有同站点登录凭证用于只读歌单请求。",
                color = Color.Gray, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { verify() }, enabled = !loading) {
                    Text("检测登录并同步", fontSize = 11.sp)
                }
                if (profile != null) {
                    TextButton(onClick = {
                        // Clear only the music site's auth cookies. Do not erase
                        // unrelated WebView login state.
                        val jar = CookieManager.getInstance()
                        jar.setCookie("https://music.163.com",
                            "MUSIC_U=; Max-Age=0; Path=/; Secure")
                        jar.setCookie("https://music.163.com",
                            "__csrf=; Max-Age=0; Path=/; Secure")
                        jar.flush()
                        profile = null
                        lists = emptyList()
                        songs = emptyList()
                        ids = emptyList()
                        notice = "已断开本机网易云网页授权"
                    }) { Text("断开", fontSize = 11.sp, color = Color.Gray) }
                }
            }
            HorizontalDivider(color = Color(0xFFF0EBEF))
            Text("备用网址：通过主页 ID 读取公开歌单", fontSize = 11.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(userIdDraft, { userIdDraft = it.take(200) },
                    label = { Text("用户 ID / 网易云主页链接") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                OutlinedButton(onClick = {
                    val uid = NeteaseCloudAccount.userId(userIdDraft)
                    if (uid == null) notice = "请输入有效的网易云用户 ID 或 /user/home?id=… 链接"
                    else loadLists(uid)
                }, enabled = !loading) { Text("读取") }
            }
            if (lists.isNotEmpty()) {
                val sorted = remember(lists) {
                    lists.sortedWith(compareByDescending<NeteaseCloudList> { it.liked }
                        .thenBy { it.subscribed })
                }
                Text("已读取：${lists.size} 张（红心歌单优先）",
                    fontSize = 12.sp, color = cloudInk)
                // Keep the cloud library inside the Music screen's ordinary scroll.
                // The first page is shown; all subsequent lists are still
                // addressable using the shared scroll rather than discarded.
                sorted.take(shownLists).forEach { list ->
                    Surface(modifier = Modifier.fillMaxWidth().clickable { openList(list) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected?.id == list.id) Color(0xFFF4EBF0) else Color(0xFFFBF9FA)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (list.liked) Icons.Outlined.Favorite else Icons.Outlined.LibraryMusic,
                                null, tint = cloudRose, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(list.name, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp, color = cloudInk)
                            Text("${list.trackCount} 首", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }
            if (lists.size > shownLists) {
                TextButton(onClick = { shownLists += 24 }) {
                    Text("展开更多云端歌单（${lists.size - shownLists} 张）", fontSize = 11.sp)
                }
            }
            selected?.let { list ->
                HorizontalDivider(color = Color(0xFFE9E2E7))
                Text("「${list.name}」· 已加载 ${songs.size} / ${ids.size} 首",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cloudInk)
                songs.forEach { song ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, fontSize = 12.sp, color = cloudInk,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, fontSize = 10.sp, color = Color.Gray,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            if (room == null) {
                                notice = "需要先创建一位角色才能一起听"
                            } else {
                                SharedMusicWindowController.select(room, SharedListenEntry(
                                    title = song.title, artist = song.artist, source = "external",
                                    uri = "https://music.163.com/song?id=${song.id}",
                                    packageName = NeteaseSongLink.packageName,
                                    songId = song.id,
                                ))
                                notice = "已选择「${song.title}」，网易云打开的实际曲目以媒体会话为准"
                            }
                        }, enabled = room != null) {
                            Icon(Icons.Outlined.PlayArrow, "播放指定歌曲", tint = cloudRose)
                        }
                    }
                }
                if (pageEnd < ids.size) {
                    OutlinedButton(onClick = { loadPage(ids, pageEnd, fresh = pageEnd == 0) },
                        enabled = !loading) {
                        Text("加载更多歌曲（还剩 ${ids.size - pageEnd} 首）")
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cloudRose)
            if (notice.isNotBlank()) {
                Text(notice, color = cloudRose, fontSize = 11.sp)
            }
        }
    }
    if (loginVisible) {
        NeteaseOfficialLoginDialog(
            onClose = { loginVisible = false },
            onCheck = {
                loginVisible = false
                verify()
            },
        )
    }
}

/**
 * Browser displays ONLY the genuine NetEase website. No JS bridge, password
 * capture or third-party login cookie forwarders. Cross-site auth redirects
 * open in the external browser instead of inside the embedded login surface.
 */
@Composable
private fun NeteaseOfficialLoginDialog(onClose: () -> Unit, onCheck: () -> Unit) {
    val context = LocalContext.current
    val browser = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    val domain = url.host?.lowercase().orEmpty()
                    val official = domain == "music.163.com" ||
                        domain.endsWith(".music.163.com") ||
                        domain == "163.com" || domain.endsWith(".163.com") ||
                        domain == "126.net" || domain.endsWith(".126.net")
                    if (url.scheme == "https" && official) return false
                    // Other provider logins must run in their official browser.
                    if (url.scheme == "https") runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                    return true
                }
            }
            loadUrl(NeteaseCloudAccount.LOGIN_PAGE)
        }
    }
    DisposableEffect(browser) {
        onDispose {
            browser.stopLoading()
            browser.destroy()
        }
    }
    Dialog(onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.88f),
            shape = RoundedCornerShape(21.dp), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("网易云官网登录", modifier = Modifier.weight(1f),
                        color = cloudInk, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, "取消网页登录")
                    }
                }
                Text("请确认页面域名为 music.163.com。若官网拒绝内嵌登录，可返回通过主页 ID 读取公开歌单。",
                    fontSize = 11.sp, color = Color.Gray)
                AndroidView(factory = { browser },
                    modifier = Modifier.weight(1f).fillMaxWidth())
                Button(onClick = {
                    CookieManager.getInstance().flush()
                    onCheck()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("我已登录 · 检测账号并同步歌单")
                }
            }
        }
    }
}
