package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Relationship network section (batch 15): private chat used to deliver
 * only the focal character's own bonds. The network section adds how the
 * focal character's contacts relate to EACH OTHER, from stored facts
 * only (shared groups, real meetings, direct mail contact). No scores,
 * no private impressions of others, no names of groups the focal
 * character is not in, and unconnected pairs are skipped entirely so
 * the section stays silent instead of dumping "尚未认识" lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterSocialNetworkTest {

    @Test
    fun acceptedQQFriendIsAnActualRelationshipEvenBeforeFirstMessageOrFormalMeeting() {
        val app = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(app)
        MigratedDomainStores.initialize(app)
        DigitalWorldStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        MomentFriendAccess.initialize(app)
        val unique = java.util.UUID.randomUUID().toString().take(8)
        val a = MigratedDomainStores.characters.create("新朋友甲-$unique").characterId
        val b = MigratedDomainStores.characters.create("新朋友乙-$unique").characterId
        try {
            val unknown = CharacterSocialRelationship.snapshot(a, b)
            assertFalse(unknown.knowsEachOther)
            assertFalse(unknown.hasConfirmedFriendship)
            assertTrue(MomentFriendAccess.setFriends(a, b, true))
            val linked = CharacterSocialRelationship.snapshot(a, b)
            assertTrue(linked.knowsEachOther)
            assertTrue(linked.hasConfirmedFriendship)
            assertEquals("双方已确认QQ好友", linked.stage)
            assertTrue(linked.shortContext().contains("QQ好友已确认"))
            assertEquals(0, linked.sharedMeetingCount)
            // Reverse observation uses the same actual friendship authority,
            // not an invented shared private attitude.
            assertTrue(CharacterSocialRelationship.snapshot(b, a).knowsEachOther)
            assertNull(CharacterSocialRelationship.perspective(a, b))
            assertNull(CharacterSocialRelationship.perspective(b, a))
        } finally {
            MomentFriendAccess.setFriends(a, b, false)
        }
        assertFalse(CharacterSocialRelationship.snapshot(a, b).hasConfirmedFriendship)
    }

    @Test
    fun encounterOnlySeesFullySharedPriorMeetingsNeverPrivateLife() {
        val a = "scene-a"
        val b = "scene-b"
        val c = "scene-c"
        val earlier = Instant.parse("2026-10-26T09:00:00Z")
        fun session(id: String, participants: List<String>, phrase: String, at: Instant) =
            MeetingSession(
                id = id, participantIds = participants,
                reality = MeetingReality.DIGITAL_WORLD,
                location = "共生庭院",
                startedAt = at.minusSeconds(60), endedAt = at,
                turns = listOf(MeetingTurn("spoken-$id", participants.first(),
                    "当时说话的人", "", phrase, at.minusSeconds(10))),
            )
        val privateMeeting = session("private", listOf(a, b),
            "只在甲乙之间说过的私下秘密", earlier)
        val publicMeeting = session("shared", listOf(a, b, c),
            "三人真的一起听见的那句笑话", earlier.plusSeconds(7200))
        val unfinished = publicMeeting.copy(id = "unfinished", endedAt = null,
            turns = listOf(MeetingTurn("incomplete", a, "甲", "",
                "没有结束也没有交付的草稿", earlier)))
        val projected = AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf(a, b, c), listOf(privateMeeting, publicMeeting, unfinished))
        assertTrue(projected.contains("三人真的一起听见"))
        assertTrue(projected.contains("shared"))
        assertFalse(projected.contains("私下秘密"))
        assertFalse(projected.contains("没有结束也没有交付的草稿"))
        // Two-person memory can retain their own witnessed words. Nothing is
        // transferred to the absent third party by the scene director.
        val pairOnly = AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf(a, b), listOf(privateMeeting))
        assertTrue(pairOnly.contains("只在甲乙之间说过"))
        assertEquals("", AutonomousSocialRuntime.witnessedSharedEncounterContext(
            listOf(a, c), listOf(privateMeeting)))
    }

    @Test
    fun pairLineSkipsUnconnectedPairs() {
        val unconnected = CharacterSocialRelationshipSnapshot(
            "a", "b", emptyList(), 0, null, "尚未认识", false)
        assertNull(CharacterSocialRelationship.networkPairLine("甲", "乙", unconnected))
    }

    @Test
    fun pairLineReportsConnectionFactsWithoutScoresOrGroupNames() {
        val met = CharacterSocialRelationshipSnapshot(
            "a", "b", listOf("共同群聊甲"), 3, Instant.parse("2026-10-20T10:00:00Z"),
            "共同群聊认识且真实见过面", true)
        val line = CharacterSocialRelationship.networkPairLine("甲", "乙", met)
        assertNotNull(line)
        assertTrue(line!!.contains("甲 ↔ 乙"))
        assertTrue(line.contains("真实见面3次"))
        assertTrue(line.contains("有真实私信来往"))
        assertFalse(line.contains("共同群聊甲"))
        assertFalse(line.contains("好感"))
    }

    @Test
    fun groupSceneDoesNotExposePairPrivateImpressionsOrPrivateLetters() {
        val app = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        MigratedDomainStores.initialize(app)
        DigitalWorldStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        CharacterInnerLifeStore.initialize(app)
        val a = MigratedDomainStores.characters.create("私交甲").characterId
        val b = MigratedDomainStores.characters.create("私交乙").characterId
        val c = MigratedDomainStores.characters.create("第三个朋友").characterId
        val introGroup = MigratedDomainStores.chat.createGroupConversation("双人群", listOf(a,b),
            ownerIncluded = false)
        assertFalse(CharacterSocialRelationship.snapshot(a,b).knowsEachOther)
        // Being added to the room is only roster awareness, not an encounter.
        MigratedDomainStores.chat.appendCharacterMessage(introGroup.id, "甲在群里先说话", a)
        assertFalse(CharacterSocialRelationship.snapshot(a,b).knowsEachOther)
        MigratedDomainStores.chat.appendCharacterMessage(introGroup.id, "乙真的接了话", b)
        assertTrue(CharacterSocialRelationship.snapshot(a,b).knowsEachOther)
        assertEquals("", CharacterSocialRelationship.contextForGroup(listOf(a,b,c)))
        val letter = ResidentSocialMailbox.send(a,b,"私下只告诉乙的秘密")
        assertFalse(CharacterSocialRelationship.contextForGroup(listOf(a,b,c))
            .contains("私下只告诉乙的秘密"))

        CharacterInnerLifeStore.observe(a, "personal-bond-test", "我当时私下生气",
            JSONObject().put("social",JSONObject()
                .put("targetId",b)
                .put("interpretation","只属于甲的私人心理印象")
                .put("reason","甲自己的判断")),setOf(b))
        assertFalse(CharacterSocialRelationship.contextForGroup(listOf(a,b,c))
            .contains("只属于甲的私人心理印象"))
        val trio = MigratedDomainStores.chat.createGroupConversation(
            "三人群", listOf(a,b,c), ownerIncluded = false)
        assertEquals("", CharacterSocialRelationship.contextForGroup(listOf(a,b,c)))
        MigratedDomainStores.chat.appendCharacterMessage(trio.id, "三人都能听到甲说", a)
        MigratedDomainStores.chat.appendCharacterMessage(trio.id, "三人都能听到乙说", b)
        val visible = CharacterSocialRelationship.contextForGroup(listOf(a,b,c))
        assertTrue(visible.contains("共同群聊"))
        assertFalse(visible.contains("私下只告诉乙的秘密"))
        assertFalse(visible.contains("只属于甲的私人心理印象"))
        assertTrue(ResidentSocialMailbox.inbox(a).any { it.id == letter.id })
        assertTrue(ResidentSocialMailbox.inbox(b).any { it.id == letter.id })
        assertTrue(ResidentSocialMailbox.inbox(c).isEmpty())
    }

    @Test
    fun thirdPartyNeverLearnsOtherTwoCharactersPrivateNetworkUntilWitnessed() {
        val app = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        MigratedDomainStores.initialize(app)
        DigitalWorldStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        val viewer = MigratedDomainStores.characters.create("旁观者").characterId
        val a = MigratedDomainStores.characters.create("甲方").characterId
        val b = MigratedDomainStores.characters.create("乙方").characterId

        // A and B know each other, but their group isn't joined by viewer.
        val privateGroup = MigratedDomainStores.chat.createGroupConversation("两人私密群", listOf(a, b),
            ownerIncluded = false)
        assertFalse(CharacterSocialRelationship.snapshot(a, b).knowsEachOther)
        MigratedDomainStores.chat.appendCharacterMessage(privateGroup.id, "甲和乙之间的一句话", a)
        MigratedDomainStores.chat.appendCharacterMessage(privateGroup.id, "乙回甲的一句话", b)
        assertTrue(CharacterSocialRelationship.snapshot(a, b).knowsEachOther)
        assertEquals("", CharacterSocialRelationship.networkContext(viewer, listOf(a, b)))

        // An actual group with all three gives the viewer limited knowledge.
        val shared = MigratedDomainStores.chat.createGroupConversation(
            "三人共同群", listOf(a, b, viewer), ownerIncluded = false)
        assertEquals("", CharacterSocialRelationship.networkContext(viewer, listOf(a, b)))
        MigratedDomainStores.chat.appendCharacterMessage(shared.id, "甲向三人打招呼", a)
        assertEquals("", CharacterSocialRelationship.networkContext(viewer, listOf(a, b)))
        MigratedDomainStores.chat.appendCharacterMessage(shared.id, "乙回应了甲", b)
        val visible = CharacterSocialRelationship.networkContext(viewer, listOf(a, b))
        assertTrue(visible.contains("甲方"))
        assertTrue(visible.contains("乙方"))
        assertTrue(visible.contains("共同群聊"))
        assertFalse(visible.contains("两人私密群"))

        // Even when A and B privately exchange messages, the observer gets
        // neither the message nor a claim that the two have private letters.
        val mail = ResidentSocialMailbox.send(a, b, "只对乙说过的私信秘密")
        val afterMail = CharacterSocialRelationship.networkContext(viewer, listOf(a, b))
        assertFalse(afterMail.contains("私信来往"))
        assertFalse(afterMail.contains("只对乙说过的私信秘密"))
        assertTrue(ResidentSocialMailbox.inbox(viewer).isEmpty())

        // Leaving the shared group immediately revokes the relationship
        // preview without deleting A/B's original group or mail history.
        assertTrue(MigratedDomainStores.chat.updateGroupConversation(
            shared.id, shared.groupChat!!.copy(
                members = listOf(LuluGroupMember(a), LuluGroupMember(b)))))
        assertEquals("", CharacterSocialRelationship.networkContext(viewer, listOf(a, b)))
        assertTrue(ResidentSocialMailbox.inbox(a).any { it.id == mail.id })
        assertTrue(ResidentSocialMailbox.inbox(b).any { it.id == mail.id })
    }

    @Test
    fun networkContextIsSilentWithoutConnections() {
        assertEquals("", CharacterSocialRelationship.networkContext("focal", emptyList()))
        assertEquals("", CharacterSocialRelationship.networkContext("focal", listOf("only-one")))
        // Fresh roles share no group, meeting or mail contact.
        assertEquals("", CharacterSocialRelationship.networkContext(
            "focal", listOf("contact-a", "contact-b")))
    }

    @Test
    fun compactContextStaysSilentWhenContactsAreUnconnected() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "network-quiet"
        CharacterInnerLifeStore.clear(role)
        // Seed two bonds the way the witness rule requires: the target
        // must be among the witnesses of the observed event.
        listOf("friend-a" to "群里接话很体贴", "friend-b" to "群里发言很积极").forEach { (target, view) ->
            val sentiment = JSONObject().put("social", JSONObject()
                .put("targetId", target).put("interpretation", view)
                .put("reason", "在共同群聊主动接了话"))
            CharacterInnerLifeStore.observe(role, "group-$target", "朋友在群里主动接了话",
                sentiment, setOf(target, "user"))
        }
        val compact = CharacterInnerLifeStore.compactContext(role)
        assertTrue(compact.contains("群里接话很体贴"))
        assertFalse(compact.contains("身边人的关系网"))
        CharacterInnerLifeStore.clear(role)
    }
}
