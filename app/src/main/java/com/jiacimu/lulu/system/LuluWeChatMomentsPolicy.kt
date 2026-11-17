package com.jiacimu.lulu.system

import com.jiacimu.lulu.system.LuluVirtualChatSendPolicy.ComposerBounds

/** Recognize WeChat's own Moments feed and its text-only compose toolbar.
 * Feed browsing uses the standard snapshot controls; publishing is explicit. */
internal object LuluWeChatMomentsPolicy {
    const val OPEN_COMPOSER = "open_wechat_moments_composer"
    const val PUBLISH = "publish_wechat_moment"

    private val navigationLabels = setOf("朋友圈", "我的朋友圈", "他的朋友圈", "她的朋友圈", "朋友圈相册", "朋友圈消息")
    private val cameraLabels = setOf("拍照", "相机", "发表朋友圈", "发朋友圈", "长按发表文字", "Camera")
    private val feedTitles = setOf("朋友圈", "Moments")
    private val cancelLabels = setOf("取消", "Cancel")
    private val publishLabels = setOf("发表", "发布", "Post")

    fun isNavigation(label: String): Boolean = label.trim() in navigationLabels
    fun isCamera(label: String): Boolean = label.trim() in cameraLabels
    fun isFeedTitle(label: String): Boolean = label.trim() in feedTitles
    fun isCancel(label: String): Boolean = label.trim() in cancelLabels
    fun isPublish(label: String): Boolean = label.trim() in publishLabels

    fun isTopToolbar(bounds: ComposerBounds, screenHeight: Int): Boolean =
        screenHeight > 0 && bounds.width > 0 && bounds.height > 0 &&
            bounds.top >= 0 && bounds.centerY <= screenHeight * 14 / 100 &&
            bounds.bottom <= screenHeight * 18 / 100

    fun isCameraControl(bounds: ComposerBounds, width: Int, height: Int, label: String): Boolean =
        isTopToolbar(bounds, height) && bounds.centerX in (width * 3 / 4) until width &&
            bounds.width <= width / 4 && (label.isBlank() || isCamera(label))

    fun composerToolbar(cancel: ComposerBounds, publish: ComposerBounds, width: Int, height: Int): Boolean =
        isTopToolbar(cancel, height) && isTopToolbar(publish, height) &&
            cancel.centerX in 0 until width / 3 && publish.centerX in (width * 2 / 3) until width &&
            cancel.width <= width / 3 && publish.width <= width / 3 &&
            kotlin.math.abs(cancel.centerY - publish.centerY) <= maxOf(cancel.height, publish.height)

    fun composerEditor(
        bounds: ComposerBounds, toolbar: ComposerBounds, width: Int, height: Int,
        hint: String?, resourceId: String?,
    ): Boolean {
        val metadata = hint.orEmpty() + " " + resourceId.orEmpty().substringAfterLast('/')
        return bounds.width >= width / 2 && bounds.height > 0 &&
            bounds.top >= toolbar.bottom && bounds.top < height / 3 &&
            !Regex("搜索|search|评论|comment|回复|reply|验证码|password|支付|标题", RegexOption.IGNORE_CASE)
                .containsMatchIn(metadata)
    }
}
