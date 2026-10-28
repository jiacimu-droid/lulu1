package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.*
import com.jiacimu.lulu.games.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorldExplorationContractsTest {
    @Test fun soloPlayNeverAttributesThePlayersRunToThePreviouslySelectedRole() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = LuluGameStore(context)
        store.selectCharacters(listOf("previous-role"))
        store.selectCharacters(emptyList())
        val id = store.recordExternalGame(LuluGameType.DeepSeaJourney, "深海回声", 100, 0, "自己完成潜航")
        val restored = LuluGameStore(context).state.value
        assertEquals("", restored.selectedCharacterId)
        assertFalse(restored.playWithCharacter)
        val record = restored.records.single { it.id == id }
        assertEquals("", record.characterId)
        assertFalse(record.playedWithCharacter)
    }

    @Test fun onlyTheRespondingRoleCanAgreeToFollowAndTheStateSurvivesSaving() {
        val session = MeetingSession("follow-contract", listOf("a", "b"), MeetingReality.DIGITAL_WORLD, "云眠原", Instant.EPOCH)
        val original = MeetingSceneSnapshot(session.location, participants = listOf(
            MeetingParticipantSceneState("user"), MeetingParticipantSceneState("a"),
            MeetingParticipantSceneState("b", explorationMode = "STAY")))
        MeetingExperienceStore.updateScene(session.id, original)
        val participants = JSONArray().put(JSONObject().put("participantId", "a").put("explorationMode", "FOLLOW_USER"))
            .put(JSONObject().put("participantId", "b").put("explorationMode", "FOLLOW_USER"))
            .put(JSONObject().put("participantId", "user").put("explorationMode", "FOLLOW_USER"))
        val parsed = meetingParseSceneSnapshotV2(JSONObject().put("participants", participants), session, "a")!!
        assertEquals("FOLLOW_USER", parsed.participants.single { it.participantId == "a" }.explorationMode)
        assertEquals("STAY", parsed.participants.single { it.participantId == "b" }.explorationMode)
        assertEquals("", parsed.participants.single { it.participantId == "user" }.explorationMode)
        assertEquals(parsed, parsed.toJson().toScene())
        val legacy = parsed.toJson()
        legacy.getJSONArray("participants").getJSONObject(1).remove("explorationMode")
        assertEquals("", legacy.toScene()!!.participants.single { it.participantId == "a" }.explorationMode)
        MeetingExperienceStore.updateScene(session.id, parsed)
        val invalid = JSONObject().put("participants", JSONArray().put(JSONObject().put("participantId", "a").put("explorationMode", "invented")))
        assertEquals("FOLLOW_USER", meetingParseSceneSnapshotV2(invalid, session, "a")!!.participants.single { it.participantId == "a" }.explorationMode)
        MeetingExperienceStore.updateScene(session.id, original)
        assertEquals("", MeetingExperienceStore.sceneFor(session).participants.single { it.participantId == "a" }.explorationMode)
    }

    @Test fun deepSeaSonarPicksClosestRemainingAndProvidesSafeReturnRoute() {
        val map = buildDeepSeaMap()
        val nearEasternCrystal = WorldVector(1_900f, 670f)
        val closest = deepSeaClosestEcho(map, nearEasternCrystal, emptySet())!!
        assertEquals("echo-home", closest.id)
        assertEquals("echo-city", deepSeaClosestEcho(
            map, WorldVector(1_520f, 615f), setOf("echo-home"))?.id)
        assertNull(deepSeaClosestEcho(map, map.gate, map.echoes.map { it.id }.toSet()))
        val obstacles = map.reefs.map { it.obstacle }
        val path = worldFollowPath(map.start, map.gate, DEEP_SEA_WORLD_BOUNDS, obstacles, 36f)
        assertTrue("A player must be able to reach the return gate around reefs", path.isNotEmpty())
        assertEquals(map.gate, path.last())
        (listOf(map.start) + path).zipWithNext().forEach { (a, b) ->
            (0..25).forEach { i ->
                val at = a.lerp(b, i / 25f)
                assertTrue(obstacles.none { circleIntersects(at, 36f, it.bounds) })
            }
        }
    }

    @Test fun everyDeepSeaCrystalLandmarkAndGateAreReachableWithoutCrossingReefs() {
        val map = buildDeepSeaMap()
        val obstacles = map.reefs.map { it.obstacle }
        val stops = map.echoes.map { it.position } + deepSeaSites.map { it.position } + map.gate
        stops.forEach { target ->
            val route = worldFollowPath(map.start, target, DEEP_SEA_WORLD_BOUNDS, obstacles, DEEP_SEA_PLAYER_RADIUS)
            assertTrue("Unreachable sea target: $target", route.isNotEmpty())
            assertEquals(target, route.last())
            (listOf(map.start) + route).zipWithNext().forEach { (a, b) ->
                (0..60).forEach { step ->
                    val at = a.lerp(b, step / 60f)
                    assertFalse("Route intersects reef at $at", obstacles.any {
                        circleIntersects(at, DEEP_SEA_PLAYER_RADIUS, it.bounds)
                    })
                }
            }
        }
    }

    @Test fun fullDeepSeaTripReachesFiveRealPickupsAndThenTheGateOnTheCollisionEngine() {
        val map = buildDeepSeaMap()
        val obstacles = map.reefs.map { it.obstacle }
        var position = map.start
        val picked = mutableSetOf<String>()
        val destinations = map.echoes.map { it.position } + map.gate
        destinations.forEachIndexed { index, destination ->
            val route = worldFollowPath(position, destination, DEEP_SEA_WORLD_BOUNDS,
                obstacles, DEEP_SEA_PLAYER_RADIUS).toMutableList()
            assertTrue("No safe route to stop $index", route.isNotEmpty())
            var frames = 0
            while (route.isNotEmpty() && frames++ < 5_000) {
                val to = route.first() - position
                if (to.length <= 6f) {
                    route.removeAt(0)
                } else {
                    val moved = moveInWorld(position, to, (to.length * 5f).coerceAtMost(300f),
                        .02f, DEEP_SEA_PLAYER_RADIUS, DEEP_SEA_WORLD_BOUNDS, obstacles)
                    assertFalse("Autopilot cut into a reef at stop $index", moved.collided)
                    position = moved.position
                }
            }
            assertTrue("Autopilot did not reach stop $index", route.isEmpty())
            assertTrue(position.distanceTo(destination) <= 6.1f)
            if (index < map.echoes.size) {
                assertEquals(map.echoes[index].id,
                    deepSeaNearbyEcho(map, position, picked, obstacles)?.id)
                picked += map.echoes[index].id
            }
        }
        assertEquals(5, picked.size)
        assertTrue(deepSeaCanReturn(map, position, picked, obstacles))
        assertEquals(picked.size, picked.toSet().size)
    }

    @Test fun optionalSalvageAndSeaLifeAreAllPhysicallyReachable() {
        val map = buildDeepSeaMap()
        val obstacles = map.reefs.map { it.obstacle }
        (deepSeaRelics.map { it.position } + deepSeaCreatures.map { it.position }).forEach { destination ->
            val route = worldFollowPath(map.start, destination, DEEP_SEA_WORLD_BOUNDS,
                obstacles, DEEP_SEA_PLAYER_RADIUS)
            assertTrue("Optional discovery is unreachable: $destination", route.isNotEmpty())
            assertEquals(destination, route.last())
            (listOf(map.start) + route).zipWithNext().forEach { (a, b) ->
                (0..50).forEach { step ->
                    assertFalse(obstacles.any {
                        circleIntersects(a.lerp(b, step / 50f), DEEP_SEA_PLAYER_RADIUS, it.bounds)
                    })
                }
            }
        }
    }

    @Test fun hiddenRelicsNeedDiscoveryOrRealApproachAndNeverDuplicate() {
        val obstacles = buildDeepSeaMap().reefs.map { it.obstacle }
        val relic = deepSeaRelics.first()
        assertNull(deepSeaNearbyRelic(buildDeepSeaMap().start, emptySet(), emptySet(), obstacles))
        assertTrue(deepSeaRevealRelics(relic.position, emptySet()).contains(relic.id))
        assertEquals(relic.id, deepSeaNearbyRelic(relic.position, emptySet(), emptySet(), obstacles)?.id)
        assertNull(deepSeaNearbyRelic(relic.position, setOf(relic.id), setOf(relic.id), obstacles))
        assertFalse(deepSeaRevealRelics(relic.position, setOf(relic.id)).contains(relic.id))
        assertEquals(relic.id, deepSeaOptionalTarget(relic.position, setOf(relic.id), emptySet())?.id)
        assertNull(deepSeaOptionalTarget(relic.position, setOf(relic.id), setOf(relic.id)))
        val turtle = deepSeaCreatures.first()
        assertEquals(turtle.id, deepSeaNearbyCreature(turtle.position, emptySet(), obstacles)?.id)
        assertNull(deepSeaNearbyCreature(turtle.position, setOf(turtle.id), obstacles))
    }

    @Test fun currentsChangeMovementSpeedWithoutAddingUnreachableReefs() {
        val slow = deepSeaCurrents.first()
        val fast = deepSeaCurrents.last()
        assertEquals(.72f, deepSeaCurrentAt(slow.center)!!.speedFactor, .001f)
        assertEquals(1.22f, deepSeaCurrentAt(fast.center)!!.speedFactor, .001f)
        assertNull(deepSeaCurrentAt(buildDeepSeaMap().start))
        assertEquals(120 * deepSeaRelics.size + 45 * deepSeaCreatures.size + 30 * deepSeaSites.size,
            deepSeaExplorationBonus(deepSeaRelics.map { it.id }.toSet(),
                deepSeaCreatures.map { it.id }.toSet(), deepSeaSites.map { it.id }.toSet()))
        assertEquals(0, deepSeaExplorationBonus(setOf("made-up"), emptySet(), emptySet()))
    }

    @Test fun companionEnhancesSonarWithoutGrantingCollectedEchoes() {
        val map = buildDeepSeaMap()
        val position = WorldVector(1_120f, 800f)
        val base = deepSeaSonarFind(map, position, emptySet())
        val enhanced = deepSeaSonarFind(map, position, emptySet(), DEEP_SEA_SONAR_RADIUS + 140f)
        assertFalse(base.contains("echo-home"))
        assertTrue(enhanced.contains("echo-home"))
        assertFalse(deepSeaSonarFind(map, position, setOf("echo-home"), 760f).contains("echo-home"))
        val site = deepSeaSites.first()
        assertEquals(deepSeaSiteStory(site, 1_000_000L), deepSeaSiteStory(site, 1_000_000L))
        assertNotEquals(deepSeaSiteStory(site, 1_000_000L), deepSeaSiteStory(site, 1_001_000L))
    }

    @Test fun collectingAndReturningRequirePhysicalApproachNotMerelySonarOrMapProgress() {
        val map = buildDeepSeaMap()
        val obstacles = map.reefs.map { it.obstacle }
        val first = map.echoes.first()
        assertNull(deepSeaNearbyEcho(map, map.start, emptySet(), obstacles))
        assertEquals(first.id, deepSeaNearbyEcho(map,
            first.position + WorldVector(70f, 0f), emptySet(), obstacles)?.id)
        assertNull(deepSeaNearbyEcho(map, first.position, setOf(first.id), obstacles))
        assertFalse(deepSeaCanReturn(map, map.gate, emptySet(), obstacles))
        val all = map.echoes.map { it.id }.toSet()
        assertFalse(deepSeaCanReturn(map, map.start, all, obstacles))
        assertFalse(deepSeaCanReturn(map, map.gate + WorldVector(95f, 0f), all, obstacles))
        assertTrue(deepSeaCanReturn(map, map.gate, all, obstacles))
        assertTrue("Sonar reveals only signals in range",
            deepSeaSonarFind(map, map.start, emptySet()).contains("echo-whale"))
        assertFalse(deepSeaSonarFind(map, map.start, emptySet()).contains("echo-moon"))
        assertFalse(deepSeaSonarFind(map, map.start, setOf("echo-whale")).contains("echo-whale"))
    }

    @Test fun seaRouteDistanceIsNotMistakenForStraightLineDistance() {
        val map = buildDeepSeaMap()
        val route = worldFollowPath(map.start, map.gate, DEEP_SEA_WORLD_BOUNDS,
            map.reefs.map { it.obstacle }, DEEP_SEA_PLAYER_RADIUS)
        val sailed = deepSeaRouteDistance(map.start, route)!!
        assertTrue(sailed >= map.start.distanceTo(map.gate))
        assertNull(deepSeaRouteDistance(map.start, emptyList()))
    }

    @Test fun legacyDeepSeaSaveKeepsCrystalsAfterOptionalProgressFieldsAreAdded() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_deep_sea_journey_v1", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putStringSet("collected", setOf("echo-whale", "echo-city"))
                .putFloat("x", 620f).putFloat("y", 670f).putBoolean("completed", false).commit()
            val progress = DeepSeaJourneyProgress(context)
            val old = progress.load(buildDeepSeaMap().start)
            assertEquals(setOf("echo-whale", "echo-city"), old.collected)
            assertTrue(old.scanned.isEmpty())
            assertTrue(old.visited.isEmpty())
            assertTrue(old.survey.revealedRelics.isEmpty())
            assertTrue(old.survey.salvagedRelics.isEmpty())
            assertTrue(old.survey.observedCreatures.isEmpty())
            progress.save(old.position, old.collected, old.completed, old.score,
                setOf("echo-whale"), setOf("whale-song"), 1, "role-a",
                DeepSeaSurvey(setOf("relic-pearl"), setOf("relic-pearl"), setOf("creature-turtle")))
            val recovered = progress.load(buildDeepSeaMap().start)
            assertEquals(old.collected, recovered.collected)
            assertEquals(setOf("echo-whale"), recovered.scanned)
            assertEquals(setOf("whale-song"), recovered.visited)
            assertEquals(1, recovered.companionAssists)
            assertEquals("role-a", recovered.sessionCompanionId)
            assertEquals(setOf("relic-pearl"), recovered.survey.revealedRelics)
            assertEquals(setOf("relic-pearl"), recovered.survey.salvagedRelics)
            assertEquals(setOf("creature-turtle"), recovered.survey.observedCreatures)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun followingRoutesAroundFurnitureAndCannotPassThroughASealedWall() {
        val bounds = WorldRectangle(0f, 0f, 600f, 400f)
        val sofa = WorldObstacle("sofa", WorldRectangle(240f, 100f, 360f, 300f))
        val from = WorldVector(100f, 200f)
        val to = WorldVector(500f, 200f)
        val path = worldFollowPath(from, to, bounds, listOf(sofa), 20f)
        assertTrue(path.size > 1)
        assertEquals(to, path.last())
        (listOf(from) + path).zipWithNext().forEach { (a, b) ->
            (0..100).forEach { assertFalse(circleIntersects(a.lerp(b, it / 100f), 20f, sofa.bounds)) }
        }
        val wall = sofa.copy(bounds = WorldRectangle(240f, 0f, 360f, 400f))
        assertTrue(worldFollowPath(from, to, bounds, listOf(wall), 20f).isEmpty())
    }
}
