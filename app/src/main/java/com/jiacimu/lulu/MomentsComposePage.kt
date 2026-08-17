package com.jiacimu.lulu

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.VisionModelService
import com.jiacimu.lulu.design.LuluColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MomentsComposePage(
    onBack: () -> Unit,
    onPublish: (String, String?, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<String?>(null) }
    var publishing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            imageUri = uri.toString()
            notice = ""
        }
    }

    BackHandler(enabled = !publishing, onBack = onBack)

    fun publish() {
        if (publishing || (content.isBlank() && imageUri.isNullOrBlank())) return
        val selectedImage = imageUri
        if (selectedImage.isNullOrBlank()) {
            onPublish(content, null, "")
            return
        }
        publishing = true
        notice = "正在让识图模型看这张图片…"
        scope.launch {
            VisionModelService.describeImage(context, selectedImage, content)
                .onSuccess { description -> onPublish(content, selectedImage, description) }
                .onFailure { error ->
                    publishing = false
                    notice = error.message ?: "识图失败，请检查识图模型设置"
                }
        }
    }

    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("发动态", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !publishing) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = ::publish,
                        enabled = !publishing && (content.isNotBlank() || !imageUri.isNullOrBlank()),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuluColors.Wheat,
                            contentColor = LuluColors.OnWheat,
                            disabledContainerColor = LuluColors.CardStrong,
                            disabledContentColor = LuluColors.Muted,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (publishing) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (publishing) "识图中" else "发布", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = LuluColors.Card,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, LuluColors.Border),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(2_000) },
                    placeholder = {
                        Text(if (imageUri == null) "分享此刻发生的事情…" else "给这张图片配一句话…")
                    },
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    minLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
            }

            if (!imageUri.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = LuluColors.Card,
                    border = BorderStroke(1.dp, LuluColors.Border),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LuluSelectedPhoto(imageUri = imageUri, modifier = Modifier.fillMaxWidth().height(190.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                modifier = Modifier.weight(1f),
                                enabled = !publishing,
                            ) {
                                Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp)); Text("换一张")
                            }
                            OutlinedButton(
                                onClick = { imageUri = null; notice = "" },
                                modifier = Modifier.weight(1f),
                                enabled = !publishing,
                            ) {
                                Icon(Icons.Outlined.Close, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp)); Text("移除")
                            }
                        }
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !publishing,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("添加图片", fontSize = 13.sp)
                }
            }

            Row(Modifier.fillMaxWidth()) {
                if (notice.isNotBlank()) {
                    Text(
                        notice,
                        Modifier.weight(1f),
                        color = if (publishing) LuluColors.Muted else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                } else Spacer(Modifier.weight(1f))
                Text("${content.length} / 2000", color = LuluColors.Muted, fontSize = 11.sp)
            }
        }
    }
}
