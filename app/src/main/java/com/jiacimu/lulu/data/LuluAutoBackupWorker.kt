package com.jiacimu.lulu.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AUTO_BACKUP_WORK_NAME = "lulu-auto-backup"
private const val AUTO_BACKUP_PREFS = "lulu_backup_prefs"
private const val AUTO_BACKUP_ENABLED_KEY = "auto_backup_enabled"
private const val AUTO_BACKUP_KEEP_COUNT = 7

fun isLuluAutoBackupEnabled(context: Context): Boolean =
    context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
        .getBoolean(AUTO_BACKUP_ENABLED_KEY, false)

fun setLuluAutoBackupEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(AUTO_BACKUP_ENABLED_KEY, enabled).apply()
    val workManager = WorkManager.getInstance(context)
    if (enabled) {
        val request = PeriodicWorkRequestBuilder<LuluAutoBackupWorker>(24, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(
            AUTO_BACKUP_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    } else {
        workManager.cancelUniqueWork(AUTO_BACKUP_WORK_NAME)
    }
}

/**
 * 每天自动备份一次：把数据打成包，写进下载文件夹里的 lulu-backups，
 * 只留最近 7 份。文件不在 app 私有空间，卸了 app 也还在；
 * 如果用户的网盘 app 会同步下载文件夹，包一生成就跟着上云。
 * 不需要自建服务器，也不需要用户每次动手。
 */
class LuluAutoBackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!isLuluAutoBackupEnabled(applicationContext)) return@withContext Result.success()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext Result.success()
            val tmp = File.createTempFile("lulu-backup-auto-", ".zip", applicationContext.cacheDir)
            try {
                FileOutputStream(tmp).use { out ->
                    LuluDataBackup.exportZip(LuluDataBackup.dataDir(applicationContext), out)
                }
                val stamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                val displayName = "lulu-backup-auto-$stamp.zip"
                insertToDownloads(tmp, displayName)
                pruneOldAutoBackups(AUTO_BACKUP_KEEP_COUNT)
                // 云备份是顺带的：本地包已经落袋，云传失败只记一条结果，
                // 绝不因此把整轮备份判成失败、下次重传也由下一轮补。
                uploadToCloudIfEnabled(tmp, displayName)
            } finally {
                tmp.delete()
            }
            Result.success()
        } catch (error: Exception) {
            Result.retry()
        }
    }

    private fun uploadToCloudIfEnabled(file: File, fileName: String) {
        try {
            val context = applicationContext
            val config = LuluCloudBackup.loadConfig(context)
            if (!config.enabled) return
            val stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            val result = LuluCloudBackup.upload(
                config.serverUrl, config.username, config.password,
                config.remoteDir, file, fileName,
            )
            LuluCloudBackup.recordUpload(
                context,
                result.fold(
                    onSuccess = { "云备份成功（$stamp）" },
                    onFailure = { "云备份失败：${it.message}" },
                ),
            )
        } catch (error: Exception) {
            // 云传的任何异常都只记结果，不连累本地备份。
            runCatching {
                LuluCloudBackup.recordUpload(applicationContext, "云备份失败：${error.message}")
            }
        }
    }

    private fun insertToDownloads(file: File, displayName: String) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/lulu-backups")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("写不进下载文件夹")
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(file).use { input -> input.copyTo(out) }
        } ?: throw IOException("打不开下载文件夹里的新文件")
    }

    private fun pruneOldAutoBackups(keep: Int) {
        val resolver = applicationContext.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("lulu-backup-auto-%.zip")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, sortOrder
        )?.use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                if (index >= keep) {
                    val id = cursor.getLong(0)
                    resolver.delete(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        null, null
                    )
                }
                index += 1
            }
        }
    }
}
