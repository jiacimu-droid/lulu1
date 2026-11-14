package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.CharacterPerceptionContext
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldStore
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ResidentSocialMailbox
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousDuelGamesTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context

    private fun realPair(): Pair<String, String> {
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        MigratedDomainStores.initialize(app)
        DigitalLifeProfileStore.initialize(app)
        DigitalWorldStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        val actors = (0..1).map { index ->
            val created = MigratedDomainStores.characters.create("双人游戏$index-${UUID.randomUUID()}")
            DigitalLifeProfileStore.registerNewLife(created.characterId,
                created.displayName, "数字生命", Instant.now().minusSeconds(86400))
            assertTrue(DigitalWorldStore.performAction(created.characterId, "visit_public_place",
                JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD)).success)
            created.characterId
        }
        return actors[0] to actors[1]
    }

    @Test fun onlyTwoSmallGamesAreOpenForPersonToPersonChallenge() {
        assertEquals(setOf("yacht_dice", "memory_match"), AutonomousDuelRules.games.keys)
        for (id in listOf("relic_escape", "block_island", "deep_sea_journey")) {
            assertThrows(IllegalArgumentException::class.java) { AutonomousDuelRules.play(id, 12) }
        }
    }

    @Test fun yachtUsesAllThirteenRealCategoriesForBothPlayers() {
        val game = AutonomousDuelRules.play("yacht_dice", 914)
        assertEquals(26, game.turns)
        assertEquals(13, game.details.getInt("roundCountPerPlayer"))
        val turns = game.details.getJSONArray("turns")
        val sheets = game.details.getJSONObject("scoreSheets")
        for (actor in 0..1) {
            val categories = sheets.getJSONObject("player$actor").getJSONObject("categories")
            assertEquals(YachtCategory.entries.size, categories.length())
            val sum = YachtCategory.entries.sumOf { categories.getInt(it.name) }
            assertEquals(sum, game.points[actor])
            assertEquals(sum, sheets.getJSONObject("player$actor").getInt("total"))
            assertEquals(13, (0 until turns.length()).count {
                turns.getJSONObject(it).getInt("player") == actor
            })
            assertTrue((0 until turns.length()).filter {
                turns.getJSONObject(it).getInt("player") == actor
            }.all { turns.getJSONObject(it).getJSONArray("rolls").length() == 3 })
        }
        assertEquals(game.points, AutonomousDuelRules.play("yacht_dice", 914).points)
    }

    @Test fun memoryPlaysTwelvePairsWithoutRevealingHiddenCardsAndHonorsExtraTurns() {
        val match = AutonomousDuelRules.play("memory_match", 802)
        assertEquals(12, match.points.sum())
        assertEquals(24, match.details.getJSONArray("cards").length())
        val turns = match.details.getJSONArray("turns")
        assertEquals(turns.length(), match.turns)
        val seen = mutableMapOf<Int, String>()
        val matched = mutableSetOf<Int>()
        var expectedPlayer = 0
        val points = IntArray(2)
        repeat(turns.length()) { index ->
            val row = turns.getJSONObject(index)
            val player = row.getInt("player")
            assertEquals("回合 $index 的行动人不合法", expectedPlayer, player)
            val first = row.getInt("first")
            val second = row.getInt("second")
            assertTrue(first != second && first !in matched && second !in matched)
            val a = row.getString("firstValue")
            val b = row.getString("secondValue")
            seen[first] = a
            seen[second] = b
            assertEquals(a == b, row.getBoolean("matched"))
            if (a == b) {
                matched.add(first)
                matched.add(second)
                points[player]++
            } else {
                expectedPlayer = 1 - player
            }
        }
        assertEquals(24, matched.size)
        assertEquals(match.points, points.toList())
        assertEquals(match.points, AutonomousDuelRules.play("memory_match", 802).points)
    }

    @Test fun invitationsNeedActualContactAndRecipientCanDeclineWithoutAnyGame() {
        val (sender, target) = realPair()
        val before = LuluGameStore(app).state.value.records.size
        assertThrows(IllegalArgumentException::class.java) {
            AutonomousDuelGames.invite(app, sender, sender, "yacht_dice", "一起玩吧")
        }
        val offered = AutonomousDuelGames.invite(app, sender, target, "memory_match", "比比谁更会记牌")
        assertEquals("pending", offered.status)
        assertEquals(offered.id, AutonomousDuelGames.pendingFor(app, target).single().id)
        assertTrue(AutonomousDuelGames.contextFor(app, target).contains(offered.id))
        assertTrue(ResidentSocialMailbox.inbox(target).any { it.content.contains(offered.id) })
        assertThrows(IllegalArgumentException::class.java) {
            AutonomousDuelGames.respond(app, sender, offered.id, "accept")
        }
        val reply = AutonomousDuelGames.respond(app, target, offered.id, "decline")
        assertTrue(reply.contains("没有开局"))
        assertEquals(before, LuluGameStore(app).state.value.records.size)
        assertTrue(AutonomousDuelGames.pendingFor(app, target).isEmpty())
    }

    @Test fun withdrawnInvitationCannotBeAcceptedEvenIfSavedAsPending() {
        val (sender, target) = realPair()
        val invite = AutonomousDuelGames.invite(app, sender, target, "memory_match", "来试试十二对记忆牌")
        val letter = ResidentSocialMailbox.inbox(target).last {
            it.content.contains("[游戏馆邀约|${invite.id}|memory_match]")
        }
        SharedExperienceTimeline.deleteEvent(ResidentSocialMailbox.evidenceId(letter, target))
        assertTrue(AutonomousDuelGames.pendingFor(app, target).none { it.id == invite.id })
        assertThrows(IllegalArgumentException::class.java) {
            AutonomousDuelGames.respond(app, target, invite.id, "accept")
        }
    }

    @Test fun acceptedMatchSettlesOnceAndBothParticipantsHaveSameWinner() {
        val (sender, target) = realPair()
        val offer = AutonomousDuelGames.invite(app, sender, target, "yacht_dice", "来比快艇骰子吧")
        val result = AutonomousDuelGames.respond(app, target, offer.id, "accept")
        assertTrue(result.contains("13轮"))
        assertEquals(result, AutonomousDuelGames.respond(app, target, offer.id, "accept"))
        val records = LuluGames.store.state.value.records.filter { it.id.startsWith("duel-${offer.id}-") }
        assertEquals(2, records.size)
        assertEquals(setOf(sender, target), records.map { it.characterId }.toSet())
        val sheet = JSONObject(records.first().detailsJson).getJSONObject("scoreSheets")
        val names = records.associate { it.characterId to MigratedDomainStores.characters.get(it.characterId).displayName }
        assertEquals(names[sender], sheet.getJSONObject("player0").getString("name"))
        assertEquals(names[target], sheet.getJSONObject("player1").getString("name"))
        val winnerLists = records.map { JSONObject(it.detailsJson).getJSONArray("winnerIds").toString() }
        assertEquals(winnerLists.first(), winnerLists.last())
        assertEquals(setOf(0, 1), records.map { JSONObject(it.detailsJson).getInt("playerIndex") }.toSet())
        assertTrue(SharedExperienceTimeline.eventsByIds(sender, listOf("duel-game-${offer.id}-$sender")).isNotEmpty())
        assertTrue(SharedExperienceTimeline.eventsByIds(target, listOf("duel-game-${offer.id}-$target")).isNotEmpty())
        val observed = SharedExperienceTimeline.eventsByIds(sender, listOf("duel-game-${offer.id}-$sender"))
        assertTrue(CharacterPerceptionContext.selectRecent(observed, Instant.now().plusSeconds(1)).any { it.content.contains("胜者") || it.content.contains("平局") })
        assertTrue(SharedExperienceTimeline.eventsByIds("uninvited-third-party", listOf("duel-game-${offer.id}-$sender")).isEmpty())
    }
}
