package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AgreedCommitmentDeadlineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = Instant.parse("2026-10-25T17:00:00Z") // 26th at 01:00 local
    private fun resolve(user: String, role: String, whenAt: Instant = at): Instant? =
        AgreedCommitmentDeadline.fromAgreement(user, role, whenAt, zone)

    @Test fun spokenSameDaySevenHoursOverridesOldTomorrowFragment() {
        val agreed = resolve("明天8点。不对，不对，是8点。你懂了吧",
            "懂了，是今天早上8点，七个小时之后。")
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), agreed)
    }

    @Test fun correctionAcrossUserBubblesUsesLatestMeaning() {
        val agreed = resolve("明天8点\n不对，是8点\n你懂了吧",
            "对，是今天8点，7小时后")
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), agreed)
    }

    @Test fun yesterdayBeforeMidnightCanResolveNextCalendarDay() {
        val beforeMidnight = Instant.parse("2026-10-25T15:00:00Z") // local 23:00
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"),
            resolve("明天8点", "明天早上8点，9小时后", beforeMidnight))
    }

    @Test fun explicitUncorrectedDateCannotBeOverriddenByWrongRole() {
        assertNull(resolve("明天8点", "今天8点，7小时后"))
        assertNull(resolve("今天8点", "明天8点，7小时后"))
    }

    @Test fun noAgreementAndMismatchedHoursMustNotSchedule() {
        assertNull(resolve("8点", "好，我明天8点找你"))
        assertNull(resolve("8点", "今天9点，七个小时之后"))
        assertNull(resolve("8点", "今天8点，十五小时以后"))
        assertNull(resolve("不对，是8点", "嗯，知道了"))
    }

    @Test fun explicitUserDateStillWorksWhenTheRoleRepeatsOnlyTheClock() {
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"),
            resolve("明天8点", "好，我8点找你"))
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"),
            resolve("今天8点", "好，我8点找你"))
    }

    @Test fun finalClockChangeOverridesAnEarlierClockInsteadOfTheFirstMatch() {
        val agreed = resolve("明天9点，不对是8点", "我明白，今天8点，七个小时之后")
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), agreed)
    }

    @Test fun agreedEveningClockUsesTwentyFourHourTime() {
        assertEquals(Instant.parse("2026-10-26T12:00:00Z"),
            resolve("不对，是今天晚上8点",
                "明白了，是今天晚上8点，十九个小时之后"))
    }

    @Test fun fixedClockWinsOverRoundedRelativeHours() {
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"),
            resolve("哦不对是8点", "还有7个小时之后，今天8点",
                at.plusSeconds(5 * 60)))
    }

    @Test fun correctedGoalDoesNotPreserveWrongDateHeading() {
        val instant = Instant.parse("2026-10-26T00:00:00Z")
        assertEquals("10月26日 08:00 按约定叫醒用户",
            AgreedCommitmentDeadline.repairedGoal(
                "10月27日 08:00 按约定叫醒用户", instant, zone))
    }
}
