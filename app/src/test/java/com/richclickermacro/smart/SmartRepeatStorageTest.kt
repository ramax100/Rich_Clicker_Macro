package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject

/** Persistence of the new repeat/order/loop fields, including v0.8.0 file compatibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartRepeatStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clear() {
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun repeatCountSurvivesSaveAndReload() {
        val repo = SmartRepository(context)
        val base = repo.getActiveProfile()
        repo.saveProfile(
            base.copy(points = listOf(SmartPoint(1L, "T1", 0.3f, 0.4f, repeatCount = 7)))
        )
        val reloaded = SmartRepository(context).getProfiles().first { it.name == base.name }
        assertEquals(7, reloaded.points.first().repeatCount)
    }

    @Test
    fun orderModeAndLoopLimitSurviveSaveAndReload() {
        val repo = SmartRepository(context)
        val base = repo.getActiveProfile()
        repo.saveProfile(
            base.copy(orderMode = SmartOrderMode.SEQUENCE, loopLimit = 12)
        )
        val reloaded = SmartRepository(context).getProfiles().first { it.name == base.name }
        assertEquals(SmartOrderMode.SEQUENCE, reloaded.orderMode)
        assertEquals(12, reloaded.loopLimit)
    }

    @Test
    fun exportImportKeepsRepeatOrderAndLoop() {
        val repo = SmartRepository(context)
        repo.saveProfile(
            repo.getActiveProfile().copy(
                points = listOf(SmartPoint(1L, "T1", 0.3f, 0.4f, repeatCount = 5)),
                orderMode = SmartOrderMode.SEQUENCE,
                loopLimit = 3
            )
        )
        val json = repo.exportJson()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = SmartRepository(context)
        fresh.importJson(json)
        val imported = fresh.getProfiles().last()
        assertEquals(5, imported.points.first().repeatCount)
        assertEquals(SmartOrderMode.SEQUENCE, imported.orderMode)
        assertEquals(3, imported.loopLimit)
    }

    @Test
    fun schemaOneFileFromPreviousVersionStillImports() {
        // A v0.8.0 export has schemaVersion 1 and none of the new keys.
        val legacy = JSONObject().apply {
            put("kind", SmartRepository.KIND)
            put("schemaVersion", 1)
            put("active", "Lama")
            put("profiles", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "Lama")
                    put("points", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", 99L)
                            put("label", "T1")
                            put("watchX", 0.5)
                            put("watchY", 0.5)
                            put("targetColor", 0xFF102030.toInt())
                            put("colorCaptured", true)
                        })
                    })
                })
            })
        }
        val repo = SmartRepository(context)
        val count = repo.importJson(legacy.toString())
        assertEquals(1, count)
        val imported = repo.getProfiles().last()
        assertEquals("Lama", imported.name)
        // Missing keys must fall back to the old behaviour, not crash or change it.
        assertEquals(1, imported.points.first().repeatCount)
        // "Bebas" was removed in 0.9.3; an old file is upgraded to the ordered run.
        assertEquals(SmartOrderMode.SEQUENCE, imported.orderMode)
        assertEquals(0, imported.loopLimit)
    }

    @Test
    fun currentSchemaIsWrittenAsFour() {
        val repo = SmartRepository(context)
        val root = JSONObject(repo.exportJson())
        // v0.9.6 added the energy refill fields.
        assertEquals(4, root.getInt("schemaVersion"))
    }

    @Test
    fun outOfRangeValuesAreClampedOnSave() {
        val repo = SmartRepository(context)
        val saved = repo.saveProfile(
            repo.getActiveProfile().copy(
                points = listOf(SmartPoint(1L, "T1", 0.3f, 0.4f, repeatCount = 100_000)),
                loopLimit = 99_999_999
            )
        )
        assertEquals(SmartPoint.MAX_REPEAT_COUNT, saved.points.first().repeatCount)
        assertEquals(SmartProfile.MAX_LOOP_LIMIT, saved.loopLimit)
    }

    @Test
    fun newFieldsDoNotTouchClassicProfiles() {
        val classicPrefs = context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE)
        classicPrefs.edit().putString("marker", "untouched").commit()
        val repo = SmartRepository(context)
        repo.saveProfile(
            repo.getActiveProfile().copy(
                points = listOf(SmartPoint(1L, "T1", 0.3f, 0.4f, repeatCount = 4)),
                orderMode = SmartOrderMode.SEQUENCE,
                loopLimit = 2
            )
        )
        assertEquals("untouched", classicPrefs.getString("marker", null))
        assertTrue(classicPrefs.all.size == 1)
    }
}
