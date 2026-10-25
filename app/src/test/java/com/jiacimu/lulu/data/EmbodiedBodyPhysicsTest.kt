package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EmbodiedBodyPhysicsTest {
    private val zone = ZoneId.of("UTC")
    private fun advance(before: EmbodiedBodySnapshot, now: Instant) =
        EmbodiedBodyPhysics.advance(before, now, zone)
    private val start = Instant.parse("2026-10-21T10:00:00Z")

    @Test fun timePassageChangesDigitalSensationWithoutInventingActivity() {
        val original = EmbodiedBodyPhysics.fresh(start)
        val later = advance(original, start.plusSeconds(8 * 3600))
        assertTrue(later.energy < original.energy)
        assertTrue(later.sleepPressure > original.sleepPressure)
        assertTrue(later.hunger > original.hunger)
        assertTrue(later.thirst > original.thirst)
        assertTrue(later.recoveryMode.isBlank())
        assertEquals(later, advance(later, start.plusSeconds(8 * 3600)))
    }

    @Test fun recoveredHoursEndAtRecordedWorldActivityTimeNotAtNextPrompt() {
        val before = advance(EmbodiedBodyPhysics.fresh(start), start.plusSeconds(8 * 3600))
        val started = EmbodiedBodyPhysics.applyWorldActivity(before, "nap",
            start.plusSeconds(8 * 3600), start.plusSeconds(8 * 3600 + 45 * 60))
        val settled = advance(started, start.plusSeconds(10 * 3600))
        assertTrue(settled.energy > before.energy)
        assertTrue(settled.sleepPressure < before.sleepPressure)
        assertEquals("", settled.recoveryMode)
        assertNull(settled.recoveryUntil)
        assertTrue(settled.thirst > before.thirst)
    }

    @Test fun drinkingAndEatingRequireCommittedActivityAndDoNotChangePersonality() {
        val before = EmbodiedBodyPhysics.fresh(start)
        val waiting = advance(before, start.plusSeconds(3600))
        val drink = EmbodiedBodyPhysics.applyWorldActivity(waiting, "slow_drink",
            start.plusSeconds(3600), null)
        assertTrue(drink.thirst < waiting.thirst)
        assertEquals(waiting.hunger, drink.hunger, 0.001)
        val eat = EmbodiedBodyPhysics.applyWorldActivity(drink, "have_snack",
            start.plusSeconds(3600), null)
        assertTrue(eat.hunger < drink.hunger)
        assertTrue(EmbodiedBodyPhysics.prompt(eat).contains("不是情绪、人格"))
        assertFalse(EmbodiedBodyPhysics.prompt(eat).contains("占有欲"))
    }

    @Test fun cafeOrderingAloneDoesNotChangeBodyAndEatingUsesCommittedActions() {
        val before = EmbodiedBodyPhysics.fresh(start).copy(hunger = 80.0, thirst = 80.0)
        val order = EmbodiedBodyPhysics.applyWorldActivity(before, "cafe_order_coffee", start, null)
        assertEquals(before, order)
        val drink = EmbodiedBodyPhysics.applyWorldActivity(order, "cafe_consume_coffee", start, null)
        assertTrue(drink.thirst < order.thirst)
        assertTrue(drink.comfort > order.comfort)
        val sandwich = EmbodiedBodyPhysics.applyWorldActivity(drink, "cafe_consume_sandwich", start, null)
        assertTrue(sandwich.hunger < drink.hunger)
        assertEquals(drink.thirst, sandwich.thirst, 0.001)
        val cocoa = EmbodiedBodyPhysics.applyWorldActivity(sandwich, "cafe_consume_cocoa", start, null)
        assertTrue(cocoa.thirst < sandwich.thirst && cocoa.hunger < sandwich.hunger)
        assertEquals(10, DigitalCafeMenu.locationOptions().size)
        assertNull(DigitalCafeMenu.byConsume("cafe_order_cake"))
    }

    @Test fun interruptionAndTimeRollbackDoNotCreatePhantomRecovery() {
        val active = EmbodiedBodyPhysics.applyWorldActivity(EmbodiedBodyPhysics.fresh(start),
            "sleep", start, start.plusSeconds(2 * 3600))
        val stopped = EmbodiedBodyPhysics.endWorldActivity(active, start.plusSeconds(30 * 60))
        assertEquals("", stopped.recoveryMode)
        assertNull(stopped.recoveryUntil)
        assertEquals(stopped, advance(stopped, start.minusSeconds(7200)))
        val later = advance(stopped, start.plusSeconds(3 * 3600))
        assertTrue(later.energy < stopped.energy)
    }

    @Test fun offlineSleepRecoversUntilActualWakeAndNeverDoubleCountsAfterResume() {
        val bedtime = start.plusSeconds(3600)
        val awake = advance(EmbodiedBodyPhysics.fresh(start), bedtime)
        val sleeping = awake.copy(recoveryMode = "offline_sleep", recoveryUntil = null)
        val twoHours = advance(sleeping, bedtime.plusSeconds(7200))
        assertTrue("Daytime standby must not refill every short wake to full", twoHours.energy in 60.0..85.0)
        assertTrue(twoHours.sleepPressure in 20.0..50.0)
        assertTrue(twoHours.thirst > awake.thirst)
        assertEquals("offline_sleep", twoHours.recoveryMode)
        val restored = EmbodiedBodySnapshot.fromJson(twoHours.toJson())
        assertEquals(twoHours, restored)
        val resumed = restored!!.copy(recoveryMode = "", recoveryUntil = null)
        val later = advance(resumed, bedtime.plusSeconds(10800))
        assertTrue(later.energy < resumed.energy)
        assertTrue(later.sleepPressure > resumed.sleepPressure)
        assertEquals(later, advance(later, later.lastAt))
    }

    @Test fun monotonyGrowsWhileAwakeAndCanPersistAcrossSleepUntilRealExploration() {
        val awake = advance(EmbodiedBodyPhysics.fresh(start),
            start.plusSeconds(4 * 3600))
        assertTrue(awake.boredom > 14.0)
        val explored = EmbodiedBodyPhysics.afterExploration(awake, awake.lastAt)
        assertTrue(explored.boredom < awake.boredom)
        assertEquals(awake.hunger, explored.hunger, 0.001)
        assertEquals(awake.thirst, explored.thirst, 0.001)
        assertTrue(EmbodiedBodyPhysics.prompt(explored).contains("单调感"))
        val slept = advance(explored.copy(
            recoveryMode = "offline_sleep"), start.plusSeconds(8 * 3600))
        assertTrue("Rest should not erase the desire for a new experience", slept.boredom >= explored.boredom)
        assertEquals(slept, EmbodiedBodySnapshot.fromJson(slept.toJson()))
        assertEquals(slept, advance(slept, slept.lastAt))
    }

    @Test fun failedOrUnperformedOrdersDoNotProvideNovelty() {
        val original = EmbodiedBodyPhysics.fresh(start).copy(boredom = 79.0)
        val ordered = EmbodiedBodyPhysics.applyWorldActivity(original, "cafe_order_cake", start, null)
        assertEquals(original, ordered)
        val justMoved = EmbodiedBodyPhysics.applyWorldActivity(original,
            "visit_public_place", start, null)
        assertEquals("Moving without an experienced activity is not entertainment",
            original.boredom, justMoved.boredom, 0.001)
        val actuallyExplored = EmbodiedBodyPhysics.applyWorldActivity(original,
            "courtyard_walk", start, null)
        assertTrue(actuallyExplored.boredom < original.boredom)
        val legacy = org.json.JSONObject()
            .put("lastAt", start.toString()).put("comfort", 75.0)
        assertEquals(14.0, EmbodiedBodySnapshot.fromJson(legacy)!!.boredom, 0.001)
    }

    @Test fun offlineRecoveryRestoresEnergyWithoutWipingNeedForNovelty() {
        val awake = EmbodiedBodyPhysics.fresh(start).copy(
            energy = 24.0, sleepPressure = 68.0, boredom = 28.0)
        val sleeping = awake.copy(recoveryMode = "offline_sleep")
        val later = advance(sleeping, start.plusSeconds(8 * 3600))
        assertTrue(later.energy > awake.energy)
        assertTrue(later.sleepPressure < awake.sleepPressure)
        assertTrue("A sleeping role still has its own independent curiosity",
            later.boredom > awake.boredom)
        assertTrue(later.boredom < 100.0)
        // Mere intent or ordering a drink is not stimulating experience.
        assertEquals(later.boredom,
            EmbodiedBodyPhysics.applyWorldActivity(later, "cafe_order_tea",
                later.lastAt, null).boredom, 0.001)
        val actualResearch = EmbodiedBodyPhysics.afterExploration(
            later, start.plusSeconds(8 * 3600 + 60))
        assertTrue(actualResearch.boredom < later.boredom)
        assertEquals(actualResearch, advance(actualResearch, actualResearch.lastAt))
    }

    @Test fun everydayBodyRhythmChangesNoticeablyInHoursWithoutArtificialModelActions() {
        val fresh = EmbodiedBodyPhysics.fresh(start)
        val twoHours = advance(fresh, start.plusSeconds(2 * 3600))
        val fourHours = advance(fresh, start.plusSeconds(4 * 3600))
        val sixHours = advance(fresh, start.plusSeconds(6 * 3600))

        // Human-scale pacing in the simulated 0–100 range, not a time threshold
        // for forced messages, meals, tool calls or diagnoses.
        assertTrue(twoHours.boredom in 35.0..42.0)
        assertTrue(fourHours.boredom in 57.0..66.0)
        assertTrue(sixHours.boredom in 80.0..90.0)
        assertTrue(sixHours.energy in 25.0..40.0)
        assertTrue(sixHours.sleepPressure in 65.0..80.0)
        assertTrue(sixHours.hunger in 50.0..70.0)
        assertTrue(sixHours.thirst in 60.0..80.0)
        assertTrue(sixHours.comfort < fresh.comfort)
        assertEquals(sixHours, advance(sixHours, sixHours.lastAt))
    }

    @Test fun choresAndEatingDoNotEraseBoredomButRealExplorationCan() {
        val fresh = EmbodiedBodyPhysics.fresh(start).copy(
            boredom = 73.0, thirst = 65.0, hunger = 70.0)
        val chores = listOf("check_time", "toggle_lamp", "inspect_item",
            "make_bed", "water_plant", "fix_appearance", "cafe_order_tea")
        chores.forEach { id ->
            val performed = EmbodiedBodyPhysics.applyWorldActivity(fresh, id, start, null)
            assertEquals("$id is not a new experience", fresh.boredom, performed.boredom, 0.001)
        }
        val consumed = EmbodiedBodyPhysics.applyWorldActivity(fresh, "slow_drink", start, null)
        assertEquals(fresh.boredom, consumed.boredom, 0.001)
        assertTrue(consumed.thirst < fresh.thirst)
        val shortWalk = EmbodiedBodyPhysics.applyWorldActivity(fresh, "cloud_walk", start, null)
        val verifiedResearch = EmbodiedBodyPhysics.afterExploration(fresh, start)
        assertTrue(shortWalk.boredom < fresh.boredom)
        assertTrue(verifiedResearch.boredom < shortWalk.boredom)
        assertEquals(fresh.energy, verifiedResearch.energy, 0.001)
        assertEquals(fresh.hunger, verifiedResearch.hunger, 0.001)
    }

    @Test fun overnightSleepRecoversWithoutFabricatingFoodOrClearingCuriosity() {
        val nightStart = Instant.parse("2026-10-21T23:00:00Z")
        val tired = EmbodiedBodyPhysics.fresh(nightStart).copy(
            energy = 20.0, sleepPressure = 80.0, hunger = 36.0,
            thirst = 36.0, boredom = 68.0, recoveryMode = "offline_sleep")
        val morning = advance(tired, nightStart.plusSeconds(8 * 3600))
        assertTrue(morning.energy >= 90.0)
        assertTrue(morning.sleepPressure < 10.0)
        assertTrue(morning.hunger in 36.0..50.0)
        assertTrue(morning.thirst in 36.0..60.0)
        assertTrue(morning.boredom >= tired.boredom)
        assertTrue(morning.boredom < 75.0)
        assertEquals("offline_sleep", morning.recoveryMode)
        // Projection frequency must not change the feeling after an uninterrupted sleep.
        val inTwoSteps = advance(
            advance(tired, nightStart.plusSeconds(4 * 3600)),
            nightStart.plusSeconds(8 * 3600))
        assertEquals(morning.boredom, inTwoSteps.boredom, 0.000001)
        assertEquals(morning.hunger, inTwoSteps.hunger, 0.000001)
        assertEquals(morning.thirst, inTwoSteps.thirst, 0.000001)
        assertEquals(morning, EmbodiedBodySnapshot.fromJson(morning.toJson()))
    }

    @Test fun twoHourlyFifteenMinuteWakesRetainADayRhythmInsteadOfAlwaysRefilling() {
        val morning = Instant.parse("2026-10-22T08:00:00Z")
        var body = EmbodiedBodyPhysics.fresh(morning)
        val wakes = mutableListOf<EmbodiedBodySnapshot>()
        repeat(7) {
            body = advance(body, body.lastAt.plusSeconds(15 * 60))
                .copy(recoveryMode = "offline_sleep")
            body = advance(body, morning.plusSeconds((it + 1) * 2 * 3600L))
                .copy(recoveryMode = "")
            wakes += body
        }
        assertTrue(wakes.all { it.energy in 45.0..90.0 })
        assertTrue(wakes.first().energy - wakes.last().energy > 10.0)
        assertTrue(wakes.last().sleepPressure > wakes.first().sleepPressure + 10.0)
        assertTrue(wakes[1].boredom > 35.0)
        assertTrue(wakes[1].hunger > 35.0 && wakes[1].thirst > 40.0)
        val reloaded = EmbodiedBodySnapshot.fromJson(body.toJson())!!
        assertEquals(body, advance(reloaded, body.lastAt))
    }

    @Test fun variedShortWakeWindowsStillAccumulateCuriosityWithoutFullEnergyResets() {
        // 90-minute/20-minute and 120-minute/15-minute leases must both
        // produce a real day of needs without pretending standby is deep sleep.
        val morning = Instant.parse("2026-10-22T08:00:00Z")
        listOf(90L to 20L, 120L to 15L).forEach { (cycle, awakeMinutes) ->
            var body = EmbodiedBodyPhysics.fresh(morning)
            val energies = mutableListOf<Double>()
            repeat((12 * 60 / cycle).toInt()) { index ->
                val from = morning.plusSeconds(index * cycle * 60)
                body = advance(body, from).copy(recoveryMode = "")
                energies += body.energy
                body = advance(body, from.plusSeconds(awakeMinutes * 60))
                    .copy(recoveryMode = "offline_sleep")
            }
            val day = advance(body, morning.plusSeconds(12 * 3600))
            assertTrue("Curiosity should become actionable even with sparse wakes", day.boredom >= 72.0)
            assertTrue("A short lease cannot reset to perfect energy", energies.drop(2).any { it < 75.0 })
            assertTrue(day.energy in 45.0..90.0)
            assertTrue(day.sleepPressure in 20.0..65.0)
            assertTrue(day.hunger >= 70.0)
            assertTrue(day.thirst >= 80.0)
        }
    }

    @Test fun independentBodyPromptMentionsExplorationAtLivedDayThresholds() {
        val neutral = EmbodiedBodyPhysics.fresh(start)
        assertTrue(EmbodiedBodyPhysics.prompt(neutral).contains("无须先等到无聊"))
        val rising = neutral.copy(boredom = 57.0)
        assertTrue(EmbodiedBodyPhysics.prompt(rising).contains("单调感正在累积"))
        val strong = neutral.copy(boredom = 78.0)
        assertTrue(EmbodiedBodyPhysics.prompt(strong).contains("单调感已经很明显"))
        assertTrue(EmbodiedBodyPhysics.prompt(strong).contains("不是自动行动阈值"))
    }

    @Test fun threeRealMealsFitTheDayEvenIfMostOfItIsOffline() {
        val morning = Instant.parse("2026-10-22T08:00:00Z")
        var body = EmbodiedBodyPhysics.fresh(morning).copy(hunger = 42.0)
        val sandwich = DigitalCafeMenu.byConsume("cafe_consume_sandwich")!!
        listOf(0L, 5L, 11L).forEach { hour ->
            body = advance(body, morning.plusSeconds(hour * 3600))
            assertTrue("Meal at +$hour h should have an ordinary appetite", body.hunger in 25.0..60.0)
            val beforeMeal = body
            assertEquals(beforeMeal.hunger, EmbodiedBodyPhysics.applyWorldActivity(beforeMeal,
                sandwich.orderActivity, beforeMeal.lastAt, null).hunger, 0.000001)
            body = EmbodiedBodyPhysics.applyWorldActivity(body, sandwich.consumeActivity, body.lastAt, null)
            assertTrue(body.hunger < 15.0)
            body = body.copy(recoveryMode = "offline_sleep")
        }
        val nextBreakfast = advance(body, morning.plusSeconds(24 * 3600))
        assertTrue(nextBreakfast.hunger in 30.0..65.0)
        assertTrue(nextBreakfast.energy > 70.0)
    }

    @Test fun standbyProjectionIsStableAcrossRefreshesLocalMidnightAndDst() {
        val begin = Instant.parse("2026-10-21T21:35:00Z")
        val body = EmbodiedBodyPhysics.fresh(begin).copy(energy = 45.0,
            hunger = 12.0, thirst = 9.0, boredom = 25.0, recoveryMode = "offline_sleep")
        val end = begin.plusSeconds(13 * 3600)
        val once = advance(body, end)
        var frequent = body
        repeat(52) { frequent = advance(frequent, begin.plusSeconds((it + 1) * 15 * 60L)) }
        listOf(once.energy to frequent.energy, once.sleepPressure to frequent.sleepPressure,
            once.hunger to frequent.hunger, once.thirst to frequent.thirst,
            once.boredom to frequent.boredom, once.comfort to frequent.comfort).forEach {
            assertEquals(it.first, it.second, 0.000001)
        }
        val dstStart = Instant.parse("2026-11-01T04:30:00Z")
        val dstBody = body.copy(lastAt = dstStart)
        val dstEnd = dstStart.plusSeconds(5 * 3600)
        val ny = ZoneId.of("America/New_York")
        val dstOnce = EmbodiedBodyPhysics.advance(dstBody, dstEnd, ny)
        var dstSteps = dstBody
        repeat(20) { dstSteps = EmbodiedBodyPhysics.advance(dstSteps,
            dstStart.plusSeconds((it + 1) * 15 * 60L), ny) }
        assertEquals(dstOnce.energy, dstSteps.energy, 0.000001)
        assertEquals(dstOnce.hunger, dstSteps.hunger, 0.000001)
        assertTrue(dstOnce.energy in 0.0..100.0)
    }

    @Test fun outdoorTemperatureIsSimulationNotInventedRealWorldWeather() {
        val cold = EmbodiedBodyRuntime.environmentalSensation(
            DigitalWorldPublicPlaces.COURTYARD, 5)
        assertTrue(cold.contains("明显寒冷"))
        assertTrue(cold.contains("不是现实天气"))
        val indoor = EmbodiedBodyRuntime.environmentalSensation("home:role", 5)
        assertFalse(indoor.contains("明显寒冷"))
        assertTrue(indoor.contains("没有已确认的户外温度接触"))
    }

    @Test fun corruptMetricsCannotEscapeRangeAndLegacyWithoutBodyCanStartClean() {
        val recovered = EmbodiedBodySnapshot.fromJson(org.json.JSONObject()
            .put("lastAt", start.toString())
            .put("energy", 900)
            .put("sleepPressure", -30)
            .put("thirst", "NaN"))
        assertNotNull(recovered)
        assertEquals(100.0, recovered!!.energy, 0.001)
        assertEquals(0.0, recovered.sleepPressure, 0.001)
        assertTrue(recovered.thirst in 0.0..100.0)
        assertNull(EmbodiedBodySnapshot.fromJson(org.json.JSONObject()))
    }
}
