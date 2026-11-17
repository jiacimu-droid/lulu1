package com.jiacimu.lulu

import android.content.Context
import android.media.MediaMetadataRetriever
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Eleven Music -> two-stem separation -> multilingual Voice Changer -> local mix.
 * Music finetunes and TTS Voice IDs are deliberately separate. A role's saved
 * ElevenLabs speech voice may be used by Voice Changer, not by Music itself.
 *
 * All three remote steps may bill independently. Failed conversion never
 * deletes the already-paid original song or pretends a foreign voice is the role.
 */
internal object ElevenLabsRoleSongPipeline {
    private const val ROOT = "https://api.elevenlabs.io/v1"
    private const val MAX_UPLOAD = 26L * 1024 * 1024
    private const val MAX_DOWNLOAD = 65L * 1024 * 1024
    private val VOICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{5,100}")
    private const val VOICE_STYLE_PREFIX = "eleven_music_voice_style:"
    const val NATURAL = "natural"
    const val EXPRESSIVE = "expressive"

    /** Independent per-role singing setting: do not alter speaking voice or phone calls. */
    fun voiceStyle(context: Context, characterId: String): String =
        context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            .getString(VOICE_STYLE_PREFIX + characterId.trim(), NATURAL)
            ?.takeIf { it == EXPRESSIVE } ?: NATURAL

    internal fun changerFields(style: String): Map<String, String> {
        require(style == NATURAL || style == EXPRESSIVE) { "未知的歌声转换模式" }
        if (style == NATURAL) return mapOf("model_id" to "eleven_multilingual_sts_v2")
        // Lower stability can allow a more expressive delivery, but does NOT
        // guarantee accurate singing pitch. Keep it opt-in and per-request.
        return mapOf(
            "model_id" to "eleven_multilingual_sts_v2",
            "voice_settings" to JSONObject()
                .put("stability", 0.35)
                .put("similarity_boost", 0.85)
                .put("style", 0.0)
                .put("use_speaker_boost", true)
                .toString(),
        )
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            .getBoolean("eleven_music_use_role_voice", false)

    /** Prefer the role's actual Eleven voice; never mistake a MiniMax ID for it. */
    fun roleVoiceId(context: Context, characterId: String): String? {
        CharacterVoicePreferenceStore.initialize(context)
        val preferences = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val roleEleven = CharacterVoicePreferenceStore.realtimeVoiceId(characterId).orEmpty().trim()
        val globalEleven = preferences.getString("eleven_voice_id", "").orEmpty().trim()
        // Generic per-role voice_id may belong to MiniMax. Only use the
        // ElevenLabs-specific voice setting or the saved global Eleven voice.
        return sequenceOf(roleEleven, globalEleven)
            .firstOrNull { VOICE_ID_PATTERN.matches(it) }
    }

    fun convertedFile(original: File, voiceId: String, style: String = NATURAL,
                      engine: String = ElevenLabsSingingSvc.ENGINE_LEGACY): File {
        require(VOICE_ID_PATTERN.matches(voiceId)) { "角色 ElevenLabs Voice ID 无效" }
        require(style == NATURAL || style == EXPRESSIVE) { "未知的歌声转换模式" }
        require(engine in setOf(ElevenLabsSingingSvc.ENGINE_LEGACY,
            ElevenLabsSingingSvc.ENGINE_SVC)) { "未知的歌声转换引擎" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(voiceId.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        // Keep the original natural cache filename unchanged for existing users.
        val suffix = when {
            engine == ElevenLabsSingingSvc.ENGINE_SVC -> "_seedvc_v1"
            style == EXPRESSIVE -> "_expressive"
            else -> ""
        }
        return File(original.parentFile, original.nameWithoutExtension + "_role_" + hash + suffix + ".wav")
    }

    fun convert(context: Context, original: File, voiceId: String,
                style: String = NATURAL,
                engine: String = ElevenLabsSingingSvc.engine(context)): File {
        require(original.isFile && original.length() in 1025..MAX_UPLOAD) {
            "原始歌曲文件不可用或过大"
        }
        val target = convertedFile(original, voiceId, style, engine)
        if (target.isFile && target.length() > 44L) return target
        if (engine == ElevenLabsSingingSvc.ENGINE_SVC) ElevenLabsSingingSvc.requireReady(context)
        // Check duration BEFORE paying for separation or voice conversion.
        val durationMs = runCatching {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(original.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                metadata.release()
            }
        }.getOrNull()
        require(durationMs == null || durationMs <= 185_000L) {
            "已保存歌曲超过角色换声的180秒上限，未发起分轨或变声调用"
        }
        val preferences = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val apiKey = preferences.getString("eleven_api_key", "").orEmpty().trim()
        require(apiKey.isNotEmpty()) { "请在语音设置中配置 ElevenLabs API Key" }

        val temp = File(context.cacheDir, "eleven_song_mix_" + UUID.randomUUID())
        check(temp.mkdirs()) { "手机存储空间不足，无法建立歌曲转换临时目录" }
        val outputPart = File(original.parentFile, target.name + ".part")
        try {
            val (vocals, instrumental) = cachedStems(original, temp, apiKey)
            val convertedVoice = File(temp, "role-vocals.mp3")
            if (engine == ElevenLabsSingingSvc.ENGINE_SVC) {
                // Existing role Voice ID / existing Eleven key. Never ask the owner
                // to provide another TTS voice ID or train a new singer first.
                val reference = ElevenLabsSingingSvc.ensureReference(context, voiceId, apiKey)
                ElevenLabsSingingSvc.convert(context, vocals, reference, convertedVoice)
            } else {
                // The speech changer is explicitly legacy/experimental.
                upload(
                    url = "$ROOT/voice-changer/$voiceId?output_format=mp3_44100_128",
                    apiKey = apiKey,
                    file = vocals,
                    fileField = "audio",
                    fields = changerFields(style),
                    accept = "audio/mpeg",
                    output = convertedVoice,
                )
            }
            check(convertedVoice.length() > 1024L) { "变声接口没有返回有效的人声" }
            outputPart.delete()
            ElevenLabsSongAudioMix.render(instrumental, convertedVoice, outputPart)
            check(outputPart.length() > 44L) { "角色歌曲混音失败" }
            check(outputPart.renameTo(target)) { "角色歌曲生成成功，但无法保存" }
            return target
        } finally {
            outputPart.delete()
            temp.deleteRecursively()
        }
    }

    /**
     * Separation is separately billed. Keep validated stems in the role's private
     * storage so changing the singing engine does not pay to split the same song
     * again. A partial or invalid cache is re-created, not reported as success.
     * Store stems BELOW a subfolder so latestOriginalSong still sees original MP3.
     */
    private fun cachedStems(original: File, temp: File, apiKey: String): Pair<File, File> {
        val folder = File(original.parentFile,
            "music_stems/" + original.nameWithoutExtension)
        val vocals = File(folder, "vocals.mp3")
        val instrumental = File(folder, "instrumental.mp3")
        if (vocals.length() > 1024 && instrumental.length() > 1024)
            return vocals to instrumental
        val zip = File(temp, "stems.zip")
        upload(
            url = "$ROOT/music/stem-separation?output_format=mp3_44100_128",
            apiKey = apiKey,
            file = original,
            fileField = "file",
            fields = mapOf("stem_variation_id" to "two_stems_v1"),
            accept = "application/zip",
            output = zip,
        )
        val (freshVoice, freshBacking) = extractTwoStems(zip, temp)
        check(folder.isDirectory || folder.mkdirs()) { "无法缓存已经付费的人声分轨" }
        for ((source, destination) in listOf(freshVoice to vocals,
                freshBacking to instrumental)) {
            val partial = File(folder, destination.name + ".part")
            try {
                source.copyTo(partial, overwrite = true)
                check(partial.length() > 1024L)
                if (destination.exists()) destination.delete()
                check(partial.renameTo(destination)) { "分轨无法写入持久缓存" }
            } finally {
                partial.delete()
            }
        }
        return vocals to instrumental
    }

    /**
     * ZIP names aren't a stable API schema; only known vocal and accompaniment
     * stems are accepted. Never infer that an arbitrary ZIP member is a voice.
     */
    internal fun stemKind(filename: String): String? {
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
            .lowercase(Locale.ROOT)
        if (!base.endsWith(".mp3")) return null
        return when {
            listOf("instrumental", "accompaniment", "backing", "no_vocals",
                "no-vocals", "music_only").any(base::contains) -> "instrumental"
            listOf("vocals", "vocal", "voice").any(base::contains) -> "vocals"
            else -> null
        }
    }

    private fun extractTwoStems(zip: File, folder: File): Pair<File, File> {
        val vocals = File(folder, "separated-vocals.mp3")
        val instrumental = File(folder, "separated-instrumental.mp3")
        var entries = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                check(entries <= 24) { "分轨压缩包内容异常" }
                val kind = if (entry.isDirectory) null else stemKind(entry.name)
                val target = when (kind) {
                    "vocals" -> vocals
                    "instrumental" -> instrumental
                    else -> null
                }
                if (target != null) {
                    check(!target.exists()) { "分轨压缩包包含重复音轨" }
                    var total = 0L
                    BufferedOutputStream(FileOutputStream(target), 8192).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_UPLOAD) { "分轨文件超过大小限制" }
                            out.write(buffer, 0, count)
                        }
                    }
                }
                input.closeEntry()
            }
        }
        check(vocals.length() > 1024L && instrumental.length() > 1024L) {
            "ElevenLabs 未返回完整的两轨音频（人声与伴奏）"
        }
        return vocals to instrumental
    }

    private fun upload(
        url: String,
        apiKey: String,
        file: File,
        fileField: String,
        fields: Map<String, String>,
        accept: String,
        output: File,
    ) {
        check(file.length() in 1..MAX_UPLOAD) { "上传音频大小超限" }
        val boundary = "lulu-" + UUID.randomUUID().toString()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 300_000
        connection.setChunkedStreamingMode(8192)
        connection.setRequestProperty("xi-api-key", apiKey)
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
            BufferedOutputStream(connection.outputStream, 8192).use { out ->
                fun part(text: String) { out.write(text.toByteArray(Charsets.UTF_8)) }
                for ((name, value) in fields) {
                    part("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                part("--$boundary\r\nContent-Disposition: form-data; name=\"$fileField\"; " +
                    "filename=\"song.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n")
                FileInputStream(file).use { it.copyTo(out, 8192) }
                part("\r\n--$boundary--\r\n")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText().take(350) }
                }.getOrNull().orEmpty()
                val diagnosis = when (status) {
                    401 -> "API Key 无效"
                    403 -> "当前账号没有分轨或变声权限"
                    429 -> "额度或调用频率已达到限制"
                    else -> detail.take(180)
                }
                error("ElevenLabs HTTP $status：$diagnosis")
            }
            var size = 0L
            BufferedInputStream(connection.inputStream, 8192).use { source ->
                BufferedOutputStream(FileOutputStream(output), 8192).use { destination ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= MAX_DOWNLOAD) { "ElevenLabs 返回音频体积超限" }
                        destination.write(buffer, 0, count)
                    }
                }
            }
            check(size > 1024L) { "ElevenLabs 返回空的音频或分轨文件" }
        } finally {
            connection.disconnect()
        }
    }
}
