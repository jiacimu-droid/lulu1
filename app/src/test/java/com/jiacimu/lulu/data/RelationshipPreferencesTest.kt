package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RelationshipPreferencesTest {
    private val at = Instant.parse("2026-01-01T00:00:00Z")
    private fun event(id: String, text: String, offset: Long = 0,
                      channel: String = "私聊", kind: EventEvidenceKind = EventEvidenceKind.UserStatement) =
        SharedTimelineEvent(id, "role", channel, "用户", text, at.plusSeconds(offset), evidenceKind = kind)
    private fun state(text: String) = CharacterRelationshipPreferences.fromEvents(listOf(event("u", text)))

    @Test fun mixedCorrectionRetainsBothPreferredAndRejectedNames() {
        val result = state("别叫我小公主，我更喜欢你叫我宝宝")
        assertEquals("宝宝", result.preferredAddress?.first)
        assertEquals(setOf("小公主"), result.rejectedAddresses)
        assertEquals("宝宝", CharacterAddressPreference.extractExplicitAddress("别叫我小公主我更喜欢你叫我宝宝"))
        assertNull(CharacterAddressPreference.extractExplicitAddress("我不喜欢被叫一些奇怪的称呼"))
    }

    @Test fun latestEvidenceChangesNameAndDeletingItRestoresTheEarlierPreference() {
        val first = event("old", "以后叫我宝宝吧")
        val second = event("new", "别叫我宝宝，以后叫我佳辞吧", 60, "电话")
        val corrected = CharacterRelationshipPreferences.fromEvents(listOf(second, first))
        assertEquals("佳辞", corrected.preferredAddress?.first)
        assertTrue("宝宝" in corrected.rejectedAddresses)
        assertEquals("宝宝", CharacterRelationshipPreferences.fromEvents(listOf(first)).preferredAddress?.first)
        assertNull(CharacterRelationshipPreferences.fromEvents(listOf(first, event("no", "别叫我宝宝", 120))).preferredAddress)
    }

    @Test fun reacceptingPreviouslyRejectedNameClearsOnlyThatRefusal() {
        val result = CharacterRelationshipPreferences.fromEvents(listOf(
            event("a", "别叫我小公主，别叫我老婆"),
            event("b", "以后叫我小公主吧", 1)))
        assertEquals("小公主", result.preferredAddress?.first)
        assertEquals(setOf("老婆"), result.rejectedAddresses)
    }

    @Test fun conditionalContractsAreRetainedWithoutAnOrdinaryRecallQuota() {
        val result = state("以后你每次抛飞吻的时候都要发mwah或者mua。以后你安慰我时不要再给我一堆计划。")
        assertEquals(2, result.preferences.size)
        val contract = result.preferences.first()
        assertEquals("抛飞吻", contract.trigger)
        assertEquals(listOf("mwah", "mua"), contract.requiredForms)
        assertEquals("u", contract.sourceId)
        assertTrue(result.preferences.last().quote.contains("一堆计划"))
    }

    @Test fun latestSameTriggerContractSupersedesEarlierLiteralForms() {
        val result = CharacterRelationshipPreferences.fromEvents(listOf(
            event("a", "以后你每次抛飞吻的时候都要发mwah或者mua"),
            event("b", "以后你每次抛飞吻的时候都要发mua", 1)))
        assertEquals(1, result.preferences.size)
        assertEquals(listOf("mua"), result.preferences.single().requiredForms)
        val cancelled = CharacterRelationshipPreferences.fromEvents(listOf(
            event("a", "以后每次抛飞吻的时候都要发mwah"),
            event("b", "以后每次抛飞吻的时候不用发mwah", 1)))
        assertTrue(cancelled.preferences.single().requiredForms.isEmpty())
        assertEquals("给你抛个飞吻。", RelationshipExpressionGuard.repair("给你抛个飞吻。", cancelled))
    }

    @Test fun importantPreferencesSurviveThousandsOfUnrelatedEvents() {
        val result = CharacterRelationshipPreferences.fromEvents(listOf(
            event("important", "以后你安慰我时不要再给我一堆计划")) +
            (1..500).map { event("small-$it", "今天喝了杯水", it.toLong()) })
        assertEquals("important", result.preferences.single().sourceId)
    }

    @Test fun conditionalWaysOfHelpingRemainCompleteInsteadOfTurningIntoAMandatoryAction() {
        val text = "当我心情不好的时候，角色抛个飞吻我就会很开心"
        val result = state(text)
        assertEquals(text, result.preferences.single().quote)
        assertTrue(result.preferences.single().requiredForms.isEmpty())
        assertEquals("今天吃了饭", RelationshipExpressionGuard.repair("今天吃了饭", result))
        assertTrue(state("今天你逗我笑了，我很开心").preferences.isEmpty())
    }

    @Test fun quotesTemporaryRequestsAndGeneratedClaimsCannotCreatePreferences() {
        assertTrue(state("比如我希望你以后每次抛飞吻的时候都要发mwah").preferences.isEmpty())
        assertTrue(state("今天先不要安慰我").preferences.isEmpty())
        assertNull(state("今天叫我公主吧").preferredAddress)
        assertTrue(state("今天不要叫我宝宝").rejectedAddresses.isEmpty())
        assertTrue(state("我喜欢巧克力").preferences.isEmpty())
        assertTrue(CharacterRelationshipPreferences.fromEvents(listOf(
            event("claim", "以后你每次抛飞吻的时候都要发mwah", kind = EventEvidenceKind.CharacterStatement))).preferences.isEmpty())
        assertTrue(CharacterRelationshipPreferences.fromEvents(listOf(
            event("fiction", "以后你每次抛飞吻的时候都要发mwah", channel = "末世求生"))).preferences.isEmpty())
    }

    @Test fun voluntaryWakeDeliveryPreferenceBelongsToThisRelationshipOnly() {
        val first = state("我更喜欢你打电话叫醒我")
        assertEquals(1, first.preferences.size)
        assertEquals("我更喜欢你打电话叫醒我", first.preferences.single().quote)
        val second = state("我偏向于你给我打电话叫醒我")
        assertEquals(1, second.preferences.size)
        assertTrue(second.preferences.single().quote.contains("偏向"))
        assertTrue(state("今天你打电话叫我起床，我很开心").preferences.isEmpty())
        assertTrue(state("我喜欢奶油意面").preferences.isEmpty())
        assertEquals(0, CharacterRelationshipPreferences.fromEvents(listOf(
            event("role-invention", "我更喜欢你打电话叫醒我",
                kind = EventEvidenceKind.CharacterStatement))).preferences.size)
    }

    @Test fun groupPreferencesNeedAnExplicitCollectiveAddressee() {
        assertTrue(CharacterRelationshipPreferences.fromEvents(listOf(
            event("group", "以后叫我宝宝吧", channel = "群聊·朋友"))).preferences.isEmpty())
        assertNull(CharacterRelationshipPreferences.fromEvents(listOf(
            event("group", "@江渡 以后叫我宝宝吧", channel = "群聊·朋友"))).preferredAddress)
        assertTrue(CharacterRelationshipPreferences.fromEvents(listOf(
            event("group", "我希望你们以后安慰我不要再给我一堆计划", channel = "群聊·朋友"))).preferences.isNotEmpty())
    }

    @Test fun literalCompletionOnlyRepairsAnActuallyChosenInteraction() {
        val preference = state("以后你每次抛飞吻的时候都要发mwah或者mua")
        assertEquals("给你抛个飞吻，mwah。", RelationshipExpressionGuard.repair("给你抛个飞吻。", preference))
        listOf("今天吃了饭", "不抛飞吻。", "下次给你抛个飞吻。", "要不要给你抛个飞吻？", "她说“给你抛个飞吻”。")
            .forEach { assertEquals(it, RelationshipExpressionGuard.repair(it, preference)) }
        assertEquals("给你抛个飞吻，mua。", RelationshipExpressionGuard.repair("给你抛个飞吻，mua。", preference))
        val repaired = RelationshipExpressionGuard.repair("给你抛个飞吻。", preference)
        assertEquals(repaired, RelationshipExpressionGuard.repair(repaired, preference))
    }

    @Test fun vocativesAreRepairedWithoutRewritingQuotesOrThirdPartyReferences() {
        val preference = state("别叫我小公主，我喜欢你叫我宝宝")
        assertEquals("宝宝，今天怎么样？", RelationshipExpressionGuard.repair("小公主，今天怎么样？", preference, "宝宝"))
        assertEquals("宝宝", RelationshipExpressionGuard.repair("小公主", preference, "宝宝"))
        listOf("你不喜欢‘小公主’这个称呼。", "小说里的小公主很勇敢。", "她说“小公主，今天怎么样？”")
            .forEach { assertEquals(it, RelationshipExpressionGuard.repair(it, preference, "宝宝")) }
        assertEquals("小公主，今天怎么样？", RelationshipExpressionGuard.repair("小公主，今天怎么样？", preference, "宝宝", false))
    }
}
