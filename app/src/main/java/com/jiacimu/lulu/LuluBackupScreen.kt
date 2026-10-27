package com.jiacimu.lulu

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.LuluCloudBackup
import com.jiacimu.lulu.data.LuluDataBackup
import com.jiacimu.lulu.data.isLuluAutoBackupEnabled
import com.jiacimu.lulu.data.setLuluAutoBackupEnabled
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复：把全部数据打成一个包，存在用户自己选的位置。
 * 文件不在 app 私有空间里，卸了 app 也还在；恢复会把当前数据
 * 整个换掉，所以恢复前先导一份当前的备份最稳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(LuluDataBackup.hasPendingRestore(context)) }
    var lastResult by remember { mutableStateOf(LuluDataBackup.readRestoreResult(context)) }
    var autoBackup by remember { mutableStateOf(isLuluAutoBackupEnabled(context)) }
    var cloudConfig by remember { mutableStateOf(LuluCloudBackup.loadConfig(context)) }
    var cloudLast by remember { mutableStateOf(LuluCloudBackup.lastUploadText(context)) }

    fun saveCloud(config: LuluCloudBackup.Config) {
        LuluCloudBackup.saveConfig(context, config)
        cloudConfig = config
    }

    fun uploadNow() {
        val config = cloudConfig
        busy = true
        status = "正在上传到云盘…"
        scope.launch(Dispatchers.IO) {
            try {
                val tmp = File.createTempFile("lulu-backup-manual-", ".zip", context.cacheDir)
                try {
                    FileOutputStream(tmp).use { out ->
                        LuluDataBackup.exportZip(LuluDataBackup.dataDir(context), out)
                    }
                    val stamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                    val result = LuluCloudBackup.upload(
                        config.serverUrl, config.username, config.password,
                        config.remoteDir, tmp, "lulu-backup-$stamp.zip",
                    )
                    val text = result.fold(
                        onSuccess = { "云备份成功" },
                        onFailure = { "云备份失败：${it.message}" },
                    )
                    LuluCloudBackup.recordUpload(context, text)
                    withContext(Dispatchers.Main) {
                        cloudLast = text
                        status = text
                    }
                } finally {
                    tmp.delete()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { status = "上传失败：${error.message}" }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导出…"
        scope.launch(Dispatchers.IO) {
            try {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("打不开保存位置")
                val result = out.use { LuluDataBackup.exportZip(LuluDataBackup.dataDir(context), it) }
                withContext(Dispatchers.Main) {
                    status = "已导出 ${result.fileCount} 个文件（${formatBackupSize(result.totalBytes)}）。" +
                        "文件存在你选的位置，卸了 app 也还在。"
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { status = "导出失败：${error.message}" }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在检查备份…"
        scope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("打不开备份文件")
                val validated = input.use { LuluDataBackup.stageForRestore(context, it) }
                withContext(Dispatchers.Main) {
                    pending = true
                    status = "已准备好（共 ${validated.entryCount} 项），重启后生效。" +
                        "恢复会把当前数据整个换掉，恢复前建议先导一份当前的备份。"
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { status = "备份不可用：${error.message}" }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun restartApp() {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        if (intent != null) context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "导出会把设置、聊天与时间线、各角色数据、日记与词库、模型皮套打成一个包，" +
                    "存在你自己选的位置，卸了 app 也还在。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Text(
                "注意：备份里有登录密钥等私密东西，别把备份文件发给别人。",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("每天自动备份", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "每天自动存一份到下载文件夹 lulu-backups，只留最近 7 份。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                Switch(
                    checked = autoBackup,
                    onCheckedChange = { checked ->
                        autoBackup = checked
                        setLuluAutoBackupEnabled(context, checked)
                        status = if (checked) {
                            "已开启每天自动备份，开启后不久会先备一份，之后每天一份。"
                        } else {
                            "已关闭每天自动备份。"
                        }
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("云备份（WebDAV）", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "自动备份后同步传到你的 WebDAV 网盘（坚果云、Nextcloud、群晖等）。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
                Switch(
                    checked = cloudConfig.enabled,
                    onCheckedChange = { checked ->
                        saveCloud(cloudConfig.copy(enabled = checked))
                        status = if (checked) "已开启云备份，下次自动备份时会同步上传。" else "已关闭云备份。"
                    },
                )
            }
            if (cloudConfig.enabled) {
                OutlinedTextField(
                    value = cloudConfig.serverUrl,
                    onValueChange = { saveCloud(cloudConfig.copy(serverUrl = it)) },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cloudConfig.username,
                    onValueChange = { saveCloud(cloudConfig.copy(username = it)) },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cloudConfig.password,
                    onValueChange = { saveCloud(cloudConfig.copy(password = it)) },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cloudConfig.remoteDir,
                    onValueChange = { saveCloud(cloudConfig.copy(remoteDir = it)) },
                    label = { Text("云端文件夹") },
                    placeholder = { Text("lulu-backups") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (cloudLast != null) {
                    Text("上次云备份：$cloudLast", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = ::uploadNow,
                    enabled = !busy && cloudConfig.serverUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("立即上传到云盘") }
            }
            if (pending) {
                Text(
                    "已有待恢复的备份，重启后生效。",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Button(onClick = ::restartApp, modifier = Modifier.fillMaxWidth()) {
                    Text("立即重启并恢复")
                }
            }
            if (lastResult != null) {
                Text("上次恢复结果：$lastResult", fontSize = 13.sp)
                TextButton(onClick = {
                    LuluDataBackup.clearRestoreResult(context)
                    lastResult = null
                }) { Text("知道了") }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val stamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                    exportLauncher.launch("lulu-backup-$stamp.zip")
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出备份") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复备份") }
            if (status != null) {
                Text(status!!, fontSize = 14.sp, lineHeight = 21.sp)
            }
            Text(
                "恢复说明：全新安装后恢复最干净；已有数据上恢复时，同名文件会被覆盖，多余文件不会被删。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

private fun formatBackupSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes 字节"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    return "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
