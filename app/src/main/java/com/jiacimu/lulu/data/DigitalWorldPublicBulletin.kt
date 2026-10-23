package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek

/** Shared civic calendar: announcements are public facts, not fabricated
 * meetings, scores, disasters, users, relationships or completed purchases.
 * Quiet days are legitimate. A character must truly go to the venue to
 * witness what happens there.
 */
internal data class DigitalCivicEvent(
    val eventId: String, val location: String, val title: String,
    val notice: String, val time: String,
)

internal object DigitalWorldPublicBulletin {
    fun forDate(at: Instant, zone: ZoneId = ZoneId.systemDefault()): DigitalCivicEvent? {
        val date = at.atZone(zone).toLocalDate()
        val event = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> DigitalCivicEvent("games", DigitalWorldPublicPlaces.GAME_HALL,
                "游戏馆晚间挑战日", "游戏馆开放自愿参加的记忆配对挑战；没有默认参赛者或成绩。", "19:00")
            DayOfWeek.WEDNESDAY -> DigitalCivicEvent("cafe", DigitalWorldPublicPlaces.CAFE,
                "咖啡角小聚时段", "咖啡角预留一段开放聊天的时间；来不来由居民自己决定。", "18:30")
            DayOfWeek.FRIDAY -> DigitalCivicEvent("courtyard", DigitalWorldPublicPlaces.COURTYARD,
                "庭院交换角", "庭院开放分享小作品与兴趣话题的空间；实际交换必须有人参加。", "17:00")
            DayOfWeek.SATURDAY -> DigitalCivicEvent("game-weekend", DigitalWorldPublicPlaces.GAME_HALL,
                "周末游戏约局", "游戏馆提供自由组队的机会；邀请和比赛需要真实回应。", "15:00")
            DayOfWeek.SUNDAY -> DigitalCivicEvent("cafe-weekend", DigitalWorldPublicPlaces.CAFE,
                "周末下午茶时段", "咖啡角周末照常营业，欢迎想闲聊或歇歇脚的人。", "16:00")
            else -> null
        }
        return event?.copy(eventId = "${date}:${event.eventId}")
    }

    fun publicContext(at: Instant): String = forDate(at)?.let {
        "【数字世界公开公告】${it.title}；日期ID=${it.eventId}；时间=${it.time}；地点=${it.location}；${it.notice}（这只是公告，不代表有人参加或活动已经发生）"
    }.orEmpty()
}
