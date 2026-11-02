package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEngineTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun point(
        id: Long,
        condition: SmartCondition = SmartCondition.COLOR_MATCH,
        color: Int = red,
        enabled: Boolean = true
    ) = SmartPoint(
        id = id,
        label = "T$id",
        watchX = 0.2f + id * 0.1f,
        watchY = 0.4f,
        targetColor = color,
        colorCaptured = true,
        tolerance = 10,
        condition = condition,
        enabled = enabled
    )

    private fun profile(
        vararg points: SmartPoint,
        confirm: Int = 2,
        cooldown: Long = 1_000L,
        idleSec: Int = 0,
        maxActions: Int = 0
    ) = SmartProfile(
        name = "Uji",
        points = points.toList(),
        confirmSamples = confirm,
        cooldownMs = cooldown,
        idleTimeoutSec = idleSec,
        maxActions = maxActions
    )

    @Test
    fun doesNotFireBeforeConfirmSamplesReached() {
        val engine = SmartEngine(profile(point(1), confirm = 2))
        engine.start(0L)
        assertTrue(engine.evaluate(100L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(200L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun nonMatchingSampleResetsConsecutiveCounter() {
        val engine = SmartEngine(profile(point(1), confirm = 2))
        engine.start(0L)
        engine.evaluate(100L, mapOf(1L to red))
        engine.evaluate(200L, mapOf(1L to blue))
        assertTrue(engine.evaluate(300L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(400L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun failedSampleNeverCountsAsBlackPixel() {
        val black = 0xFF000000.toInt()
        val engine = SmartEngine(profile(point(1, color = black), confirm = 1))
        engine.start(0L)
        // null means "could not read" and must not satisfy a black target.
        assertTrue(engine.evaluate(100L, mapOf(1L to null)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(200L, emptyMap()) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(300L, mapOf(1L to black)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun cooldownBlocksImmediateRefire() {
        val engine = SmartEngine(profile(point(1), confirm = 1, cooldown = 1_000L))
        engine.start(0L)
        assertTrue(engine.evaluate(100L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
        assertTrue(engine.evaluate(200L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(1_050L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(1_200L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun colorChangeArmsBaselineFromFirstSampleAndDoesNotFireOnIt() {
        val engine = SmartEngine(profile(point(1, condition = SmartCondition.COLOR_CHANGE), confirm = 1))
        engine.start(0L)
        assertTrue(engine.evaluate(100L, mapOf(1L to blue)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(200L, mapOf(1L to blue)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(300L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun colorChangeRearmsAfterFiringSoItNeedsANewDifference() {
        val engine = SmartEngine(profile(point(1, condition = SmartCondition.COLOR_CHANGE), confirm = 1, cooldown = 100L))
        engine.start(0L)
        engine.evaluate(100L, mapOf(1L to blue))
        assertTrue(engine.evaluate(200L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
        // Next scan re-arms against the current colour instead of firing forever.
        assertTrue(engine.evaluate(400L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(600L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(800L, mapOf(1L to blue)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun everyPointGetsItsTurnUnderTheGlobalSpeedGate() {
        // v0.9.4: the cooldown is shared by the whole run, so point 2 waits for the same
        // gap as point 1 instead of firing immediately on its own private timer.
        val engine = SmartEngine(profile(point(1), point(2), confirm = 1, cooldown = 300L))
        engine.start(0L)
        val first = engine.evaluate(100L, mapOf(1L to red, 2L to red)) as SmartEngine.Decision.Fire
        assertEquals(1L, first.point.id)
        assertTrue(
            "no point may jump ahead of the shared gap",
            engine.evaluate(300L, mapOf(1L to red, 2L to red)) is SmartEngine.Decision.Wait
        )
        val second = engine.evaluate(400L, mapOf(1L to red, 2L to red)) as SmartEngine.Decision.Fire
        assertEquals("the next point must still get its turn", 2L, second.point.id)
    }

    @Test
    fun disabledPointsAreNeverSampledOrFired() {
        val engine = SmartEngine(profile(point(1, enabled = false), point(2), confirm = 1))
        engine.start(0L)
        val decision = engine.evaluate(100L, mapOf(1L to red, 2L to red)) as SmartEngine.Decision.Fire
        assertEquals(2L, decision.point.id)
        assertEquals(1, engine.enabledPoints().size)
    }

    @Test
    fun stopsWhenNoPointIsEnabled() {
        val engine = SmartEngine(profile(point(1, enabled = false)))
        engine.start(0L)
        val decision = engine.evaluate(100L, mapOf(1L to red))
        assertTrue(decision is SmartEngine.Decision.Stop)
        assertEquals("no_enabled_point", (decision as SmartEngine.Decision.Stop).reason)
    }

    @Test
    fun idleTimeoutStopsARunThatNeverMatches() {
        val engine = SmartEngine(profile(point(1), confirm = 2, idleSec = 5))
        engine.start(0L)
        assertTrue(engine.evaluate(1_000L, mapOf(1L to blue)) is SmartEngine.Decision.Wait)
        val decision = engine.evaluate(5_000L, mapOf(1L to blue))
        assertTrue(decision is SmartEngine.Decision.Stop)
        assertEquals("idle_timeout", (decision as SmartEngine.Decision.Stop).reason)
    }

    @Test
    fun successfulActionPostponesTheIdleTimeout() {
        val engine = SmartEngine(profile(point(1), confirm = 1, cooldown = 100L, idleSec = 5))
        engine.start(0L)
        engine.evaluate(4_000L, mapOf(1L to red))
        assertTrue(engine.evaluate(8_000L, mapOf(1L to blue)) is SmartEngine.Decision.Wait)
    }

    @Test
    fun maxActionsStopsTheRun() {
        // Cooldown is clamped to MIN_COOLDOWN_MS (300), so space scans accordingly.
        val engine = SmartEngine(profile(point(1), confirm = 1, cooldown = 300L, maxActions = 2))
        engine.start(0L)
        assertTrue(engine.evaluate(100L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
        assertTrue(engine.evaluate(500L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
        val decision = engine.evaluate(900L, mapOf(1L to red))
        assertTrue(decision is SmartEngine.Decision.Stop)
        assertEquals("max_actions", (decision as SmartEngine.Decision.Stop).reason)
        assertEquals(2, engine.actionCount)
    }

    @Test
    fun cooldownBelowPlatformMinimumIsClampedNotHonoured() {
        val engine = SmartEngine(profile(point(1), confirm = 1, cooldown = 1L))
        engine.start(0L)
        assertTrue(engine.evaluate(0L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
        // 1 ms was requested, but the sanitized minimum still blocks an instant refire.
        assertTrue(engine.evaluate(50L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(300L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun evaluateBeforeStartIsRejectedNotSilentlyIgnored() {
        val engine = SmartEngine(profile(point(1)))
        val decision = engine.evaluate(0L, mapOf(1L to red))
        assertTrue(decision is SmartEngine.Decision.Stop)
        assertEquals("engine_not_started", (decision as SmartEngine.Decision.Stop).reason)
    }

    @Test
    fun startResetsCountersForReuse() {
        val engine = SmartEngine(profile(point(1), confirm = 1, cooldown = 100L, maxActions = 1))
        engine.start(0L)
        engine.evaluate(100L, mapOf(1L to red))
        assertEquals(1, engine.actionCount)
        engine.start(1_000L)
        assertEquals(0, engine.actionCount)
        assertTrue(engine.evaluate(1_100L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun failedGestureKeepsCooldownSoItCannotSpin() {
        val engine = SmartEngine(profile(point(1), confirm = 1, cooldown = 1_000L))
        engine.start(0L)
        engine.evaluate(100L, mapOf(1L to red))
        engine.onActionFailed(1L, 150L)
        assertTrue(engine.evaluate(400L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(1_100L, mapOf(1L to red)) is SmartEngine.Decision.Wait)
        assertTrue(engine.evaluate(1_300L, mapOf(1L to red)) is SmartEngine.Decision.Fire)
    }
}
