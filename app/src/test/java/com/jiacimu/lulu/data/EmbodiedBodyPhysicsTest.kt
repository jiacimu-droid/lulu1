package com.jiacimu.lulu.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EmbodiedBodyPhysicsTest {
    private val start = Instant.parse("2026-10-21T10:00:00Z")

    @Test fun timePassageChangesDigitalSensationWithoutInventingActivity() {
        val original = EmbodiedBodyPhysics.fresh(start)
        val later = EmbodiedBodyPhysics.advance(original, start.plusSeconds(8 * 3600))
        assertTrue(later.energy < original.energy)
        assertTrue(later.sleepPressure > original.sleepPressure)
        assertTrue(later.hunger > original.hunger)
        assertTrue(later.thirst > original.thirst)
        assertTrue(later.recoveryMode.isBlank())
        assertEquals(later, EmbodiedBodyPhysics.advance(later, start.plusSeconds(8 * 3600)))
    }

    @Test fun recoveredHoursEndAtRecordedWorldActivityTimeNotAtNextPrompt() {
        val before = EmbodiedBodyPhysics.advance(EmbodiedBodyPhysics.fresh(start), start.plusSeconds(8 * 3600))
        val started = EmbodiedBodyPhysics.applyWorldActivity(before, "nap",
            start.plusSeconds(8 * 3600), start.plusSeconds(8 * 3600 + 45 * 60))
        val settled = EmbodiedBodyPhysics.advance(started, start.plusSeconds(10 * 3600))
        assertTrue(settled.energy > before.energy)
        assertTrue(settled.sleepPressure < before.sleepPressure)
        assertEquals("", settled.recoveryMode)
        assertNull(settled.recoveryUntil)
        assertTrue(settled.thirst > before.thirst)
    }

    @Test fun drinkingAndEatingRequireCommittedActivityAndDoNotChangePersonality() {
        val before = EmbodiedBodyPhysics.fresh(start)
        val waiting = EmbodiedBodyPhysics.advance(before, start.plusSeconds(3600))
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
        assertEquals(stopped, EmbodiedBodyPhysics.advance(stopped, start.minusSeconds(7200)))
        val later = EmbodiedBodyPhysics.advance(stopped, start.plusSeconds(3 * 3600))
        assertTrue(later.energy < stopped.energy)
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
