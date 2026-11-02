package com.richclickermacro.smart

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.richclickermacro.data.MacroRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SmartRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: SmartRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        repository = SmartRepository(context)
    }

    private fun samplePoint(id: Long = 1L) = SmartPoint(
        id = id,
        label = "T$id",
        watchX = 0.3f,
        watchY = 0.6f,
        targetColor = 0xFF112233.toInt(),
        colorCaptured = true,
        tolerance = 30
    )

    @Test
    fun createsDefaultProfileOnFirstUse() {
        val profiles = repository.getProfiles()
        assertEquals(1, profiles.size)
        assertTrue(profiles.first().points.isEmpty())
    }

    @Test
    fun saveAndReloadPreservesEveryField() {
        val profile = repository.getActiveProfile()
        val point = samplePoint().copy(
            condition = SmartCondition.COLOR_CHANGE,
            action = SmartAction.LONG_PRESS,
            holdMs = 1_500L,
            enabled = false
        )
        val saved = repository.saveProfile(
            profile.copy(
                points = listOf(point),
                scanIntervalMs = 600L,
                cooldownMs = 2_500L,
                confirmSamples = 3,
                idleTimeoutSec = 45,
                maxActions = 99,
                countdownSeconds = 7,
                keepScreenOn = true,
                stopOnAppChange = false
            )
        )
        val reloaded = SmartRepository(context).getProfiles().first { it.name == saved.name }
        assertEquals(600L, reloaded.scanIntervalMs)
        assertEquals(2_500L, reloaded.cooldownMs)
        assertEquals(3, reloaded.confirmSamples)
        assertEquals(45, reloaded.idleTimeoutSec)
        assertEquals(99, reloaded.maxActions)
        assertEquals(7, reloaded.countdownSeconds)
        assertTrue(reloaded.keepScreenOn)
        assertFalse(reloaded.stopOnAppChange)
        val restored = reloaded.points.single()
        assertEquals(point.targetColor, restored.targetColor)
        assertTrue(restored.colorCaptured)
        assertEquals(restored.watchX, restored.effectiveTapX(), 0.0001f)
        assertEquals(restored.watchY, restored.effectiveTapY(), 0.0001f)
        assertEquals(SmartCondition.COLOR_CHANGE, restored.condition)
        assertEquals(SmartAction.LONG_PRESS, restored.action)
        assertEquals(1_500L, restored.holdMs)
        assertFalse(restored.enabled)
    }

    @Test
    fun exportImportRoundTrip() {
        repository.saveProfile(repository.getActiveProfile().copy(points = listOf(samplePoint())))
        val json = repository.exportJson()
        val count = repository.importJson(json)
        assertEquals(1, count)
        val profiles = repository.getProfiles()
        assertEquals(2, profiles.size)
        // Imported duplicate must be renamed, never overwrite the original.
        assertTrue(profiles.map { it.name }.toSet().size == 2)
        assertTrue(profiles.all { it.points.size == 1 })
    }

    @Test
    fun rejectsClassicMacroFileSoModesCannotCrossImport() {
        val classicJson = MacroRepository(context).exportJson()
        val failure = runCatching { repository.importJson(classicJson) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("Multi Smart Click"))
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        val root = org.json.JSONObject(repository.exportJson())
        root.put("schemaVersion", 99)
        val json = root.toString()
        assertTrue(runCatching { repository.importJson(json) }.isFailure)
    }

    @Test
    fun corruptStorageIsBackedUpAndReplacedWithRecoveryProfile() {
        context.getSharedPreferences("smart_profiles", Context.MODE_PRIVATE)
            .edit().putString("smart_profiles_json", "{bukan json").commit()
        val fresh = SmartRepository(context)
        val profiles = fresh.getProfiles()
        assertEquals(1, profiles.size)
        assertTrue(fresh.hasRecoveryBackup())
    }

    @Test
    fun deletingLastProfileRecreatesADefault() {
        val only = repository.getActiveProfile().name
        val next = repository.deleteProfile(only)
        assertEquals(1, repository.getProfiles().size)
        assertEquals(next.name, repository.getProfiles().first().name)
    }

    @Test
    fun updatePointsDoesNotChangeOtherProfiles() {
        val first = repository.getActiveProfile()
        val second = repository.createProfile("Kedua")
        repository.updatePoints(first.name, listOf(samplePoint()))
        val reloadedSecond = repository.getProfiles().first { it.name == second.name }
        assertTrue(reloadedSecond.points.isEmpty())
    }

    @Test
    fun pointCountIsCappedToProtectMemory() {
        val many = (1..25).map { samplePoint(it.toLong()) }
        val saved = repository.saveProfile(repository.getActiveProfile().copy(points = many))
        assertEquals(SmartProfile.MAX_POINTS, saved.points.size)
    }

    @Test
    fun scanIntervalIsClampedToPlatformLimit() {
        val saved = repository.saveProfile(repository.getActiveProfile().copy(scanIntervalMs = 10L))
        assertEquals(SmartProfile.MIN_SCAN_INTERVAL_MS, saved.scanIntervalMs)
    }

    @Test
    fun smartStorageDoesNotTouchClassicProfiles() {
        val classic = MacroRepository(context)
        val before = classic.exportJson()
        repository.saveProfile(repository.getActiveProfile().copy(points = listOf(samplePoint())))
        repository.createProfile("Smart lain")
        assertEquals(before, classic.exportJson())
    }
}
