package com.jiacimu.lulu

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Eleven Music is a DIFFERENT product from ElevenLabs Text-to-Speech.
 * A TTS Voice ID is not a Music Finetune ID. This client generates original music only;
 * it does not claim to cover or transform an existing copyrighted recording.
 */
internal object ElevenLabsMusic {
    private const val PREFS = "lulu_advanced_settings"
    private const val API = "https://api.elevenlabs.io/v1/music?output_format=mp3_48000_192"
    const val DEFAULT_MODEL = "music_v2_5"
    const val MAX_PROMPT = 4100
    private const val MAX_BYTES = 25 * 1024 * 1024

    data class Song(
        val file: File,
        val model: String,
        val usedFinetune: Boolean,
        val songId: String,
        val usedRoleVoice: Boolean = false,
    )

    fun isEnabledAndConfigured(context: Context, characterId: String): Boolean {
        if (characterId.isBlank() || characterId.startsWith("resident:")) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean("eleven_music_enabled", false) &&
            prefs.getString("eleven_api_key", "").orEmpty().isNotBlank()
    }

    fun finetuneId(context: Context, characterId: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("eleven_music_finetune:" + characterId.trim(), "").orEmpty().trim()

    /** An explicit model selection avoids the retiring music_v1 default. */
    fun body(prompt: String, durationSeconds: Int, model: String, finetuneId: String): JSONObject {
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT) { "歌曲描述不能为空，最多4100字" }
        require(durationSeconds in 3..600) { "歌曲时长需在3～600秒之间" }
        require(model in setOf("music_v2", "music_v2_5")) { "不支持的 Eleven Music 模型" }
        require(finetuneId.length <= 100) { "Music Finetune ID 过长" }
        return JSONObject().put("prompt", prompt.trim()).put("model_id", model)
            .put("music_length_ms", durationSeconds * 1000)
            .put("force_instrumental", false)
            .apply { if (finetuneId.isNotEmpty()) put("finetune_id", finetuneId) }
    }

    /** Persist charged output before attempting playback; never pay a second time on speaker failure. */
    suspend fun generate(context: Context, characterId: String, prompt: String, durationSeconds: Int): Song =
        withContext(Dispatchers.IO) {
            val application = context.applicationContext
            require(isEnabledAndConfigured(application, characterId)) {
                "请先在语音设置→角色唱歌开启功能并填写 ElevenLabs API Key"
            }
            val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = prefs.getString("eleven_api_key", "").orEmpty().trim()
            val model = prefs.getString("eleven_music_model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
            val finetune = finetuneId(application, characterId)
            val roleVoice = if (ElevenLabsRoleSongPipeline.enabled(application)) {
                ElevenLabsRoleSongPipeline.roleVoiceId(application, characterId)
                    ?: error("该角色还没有 ElevenLabs Voice ID；请先选择已有角色音色，或关闭「角色原声唱歌」")
            } else null
            require(roleVoice == null || durationSeconds <= 180) {
                "角色原声转换目前只支持最长180秒；未发起收费的音乐生成请求"
            }
            val request = body(prompt, durationSeconds, model, finetune)
            val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 300_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("xi-api-key", key)
                setRequestProperty("Accept", "audio/mpeg")
            }
            var staging: File? = null
            try {
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val explanation = runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
                    }.getOrDefault("")
                    error("Eleven Music HTTP " + status + "；" +
                        if (status == 403) "请检查 Music API 或 Finetune 权限" else explanation.take(250))
                }
                val folder = roleFolder(application, characterId)
                check(folder.isDirectory || folder.mkdirs()) { "无法创建角色歌曲目录" }
                val songName = UUID.randomUUID().toString() + ".mp3"
                val finalFile = File(folder, songName)
                staging = File(folder, songName + ".part")
                var length = 0
                connection.inputStream.use { input ->
                    staging!!.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            length += count
                            check(length <= MAX_BYTES) { "歌曲文件超过25MB，未保存不完整音频" }
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                check(length > 1024) { "Eleven Music 没有返回有效歌曲音频" }
                check(staging!!.renameTo(finalFile)) { "歌曲已生成但无法保存到本机" }
                val songId = connection.getHeaderField("song-id").orEmpty()
                if (roleVoice == null) {
                    Song(finalFile, model, finetune.isNotEmpty(), songId)
                } else {
                    // The billed original remains available for manual retry. Never
                    // play a stranger's generated voice as if it were the role's.
                    val converted = try {
                        ElevenLabsRoleSongPipeline.convert(application, finalFile, roleVoice)
                    } catch (failure: Throwable) {
                        throw IllegalStateException(
                            "原曲已保存，但角色音色转换失败：${failure.message}。可点「只重试换声」，不必重新生成歌曲。",
                            failure,
                        )
                    }
                    Song(converted, model, finetune.isNotEmpty(), songId, usedRoleVoice = true)
                }
            } finally {
                staging?.delete()
                connection.disconnect()
            }
        }

    private fun roleFolder(context: Context, characterId: String): File {
        val roleFolder = MessageDigest.getInstance("SHA-256")
            .digest(characterId.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        return File(context.applicationContext.filesDir, "eleven_music/" + roleFolder)
    }

    /** Reuse the locally saved source if the voice step failed after Music was billed. */
    suspend fun retryLatestRoleVoice(context: Context, characterId: String): Song =
        withContext(Dispatchers.IO) {
            require(isEnabledAndConfigured(context, characterId)) { "请先启用 Eleven Music" }
            val voice = ElevenLabsRoleSongPipeline.roleVoiceId(context, characterId)
                ?: error("没有找到该角色的 ElevenLabs Voice ID")
            val original = roleFolder(context, characterId).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".mp3") }
                ?.maxByOrNull(File::lastModified)
                ?: error("请先生成一首原创歌曲")
            val result = ElevenLabsRoleSongPipeline.convert(context, original, voice)
            Song(result, DEFAULT_MODEL, false, "", usedRoleVoice = true)
        }

    /** Local replay does not make another billable music request. */
    fun latestSong(context: Context, characterId: String): Song? =
        roleFolder(context, characterId).listFiles()
            ?.asSequence()
            ?.filter { it.isFile && (it.name.endsWith(".mp3") || it.name.endsWith(".wav")) }
            ?.maxByOrNull(File::lastModified)
            ?.let { Song(it, DEFAULT_MODEL, false, "", usedRoleVoice = it.name.endsWith(".wav")) }

    @Synchronized
    fun stop() {
        currentPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        currentPlayer = null
    }

    private var currentPlayer: MediaPlayer? = null

    /** Starts the locally persisted audio; success is playback started, not heard-to-completion. */
    @Synchronized
    fun play(song: Song): Boolean {
        check(song.file.isFile && song.file.length() > 0) { "歌曲文件不存在" }
        currentPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        currentPlayer = null
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            player.setDataSource(song.file.absolutePath)
            player.setOnCompletionListener { finished ->
                synchronized(this) {
                    if (currentPlayer === finished) currentPlayer = null
                    finished.release()
                }
            }
            player.prepare()
            currentPlayer = player
            player.start()
            return true
        } catch (error: Throwable) {
            if (currentPlayer === player) currentPlayer = null
            player.release()
            throw error
        }
    }
}
