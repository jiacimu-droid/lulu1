package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluCssPageScope
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.LocalLuluCssSheet
import com.jiacimu.lulu.data.LuluCssSheet
import com.jiacimu.lulu.design.cssColor
import com.jiacimu.lulu.design.cssFontSize
import com.jiacimu.lulu.design.luluCssElement
import com.jiacimu.lulu.design.luluCssStyle

private val ink = Color(0xFF29242B)
private val rose = Color(0xFFA87288)
private val paper = Color(0xFFFDFBFC)

private fun clock(ms: Long) =
    (ms / 60000).toString() + ":" + ((ms / 1000) % 60).toString().padStart(2, '0')

/** An overlay, NOT a second conversation screen or fullscreen Dialog. */
@Composable
internal fun ListenTogetherMiniWindow(
    roleName: String,
    roleAvatar: String?,
    systemMode: Boolean,
    external: SharedListeningTrack?,
    externalError: String,
    localName: String,
    localPlaying: Boolean,
    localUri: String,
    localPosition: Int,
    localDuration: Int,
    status: String,
    onToggleMode: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectLocal: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenNetease: () -> Unit,
    onEnableSystemOverlay: () -> Unit,
    onJoin: () -> Unit,
    onClose: () -> Unit,
) {
    val display = SharedMusicWindowController.display
    val trackName = if (systemMode) external?.title.orEmpty().ifBlank { "选择一首歌" }
        else localName.ifBlank { "选择本地歌曲" }
    val artist = if (systemMode) external?.artist.orEmpty().ifBlank { "手机媒体播放器" }
        else "本地音乐"
    val playing = if (systemMode) external?.playing == true else localPlaying
    val position = if (systemMode) external?.positionMs ?: 0L else localPosition.toLong()
    val duration = if (systemMode) external?.durationMs ?: 0L else localDuration.toLong()
    val controlsAvailable = if (systemMode) external != null else localUri.isNotBlank()
    val context = LocalContext.current
    val owner = context.getSharedPreferences("lulu_user_profile", android.content.Context.MODE_PRIVATE)
    val userName = owner.getString("display_name", "我").orEmpty().ifBlank { "我" }
    val userAvatar = owner.getString("avatar_uri", null)

    LuluCssPageScope("ListenTogether") {
        val miniSkin = luluCssStyle("music-mini")
        val compactSkin = luluCssStyle("music-mini-compact")
        val hiddenSkin = luluCssStyle("music-mini-hidden")
        val trackSkin = luluCssStyle("music-track-title")
        val artistSkin = luluCssStyle("music-track-artist")
        val progressSkin = luluCssStyle("music-progress")
        val playSkin = luluCssStyle("music-play")
        val inheritedPaper = LocalLuluCssSheet.current.page("ListenTogether")["--paper"]
        val themePaper = if (inheritedPaper != null)
            Color(LocalLuluAppearance.current.palette.paper.toInt()) else paper
        val miniColor = if (LuluCssSheet.assetToken(miniSkin["background-image"]) != null)
            Color.Transparent else miniSkin.cssColor("background-color") ?: themePaper
        val compactColor = if (LuluCssSheet.assetToken(compactSkin["background-image"]) != null)
            Color.Transparent else compactSkin.cssColor("background-color") ?: themePaper
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 41.dp, end = 12.dp, start = 12.dp),
            contentAlignment = Alignment.TopCenter) {
        when (display) {
            SharedMusicWindow.Hidden -> {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).luluCssElement("music-mini-hidden").clickable {
                        SharedMusicWindowController.expand()
                    },
                    color = hiddenSkin.cssColor("background-color") ?: rose,
                    shape = CircleShape, shadowElevation = 8.dp,
                ) {
                    Icon(Icons.Outlined.MusicNote, "展开一起听",
                        Modifier.padding(14.dp).size(22.dp), tint = Color.White)
                }
            }
            SharedMusicWindow.Compact -> {
                Surface(
                    modifier = Modifier.luluCssElement("music-mini-compact"),
                    shape = RoundedCornerShape(24.dp),
                    color = compactColor,
                    shadowElevation = 9.dp,
                    border = BorderStroke(1.dp, Color(0xFFE7DFE4)),
                ) {
                    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 13.dp, end = 5.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Album, null, tint = rose)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f).clickable { SharedMusicWindowController.expand() }) {
                            Text(trackName, color = ink, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            Text("一起听 · 点击展开", color = rose, fontSize = 10.sp)
                        }
                        IconButton(onClick = onToggle, enabled = controlsAvailable) {
                            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "播放或暂停",
                                tint = ink)
                        }
                        IconButton(onClick = { SharedMusicWindowController.hide() }) {
                            Icon(Icons.Outlined.VisibilityOff, "隐藏悬浮窗", tint = ink)
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Outlined.Close, "结束一起听", tint = ink)
                        }
                    }
                }
            }
            SharedMusicWindow.Expanded -> {
                Surface(
                    modifier = Modifier.luluCssElement("music-mini"),
                    shape = RoundedCornerShape(25.dp),
                    color = miniColor,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, Color(0xFFE7DFE4)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth().luluCssElement("music-header"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("一起听", color = ink, fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = onOpenMusic, modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.LibraryMusic, "打开音乐与歌单",
                                    Modifier.size(19.dp), tint = rose)
                            }
                            IconButton(onClick = onEnableSystemOverlay,
                                modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.OpenInNew, "允许跨应用悬浮",
                                    Modifier.size(19.dp), tint = rose)
                            }
                            IconButton(onClick = { SharedMusicWindowController.compact() },
                                modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.Remove, "收起为播放条", Modifier.size(19.dp), tint = ink)
                            }
                            IconButton(onClick = { SharedMusicWindowController.hide() },
                                modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.VisibilityOff, "隐藏一起听", Modifier.size(19.dp), tint = ink)
                            }
                            IconButton(onClick = onClose, modifier = Modifier.size(33.dp)) {
                                Icon(Icons.Outlined.Close, "结束一起听", Modifier.size(19.dp), tint = ink)
                            }
                        }
                        ListenTogetherArtwork(
                            roleName = roleName, roleAvatar = roleAvatar,
                            userAvatar = userAvatar, userName = userName, spinning = playing)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(trackName,
                                    modifier = Modifier.luluCssElement("music-track-title"),
                                    color = trackSkin.cssColor("color") ?: ink,
                                    fontSize = trackSkin.cssFontSize() ?: 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(artist, modifier = Modifier.luluCssElement("music-track-artist"),
                                    color = artistSkin.cssColor("color") ?: rose,
                                    fontSize = artistSkin.cssFontSize() ?: 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = onToggleMode) {
                                Text(if (systemMode) "手机播放器" else "本地音乐",
                                    fontSize = 10.sp, color = rose)
                            }
                        }
                        LinearProgressIndicator(
                            progress = { if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f)
                                else 0f },
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp)
                                .luluCssElement("music-progress"),
                            color = progressSkin.cssColor("color") ?: rose,
                            trackColor = progressSkin.cssColor("background-color") ?: Color(0xFFF0E7EA))
                        Row(Modifier.fillMaxWidth().luluCssElement("music-controls"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(clock(position), fontSize = 10.sp, color = Color.Gray)
                            IconButton(onClick = onPrevious, enabled = controlsAvailable,
                                modifier = Modifier.size(37.dp)) {
                                Icon(Icons.Outlined.SkipPrevious, "上一首", tint = ink)
                            }
                            FilledIconButton(onClick = onToggle, enabled = controlsAvailable,
                                modifier = Modifier.size(42.dp).luluCssElement("music-play"),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = playSkin.cssColor("background-color") ?: rose,
                                    contentColor = playSkin.cssColor("color") ?: Color.White)) {
                                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    if (playing) "暂停" else "播放")
                            }
                            IconButton(onClick = onNext, enabled = controlsAvailable,
                                modifier = Modifier.size(37.dp)) {
                                Icon(Icons.Outlined.SkipNext, "下一首", tint = ink)
                            }
                            Text(if (duration > 0L) clock(duration) else "—:—",
                                fontSize = 10.sp, color = Color.Gray)
                        }
                        Row(Modifier.fillMaxWidth().luluCssElement("music-actions"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = if (systemMode) onOpenNetease else onSelectLocal) {
                                Text(if (systemMode) "网易云" else "选择音频", fontSize = 11.sp)
                            }
                            TextButton(onClick = onOpenMusic) {
                                Text("歌单 / 选歌", fontSize = 11.sp)
                            }
                            TextButton(onClick = onJoin,
                                enabled = systemMode && external?.playing == true) {
                                Text("一起听 ♡", fontSize = 11.sp)
                            }
                        }
                        if (status.isNotBlank() || (systemMode && external == null)) {
                            Text(status.ifBlank { externalError.ifBlank { "等待手机播放器…" } },
                                color = rose, fontSize = 10.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        }
    }
}
