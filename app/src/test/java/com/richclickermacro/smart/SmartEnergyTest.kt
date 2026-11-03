package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rules for automatic energy refill.
 *
 * The important properties are not "does it tap" but the safety ones: a failed purchase
 * must never loop forever, the guard must never take a turn in the order, and a resumed
 * run must start again from point 1 instead of silently skipping steps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartEnergyTest {

    private val full = 0xFF00FF00.toInt()
    private val empty = 0xFFFF0000.toInt()
    private val other = 0xFF0000FF.toInt()

    private fun actionPoint(id: Long) = SmartPoint(
        id = id, label = "T$id",
        watchX = 0.2f + id * 0.1f, watchY = 0.4f,
        targetColor = full, colorCaptured = true,
        tolerance = 10, condition = SmartCondition.COLOR_MATCH
    )

    private fun guardPoint(id: Long = 99L) = SmartPoint(
        id = id, label = "Energi",
        watchX = 0.9f, watchY = 0.1f,
        targetColor = empty, colorCaptured = true,
        tolerance = 10, condition = SmartCondition.COLOR_MATCH,
        isGuard = true
    )

    private fun buyProfile(
        attempts: Int = 3,
        buyLimit: Int = 10,
        steps: List<SmartRecoveryStep> = listOf(
            SmartRecoveryStep(0.5f, 0.5f, 1_500L),
            SmartRecoveryStep(0.6f, 0.6f, 1_500L)
        )
    ) = SmartProfile(
        name = "Energi",
        points = listOf(actionPoint(1), actionPoint(2), guardPoint()),
        confirmSamples = 1,
        cooldownMs = 300L,
        idleTimeoutSec = 0,
        energyMode = EnergyMode.BUY,
        energyGuardId = 99L,
        recoverySteps = steps,
        recoveryAttempts = attempts,
        recoveryBuyLimit = buyLimit
    )

    private fun samples(guardColor: Int) = mapOf(1L to other, 2L to other, 99L to guardColor)

    /** Runs the guard confirmation scans the engine requires. */
    private fun drainGuard(engine: SmartEngine, guardColor: Int, startMs: Long): SmartEngine.Decision? {
        var decision: SmartEngine.Decision? = null
        repeat(SmartEngine.GUARD_CONFIRM_SCANS) { index ->
            decision = engine.evaluate(startMs + index * 10L, samples(guardColor))
        }
        return decision
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun guardPointNeverTakesATurnInTheOrder() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        val order = engine.enabledPoints().map { it.id }
        assertEquals(listOf(1L, 2L), order)
    }

    @Test
    fun guardPointIsStillSampled() {
        val engine = SmartEngine(buyProfile())
        assertEquals(listOf(1L, 2L, 99L), engine.sampledPoints().map { it.id })
    }

    @Test
    fun aGuardOnlyProfileStopsInsteadOfClickingTheGuard() {
        val engine = SmartEngine(
            buyProfile().copy(points = listOf(guardPoint()), energyGuardId = 99L)
        )
        engine.start(0L)
        val decision = engine.evaluate(10L, samples(full))
        assertTrue(decision is SmartEngine.Decision.Stop)
        assertEquals("no_enabled_point", (decision as SmartEngine.Decision.Stop).reason)
    }

    // ---------------------------------------------------------------- detection

    @Test
    fun oneEmptyReadingIsNotEnoughToBuy() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        val first = engine.evaluate(10L, samples(empty))
        assertFalse("a single bad frame must not trigger a purchase", first is SmartEngine.Decision.Recover)
    }

    @Test
    fun agreeingEmptyReadingsTriggerRecovery() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        val decision = drainGuard(engine, empty, 0L)
        assertTrue(decision is SmartEngine.Decision.Recover)
    }

    @Test
    fun recoveryCarriesTheConfiguredStepsOnlyInBuyMode() {
        val buy = SmartEngine(buyProfile())
        buy.start(0L)
        val buyDecision = drainGuard(buy, empty, 0L) as SmartEngine.Decision.Recover
        assertEquals(2, buyDecision.steps.size)
        assertEquals(3, buyDecision.maxAttempts)
        assertEquals(10, buyDecision.buyLimit)

        val wait = SmartEngine(buyProfile().copy(energyMode = EnergyMode.WAIT))
        wait.start(0L)
        val waitDecision = drainGuard(wait, empty, 0L) as SmartEngine.Decision.Recover
        assertTrue("WAIT mode taps nothing", waitDecision.steps.isEmpty())
    }

    @Test
    fun energyModeOffIgnoresTheGuardCompletely() {
        val engine = SmartEngine(buyProfile().copy(energyMode = EnergyMode.OFF))
        engine.start(0L)
        repeat(4) { index ->
            val decision = engine.evaluate(100L + index * 100L, samples(empty))
            assertFalse(decision is SmartEngine.Decision.Recover)
        }
    }

    @Test
    fun aFullGuardDoesNotTriggerRecovery() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        repeat(4) { index ->
            assertFalse(engine.evaluate(100L + index * 100L, samples(full)) is SmartEngine.Decision.Recover)
        }
    }

    // ---------------------------------------------------------------- safety caps

    @Test
    fun aRecoveredRunResumesFromPointOne() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        assertEquals(RecoveryOutcome.RESUMED, engine.completeRecovery(stillEmpty = false, nowMs = 50L))
        assertEquals(0, engine.recoveryAttempts)
    }

    @Test
    fun aRunResumesAtTheFirstActionPointNotTheGuard() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        engine.completeRecovery(stillEmpty = false, nowMs = 50L)
        val decision = engine.evaluate(1_000L, mapOf(1L to full, 2L to full, 99L to full))
        assertTrue(decision is SmartEngine.Decision.Fire)
        assertEquals("the order must restart at point 1", 1L, (decision as SmartEngine.Decision.Fire).point.id)
    }

    @Test
    fun repeatedFailureStopsAtTheAttemptLimit() {
        val engine = SmartEngine(buyProfile(attempts = 3))
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        assertEquals(RecoveryOutcome.RETRY, engine.completeRecovery(true, 100L))
        assertEquals(RecoveryOutcome.RETRY, engine.completeRecovery(true, 200L))
        assertEquals(RecoveryOutcome.STOPPED, engine.completeRecovery(true, 300L))
    }

    @Test
    fun theBuyLimitStopsAnEndlessPurchaseLoop() {
        val engine = SmartEngine(buyProfile(attempts = 10, buyLimit = 2))
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        assertEquals(RecoveryOutcome.RETRY, engine.completeRecovery(true, 100L))
        assertEquals(RecoveryOutcome.STOPPED, engine.completeRecovery(true, 200L))
        assertEquals(2, engine.recoveryBuyCount)
    }

    @Test
    fun waitModeNeverCountsAPurchase() {
        val engine = SmartEngine(buyProfile().copy(energyMode = EnergyMode.WAIT, recoveryAttempts = 5))
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        engine.completeRecovery(true, 100L)
        engine.completeRecovery(true, 200L)
        assertEquals(0, engine.recoveryBuyCount)
    }

    @Test
    fun anInterruptedRecoveryCountsNothing() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        engine.abortRecovery()
        assertEquals(0, engine.recoveryBuyCount)
        assertEquals(0, engine.recoveryAttempts)
        // The guard can trigger again right away.
        assertTrue(drainGuard(engine, empty, 500L) is SmartEngine.Decision.Recover)
    }

    @Test
    fun recoveryDoesNotSpendTheLoopBudget() {
        val engine = SmartEngine(buyProfile())
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        engine.completeRecovery(true, 100L)
        assertEquals("a pause is not a completed loop", 0, engine.loopCount)
        assertEquals(0, engine.actionCount)
    }

    @Test
    fun recoveryDoesNotTripTheIdleGuard() {
        val engine = SmartEngine(buyProfile().copy(idleTimeoutSec = 5))
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        // Buying took 20 seconds: well past the idle limit.
        engine.completeRecovery(false, 20_000L)
        val decision = engine.evaluate(20_500L, mapOf(1L to full, 2L to full, 99L to full))
        assertTrue("the run must continue after a slow purchase", decision !is SmartEngine.Decision.Stop)
    }

    @Test
    fun theStopMessageMentionsTheReason() {
        val engine = SmartEngine(buyProfile(attempts = 2))
        engine.start(0L)
        drainGuard(engine, empty, 0L)
        engine.completeRecovery(true, 100L)
        engine.completeRecovery(true, 200L)
        assertTrue(engine.recoveryStopMessage().contains("Energi belum terisi"))
    }

    // ---------------------------------------------------------------- clamping

    @Test
    fun limitsAreClampedToSafeRanges() {
        val clamped = SmartLimits.sanitizeProfile(
            buyProfile().copy(
                recoveryAttempts = 999,
                recoveryBuyLimit = 0,
                recoveryRetryDelayMs = 1L,
                recoverySteps = List(20) { SmartRecoveryStep(0.5f, 0.5f, 1L) }
            )
        )
        assertEquals(SmartProfile.MAX_RECOVERY_ATTEMPTS, clamped.recoveryAttempts)
        assertEquals(SmartProfile.MIN_RECOVERY_BUY_LIMIT, clamped.recoveryBuyLimit)
        assertEquals(SmartProfile.MIN_RECOVERY_RETRY_DELAY_MS, clamped.recoveryRetryDelayMs)
        assertEquals(SmartProfile.MAX_RECOVERY_STEPS, clamped.recoverySteps.size)
        assertTrue(clamped.recoverySteps.all { it.waitMs >= SmartRecoveryStep.MIN_WAIT_MS })
    }

    @Test
    fun aGuardThatNoLongerExistsIsCleared() {
        val clamped = SmartLimits.sanitizeProfile(buyProfile().copy(energyGuardId = 12345L))
        assertNull("a dangling guard id must not block the run", clamped.energyGuardId)
    }

    @Test
    fun buyModeIsKeptEvenWithNoSteps() {
        // The UI warns instead of silently switching the user's choice to another mode.
        val clamped = SmartLimits.sanitizeProfile(
            buyProfile().copy(recoverySteps = emptyList())
        )
        assertEquals(EnergyMode.BUY, clamped.energyMode)
    }

    // ---------------------------------------------------------------- storage

    @Test
    fun energySettingsSurviveSaveAndReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = SmartRepository(context)
        val profile = repository.getActiveProfile()
        val configured = SmartLimits.sanitizeProfile(
            profile.copy(
                points = listOf(actionPoint(1), guardPoint()),
                energyMode = EnergyMode.BUY,
                energyGuardId = 99L,
                recoverySteps = listOf(SmartRecoveryStep(0.25f, 0.75f, 2_000L)),
                recoveryAttempts = 4,
                recoveryBuyLimit = 6,
                recoveryRetryDelayMs = 5_000L
            )
        )
        repository.updateProfile(configured)
        val stored = repository.getActiveProfile()
        assertEquals(EnergyMode.BUY, stored.energyMode)
        assertEquals(99L, stored.energyGuardId)
        assertEquals(1, stored.recoverySteps.size)
        assertEquals(0.25f, stored.recoverySteps[0].x, 0.0001f)
        assertEquals(2_000L, stored.recoverySteps[0].waitMs)
        assertEquals(4, stored.recoveryAttempts)
        assertEquals(6, stored.recoveryBuyLimit)
        assertEquals(5_000L, stored.recoveryRetryDelayMs)
        assertTrue(stored.points.first { it.id == 99L }.isGuard)
    }

    @Test
    fun exportImportKeepsTheEnergySetup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = SmartRepository(context)
        val profile = repository.getActiveProfile()
        repository.updateProfile(
            SmartLimits.sanitizeProfile(
                profile.copy(
                    points = listOf(actionPoint(1), guardPoint()),
                    energyMode = EnergyMode.BUY,
                    energyGuardId = 99L,
                    recoverySteps = listOf(SmartRecoveryStep(0.4f, 0.4f, 1_000L))
                )
            )
        )
        val json = repository.exportJson()
        repository.importJson(json)
        val imported = repository.getProfiles().last { it.recoverySteps.isNotEmpty() }
        assertEquals(EnergyMode.BUY, imported.energyMode)
        assertEquals(99L, imported.energyGuardId)
        assertTrue(imported.points.any { it.isGuard })
    }

    @Test
    fun profilesWithoutEnergySettingsKeepWorking() {
        // Every profile written before v0.9.6 has no energy keys at all.
        val legacy = """
            {"kind":"rich-clicker-smart","schemaVersion":3,"active_smart_profile":"Lama",
             "profiles":[{"name":"Lama","scanIntervalMs":400,"cooldownMs":600,"confirmSamples":2,
              "speedMode":"RECOMMENDED","points":[
                {"id":1,"label":"A","watchX":0.2,"watchY":0.3,"targetColor":-65536}]}]}
        """.trimIndent()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = SmartRepository(context)
        repository.importJson(legacy)
        val profile = repository.getProfiles().first { it.name.startsWith("Lama") }
        assertEquals(EnergyMode.OFF, profile.energyMode)
        assertNull(profile.energyGuardId)
        assertTrue(profile.recoverySteps.isEmpty())
        assertNotEquals(0, profile.recoveryAttempts)
    }

    @Before
    fun clearPrefs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
