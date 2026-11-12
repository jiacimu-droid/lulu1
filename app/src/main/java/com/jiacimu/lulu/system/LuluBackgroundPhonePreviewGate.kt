package com.jiacimu.lulu.system

/**
 * Local privacy gate for an owner-initiated, read-only virtual-screen peek.
 * This does NOT authorize background actions or reveal any other role's view.
 * The verified per-display accessibility window must still be checked before
 * AND after acquiring the actual pixels.
 */
internal object LuluBackgroundPhonePreviewGate {
    fun requireAccess(
        requestedCharacterId: String,
        ownerCharacterId: String,
        displayId: Int,
        packageName: String,
        connected: Boolean,
        ownerPaused: Boolean,
        screenConsent: Boolean,
        deviceLocked: Boolean,
        excluded: Boolean,
    ) {
        check(requestedCharacterId.isNotBlank() &&
            !requestedCharacterId.startsWith("resident:")) {
            "没有可查看后台手机的真实角色"
        }
        check(!ownerPaused) { "主人已暂停后台手机操作" }
        check(screenConsent) { "主人尚未授权按需读取屏幕" }
        check(!deviceLocked) { "手机已锁屏，不能查看后台画面" }
        check(connected && displayId > 0) {
            "当前没有运行中的独立后台屏；请先让角色真实打开一个应用"
        }
        check(ownerCharacterId == requestedCharacterId) {
            "这块后台屏属于另一角色，不能跨角色查看"
        }
        check(packageName.isNotBlank() && !excluded) {
            "后台没有已授权的可查看应用"
        }
    }
}
