package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Direct short-form singing using the character's OWN saved ElevenLabs voice.
 *
 * No music generation, stem separation or speech-to-speech conversion: Eleven v4
 * is prompted to sing a couple of lines using the original speaking Voice ID.
 * This is experimental a cappella singing, NOT a pitch-accurate music model.
 */
internal object ElevenLabsDirectSinging {
    private const val API = "https://api.elevenlabs.io/v1/text-to-speech/"
    private const val PREFS = "lulu_advanced_settings"
    private const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    private const val MAX_LYRICS = 240
    const val MODEL = "eleven_v4"

    fun payload(lyrics: String): JSONObject {
        val text = lyrics.trim()
        require(text.length in 2..MAX_LYRICS) { "试唱歌词请控制在2～240字以内" }
        require(text.any { it.isLetterOrDigit() }) { "请输入要唱出的歌词" }
        // v4 supports natural-language audio tags; [sings] is experimental.
        // The model chooses the melody. A voice that only learned speech may
        // sing poorly, which must be judged by a short listening test.
        return JSONObject()
            .put("text", "[sings softly, with a clear light youthful singing tone] " + text)
            .put("model_id", MODEL)
    }

    private fun roleFolder(context: Context, characterId: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(characterId.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        return File(context.applicationContext.filesDir, "eleven_direct_singing/$hash")
    }

    fun latest(context: Context, characterId: String): File? =
        roleFolder(context, characterId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp3") && it.length() > 1024L }
            ?.maxByOrNull(File::lastModified)

    suspend fun generate(context: Context, characterId: String, lyrics: String): File =
        withContext(Dispatchers.IO) {
            val body = payload(lyrics)
            val app = context.applicationContext
            val voiceId = ElevenLabsRoleSongPipeline.roleVoiceId(app, characterId)
                ?: error("这个角色还没有 ElevenLabs Voice ID，请先选择角色现有的声线")
            val key = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("eleven_api_key", "").orEmpty().trim()
            require(key.isNotEmpty()) { "请先在语音设置中填写 ElevenLabs API Key" }
            val folder = roleFolder(app, characterId)
            check(folder.isDirectory || folder.mkdirs()) { "无法保存短句试唱文件" }
            val file = File(folder, UUID.randomUUID().toString() + ".mp3")
            val partial = File(folder, file.name + ".part")
            val connection = URL(API + voiceId + "?output_format=mp3_44100_128")
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 100_000
                connection.setRequestProperty("xi-api-key", key)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "audio/mpeg")
                connection.outputStream.use { out ->
                    out.write(body.toString().toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    val advice = when (status) {
                        401, 403 -> "请检查当前 ElevenLabs API Key 的 Text to Speech 权限和声线访问资格"
                        429 -> "当前语音调用额度或频率已到限制"
                        else -> "请检查已保存的模型、音色及语音额度"
                    }
                    error("角色试唱失败（ElevenLabs HTTP $status）：$advice；不会自动生成音乐或重复扣费。")
                }
                var size = 0
                connection.inputStream.use { input ->
                    partial.outputStream().use { out ->
                        val chunk = ByteArray(8192)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            size += n
                            check(size <= MAX_AUDIO_BYTES) { "试唱音频过大，已拒绝保存" }
                            out.write(chunk, 0, n)
                        }
                    }
                }
                check(size > 1024 && hasMp3Header(partial)) {
                    "ElevenLabs 未返回有效 MP3 音频"
                }
                check(partial.renameTo(file)) { "试唱生成成功但无法保存" }
                file
            } finally {
                partial.delete()
                connection.disconnect()
            }
        }

    internal fun hasMp3Header(file: File): Boolean =
        file.inputStream().use { input ->
            val start = ByteArray(3)
            input.read(start) == 3 &&
                (String(start, Charsets.US_ASCII) == "ID3" ||
                    ((start[0].toInt() and 0xff) == 0xff &&
                        (start[1].toInt() and 0xe0) == 0xe0))
        }
}
