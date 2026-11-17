package com.jiacimu.lulu

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * Singing-voice conversion (Seed-VC SVC), not ElevenLabs' speech voice changer.
 *
 * A dedicated GPU service is required. A standard ElevenLabs Voice ID alone
 * cannot run an SVC model; instead we synthesize one short reference sample
 * from the role's EXISTING voice ID and shared ElevenLabs API key.
 *
 * The SVC backend contract is shipped in tools/singing-svc. No backend URL,
 * no paid music generation: never silently replace it with speech conversion.
 */
internal object ElevenLabsSingingSvc {
    const val ENGINE_SVC = "seed_vc"
    const val ENGINE_LEGACY = "eleven_speech"
    const val KEY_ENGINE = "eleven_music_conversion_engine"
    const val KEY_ENDPOINT = "eleven_music_svc_endpoint"
    const val KEY_TOKEN = "eleven_music_svc_token"
    private const val PREFS = "lulu_advanced_settings"
    private const val MAX_FILE_BYTES = 26L * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 27L * 1024 * 1024
    private const val TTS_ROOT = "https://api.elevenlabs.io/v1/text-to-speech/"
    private const val REFERENCE_TEXT =
        "你好呀。今天风很轻，我有些话想慢慢告诉你。" +
            "你看，阳光正好，远处的云也漂亮。嗯，听到你声音我就开心。"

    fun engine(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE, ENGINE_SVC)
            .takeIf { it == ENGINE_LEGACY } ?: ENGINE_SVC

    /** HTTPS only; a publicly exposed GPU backend handles potentially private voice audio. */
    internal fun endpointIsSafe(raw: String): Boolean {
        val candidate = raw.trim()
        if (candidate.length !in 13..350) return false
        return runCatching {
            val uri = URI(candidate)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in -1..65535 &&
                uri.path == "/v1/singing/convert"
        }.getOrDefault(false)
    }

    fun isReady(context: Context): Boolean {
        if (engine(context) == ENGINE_LEGACY) return true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return endpointIsSafe(prefs.getString(KEY_ENDPOINT, "").orEmpty()) &&
            prefs.getString(KEY_TOKEN, "").orEmpty().trim().isNotEmpty()
    }

    fun requireReady(context: Context) {
        require(isReady(context)) {
            "歌声专用转换需要已部署的 Seed-VC GPU 服务。在语音设置的「歌声转换服务」中填入 HTTPS 地址和访问密钥。" +
                "配置之前不会扣费生成新歌曲，也不会退回效果不佳的普通语音变声。"
        }
    }

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).take(12)
        .joinToString("") { "%02x".format(it) }

    internal fun referenceFile(context: Context, voiceId: String): File =
        File(context.filesDir, "eleven_music/svc_references/${hash(voiceId)}-v1.mp3")

    /** Cache reference by Voice ID: subsequent songs reuse the same small speech sample. */
    fun ensureReference(context: Context, voiceId: String, apiKey: String): File {
        require(Regex("[A-Za-z0-9_-]{5,100}").matches(voiceId))
        val target = referenceFile(context, voiceId)
        if (target.length() > 1024L) return target
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true) {
            "无法保存角色声线参考音频"
        }
        val temp = File(target.parentFile, target.name + "." + UUID.randomUUID() + ".part")
        val conn = (URL(TTS_ROOT + voiceId + "?output_format=mp3_44100_128")
            .openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 80_000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Accept", "audio/mpeg")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use {
                it.write(JSONObject().put("text", REFERENCE_TEXT)
                    .put("model_id", "eleven_multilingual_v2")
                    .toString().toByteArray(Charsets.UTF_8))
            }
            if (conn.responseCode !in 200..299) error(
                "生成角色歌声参考音色失败（ElevenLabs HTTP ${conn.responseCode}）。" +
                    "请确认已有声线支持文本转语音；原曲及已分离人声仍保留。")
            streamBounded(conn, temp, MAX_FILE_BYTES)
            check(temp.length() > 1024L && looksLikeMp3(temp)) {
                "ElevenLabs 没有返回可用的角色声线参考音频"
            }
            check(temp.renameTo(target)) { "参考音色下载完成但无法保存" }
            return target
        } finally {
            temp.delete()
            conn.disconnect()
        }
    }

    fun convert(context: Context, sourceVocals: File, voiceReference: File, output: File) {
        requireReady(context)
        require(sourceVocals.isFile && sourceVocals.length() in 1025..MAX_FILE_BYTES)
        require(voiceReference.isFile && voiceReference.length() in 1025..MAX_FILE_BYTES)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty().trim()
        val token = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        val boundary = "lulu-seedvc-" + UUID.randomUUID()
        val conn = (URL(endpoint).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 25_000
            readTimeout = 600_000
            setChunkedStreamingMode(8192)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "audio/mpeg")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            BufferedOutputStream(conn.outputStream, 8192).use { out ->
                fun write(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
                fun filePart(name: String, source: File) {
                    write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"audio.mp3\"\r\n" +
                        "Content-Type: audio/mpeg\r\n\r\n")
                    source.inputStream().use { it.copyTo(out, 8192) }
                    write("\r\n")
                }
                filePart("source_vocals", sourceVocals)
                filePart("reference_voice", voiceReference)
                write("--$boundary\r\nContent-Disposition: form-data; name=\"pitch_shift\"\r\n\r\n0\r\n")
                write("--$boundary--\r\n")
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                val message = when (status) {
                    401, 403 -> "歌声专用服务认证失败，请核对访问密钥"
                    429 -> "歌声服务正在处理其他歌曲或已限流，请稍后重试"
                    503 -> "歌声服务暂时不可用，请检查 GPU 服务是否启动"
                    else -> "歌声服务返回 HTTP $status"
                }
                error(message + "。原曲仍已保存，不会自动重新生成。")
            }
            require(conn.contentType.orEmpty().lowercase().startsWith("audio/mpeg")) {
                "歌声服务没有返回约定的 MP3 音频"
            }
            streamBounded(conn, output, MAX_RESPONSE_BYTES)
            check(output.length() > 1024L && looksLikeMp3(output)) {
                "歌声转换服务返回的音频无效，不能冒充成功"
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun streamBounded(conn: HttpURLConnection, target: File, maximum: Long) {
        var received = 0L
        BufferedInputStream(conn.inputStream, 8192).use { input ->
            BufferedOutputStream(FileOutputStream(target), 8192).use { out ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    received += n
                    check(received <= maximum) { "返回音频超过安全大小上限" }
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun looksLikeMp3(file: File): Boolean {
        if (file.length() < 4) return false
        return file.inputStream().use { source ->
            val header = ByteArray(3)
            source.read(header) == 3 && (
                String(header, Charsets.US_ASCII) == "ID3" ||
                    (header[0].toInt() and 0xff) == 0xff &&
                    (header[1].toInt() and 0xe0) == 0xe0
            )
        }
    }
}
