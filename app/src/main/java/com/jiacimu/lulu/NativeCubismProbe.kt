package com.jiacimu.lulu

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
}
