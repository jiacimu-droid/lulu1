package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * Evidence-based end of a particular relationship dispute, not a mood classifier.
 *
 * Only the user's explicit, affirmative words can settle a repair attempt.
 * An apology, delivery receipt, silence or the character's wish to be forgiven
 * cannot do so. Original chat evidence remains the authority and may be deleted.
 */
internal object RelationshipRepairContinuity {
    private val directClosure = listOf(
        "我原谅你了", "我原谅你啦", "原谅你了", "原谅你啦",
        "我不生气了", "已经不生气了", "不怪你了", "我不怪你了",
        "我们和好了", "已经和好了", "和好啦", "我们说开了",
        "已经说开了", "说开了", "都说开了", "已经讲开了",
        "这件事翻篇了", "这事翻篇了", "这件事过去了", "这事过去了",
        "这件事解决了", "这事解决了", "误会解开了", "我们已经说清楚了",
    )
    private val negation = listOf(
        "还没原谅", "没有原谅", "不原谅", "不会原谅",
        "还没和好", "没有和好", "并没和好", "还没说开",
        "没有说开", "并没说开", "还在生气", "并没有解决",
        "不代表原谅", "不等于原谅", "没有过去", "还没解决",
    )
    private fun normalized(text: String): String =
        text.lowercase().replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_\\\"'（）()]+"), "")

    fun userExplicitlySettled(text: String): Boolean {
        val s = normalized(text).take(600)
        if (s.isBlank() || negation.any(s::contains)) return false
        // Hypothetical, quoted or interrogative reconciliation is not assent.
        if (listOf("假如", "如果", "假设", "能不能", "是不是", "凭什么",
                "怎么可能", "你觉得", "他说", "她说", "比如", "例如").any(s::contains)) return false
        return directClosure.any(s::contains)
    }

    fun isRepairConcern(focus: String): Boolean {
        val s = normalized(focus)
        val repair = listOf("原谅", "道歉", "和好", "修复关系", "消除误会",
            "解开误会", "弥补", "补偿", "求她", "让她不生气",
            "她还在生气", "她还委屈", "惹她不开心", "她还难过",
            "跟她说开", "和她说开", "恢复信任", "重新获得信任")
        return (repair.any(s::contains) ||
            Regex("修复.{0,12}关系|弥补.{0,12}伤害|取得.{0,8}原谅|处理.{0,6}(争吵|矛盾)").containsMatchIn(s)) &&
            // An unrelated NPC's dispute is not resolved by the user's words.
            !listOf("群友", "居民", "npc", "同事", "朋友之间").any(s::contains)
    }

    fun isRepairGoal(aim: String): Boolean = isRepairConcern(aim)

    fun eligiblePriorState(createdAt: String, resolvedAt: Instant): Boolean {
        val created = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return false
        val age = Duration.between(created, resolvedAt)
        return !age.isNegative && age <= Duration.ofDays(30)
    }

    /** Used only for unsolicited autonomous messages, never for direct user replies. */
    fun isReopenedApology(text: String): Boolean {
        val compact = normalized(text).take(550)
        if (compact.isEmpty()) return false
        return listOf("对不起", "是我不好", "我错了", "原谅我", "别生我气",
            "还委屈", "还生气", "再给我一次机会").any(compact::contains) &&
            !listOf("不用说对不起", "不必说对不起", "别再说对不起").any(compact::contains)
    }
}
