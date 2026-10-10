package com.jiacimu.lulu

/**
 * An approved, consistently illustrated collection curated for the Lulu chat UI.
 * Art: OpenMoji contributors (CC BY-SA 4.0).
 * https://github.com/hfg-gmuend/openmoji
 * Only exact code points from this allowlisted upstream are downloaded.
 */
internal data class BuiltInCuteSticker(val code: String, val name: String, val pack: String) {
    val id: String get() = "openmoji-${code.lowercase()}"
    val url: String get() =
        "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/72x72/$code.png"
}

internal object BundledCuteStickerCatalog {
    const val SOURCE = "https://github.com/hfg-gmuend/openmoji"
    const val LICENSE = "CC BY-SA 4.0"
    val items: List<BuiltInCuteSticker> = listOf(
        BuiltInCuteSticker("1F63A", "猫猫大笑 · 得意", "猫猫表情"),
        BuiltInCuteSticker("1F638", "猫猫笑哭 · 高兴", "猫猫表情"),
        BuiltInCuteSticker("1F63B", "猫猫心动 · 喜欢", "猫猫表情"),
        BuiltInCuteSticker("1F63D", "猫猫亲亲 · 撒娇", "猫猫表情"),
        BuiltInCuteSticker("1F63C", "猫猫坏笑 · 调皮", "猫猫表情"),
        BuiltInCuteSticker("1F640", "猫猫震惊 · 啊？", "猫猫表情"),
        BuiltInCuteSticker("1F63F", "猫猫委屈 · 想哭", "猫猫表情"),
        BuiltInCuteSticker("1F63E", "猫猫生气 · 哼", "猫猫表情"),
        BuiltInCuteSticker("1F639", "猫猫感动 · 笑出眼泪", "猫猫表情"),

        BuiltInCuteSticker("1F430", "兔兔乖巧 · 卖萌", "软萌动物"),
        BuiltInCuteSticker("1F407", "兔兔蹦跶 · 活力", "软萌动物"),
        BuiltInCuteSticker("1F43C", "熊猫呆呆 · 无语", "软萌动物"),
        BuiltInCuteSticker("1F43B", "小熊抱抱 · 温暖", "软萌动物"),
        BuiltInCuteSticker("1F439", "仓鼠眨眼 · 呆萌", "软萌动物"),
        BuiltInCuteSticker("1F42D", "小鼠探头 · 偷看", "软萌动物"),
        BuiltInCuteSticker("1F436", "小狗乖巧 · 期待", "软萌动物"),
        BuiltInCuteSticker("1F424", "小黄鸡 · 贴贴", "软萌动物"),

        BuiltInCuteSticker("1F970", "冒爱心 · 超喜欢", "日常心情梗"),
        BuiltInCuteSticker("1F979", "感动想哭 · 眼泪汪汪", "日常心情梗"),
        BuiltInCuteSticker("1F92D", "偷笑捂嘴 · 憋不住", "日常心情梗"),
        BuiltInCuteSticker("1F914", "思考中 · 嗯？", "日常心情梗"),
        BuiltInCuteSticker("1F92F", "脑袋爆炸 · 震惊", "日常心情梗"),
        BuiltInCuteSticker("1F440", "暗中观察 · 偷看", "日常心情梗"),
        BuiltInCuteSticker("1F973", "开心庆祝 · 耶", "日常心情梗"),
        BuiltInCuteSticker("1F97A", "可怜巴巴 · 撒娇", "日常心情梗"),
        BuiltInCuteSticker("1F60E", "墨镜得意 · 酷", "日常心情梗"),
        BuiltInCuteSticker("1F633", "脸红无措 · 害羞", "日常心情梗"),
        BuiltInCuteSticker("1F634", "困困睡觉 · 晚安", "日常心情梗"),
        BuiltInCuteSticker("1F44D", "点赞 · 好耶", "日常心情梗"),
    )
}
