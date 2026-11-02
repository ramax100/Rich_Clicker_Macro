package com.richclickermacro.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MacroRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: MacroRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("auto_clicker_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        repository = MacroRepository(context)
    }

    @Test
    fun createsDefaultProfileWithAppGuardEnabled() {
        val profiles = repository.getProfiles()
        assertEquals(1, profiles.size)
        assertEquals("Default", profiles[0].name)
        assertTrue(profiles[0].stopOnAppChange)
        assertEquals(1, profiles[0].points.size)
    }

    @Test
    fun createRenameAndDeleteProfile() {
        val created = repository.createProfile("Game A")
        assertEquals("Game A", created.name)
        val renamed = repository.saveProfile(created.copy(name = "Game B"), created.name)
        assertEquals("Game B", renamed.name)
        assertEquals(2, repository.getProfiles().size)
        repository.deleteProfile("Game B")
        assertFalse(repository.getProfiles().any { it.name == "Game B" })
    }

    @Test
    fun duplicateNamesGetSuffix() {
        repository.createProfile("Macro")
        val second = repository.createProfile("Macro")
        assertEquals("Macro (2)", second.name)
    }

    @Test
    fun exportUsesSchemaV3AndImportRoundTrips() {
        repository.createProfile("Round Trip")
        val json = repository.exportJson()
        val root = JSONObject(json)
        assertEquals(3, root.getInt("schemaVersion"))
        val count = repository.importJson(json)
        assertTrue(count >= 2)
        assertTrue(repository.getProfiles().any { it.name == "Round Trip (2)" })
    }

    @Test
    fun importsLegacySchemaV1WithSafeDefaults() {
        val legacy = """
            {
              "schemaVersion": 1,
              "activeProfile": "Legacy",
              "profiles": [
                {
                  "name": "Legacy",
                  "defaultDelayMs": 1000,
                  "points": [{"x": 0.5, "y": 0.3, "delayMs": 1000}]
                }
              ]
            }
        """.trimIndent()
        assertEquals(1, repository.importJson(legacy))
        val profile = repository.getProfile("Legacy")!!
        assertTrue(profile.stopOnAppChange)
        assertEquals(1_000L, profile.loopDelayMs)
        assertEquals(PointActionType.TAP, profile.points[0].actionType)
    }

    @Test
    fun corruptProfilesAreBackedUpAndRecovered() {
        context.getSharedPreferences("macro_profiles", Context.MODE_PRIVATE)
            .edit().putString("profiles_json", "{corrupt").commit()
        val profiles = repository.getProfiles()
        assertEquals("Recovery", profiles[0].name)
        assertTrue(repository.hasRecoveryBackup())
    }
}
