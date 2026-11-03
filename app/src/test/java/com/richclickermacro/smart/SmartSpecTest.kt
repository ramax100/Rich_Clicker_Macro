package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the feature list the user fixed for v0.9.3:
 *  - add point
 *  - custom click count per point
 *  - loop from point 1 to the last point, unlimited by default or a custom count
 *  - recapture colour
 *  - tap vs long-press as an on/off choice
 *  - speed: Recommended / Fast / Custom, each with an explanation
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartSpecTest {

    private lateinit var context: Context
    private lateinit var repository: SmartRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmartRepository(context)
    }

    // ---------------------------------------------------------------- loop

    @Test
    fun loopIsUnlimitedByDefault() {
        val profile = repository.getActiveProfile()
        assertEquals("0 means unlimited", 0, profile.loopLimit)
    }

    @Test
    fun runAlwaysWalksFirstPointToLast() {
        val profile = repository.getActiveProfile()
        assertEquals(SmartOrderMode.SEQUENCE, profile.orderMode)
    }

    @Test
    fun anOldFreeOrderProfileIsUpgradedToOrdered() {
        // "Bebas" no longer exists. An old profile must not keep a mode the UI cannot show.
        val sanitized = SmartLimits.sanitizeProfile(
            repository.getActiveProfile().copy(orderMode = SmartOrderMode.ANY)
        )
        assertEquals(SmartOrderMode.SEQUENCE, sanitized.orderMode)
    }

    @Test
    fun customLoopCountSurvivesSaveAndReload() {
        val saved = repository.saveProfile(repository.getActiveProfile().copy(loopLimit = 25))
        val reloaded = SmartRepository(context).getProfiles().first { it.name == saved.name }
        assertEquals(25, reloaded.loopLimit)
    }

    @Test
    fun loopCountIsClampedToTheSupportedRange() {
        assertEquals(0, SmartLimits.sanitizeProfile(repository.getActiveProfile().copy(loopLimit = -5)).loopLimit)
        assertEquals(
            SmartProfile.MAX_LOOP_LIMIT,
            SmartLimits.sanitizeProfile(
                repository.getActiveProfile().copy(loopLimit = Int.MAX_VALUE)
            ).loopLimit
        )
    }

    @Test
    fun engineStopsExactlyAtTheRequestedLoopCount() {
        val a = SmartPoint(id = 1L, label = "T1", watchX = 0.2f, watchY = 0.2f, targetColor = 0xFF0000FF.toInt(), colorCaptured = true)
        val b = SmartPoint(id = 2L, label = "T2", watchX = 0.8f, watchY = 0.8f, targetColor = 0xFF0000FF.toInt(), colorCaptured = true)
        val profile = SmartLimits.sanitizeProfile(
            SmartProfile(name = "P", points = listOf(a, b), loopLimit = 2, confirmSamples = 1, cooldownMs = 300L)
        )
        val engine = SmartEngine(profile)
        engine.start(0L)
        var now = 0L
        var fired = 0
        var stopped: SmartEngine.Decision.Stop? = null
        repeat(40) {
            now += 1_000L
            when (val d = engine.evaluate(now, mapOf(1L to 0xFF0000FF.toInt(), 2L to 0xFF0000FF.toInt()))) {
                is SmartEngine.Decision.Fire -> fired++
                is SmartEngine.Decision.Stop -> { stopped = d; return@repeat }
                else -> Unit
            }
        }
        assertEquals("2 loops x 2 points", 4, fired)
        assertEquals(2, engine.loopCount)
        assertEquals("loop_limit", stopped?.reason)
    }

    @Test
    fun unlimitedLoopKeepsGoingPastManyCycles() {
        val a = SmartPoint(id = 1L, label = "T1", watchX = 0.2f, watchY = 0.2f, targetColor = 0xFF00FF00.toInt(), colorCaptured = true)
        val profile = SmartLimits.sanitizeProfile(
            SmartProfile(name = "P", points = listOf(a), loopLimit = 0, confirmSamples = 1, cooldownMs = 300L)
        )
        val engine = SmartEngine(profile)
        engine.start(0L)
        var now = 0L
        var fired = 0
        repeat(30) {
            now += 1_000L
            val d = engine.evaluate(now, mapOf(1L to 0xFF00FF00.toInt()))
            if (d is SmartEngine.Decision.Fire) fired++
            assertFalse("unlimited must never stop on its own", d is SmartEngine.Decision.Stop)
        }
        assertEquals(30, fired)
    }

    // ---------------------------------------------------------- click count

    @Test
    fun customClickCountIsPersistedPerPoint() {
        val point = SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.5f, repeatCount = 7)
        val saved = repository.saveProfile(repository.getActiveProfile().copy(points = listOf(point)))
        val restored = SmartRepository(context).getProfiles().first { it.name == saved.name }.points.single()
        assertEquals(7, restored.repeatCount)
    }

    @Test
    fun clickCountAcceptsTheFullRange() {
        val base = SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.5f)
        assertEquals(1, SmartLimits.sanitizePoint(base.copy(repeatCount = 0)).repeatCount)
        assertEquals(1_000, SmartLimits.sanitizePoint(base.copy(repeatCount = 50_000)).repeatCount)
        assertEquals(500, SmartLimits.sanitizePoint(base.copy(repeatCount = 500)).repeatCount)
    }

    // ------------------------------------------------------------ long press

    @Test
    fun longPressIsAnOnOffChoiceAndSurvivesReload() {
        val tap = SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.5f)
        assertEquals(SmartAction.TAP, tap.action)
        val held = tap.copy(action = SmartAction.LONG_PRESS, holdMs = 1_500L)
        val saved = repository.saveProfile(repository.getActiveProfile().copy(points = listOf(held)))
        val restored = SmartRepository(context).getProfiles().first { it.name == saved.name }.points.single()
        assertEquals(SmartAction.LONG_PRESS, restored.action)
        assertEquals(1_500L, restored.holdMs)
    }

    @Test
    fun holdDurationIsClamped() {
        val base = SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.5f, action = SmartAction.LONG_PRESS)
        assertEquals(SmartPoint.MIN_HOLD_MS, SmartLimits.sanitizePoint(base.copy(holdMs = 1L)).holdMs)
        assertEquals(SmartPoint.MAX_HOLD_MS, SmartLimits.sanitizePoint(base.copy(holdMs = 999_999L)).holdMs)
    }

    // ---------------------------------------------------------------- speed

    @Test
    fun recommendedIsTheDefaultSpeed() {
        assertEquals(SmartSpeedMode.RECOMMENDED, repository.getActiveProfile().speedMode)
    }

    @Test
    fun aNewProfileActuallyMatchesTheRecommendedLabel() {
        val profile = repository.getActiveProfile()
        assertEquals(SmartProfile.RECOMMENDED_SCAN_MS, profile.scanIntervalMs)
        assertEquals(SmartProfile.RECOMMENDED_COOLDOWN_MS, profile.cooldownMs)
        assertEquals(SmartProfile.RECOMMENDED_CONFIRM, profile.confirmSamples)
    }

    @Test
    fun fastPresetUsesTheMinimumSafeTimings() {
        val fast = SmartLimits.applySpeed(repository.getActiveProfile(), SmartSpeedMode.FAST)
        assertEquals(SmartSpeedMode.FAST, fast.speedMode)
        assertEquals(SmartProfile.MIN_SCAN_INTERVAL_MS, fast.scanIntervalMs)
        assertEquals(SmartProfile.MIN_COOLDOWN_MS, fast.cooldownMs)
        assertEquals(1, fast.confirmSamples)
    }

    @Test
    fun recommendedIsSlowerThanFastButStillResponsive() {
        val base = repository.getActiveProfile()
        val rec = SmartLimits.applySpeed(base, SmartSpeedMode.RECOMMENDED)
        val fast = SmartLimits.applySpeed(base, SmartSpeedMode.FAST)
        assertTrue(rec.cooldownMs > fast.cooldownMs)
        assertTrue(rec.confirmSamples > fast.confirmSamples)
        assertTrue("must stay within the platform screenshot limit", rec.scanIntervalMs >= 400L)
    }

    @Test
    fun customKeepsTheNumbersAlreadyStored() {
        val tuned = repository.getActiveProfile().copy(
            scanIntervalMs = 900L, cooldownMs = 4_000L, confirmSamples = 4
        )
        val custom = SmartLimits.applySpeed(tuned, SmartSpeedMode.CUSTOM)
        assertEquals(SmartSpeedMode.CUSTOM, custom.speedMode)
        assertEquals(900L, custom.scanIntervalMs)
        assertEquals(4_000L, custom.cooldownMs)
        assertEquals(4, custom.confirmSamples)
    }

    @Test
    fun switchingPresetsNeverDropsBelowThePlatformFloor() {
        SmartSpeedMode.values().forEach { mode ->
            val result = SmartLimits.applySpeed(repository.getActiveProfile(), mode)
            assertTrue(result.scanIntervalMs >= SmartProfile.MIN_SCAN_INTERVAL_MS)
            assertTrue(result.cooldownMs >= SmartProfile.MIN_COOLDOWN_MS)
            assertTrue(result.confirmSamples >= SmartProfile.MIN_CONFIRM_SAMPLES)
        }
    }

    @Test
    fun speedChoiceSurvivesSaveAndReload() {
        val saved = repository.saveProfile(
            SmartLimits.applySpeed(repository.getActiveProfile(), SmartSpeedMode.FAST)
        )
        val reloaded = SmartRepository(context).getProfiles().first { it.name == saved.name }
        assertEquals(SmartSpeedMode.FAST, reloaded.speedMode)
    }

    @Test
    fun customTimingsSurviveSaveAndReload() {
        val custom = SmartLimits.applySpeed(
            repository.getActiveProfile().copy(scanIntervalMs = 750L, cooldownMs = 2_000L, confirmSamples = 3),
            SmartSpeedMode.CUSTOM
        )
        val saved = repository.saveProfile(custom)
        val reloaded = SmartRepository(context).getProfiles().first { it.name == saved.name }
        assertEquals(SmartSpeedMode.CUSTOM, reloaded.speedMode)
        assertEquals(750L, reloaded.scanIntervalMs)
        assertEquals(2_000L, reloaded.cooldownMs)
        assertEquals(3, reloaded.confirmSamples)
    }

    // ------------------------------------------------- backward compatibility

    @Test
    fun anOldFileWithoutSpeedModeGetsAnHonestLabel() {
        // v0.9.0 "Cepat" was scan 400 / cooldown 300 / confirm 1 with no speedMode key.
        val json = """
            {
              "schemaVersion": 2,
              "kind": "rich-clicker-smart",
              "profiles": [
                {
                  "name": "Lama",
                  "scanIntervalMs": 400,
                  "cooldownMs": 300,
                  "confirmSamples": 1,
                  "points": [
                    {"id": 1, "label": "T1", "watchX": 0.3, "watchY": 0.4,
                     "targetColor": -16776961, "colorCaptured": true}
                  ]
                }
              ]
            }
        """.trimIndent()
        assertTrue(repository.importJson(json) > 0)
        val imported = repository.getProfiles().first { it.name == "Lama" }
        assertEquals("must be labelled Fast, not silently retimed", SmartSpeedMode.FAST, imported.speedMode)
        assertEquals(300L, imported.cooldownMs)
        assertEquals(1, imported.confirmSamples)
        assertEquals(SmartOrderMode.SEQUENCE, imported.orderMode)
    }

    @Test
    fun anOldFileWithUnusualTimingsIsLabelledCustom() {
        val json = """
            {
              "schemaVersion": 2,
              "kind": "rich-clicker-smart",
              "profiles": [
                {"name": "Aneh", "scanIntervalMs": 800, "cooldownMs": 5000, "confirmSamples": 5,
                 "points": [{"id": 1, "label": "T1", "watchX": 0.5, "watchY": 0.5}]}
              ]
            }
        """.trimIndent()
        repository.importJson(json)
        val imported = repository.getProfiles().first { it.name == "Aneh" }
        assertEquals(SmartSpeedMode.CUSTOM, imported.speedMode)
        assertEquals("stored timings must not be rewritten", 5_000L, imported.cooldownMs)
        assertEquals(5, imported.confirmSamples)
    }

    @Test
    fun currentSchemaIsWrittenAndOlderSchemasStillImport() {
        repository.saveProfile(repository.getActiveProfile())
        val root = org.json.JSONObject(repository.exportJson())
        // 4 since v0.9.6; schemas 1-3 still import unchanged.
        assertEquals(4, root.getInt("schemaVersion"))
        assertTrue(repository.importJson(repository.exportJson()) > 0)
    }

    @Test
    fun colourRecaptureClearsNothingElse() {
        val point = SmartPoint(
            id = 1L, label = "T1", watchX = 0.4f, watchY = 0.6f,
            targetColor = 0xFF112233.toInt(), colorCaptured = true, repeatCount = 4,
            action = SmartAction.LONG_PRESS, holdMs = 900L
        )
        val recaptured = point.copy(targetColor = 0xFF445566.toInt(), colorCaptured = true)
        assertEquals(4, recaptured.repeatCount)
        assertEquals(SmartAction.LONG_PRESS, recaptured.action)
        assertEquals(900L, recaptured.holdMs)
        assertEquals(0.4f, recaptured.watchX, 0.0001f)
    }

    @Test
    fun idleGuardCanBeDisabledForLongWaits() {
        val saved = repository.saveProfile(repository.getActiveProfile().copy(idleTimeoutSec = 0))
        val reloaded = SmartRepository(context).getProfiles().first { it.name == saved.name }
        assertEquals(0, reloaded.idleTimeoutSec)

        val point = SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.5f, targetColor = 0xFF000000.toInt(), colorCaptured = true)
        val engine = SmartEngine(
            SmartLimits.sanitizeProfile(SmartProfile(name = "P", points = listOf(point), idleTimeoutSec = 0))
        )
        engine.start(0L)
        // Ten minutes of never matching must not stop the run when the guard is off.
        val decision = engine.evaluate(600_000L, mapOf(1L to 0xFFFFFFFF.toInt()))
        assertFalse(decision is SmartEngine.Decision.Stop)
    }

    @Test
    fun smartChangesStillDoNotTouchClassicProfiles() {
        val classic = context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE)
        classic.edit().putString("sentinel", "untouched").commit()
        repository.saveProfile(
            SmartLimits.applySpeed(repository.getActiveProfile(), SmartSpeedMode.FAST)
        )
        assertEquals(
            "untouched",
            context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE).getString("sentinel", null)
        )
    }
}
