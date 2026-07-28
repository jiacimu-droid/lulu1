package com.jiacimu.lulu.study

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StarWishVideoContent(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
    context: Context,
) {
    var message by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val rawTitle = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
            val title = rawTitle?.takeIf { value -> value.isNotBlank() } ?: "星愿视频"
            store.addVideo(StarWishVideoItem(title = title, uri = uri.toString()))
            message = "已加入视频柜，使用 1 枚视频碎片后解锁"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("视频柜", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("视频碎片：${studyState.inventory.videoCards}", color = StudyDesign.muted)
                Button(
                    onClick = { launcher.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.VideoLibrary, null)
                    Spacer(Modifier.width(7.dp))
                    Text("导入本机视频")
                }
                if (message.isNotBlank()) {
                    StudyMessage(message, error = message.contains("不足") || message.contains("失败"))
                }
            }
        }

        if (state.videos.isEmpty()) {
            item {
                StudyCard {
                    Text(
                        "还没有视频。先从本机导入，再使用视频碎片解锁。",
                        color = StudyDesign.muted,
                    )
                }
            }
        } else {
            items(state.videos, key = { item -> item.id }) { video ->
                StudyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (video.unlocked) Icons.Outlined.PlayCircle else Icons.Outlined.Lock,
                            null,
                            modifier = Modifier.size(34.dp),
                            tint = StudyDesign.muted,
                        )
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.title, fontWeight = FontWeight.Bold)
                            Text(
                                if (video.unlocked) "已解锁" else "需要 1 枚视频碎片",
                                color = StudyDesign.muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (video.unlocked) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(Uri.parse(video.uri), "video/*")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                        )
                                    }.onFailure {
                                        message = "找不到可以播放此视频的应用"
                                    }
                                } else if (StarWishInventoryBridge.consumeVideoCard(studyStore)) {
                                    store.unlockVideo(video.id)
                                    message = "已解锁：${video.title}，视频碎片 -1"
                                } else {
                                    message = "视频碎片不足"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (video.unlocked) "播放" else "解锁")
                        }
                        OutlinedButton(
                            onClick = { store.deleteVideo(video.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, null)
                            Spacer(Modifier.width(5.dp))
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}
