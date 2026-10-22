package com.jiacimu.lulu

/** Only completed tactile gestures become durable call facts. */
internal object Live2DTouchZones {
    val labels: Map<String, String> = linkedMapOf(
        "ear-left" to "左耳", "ear-right" to "右耳",
        "hair" to "头发", "forehead" to "额头",
        "eye-left" to "左眼", "eye-right" to "右眼",
        "nose" to "鼻子", "cheek-left" to "左脸颊",
        "cheek-right" to "右脸颊", "mouth" to "嘴巴",
        "neck" to "脖子", "collarbone" to "锁骨",
        "chest" to "胸口", "arm-left" to "左手臂",
        "arm-right" to "右手臂", "hand-left" to "左手",
        "hand-right" to "右手", "body" to "身体",
        "head" to "头部", "arm" to "手臂",
    )
    fun label(region: String): String? = labels[region]
}
