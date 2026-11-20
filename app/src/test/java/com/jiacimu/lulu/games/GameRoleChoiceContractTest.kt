package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GameRoleChoiceContractTest {
    @Test fun yachtRoleCanSacrificeScoreInsteadOfBeingForcedToHighestImmediateValue() {
        val decision = parseYachtRoleDecision(
            """{"action":"score","category":"Ones","say":"这一格先忍了。"}""",
            setOf(YachtCategory.Ones, YachtCategory.Chance), roll = 1,
        )
        assertEquals(YachtRoleDecision.Score(YachtCategory.Ones, "这一格先忍了。"), decision)
        assertEquals(
            YachtRoleDecision.Keep(setOf(0, 2, 4), "我想再赌一次", YachtCategory.Ones),
            parseYachtRoleDecision(
                """{"action":"keep","indices":[0,2,4],"fallbackCategory":"Ones","say":"我想再赌一次"}""",
                setOf(YachtCategory.Ones), roll = 2,
            ),
        )
    }

    @Test fun secondRollBackupMustBeRoleApprovedAndAvailable() {
        val available = setOf(YachtCategory.Fours, YachtCategory.Chance)
        assertEquals(
            YachtRoleDecision.Keep(setOf(1, 3), "想再摇一次", YachtCategory.Fours),
            parseYachtRoleDecision(
                """{"action":"keep","indices":[1,3],"fallbackCategory":"Fours","say":"想再摇一次"}""",
                available, roll = 2,
            ),
        )
        assertNull(parseYachtRoleDecision(
            """{"action":"keep","indices":[1],"say":""}""", available, roll = 2,
        ))
        assertNull(parseYachtRoleDecision(
            """{"action":"keep","indices":[1],"fallbackCategory":"Yacht","say":""}""",
            available, roll = 2,
        ))
        assertNull(parseYachtRoleDecision(
            """{"action":"keep","indices":[1],"fallbackCategory":"Chance","say":""}""",
            available, roll = 3,
        ))
    }

    @Test fun yachtInvalidChoicesNeverTurnIntoAutomaticMaxScore() {
        val available = setOf(YachtCategory.Ones)
        assertNull(parseYachtRoleDecision(
            """{"action":"score","category":"Chance","say":""}""", available, 1,
        ))
        assertNull(parseYachtRoleDecision(
            """{"action":"keep","indices":[0],"say":""}""", available, 3,
        ))
        assertNull(parseYachtRoleDecision(
            """{"action":"keep","indices":[1,1],"say":""}""", available, 1,
        ))
        assertNull(parseYachtRoleDecision(
            """{"action":"keep","indices":[5],"say":""}""", available, 2,
        ))
        assertNull(parseYachtRoleDecision("想想再说", available, 1))
    }

    @Test fun memoryRoleCanSelectOnlyCurrentlyUnopenedAndUnmatchedCards() {
        assertEquals(
            MemoryRoleChoice(2, "这里我好像见过"),
            parseMemoryRoleChoice("""{"index":2,"say":"这里我好像见过"}""", setOf(2, 5)),
        )
        assertNull(parseMemoryRoleChoice("""{"index":1,"say":"偷看"}""", setOf(2, 5)))
        assertNull(parseMemoryRoleChoice("""{"index":99,"say":""}""", setOf(2, 5)))
        assertNull(parseMemoryRoleChoice("我选第2张", setOf(2, 5)))
    }
}
