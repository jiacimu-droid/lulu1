package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.games.LuluGames
import kotlinx.coroutines.runBlocking
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
class SoloGameSystemNoticeTest {
    @Test fun autonomousRelicSendsOneIndependentSystemReceiptFromSavedPhysicalRun() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        LuluRepositories.initialize(app)
        MigratedDomainStores.initialize(app)
        SharedExperienceTimeline.initialize(app)
        LuluGames.initialize(app)
        val actor = MigratedDomainStores.characters.create("独玩回执验收-${UUID.randomUUID().toString().take(8)}")
        val id = actor.characterId
        val result = CompanionActionRuntime.execute(app, id, "play_solo_game",
            JSONObject().put("gameId", "relic_escape").put("focus", "safe").put("turns", 128),
            Instant.parse("2026-11-15T09:00:00Z"))
        assertTrue(result.summary, result.success)
        val record = LuluGames.store.state.value.records.first { it.id == result.evidenceId.removePrefix("game-solo-") }
        assertTrue(record.characterSolo)
        assertFalse(record.playedWithCharacter)
        assertEquals(id, record.characterId)
        val conversation = MigratedDomainStores.chat.conversations.value.first {
            it.characterId == id && it.groupChat == null
        }
        val actual = MigratedDomainStores.chat.messages(conversation.id).value.filter {
            it.sender == LuluChatMessage.Sender.System &&
                it.content.startsWith("[游戏记录] [游戏事件|solo-game-${record.id}]")
        }
        assertEquals(1, actual.size)
        assertTrue(actual.single().content.contains(record.summary))
        assertFalse(actual.single().content.startsWith("[共同活动]"))
        val again = MigratedDomainStores.chat.appendPrivateActivityNotice(
            id, "[独自游戏] ${record.summary}", "solo-game-${record.id}")
        assertEquals(actual.single().id, again.id)
        assertEquals(1, MigratedDomainStores.chat.messages(conversation.id).value.count {
            it.sender == LuluChatMessage.Sender.System &&
                it.content.startsWith("[游戏记录] [游戏事件|solo-game-${record.id}]")
        })
        val phase = JSONObject(record.detailsJson).getString("phase")
        if (phase != "SUCCESS") assertEquals(0, record.score)
    }
}
