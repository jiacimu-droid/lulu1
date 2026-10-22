package com.jiacimu.lulu

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Mobile render-only atlas variants. Original purchased model and 8192px textures
 * are never modified. 4096px output costs roughly one quarter the GPU memory.
 * This runs in WebViewAssetLoader.shouldInterceptRequest (a worker thread).
 */
internal object Live2DMobileTextureAdapter {
    private const val MOBILE_SIDE = 4096

    @Synchronized
    fun prepare(settings: File, manifest: JSONObject, maxSide: Int = MOBILE_SIDE): JSONObject {
        require(maxSide >= 128)
        val base = settings.parentFile?.canonicalFile ?: return manifest
        val refs = manifest.getJSONObject("FileReferences")
        val sources = refs.getJSONArray("Textures")
        for (index in 0 until sources.length()) {
            val relative = sources.getString(index).replace('\\', '/')
            val source = File(base, relative).canonicalFile
            require(source.path.startsWith(base.path + File.separator) && source.isFile) {
                "Live2D 纹理文件不存在或引用越界：$relative"
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "无法读取 Live2D 纹理：$relative"
            }
            if (maxOf(bounds.outWidth, bounds.outHeight) <= maxSide) continue
            val generated = File(source.parentFile,
                source.nameWithoutExtension + ".lulu-mobile-$maxSide.png")
            if (!generated.isFile || generated.length() == 0L ||
                generated.lastModified() < source.lastModified()) {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(source.path, options)
                    ?: error("手机无法解码高分辨率 Live2D 纹理 $relative")
                val temporary = File(generated.parentFile, generated.name + "." + UUID.randomUUID() + ".tmp")
                try {
                    FileOutputStream(temporary).use { output ->
                        check(decoded.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "无法缓存适合手机显示的 Live2D 纹理"
                        }
                    }
                    if (generated.exists()) check(generated.delete()) { "无法更新过期贴图缓存" }
                    check(temporary.renameTo(generated)) { "手机优化贴图保存失败" }
                } finally {
                    decoded.recycle()
                    temporary.delete()
                }
            }
            val original = File(base, relative)
            val derivedRelative = File(original.parentFile, generated.name)
                .relativeTo(base).invariantSeparatorsPath
            sources.put(index, derivedRelative)
        }
        return manifest
    }
}
