package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smart mode must not add waiting on top of the platform screenshot limit.
 * These tests pin the parts of the latency that we actually control.
 */
class SmartSpeedTest {

    private fun point(id: Long, color: Int = 0xFF3050A0.toInt()) = SmartPoint(
        id = id,
        label = "T$id",
        watchX = 0.3f,
        watchY = 0.4f,
        targetColor = color,
        colorCaptured = true
    )

    @Test
    fun fastPresetFiresOnTheFirstMatchingScan() {
        val p = point(1)
        val fast = SmartLimits.applySpeed(SmartProfile(name = "D", points = listOf(p)), SmartSpeedMode.FAST)
        val engine = SmartEngine(fast)
        engine.start(0L)
        val decision = engine.evaluate(1_000L, mapOf(p.id to p.targetColor))
        assertTrue("Fast must not require a second confirming read", decision is SmartEngine.Decision.Fire)
    }

    @Test
    fun recommendedPresetConfirmsTwiceBeforeFiring() {
        val p = point(1)
        val rec = SmartLimits.applySpeed(SmartProfile(name = "D", points = listOf(p)), SmartSpeedMode.RECOMMENDED)
        val engine = SmartEngine(rec)
        engine.start(0L)
        val first = engine.evaluate(1_000L, mapOf(p.id to p.targetColor))
        assertFalse("Recommended waits for a second agreeing read", first is SmartEngine.Decision.Fire)
        val second = engine.evaluate(1_400L, mapOf(p.id to p.targetColor))
        assertTrue(second is SmartEngine.Decision.Fire)
    }

    @Test
    fun defaultConfirmSamplesMatchesTheRecommendedPreset() {
        assertEquals(SmartProfile.RECOMMENDED_CONFIRM, SmartProfile.DEFAULT_CONFIRM_SAMPLES)
        assertEquals(2, SmartProfile.DEFAULT_CONFIRM_SAMPLES)
        assertEquals(1, SmartProfile.FAST_CONFIRM)
    }

    @Test
    fun fastCooldownDoesNotExceedTheScanFloor() {
        assertTrue(
            "Fast must not add waiting beyond one scan",
            SmartProfile.FAST_COOLDOWN_MS <= SmartProfile.MIN_SCAN_INTERVAL_MS
        )
        assertTrue(SmartProfile.MIN_COOLDOWN_MS <= SmartProfile.MIN_SCAN_INTERVAL_MS)
        assertTrue(
            "Recommended deliberately waits longer than Fast",
            SmartProfile.RECOMMENDED_COOLDOWN_MS > SmartProfile.FAST_COOLDOWN_MS
        )
    }

    @Test
    fun scanFloorStaysAboveThePlatformRateLimit() {
        // AccessibilityService rejects screenshots requested less than 333 ms apart.
        assertTrue(SmartProfile.MIN_SCAN_INTERVAL_MS >= 333L)
        assertTrue(ScreenSampler.MIN_REQUEST_GAP_MS >= 333L)
    }

    @Test
    fun samePointCanFireAgainOnTheVeryNextScan() {
        val p = point(1)
        val profile = SmartProfile(
            name = "D",
            points = listOf(p),
            scanIntervalMs = SmartProfile.MIN_SCAN_INTERVAL_MS,
            cooldownMs = SmartProfile.MIN_COOLDOWN_MS,
            confirmSamples = 1
        )
        val engine = SmartEngine(profile)
        engine.start(0L)
        var now = 1_000L
        assertTrue(engine.evaluate(now, mapOf(p.id to p.targetColor)) is SmartEngine.Decision.Fire)
        now += SmartProfile.MIN_SCAN_INTERVAL_MS
        assertTrue(
            "a still-matching point must be able to fire on the next scan",
            engine.evaluate(now, mapOf(p.id to p.targetColor)) is SmartEngine.Decision.Fire
        )
        assertEquals(2, engine.actionCount)
    }

    @Test
    fun stablePresetNeedsTwoAgreeingReads() {
        val p = point(1)
        val engine = SmartEngine(
            SmartProfile(name = "S", points = listOf(p), confirmSamples = 2, cooldownMs = 1_200L)
        )
        engine.start(0L)
        assertTrue(engine.evaluate(1_000L, mapOf(p.id to p.targetColor)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(1_500L, mapOf(p.id to p.targetColor)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun stablePresetRejectsASingleTransientMatch() {
        val p = point(1)
        val engine = SmartEngine(SmartProfile(name = "S", points = listOf(p), confirmSamples = 2))
        engine.start(0L)
        engine.evaluate(1_000L, mapOf(p.id to p.targetColor))
        // A single frame of noise must not trigger a click in stable mode.
        assertTrue(engine.evaluate(1_400L, mapOf(p.id to 0xFF000000.toInt())) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(1_800L, mapOf(p.id to p.targetColor)) is SmartEngine.Decision.Wait)
        assertEquals(0, engine.actionCount)
    }

    @Test
    fun repeatBurstIsNotDelayedByTheScanInterval() {
        val p = point(1).copy(repeatCount = 5)
        val engine = SmartEngine(SmartProfile(name = "B", points = listOf(p), confirmSamples = 1))
        engine.start(0L)
        engine.evaluate(1_000L, mapOf(p.id to p.targetColor))
        // Repeats are drained on the caller's gesture cadence, not the 400 ms scan floor.
        var now = 1_000L
        repeat(4) {
            now += 60L
            assertTrue(engine.nextRepeat(now) is SmartEngine.Decision.Fire)
        }
        assertEquals(5, engine.actionCount)
        assertFalse(engine.hasPendingBurst())
    }

    @Test
    fun existingStoredSettingsAreNotSilentlyRewritten() {
        // A profile saved with slower values must keep them; only new defaults changed.
        val slow = SmartProfile(
            name = "Lama",
            points = listOf(point(1)),
            scanIntervalMs = 900L,
            cooldownMs = 2_000L,
            confirmSamples = 3
        )
        val cleaned = SmartLimits.sanitizeProfile(slow)
        assertEquals(900L, cleaned.scanIntervalMs)
        assertEquals(2_000L, cleaned.cooldownMs)
        assertEquals(3, cleaned.confirmSamples)
    }
}
