package com.jiacimu.lulu.data

/**
 * Detect the role's own immediate intention, independent of JSON field shape.
 * The executor still requires the real, undeleted user message before saving.
 * "Might later", quoted questions and tool explanations are NOT decisions.
 */
internal object CharacterPhoneFavoriteIntent {
    private val wish = Regex(
        """(?:我|自己|这句话|这条消息).{0,18}(?:想|要|决定|打算|准备|这就|马上|现在).{0,12}(?:收藏|收进辞海|存进辞海|收起来)|(?:我|自己)(?:现在|马上|这就)?.{0,10}(?:收藏|收进辞海|存进辞海)(?:这句|这条|你刚才的话)"""
    )
    private val notNow = Regex(
        """(?:不想|不打算|不准备|别|不能|无法|没法).{0,14}(?:收藏|收起来)|(?:等|挂了|挂断|待会|过会|明天|回头|以后|下次|有空|稍后|假如|如果|或许|可能).{0,22}(?:收藏|收起来)|(?:收藏|收起来).{0,12}(?:以后|再说|回头|吗|？|\?)|(?:收藏).{0,10}(?:功能|工具|接口|权限|线路)|(?:功能|工具|接口|权限|线路).{0,10}(?:收藏)"""
    )
    fun decided(text: String): Boolean {
        val clean = text.trim().replace(Regex("""\s+"""), "")
        if (clean.isEmpty() || clean.length > 300 || notNow.containsMatchIn(clean)) return false
        return wish.containsMatchIn(clean)
    }
}
