package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentDailyContinuityTest {
    @org.junit.Before fun initializeWorkerHost() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
    }

    @Test(timeout = 60_000) fun dailyWorkerRestoresModelRuntimeWithoutAnActivityOrSilentChatBilling() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        app.getSharedPreferences("lulu_scoped_model_selections_v1", android.content.Context.MODE_PRIVATE)
            .edit().remove("archive_resident_daily").commit()
        ScopedModelSelections.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(ScopedModelSelections, null)
        listOf("connectionStoreInternal", "gatewayInternal").forEach { field ->
            LuluAiServices.javaClass.getDeclaredField(field).apply { isAccessible = true }
                .set(LuluAiServices, null)
        }
        assertTrue(runCatching { LuluAiServices.gateway }.isFailure)
        assertEquals(0, ResidentDailyDecision.tick(app))
        assertNotNull(LuluAiServices.gateway)
        // An absent dedicated selection must still not fall back to Chat.
        assertNull(ScopedModelSelections.selectedArchiveId(ResidentDailyDecision.SCOPE))
    }

    @Test(timeout = 60_000) fun dailyDecisionKeepsIdentityMotivesAndMailAndSavesIndependentReceipts() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        DigitalWorldExpansionStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "澄${UUID.randomUUID().toString().take(6)}", identity = "旧地图修复师",
            homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            temperament = "喜欢安静和认真谈论旧地图", createdAt = now.minusSeconds(7 * 86_400)))
        DigitalWorldExpansionStore.commitResidentDuty(native.id, DigitalWorldPublicPlaces.COURTYARD,
            "test-duty", "整理旧地图", now)
        ResidentCharacterBridge.sync(app, now)
        val id = ResidentCharacterBridge.characterId(native.id)
        val peer = MigratedDomainStores.characters.create("朋友${UUID.randomUUID().toString().take(6)}")
        DigitalLifeProfileStore.registerNewLife(peer.characterId, peer.displayName, "数字生命", now.minusSeconds(1))
        assertTrue(DigitalWorldStore.performAction(peer.characterId, "visit_public_place",
            JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD)).success)
        val letter = ResidentSocialMailbox.send(peer.characterId, id, "下次继续聊旧地图上的河流。", at = now)
        SharedExperienceTimeline.record("own-map-$id", id, "数字世界·工作", native.name,
            "我在整理旧地图，还有一处河流没有核对完。", now, triggerExtraction = false,
            source = "resident-duty", evidenceKind = EventEvidenceKind.Observation)
        CharacterInnerLifeStore.observe(id, "own-map-$id", "尚未核对完旧地图",
            JSONObject().put("motives", JSONArray().put(JSONObject().put("op", "start")
                .put("aim", "想核对旧地图的河流").put("why", "自己还没弄清楚"))), now = now)
        // An unrelated person's private state is not part of this citizen's day.
        val unrelated = MigratedDomainStores.characters.create("旁人${UUID.randomUUID().toString().take(6)}")
        CharacterInnerLifeStore.observe(unrelated.characterId, "private-other", "旁人的私事",
            JSONObject().put("emotion", JSONObject().put("feeling", "OTHER_PRIVATE_SENTINEL")
                .put("cause", "自己的私人经历")), now = now)
        val output = JSONObject().put("lunch", "cafe").put("evening", "courtyard")
            .put("eveningActivity", "读完整本河流图册并与朋友聊一晚")
            .put("thought", "还想接着聊河流的事")
            .put("messageTo", peer.characterId).put("message", "好呀，我们下次接着聊。")
            .put("post", "今天看见旧地图上细细的一条河。")
            .put("innerLife", JSONObject().put("social", JSONObject()
                .put("targetId", peer.characterId).put("interpretation", "他也对旧地图有兴趣")
                .put("reason", "收到他关于河流的来信")))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", output.toString())))).toString()))
        server.start()
        try {
            // Test this citizen's one decision. Other online characters may
            // legitimately choose their own paid reaction to a public post.
            MigratedDomainStores.characters.settings.value.keys
                .filterNot { it.startsWith("resident:") }
                .forEach { CompanionOnlineStore.resetCharacter(it) }
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "native-test",
                server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runOnce(app, native, now))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(1, server.requestCount)
            val request = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).toString()
            assertTrue(request.contains("旧地图修复师"))
            assertTrue(request.contains("想核对旧地图的河流"))
            assertTrue(request.contains("下次继续聊旧地图上的河流"))
            assertFalse(request.contains("这是独立跨世界场景"))
            assertFalse(request.contains("OTHER_PRIVATE_SENTINEL"))
            assertTrue(ResidentSocialMailbox.pendingIncoming(id).isEmpty())
            val incomingId = ResidentSocialMailbox.evidenceId(letter, id)
            assertEquals(incomingId, CharacterSocialRelationship.perspective(id, peer.characterId)?.evidenceId)
            assertTrue(CharacterInnerLifeStore.snapshot(id).toString().contains("还想接着聊河流的事"))
            val receipts = SharedExperienceTimeline.all(id).filter { it.source == "tool-router" }
            assertTrue(receipts.any { JSONObject(it.content).optString("tool") == "publish_moment" })
            assertTrue(receipts.any { JSONObject(it.content).optString("tool") == "send_character_message" })
            assertTrue(ResidentSocialMailbox.inbox(peer.characterId).any {
                it.fromId == id && it.content == "好呀，我们下次接着聊。" })
            // The daily plan's prose is an intention, not an executor receipt.
            ResidentDailyDecision.schedule(app)
            val evening = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant()
            val slot = ResidentDailyRoutine.plan(native, evening)
            assertEquals("读完整本河流图册并与朋友聊一晚", slot.intendedActivity)
            assertFalse(slot.activity.contains("读完整本"))
            ResidentDailyRoutine.refresh(app, evening)
            assertTrue(SharedExperienceTimeline.all(id).any { it.source == "resident-duty" &&
                it.content.contains("具体活动尚待实际执行") })
            SharedExperienceTimeline.deleteEvent(incomingId)
            assertNull(CharacterSocialRelationship.perspective(id, peer.characterId))
            assertFalse(CharacterInnerLifeStore.snapshot(id).toString().contains("还想接着聊河流的事"))
        } finally { server.shutdown() }
    }
}
