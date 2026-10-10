package com.jiacimu.lulu

/**
 * Accurate picture descriptions are authored with the catalogue itself.
 * The character is not shown images by the text-only model; the description
 * is what is visibly drawn, while usage is merely a possible social reading.
 *
 * Art: OpenMoji contributors, CC BY-SA 4.0, https://openmoji.org/
 */
internal data class BuiltInCuteSticker(
    val code: String,
    val name: String,
    val pack: String,
    val description: String,
    val usage: String,
) {
    val id: String get() = "openmoji-${code.lowercase()}"
    val url: String get() =
        "https://raw.githubusercontent.com/hfg-gmuend/openmoji/master/color/72x72/$code.png"
}

internal object BundledCuteStickerCatalog {
    const val SOURCE = "https://github.com/hfg-gmuend/openmoji"
    const val LICENSE = "CC BY-SA 4.0"
    val items: List<BuiltInCuteSticker> = listOf(
        BuiltInCuteSticker("1F63A", "猫猫大笑", "猫猫表情", "一张简约黄橙色猫脸，尖尖的猫耳、张开的笑嘴，正面露出开心的表情。", "开心地打招呼、笑了"),
        BuiltInCuteSticker("1F638", "猫猫眯眼笑", "猫猫表情", "黄橙色猫咪的正面脸，耳朵竖起，眼睛弯成两道笑弧，嘴巴咧开。", "咧嘴开心、很满足"),
        BuiltInCuteSticker("1F63B", "猫猫心动", "猫猫表情", "一张黄橙色猫脸，双眼变成两颗红色爱心，咧嘴笑着。", "喜欢、心动、被可爱到"),
        BuiltInCuteSticker("1F63D", "猫猫亲亲", "猫猫表情", "黄橙色的猫咪脸，眼睛闭着，嘴巴嘟起呈亲吻状，脸颊带着柔和的神情。", "亲亲、撒娇、甜甜的示好"),
        BuiltInCuteSticker("1F63C", "猫猫坏笑", "猫猫表情", "黄橙色猫咪的正面脸，眼睛半眯，一边嘴角微微上扬，像在偷偷得意。", "狡黠、得意、想逗人"),
        BuiltInCuteSticker("1F640", "猫猫震惊", "猫猫表情", "黄橙色猫脸，两只眼睛睁得大大的，嘴巴张得很圆，像受了一惊。", "震惊、哎呀、怎么回事"),
        BuiltInCuteSticker("1F63F", "猫猫掉眼泪", "猫猫表情", "黄橙色猫脸，眉眼低垂，脸上流下明显的眼泪，嘴角下弯。", "委屈、难过、求安慰"),
        BuiltInCuteSticker("1F63E", "猫猫气鼓鼓", "猫猫表情", "黄橙色猫脸，眉头皱起、嘴角向下，表情严肃不高兴。", "生气、哼、气鼓鼓"),
        BuiltInCuteSticker("1F639", "猫猫笑出泪", "猫猫表情", "黄橙色猫咪咧嘴笑着，眼睛弯起来，眼角有蓝色泪珠。", "笑哭了、乐得不行"),
        BuiltInCuteSticker("1F430", "小白兔头像", "软萌动物", "白色兔子的正面脸，长长的竖耳朵，粉色耳朵内侧和小鼻子，圆圆的脸。", "乖巧、软萌、想亲近"),
        BuiltInCuteSticker("1F407", "小兔子全身", "软萌动物", "一只浅色的小兔子，能看到长耳朵、圆滚滚的身体和短尾巴，不只是脸部表情。", "兔兔出没、活泼的动物梗"),
        BuiltInCuteSticker("1F43C", "熊猫头像", "软萌动物", "黑白色熊猫的正面脸，两只黑耳朵，眼睛周围有大块黑色眼圈。", "呆呆的、茫然、卖萌"),
        BuiltInCuteSticker("1F43B", "小熊头像", "软萌动物", "一张棕色小熊的正面脸，圆耳朵、黑色小眼睛和颜色较浅的鼻口部。", "暖乎乎、憨憨、陪伴"),
        BuiltInCuteSticker("1F439", "仓鼠头像", "软萌动物", "一张小仓鼠的圆脸，两边鼓鼓的腮帮子，小圆耳朵和深色眼睛。", "呆萌、可爱、嘴巴鼓鼓"),
        BuiltInCuteSticker("1F42D", "小鼠头像", "软萌动物", "一张灰色小老鼠的正面脸，大大的圆耳朵、粉色鼻子和细细的胡须。", "小心翼翼、探头、萌萌的"),
        BuiltInCuteSticker("1F436", "小狗头像", "软萌动物", "一只棕白色小狗的正面脸，耳朵垂在脸旁边，黑色小鼻子，眼睛圆圆的。", "乖狗狗、求关注、友好"),
        BuiltInCuteSticker("1F424", "小黄鸡", "软萌动物", "一只黄色的小鸡，有小小的翅膀、橙色小嘴和黑色眼睛。", "啾啾、软乎乎、俏皮"),
        BuiltInCuteSticker("1F970", "爱心环绕脸", "日常心情梗", "一张黄色笑脸，眼睛微微眯起，脸颊周围飘着几颗红色爱心。", "幸福、喜欢得不行"),
        BuiltInCuteSticker("1F979", "眼含泪光脸", "日常心情梗", "一张黄色圆脸，睁着圆圆的眼睛，眼里积着大颗闪亮泪水，像在努力忍住哭。", "感动、委屈巴巴"),
        BuiltInCuteSticker("1F92D", "捂嘴偷笑脸", "日常心情梗", "一张黄色圆脸，一只手捂在嘴巴前，眼睛带着笑意。", "偷笑、忍不住了、嘻嘻"),
        BuiltInCuteSticker("1F914", "托腮思考脸", "日常心情梗", "一张黄色圆脸，眉头微皱，一只手托住下巴，像正在琢磨事情。", "疑惑、认真考虑"),
        BuiltInCuteSticker("1F92F", "脑袋炸裂脸", "日常心情梗", "一张黄色惊讶的脸，头顶部分夸张地炸开，像烟花或爆炸的云朵。", "震惊到脑袋宕机"),
        BuiltInCuteSticker("1F440", "两只眼睛", "日常心情梗", "画面主要是并排的一双睁开的眼睛，眼珠看向侧方，没有完整的头和身体。", "围观、悄悄看、吃瓜"),
        BuiltInCuteSticker("1F973", "派对庆祝脸", "日常心情梗", "黄色笑脸戴着彩色派对帽，嘴边有吹起来的派对哨，周围有庆祝彩屑。", "庆祝、好耶、开心"),
        BuiltInCuteSticker("1F97A", "可怜巴巴脸", "日常心情梗", "一张黄色圆脸，眼睛特别大而湿润，眉毛向中间抬起，像在期待别人心软。", "撒娇、求求、拜托"),
        BuiltInCuteSticker("1F60E", "墨镜酷脸", "日常心情梗", "黄色的笑脸，脸上戴着黑色墨镜，嘴角自信地弯起。", "酷、得意、装帅"),
        BuiltInCuteSticker("1F633", "红脸发呆", "日常心情梗", "黄色圆脸，双眼睁圆，两颊通红，嘴巴小小的，神情像突然愣住。", "脸红、尴尬、害羞"),
        BuiltInCuteSticker("1F634", "打鼾睡脸", "日常心情梗", "黄色圆脸闭着眼睛，嘴巴张开，旁边有表示睡觉的 Z 字符号。", "困了、睡觉、晚安"),
        BuiltInCuteSticker("1F44D", "点赞的手", "日常心情梗", "画面是一只黄色手掌，四指握着、拇指竖起，做出明显的点赞手势。", "赞同、好耶、做得好"),
    )
}
