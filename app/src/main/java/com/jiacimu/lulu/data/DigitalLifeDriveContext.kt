package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant

/**
 * Soft, evidence-based life rhythm for autonomous characters.
 *
 * This deliberately does not assign quotas or random actions. It only exposes which parts of a
 * character's real life have recently happened (or not happened) so personality, relationships and
 * current desire can turn long gaps into curiosity, boredom, expression or social impulses when
 * that actually fits the character.
 */
internal object DigitalLifeDriveContext {
    fun promptSection(
        characterId: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val birth = DigitalLifeProfileStore.birthAt(characterId) ?: now
        val events = SharedExperienceTimeline.all(characterId)
            .filter { !it.occurredAt.isAfter(now) }

        fun lastOwn(predicate: (SharedTimelineEvent) -> Boolean): Instant? = events.asReversed()
            .firstOrNull { event -> event.speaker == displayName && predicate(event) }
            ?.occurredAt

        val lastMoment = lastOwn { it.channel.contains("朋友圈") }
        val lastJournal = lastOwn { it.channel.contains("日记") }
        val lastGroup = lastOwn { it.channel.startsWith("群聊") }
        val lastPrivate = lastOwn { it.channel == "私聊" }
        val lastCall = lastOwn { it.channel.contains("电话") }
        val lastReading = lastOwn { it.channel.startsWith("独自阅读") }
        val lastGame = lastOwn { it.channel.startsWith("独自游戏") }
        val lastRealityWindow = lastOwn { it.channel.startsWith("现实世界窗口") }
        val lastWorldAction = lastOwn {
            it.channel == "数字世界" || it.channel.startsWith("数字世界·生活片段")
        }
        val lastCharacterMeeting = events.asReversed().firstOrNull { event ->
            event.occurredAt <= now &&
                (event.channel.contains("角色自主") || event.channel.contains("数字世界见面") || event.channel.contains("共同经历"))
        }?.occurredAt

        fun gapHours(last: Instant?): Long = Duration.between(last ?: birth, now).toHours().coerceAtLeast(0)
        fun ageLabel(last: Instant?): String {
            if (last == null) return "还没有真实记录"
            val minutes = Duration.between(last, now).toMinutes().coerceAtLeast(0)
            return when {
                minutes < 10 -> "刚刚"
                minutes < 60 -> "约${minutes}分钟前"
                minutes < 24 * 60 -> "约${minutes / 60}小时前"
                else -> "约${minutes / (24 * 60)}天前"
            }
        }

        val socialNames = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
        val softSignals = buildList {
            val lifeHours = Duration.between(birth, now).toHours().coerceAtLeast(0)
            fun addIfOld(last: Instant?, thresholdHours: Long, text: String) {
                if (lifeHours >= thresholdHours && gapHours(last) >= thresholdHours) add(text)
            }
            addIfOld(lastWorldAction, 18, "探索/换环境已经有一阵没发生；如果这个角色会无聊、好奇或坐不住，可以认真考虑出门、逛公共地点、串门或使用新的真实设施。")
            if (socialNames?.optString("userRemark").isNullOrBlank())
                add("还没给用户留过私人联系人备注；如果某次相处让你真正想留个称呼，可以自主设置，不必为了填资料强行命名。")
            if (socialNames?.optString("selfNickname").isNullOrBlank())
                add("自己一直沿用初始网名；如果某次经历激发了新的自我表达，也可以自然想换个网名，否则继续沿用就好。")
            addIfOld(lastGroup, 24, "和伙伴主动群聊已经有一阵没发生；如果她本来就会分享、吐槽、接话或想找同伴，可以主动开口，不必等用户先说。")
            addIfOld(lastMoment, 30, "朋友圈已经有一阵没出现；若她有公开分享习惯，小事、读后感、游戏结果、环境瞬间、吐槽或得意都可以成为真实分享，不需要等“大事件”。")
            addIfOld(lastJournal, 48, "私人日记已经有一阵没写；若她有自我整理、记录或情绪消化习惯，可以写；若人设本来不爱写日记，就继续不写。")
            addIfOld(lastReading, 36, "阅读已经有一阵没发生；若她对阅读、故事或新知识有兴趣，可以从真实书目继续读。")
            addIfOld(lastGame, 36, "独自游戏已经有一阵没发生；若她爱玩、想挑战或只是想消遣，可以真的去玩一局。")
            addIfOld(lastRealityWindow, 30, "现实世界窗口已经有一阵没主动看；如果这个角色有求知欲、追番/游戏/新闻习惯、关心现实动态或单纯好奇，可以自己探索一个现实圈子；不需要为了显得活跃而硬看新闻。")
            addIfOld(lastCharacterMeeting, 48, "和其他数字生命的线下相处已经有一阵没发生；若关系与性格合适，可以去公共地点或串门，但不是强制社交。")
        }

        return buildString {
            appendLine("【生活节奏与未展开的可能性｜软驱动力，不是任务清单】")
            appendLine("- 这部分来自真实时间线，只描述最近哪些生活面向发生过。它不是配额、轮换表、冷却惩罚或强制多样化；最终仍由人设、关系、记忆、当前心情和现实可执行条件决定。")
            appendLine("- 公开表达：朋友圈=${ageLabel(lastMoment)}；私人整理：日记=${ageLabel(lastJournal)}。")
            appendLine("- 主动社交：群聊=${ageLabel(lastGroup)}；私聊用户=${ageLabel(lastPrivate)}；电话=${ageLabel(lastCall)}；角色间见面=${ageLabel(lastCharacterMeeting)}。")
            appendLine("- 自己的生活：数字世界行动=${ageLabel(lastWorldAction)}；阅读=${ageLabel(lastReading)}；独自游戏=${ageLabel(lastGame)}；现实窗口=${ageLabel(lastRealityWindow)}。")
            appendLine("- 生命不必围着用户转。可以因为无聊、好奇、审美、习惯、关系、刚经历的小事、突然想记录、想找人说话、想换个地方或想看看现实世界而主动行动；也允许因为性格和状态完全不想做某一类事。")
            if (softSignals.isNotEmpty()) {
                appendLine("- 当前可能自然冒出来、但完全不强制的生活冲动：")
                softSignals.take(7).forEach { appendLine("  · $it") }
            }
        }.trim()
    }
}