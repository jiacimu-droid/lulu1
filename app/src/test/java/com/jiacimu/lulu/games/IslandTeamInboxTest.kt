package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandTeamInboxTest {
    @Test fun oneTeammateFailureKeepsItsUnreadMessagesWithoutRepeatingSuccessfulDelivery() {
        var inbox = IslandTeamInbox().enqueue("小心左边").enqueue("先别走")
        inbox = inbox.acknowledge("alice", 2).compact(listOf("alice", "bob"))
        assertEquals(emptyList<String>(), inbox.forRole("alice"))
        assertEquals(listOf("小心左边", "先别走"), inbox.forRole("bob"))
        inbox = inbox.acknowledge("bob", 2).compact(listOf("alice", "bob"))
        assertTrue(inbox.messages.isEmpty())
    }

    @Test fun newSpeechWhileModelWorksIsNotAccidentallyAcknowledged() {
        val snapshot = IslandTeamInbox().enqueue("先去看看")
        var live = snapshot.enqueue("我发现了矿石")
        live = live.acknowledge("alice", snapshot.messages.size)
            .acknowledge("bob", snapshot.messages.size)
            .compact(listOf("alice", "bob"))
        assertEquals(listOf("我发现了矿石"), live.messages)
        assertEquals(listOf("我发现了矿石"), live.forRole("alice"))
        assertEquals(listOf("我发现了矿石"), live.forRole("bob"))
    }

    @Test fun extraOrDuplicateAcknowledgementDoesNotConsumeNewSpeech() {
        val original = IslandTeamInbox().enqueue("A")
        val once = original.acknowledge("alice", 1)
        val duplicate = once.acknowledge("alice", 1).enqueue("B")
        assertEquals(listOf("B"), duplicate.forRole("alice"))
        assertEquals(listOf("A", "B"), duplicate.forRole("bob"))
        assertEquals(duplicate, duplicate.acknowledge("", 99))
    }

    @Test fun independentReceiptsSurviveRotationAndKeepEachCharacterSeparate() {
        val before = IslandTeamInbox().enqueue("集合").enqueue("等等")
            .acknowledge("alice", 2)
        val restored = IslandTeamInbox.fromJson(before.toJson())
        assertEquals(before, restored)
        assertEquals(emptyList<String>(), restored.forRole("alice"))
        assertEquals(listOf("集合", "等等"), restored.forRole("bob"))
    }
}
