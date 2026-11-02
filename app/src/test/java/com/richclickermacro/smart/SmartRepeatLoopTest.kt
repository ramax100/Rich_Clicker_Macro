package com.richclickermacro.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two behaviours users asked to match the classic macro:
 *  - one point clicking several times per satisfied condition
 *  - an ordered 1 -> last pass that repeats, with an optional loop limit
 *
 * The engine is pure, so these run without a device.
 */
class SmartRepeatLoopTest {

    private fun point(
        id: Long,
        repeat: Int = 1,
        color: Int = 0xFF204080.toInt()
    ) = SmartPoint(
        id = id,
        label = "T$id",
        watchX = 0.2f + id * 0.1f,
        watchY = 0.5f,
        targetColor = color,
        colorCaptured = true,
        repeatCount = repeat
    )

    private fun profile(
        points: List<SmartPoint>,
        order: SmartOrderMode = SmartOrderMode.ANY,
        loops: Int = 0,
        confirm: Int = 1
    ) = SmartProfile(
        name = "Test",
        points = points,
        orderMode = order,
        loopLimit = loops,
        confirmSamples = confirm,
        idleTimeoutSec = 0
    )

    private fun samples(vararg points: SmartPoint): Map<Long, Int?> =
        points.associate { it.id to it.targetColor }

    // ------------------------------------------------------------- repeat count

    @Test
    fun repeatCountProducesThatManyClicksFromOneCondition() {
        val p = point(1, repeat = 3)
        val engine = SmartEngine(profile(listOf(p)))
        engine.start(1_000L)

        val first = engine.evaluate(1_000L, samples(p)) as SmartEngine.Decision.Fire
        assertEquals(1, first.repeatIndex)
        assertEquals(3, first.repeatTotal)
        assertTrue(engine.hasPendingBurst())

        val second = engine.nextRepeat(1_100L) as SmartEngine.Decision.Fire
        assertEquals(2, second.repeatIndex)
        assertTrue(engine.hasPendingBurst())

        val third = engine.nextRepeat(1_200L) as SmartEngine.Decision.Fire
        assertEquals(3, third.repeatIndex)
        assertFalse("burst must end after the last repeat", engine.hasPendingBurst())
        assertEquals(3, engine.actionCount)
    }

    @Test
    fun everyRepeatTargetsTheSamePoint() {
        val a = point(1, repeat = 4)
        val b = point(2)
        val engine = SmartEngine(profile(listOf(a, b)))
        engine.start(0L)
        val first = engine.evaluate(0L, samples(a, b)) as SmartEngine.Decision.Fire
        repeat(3) {
            val next = engine.nextRepeat(100L) as SmartEngine.Decision.Fire
            assertEquals("burst must not drift to another point", first.point.id, next.point.id)
        }
        assertFalse(engine.hasPendingBurst())
    }

    @Test
    fun repeatCountOneLeavesNoPendingBurst() {
        val p = point(1)
        val engine = SmartEngine(profile(listOf(p)))
        engine.start(0L)
        val fire = engine.evaluate(0L, samples(p)) as SmartEngine.Decision.Fire
        assertEquals(1, fire.repeatTotal)
        assertFalse(engine.hasPendingBurst())
        assertEquals(1, engine.actionCount)
    }

    @Test
    fun nextRepeatWithoutBurstIsWait() {
        val engine = SmartEngine(profile(listOf(point(1))))
        engine.start(0L)
        assertTrue(engine.nextRepeat(0L) is SmartEngine.Decision.Wait)
    }

    @Test
    fun maxActionsInterruptsABurst() {
        val p = point(1, repeat = 10)
        val engine = SmartEngine(profile(listOf(p)).copy(maxActions = 2))
        engine.start(0L)
        engine.evaluate(0L, samples(p))
        engine.nextRepeat(60L)
        val stopped = engine.nextRepeat(120L)
        assertTrue("limit must cut a burst short", stopped is SmartEngine.Decision.Stop)
        assertEquals("max_actions", (stopped as SmartEngine.Decision.Stop).reason)
        assertEquals(2, engine.actionCount)
    }

    @Test
    fun failedGestureCancelsRemainingRepeats() {
        val p = point(1, repeat = 5)
        val engine = SmartEngine(profile(listOf(p)))
        engine.start(0L)
        engine.evaluate(0L, samples(p))
        assertTrue(engine.hasPendingBurst())
        engine.onActionFailed(p.id, 50L)
        assertFalse("a rejected click must not keep firing", engine.hasPendingBurst())
    }

    @Test
    fun repeatCountIsClampedToSafeRange() {
        val engine = SmartEngine(profile(listOf(point(1, repeat = 99_999))))
        assertEquals(SmartPoint.MAX_REPEAT_COUNT, engine.profile().points.first().repeatCount)
        val low = SmartEngine(profile(listOf(point(2, repeat = 0))))
        assertEquals(1, low.profile().points.first().repeatCount)
    }

    // -------------------------------------------------------------- ordered run

    @Test
    fun sequenceModeFiresPointsInListOrder() {
        val a = point(1)
        val b = point(2)
        val c = point(3)
        val engine = SmartEngine(profile(listOf(a, b, c), order = SmartOrderMode.SEQUENCE))
        engine.start(0L)
        // Cooldown is per point, so advancing far enough keeps every step eligible.
        val order = mutableListOf<Long>()
        var now = 0L
        repeat(3) {
            now += 5_000L
            val fire = engine.evaluate(now, samples(a, b, c)) as SmartEngine.Decision.Fire
            order.add(fire.point.id)
        }
        assertEquals(listOf(1L, 2L, 3L), order)
    }

    @Test
    fun sequenceModeWaitsForTheCurrentPointOnly() {
        val a = point(1, color = 0xFF111111.toInt())
        val b = point(2, color = 0xFF222222.toInt())
        val engine = SmartEngine(profile(listOf(a, b), order = SmartOrderMode.SEQUENCE))
        engine.start(0L)
        // Only point 2 matches; step 1 is still pending, so nothing may fire.
        val decision = engine.evaluate(1_000L, mapOf(a.id to 0xFF999999.toInt(), b.id to b.targetColor))
        assertTrue("must not skip ahead to a later point", decision is SmartEngine.Decision.Wait)
    }

    @Test
    fun completingLastPointCountsOneLoop() {
        val a = point(1)
        val b = point(2)
        val engine = SmartEngine(profile(listOf(a, b), order = SmartOrderMode.SEQUENCE))
        engine.start(0L)
        var now = 0L
        repeat(2) {
            now += 5_000L
            engine.evaluate(now, samples(a, b))
        }
        assertEquals(1, engine.loopCount)
        repeat(2) {
            now += 5_000L
            engine.evaluate(now, samples(a, b))
        }
        assertEquals(2, engine.loopCount)
    }

    @Test
    fun loopLimitStopsAfterThatManyPasses() {
        val a = point(1)
        val b = point(2)
        val engine = SmartEngine(profile(listOf(a, b), order = SmartOrderMode.SEQUENCE, loops = 2))
        engine.start(0L)
        var now = 0L
        var stop: SmartEngine.Decision.Stop? = null
        repeat(6) {
            if (stop != null) return@repeat
            now += 5_000L
            val decision = engine.evaluate(now, samples(a, b))
            if (decision is SmartEngine.Decision.Stop) stop = decision
        }
        assertEquals("loop_limit", stop?.reason)
        assertEquals(2, engine.loopCount)
        assertEquals("two passes over two points", 4, engine.actionCount)
    }

    @Test
    fun loopLimitZeroMeansUnlimited() {
        val a = point(1)
        val engine = SmartEngine(profile(listOf(a), order = SmartOrderMode.SEQUENCE, loops = 0))
        engine.start(0L)
        var now = 0L
        repeat(8) {
            now += 5_000L
            assertTrue(engine.evaluate(now, samples(a)) is SmartEngine.Decision.Fire)
        }
        assertEquals(8, engine.loopCount)
    }

    @Test
    fun sequenceWithRepeatFinishesBurstBeforeAdvancing() {
        val a = point(1, repeat = 3)
        val b = point(2)
        val engine = SmartEngine(profile(listOf(a, b), order = SmartOrderMode.SEQUENCE))
        engine.start(0L)
        val first = engine.evaluate(1_000L, samples(a, b)) as SmartEngine.Decision.Fire
        assertEquals(1L, first.point.id)
        // Still on step 1 while its burst drains.
        assertEquals(1L, (engine.nextRepeat(1_060L) as SmartEngine.Decision.Fire).point.id)
        assertEquals(1L, (engine.nextRepeat(1_120L) as SmartEngine.Decision.Fire).point.id)
        assertFalse(engine.hasPendingBurst())
        val next = engine.evaluate(8_000L, samples(a, b)) as SmartEngine.Decision.Fire
        assertEquals("step 2 only after step 1 finished all repeats", 2L, next.point.id)
    }

    @Test
    fun freeOrderIsUpgradedToOrderedSoLoopCountingAlwaysApplies() {
        // v0.9.3: the run always walks point 1 -> last. A profile still asking for the
        // removed ANY mode is sanitized to SEQUENCE, so the loop limit is honoured.
        val a = point(1)
        val engine = SmartEngine(profile(listOf(a), order = SmartOrderMode.ANY, loops = 1))
        engine.start(0L)
        assertTrue(engine.evaluate(5_000L, samples(a)) is SmartEngine.Decision.Fire)
        assertEquals(1, engine.loopCount)
        val stopped = engine.evaluate(10_000L, samples(a))
        assertTrue("loop limit must now stop the run", stopped is SmartEngine.Decision.Stop)
    }

    @Test
    fun disabledPointsAreSkippedInTheSequence() {
        val a = point(1)
        val b = point(2).copy(enabled = false)
        val c = point(3)
        val engine = SmartEngine(profile(listOf(a, b, c), order = SmartOrderMode.SEQUENCE))
        engine.start(0L)
        var now = 0L
        val seen = mutableListOf<Long>()
        repeat(2) {
            now += 5_000L
            seen.add((engine.evaluate(now, samples(a, c)) as SmartEngine.Decision.Fire).point.id)
        }
        assertEquals(listOf(1L, 3L), seen)
        assertEquals(1, engine.loopCount)
    }

    @Test
    fun sequencePositionIsReportedForTheUi() {
        val a = point(1)
        val b = point(2)
        val engine = SmartEngine(profile(listOf(a, b), order = SmartOrderMode.SEQUENCE))
        engine.start(0L)
        assertEquals(1, engine.sequencePosition())
        engine.evaluate(1_000L, samples(a, b))
        assertEquals(2, engine.sequencePosition())
    }

    @Test
    fun restartClearsBurstAndLoopCounters() {
        val a = point(1, repeat = 4)
        val engine = SmartEngine(profile(listOf(a), order = SmartOrderMode.SEQUENCE, loops = 1))
        engine.start(0L)
        engine.evaluate(0L, samples(a))
        engine.nextRepeat(60L)
        assertTrue(engine.hasPendingBurst())
        engine.start(10_000L)
        assertFalse(engine.hasPendingBurst())
        assertEquals(0, engine.actionCount)
        assertEquals(0, engine.loopCount)
        assertEquals(1, engine.sequencePosition())
    }
}
