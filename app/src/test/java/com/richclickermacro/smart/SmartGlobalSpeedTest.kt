package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The click-speed setting is global: one choice governs every point.
 *
 * Before v0.9.4 each point carried its own cooldown, so a 3-point run clicked about
 * three times faster than the selected speed and the setting did not feel like it
 * applied to everything. These tests pin the shared gate.
 */
class SmartGlobalSpeedTest {

    private val blue = 0xFF0000FF.toInt()

    private fun point(id: Long, x: Float, repeat: Int = 1) = SmartPoint(
        id = id, label = "T$id", watchX = x, watchY = x,
        targetColor = blue, colorCaptured = true, tolerance = 24, repeatCount = repeat
    )

    private fun profile(points: List<SmartPoint>, mode: SmartSpeedMode) =
        SmartLimits.applySpeed(
            SmartProfile(name = "P", points = points, idleTimeoutSec = 0, loopLimit = 0),
            mode
        )

    private fun samples(points: List<SmartPoint>): Map<Long, Int?> = points.associate { it.id to blue }

    @Test
    fun speedIsStoredOnTheProfileNotOnEachPoint() {
        // There is no per-point speed field at all, so one choice cannot diverge.
        val p = profile(listOf(point(1, 0.2f), point(2, 0.5f), point(3, 0.8f)), SmartSpeedMode.FAST)
        assertEquals(SmartSpeedMode.FAST, p.speedMode)
        assertEquals(SmartProfile.FAST_COOLDOWN_MS, p.cooldownMs)
        assertEquals(3, p.points.size)
    }

    @Test
    fun withThreePointsTheGapBetweenClicksIsStillTheChosenGap() {
        val points = listOf(point(1, 0.2f), point(2, 0.5f), point(3, 0.8f))
        val p = profile(points, SmartSpeedMode.FAST)
        val engine = SmartEngine(p)
        engine.start(0L)

        val times = mutableListOf<Long>()
        var now = 0L
        // Step in 50 ms ticks so we observe the true earliest fire time.
        repeat(400) {
            now += 50L
            if (engine.evaluate(now, samples(points)) is SmartEngine.Decision.Fire) times.add(now)
        }
        assertTrue("must have fired several times", times.size >= 4)
        for (i in 1 until times.size) {
            val gap = times[i] - times[i - 1]
            assertTrue(
                "gap $gap must respect the global cooldown ${p.cooldownMs}, not cooldown/points",
                gap >= p.cooldownMs
            )
        }
    }

    @Test
    fun addingMorePointsDoesNotSpeedTheRunUp() {
        fun clicksFor(count: Int): Int {
            val points = (1..count).map { point(it.toLong(), it / (count + 1f)) }
            val engine = SmartEngine(profile(points, SmartSpeedMode.FAST))
            engine.start(0L)
            var now = 0L
            var fired = 0
            repeat(60) {
                now += 100L
                if (engine.evaluate(now, samples(points)) is SmartEngine.Decision.Fire) fired++
            }
            return fired
        }
        val one = clicksFor(1)
        val five = clicksFor(5)
        assertEquals("5 points must not click 5x as often as 1 point", one, five)
    }

    @Test
    fun recommendedIsMeasurablySlowerThanFastAcrossAllPoints() {
        fun clicksFor(mode: SmartSpeedMode): Int {
            val points = listOf(point(1, 0.2f), point(2, 0.5f), point(3, 0.8f))
            val engine = SmartEngine(profile(points, mode))
            engine.start(0L)
            var now = 0L
            var fired = 0
            repeat(120) {
                now += 100L
                if (engine.evaluate(now, samples(points)) is SmartEngine.Decision.Fire) fired++
            }
            return fired
        }
        val fast = clicksFor(SmartSpeedMode.FAST)
        val recommended = clicksFor(SmartSpeedMode.RECOMMENDED)
        assertTrue("Fast ($fast) must fire more often than Recommended ($recommended)", fast > recommended)
    }

    @Test
    fun changingSpeedAppliesToEveryPointImmediately() {
        val points = listOf(point(1, 0.2f), point(2, 0.8f))
        val fast = SmartEngine(profile(points, SmartSpeedMode.FAST))
        fast.start(0L)
        assertTrue(fast.evaluate(400L, samples(points)) is SmartEngine.Decision.Fire)
        // Point 2 would have been ready under the old per-point cooldown.
        assertFalse("second point must wait for the shared gap", fast.evaluate(500L, samples(points)) is SmartEngine.Decision.Fire)

        val custom = SmartEngine(
            SmartLimits.sanitizeProfile(
                profile(points, SmartSpeedMode.CUSTOM).copy(cooldownMs = 2_000L, confirmSamples = 1)
            )
        )
        custom.start(0L)
        assertTrue(custom.evaluate(400L, samples(points)) is SmartEngine.Decision.Fire)
        assertFalse(custom.evaluate(1_000L, samples(points)) is SmartEngine.Decision.Fire)
        assertFalse(custom.evaluate(2_000L, samples(points)) is SmartEngine.Decision.Fire)
        assertTrue(custom.evaluate(2_400L, samples(points)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun aBurstStillUsesItsOwnTightSpacing() {
        // "Berapa kali diklik" is per point on purpose; the global gate resumes after it.
        val points = listOf(point(1, 0.2f, repeat = 3), point(2, 0.8f))
        val p = profile(points, SmartSpeedMode.FAST)
        val engine = SmartEngine(p)
        engine.start(0L)
        val first = engine.evaluate(400L, samples(points)) as SmartEngine.Decision.Fire
        assertEquals(1L, first.point.id)
        assertTrue(engine.nextRepeat(460L) is SmartEngine.Decision.Fire)
        assertTrue(engine.nextRepeat(520L) is SmartEngine.Decision.Fire)
        assertFalse(engine.hasPendingBurst())
        // The next point must still respect the global cooldown measured from the last click.
        assertFalse(engine.evaluate(600L, samples(points)) is SmartEngine.Decision.Fire)
        assertTrue(engine.evaluate(900L, samples(points)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun aFailedClickAlsoDelaysTheWholeRunNotJustThatPoint() {
        val points = listOf(point(1, 0.2f), point(2, 0.8f))
        val p = profile(points, SmartSpeedMode.FAST)
        val engine = SmartEngine(p)
        engine.start(0L)
        assertTrue(engine.evaluate(400L, samples(points)) is SmartEngine.Decision.Fire)
        engine.onActionFailed(1L, 400L)
        assertFalse("no other point may jump in during the gap", engine.evaluate(500L, samples(points)) is SmartEngine.Decision.Fire)
    }

    @Test
    fun orderedLoopStillAdvancesUnderTheGlobalGate() {
        val points = listOf(point(1, 0.2f), point(2, 0.5f), point(3, 0.8f))
        val engine = SmartEngine(
            SmartLimits.sanitizeProfile(profile(points, SmartSpeedMode.FAST).copy(loopLimit = 2))
        )
        engine.start(0L)
        val seen = mutableListOf<Long>()
        var now = 0L
        var stopped = false
        repeat(200) {
            now += 100L
            when (val d = engine.evaluate(now, samples(points))) {
                is SmartEngine.Decision.Fire -> seen.add(d.point.id)
                is SmartEngine.Decision.Stop -> stopped = true
                else -> Unit
            }
        }
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), seen)
        assertTrue("must stop after the requested loops", stopped)
        assertEquals(2, engine.loopCount)
    }
}
