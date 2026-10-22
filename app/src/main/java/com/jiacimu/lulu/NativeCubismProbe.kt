package com.jiacimu.lulu

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Optional official ARM64 Cubism Core readiness check.
 * Core parsing is not a renderer or proof of a visible Android GPU frame.
 * The APK built without -PluluNativeSdkDir keeps working via WebGL/Compose.
 */
internal object NativeCubismProbe {
    val nativeCoreLinked: Boolean by lazy {
        runCatching { System.loadLibrary("lulu-cubism-probe") }.isSuccess
    }

    private external fun nativeDrawableCount(moc: ByteArray): Int

    fun inspect(moc: ByteArray): Result<Int> {
        if (!nativeCoreLinked) {
            return Result.failure(IllegalStateException(
                "未编译官方 Cubism Native Core：当前手机继续使用已验证的 WebGL 兼容画面"))
        }
        if (moc.isEmpty()) {
            return Result.failure(IllegalArgumentException("Moc3 文件为空"))
        }
        return runCatching { nativeDrawableCount(moc) }
            .mapCatching { count ->
                check(count > 0) { "Native Core 无法加载该 Moc3（错误码 $count）" }
                count
            }
    }
    fun inspectImportedModel(context: Context, characterId: String): Result<Int> {
        return runCatching {
            val modelUrl = Live2DAssets.modelUrl(context, characterId)
                ?: error("当前角色没有可读取的模型")
            val base = Live2DAssets.base(context).canonicalFile
            val relative = Uri.decode(Uri.parse(modelUrl).encodedPath.orEmpty()
                .removePrefix("/private/"))
            require(relative.startsWith("models/")) { "非法模型路径" }
            val modelJson = File(base, relative).canonicalFile
            check(modelJson.path.startsWith(base.path + File.separator) && modelJson.isFile) {
                "模型路径不在角色专属资源目录"
            }
            val mocRelative = JSONObject(modelJson.readText())
                .getJSONObject("FileReferences").getString("Moc")
            val moc = File(modelJson.parentFile, mocRelative).canonicalFile
            check(moc.path.startsWith(base.path + File.separator) && moc.isFile &&
                moc.length() in 1..(100L * 1024L * 1024L)) {
                "Moc3 引用缺失、超出允许的单次加载内存或指向了外部路径"
            }
            inspect(moc.readBytes()).getOrThrow()
        }
    }

}
