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
 * v0.9.3 keeps every setting visible (the 0.9.2 "advanced mode" gate was reverted
 * because hiding rows made them harder to find) but still has no separate tap location.
 *
 * These tests guard the two promises that matter to the user:
 *  1. a point can never click somewhere other than where it watches;
 *  2. old exports and stored profiles keep working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartSimplifiedUiTest {

    private lateinit var context: Context
    private lateinit var repository: SmartRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmartRepository(context)
    }

    @Test
    fun everyNewPointClicksExactlyWhereItWatches() {
        val positions = listOf(0f to 0f, 0.25f to 0.75f, 0.5f to 0.5f, 1f to 1f)
        positions.forEach { (x, y) ->
            val point = SmartPoint(id = 1L, label = "T1", watchX = x, watchY = y)
            assertEquals(x, point.effectiveTapX(), 0.00001f)
            assertEquals(y, point.effectiveTapY(), 0.00001f)
        }
    }

    @Test
    fun movingAPointMovesItsClickTargetToo() {
        val original = SmartPoint(id = 1L, label = "T1", watchX = 0.2f, watchY = 0.2f)
        val moved = original.copy(watchX = 0.9f, watchY = 0.1f)
        assertEquals(0.9f, moved.effectiveTapX(), 0.00001f)
        assertEquals(0.1f, moved.effectiveTapY(), 0.00001f)
    }

    @Test
    fun legacySeparateTapFieldsAreIgnoredNotRejected() {
        // A v0.8/0.9.0 export could contain separateTap/tapX/tapY. It must still import,
        // and the point must fall back to clicking where it watches.
        val json = """
            {
              "schemaVersion": 2,
              "kind": "rich-clicker-smart",
              "active_smart_profile": "Lama",
              "profiles": [
                {
                  "name": "Lama",
                  "points": [
                    {
                      "id": 1,
                      "label": "T1",
                      "watchX": 0.25,
                      "watchY": 0.35,
                      "separateTap": true,
                      "tapX": 0.9,
                      "tapY": 0.95,
                      "targetColor": -65536,
                      "colorCaptured": true,
                      "tolerance": 24,
                      "repeatCount": 2
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val imported = repository.importJson(json)
        assertTrue("legacy file must still import", imported > 0)
        val point = repository.getProfiles().first { it.name == "Lama" }.points.single()
        assertEquals(0.25f, point.watchX, 0.0001f)
        assertEquals(0.35f, point.watchY, 0.0001f)
        assertEquals("must click the watched spot, not the old tap spot", 0.25f, point.effectiveTapX(), 0.0001f)
        assertEquals(0.35f, point.effectiveTapY(), 0.0001f)
        assertEquals("other settings survive", 2, point.repeatCount)
    }

    @Test
    fun savedProfilesNoLongerWriteTheRemovedFields() {
        val profile = repository.getActiveProfile()
        repository.saveProfile(
            profile.copy(points = listOf(SmartPoint(id = 1L, label = "T1", watchX = 0.4f, watchY = 0.6f)))
        )
        val raw = context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE)
            .getString("smart_profiles_json", "") ?: ""
        assertFalse("separateTap must not be written anymore", raw.contains("separateTap"))
        assertFalse(raw.contains("\"tapX\""))
        assertFalse(raw.contains("\"tapY\""))
    }

    @Test
    fun simpleModeStillSupportsRepeatAndOrderedLoops() {
        // The features the user explicitly asked for must survive the simplification.
        val profile = repository.getActiveProfile()
        val saved = repository.saveProfile(
            profile.copy(
                points = listOf(
                    SmartPoint(id = 1L, label = "T1", watchX = 0.2f, watchY = 0.2f, repeatCount = 5),
                    SmartPoint(id = 2L, label = "T2", watchX = 0.8f, watchY = 0.8f, repeatCount = 1)
                ),
                orderMode = SmartOrderMode.SEQUENCE,
                loopLimit = 4
            )
        )
        val reloaded = SmartRepository(context).getProfiles().first { it.name == saved.name }
        assertEquals(5, reloaded.points.first().repeatCount)
        assertEquals(SmartOrderMode.SEQUENCE, reloaded.orderMode)
        assertEquals(4, reloaded.loopLimit)
    }

    @Test
    fun removingTheOptionDidNotTouchClassicProfiles() {
        val classic = context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE)
        classic.edit().putString("sentinel", "untouched").commit()
        repository.saveProfile(
            repository.getActiveProfile().copy(
                points = listOf(SmartPoint(id = 1L, label = "T1", watchX = 0.5f, watchY = 0.5f))
            )
        )
        assertEquals(
            "untouched",
            context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE).getString("sentinel", null)
        )
    }
}
