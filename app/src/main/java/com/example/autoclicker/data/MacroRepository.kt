package com.example.autoclicker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MacroRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPreferences = ClickPreferences(context)

    @Synchronized
    fun getProfiles(): List<MacroProfile> {
        return when (val result = readProfiles()) {
            ProfileReadResult.Missing -> {
                val initial = createDefaultProfile(DEFAULT_PROFILE_NAME)
                writeProfiles(listOf(initial), initial.name)
                listOf(initial)
            }
            is ProfileReadResult.Loaded -> result.profiles
            is ProfileReadResult.Corrupt -> {
                backupCorruptProfiles(result.raw)
                listOf(createDefaultProfile(RECOVERY_PROFILE_NAME))
            }
        }
    }

    fun hasRecoveryBackup(): Boolean = preferences.contains(KEY_CORRUPT_BACKUP)

    @Synchronized
    fun getActiveProfile(): MacroProfile {
        val profiles = getProfiles()
        val activeName = preferences.getString(KEY_ACTIVE_PROFILE, null)
        return profiles.firstOrNull { it.name == activeName } ?: profiles.first()
    }

    @Synchronized
    fun getProfile(name: String): MacroProfile? = getProfiles().firstOrNull { it.name == name }

    @Synchronized
    fun setActiveProfile(name: String) {
        if (getProfiles().any { it.name == name }) {
            preferences.edit().putString(KEY_ACTIVE_PROFILE, name).apply()
        }
    }

    @Synchronized
    fun createProfile(requestedName: String): MacroProfile {
        val profiles = getProfiles().toMutableList()
        val uniqueName = uniqueName(cleanName(requestedName), profiles.map { it.name }.toSet())
        val profile = createDefaultProfile(uniqueName)
        profiles.add(profile)
        writeProfiles(profiles, profile.name)
        return profile
    }

    @Synchronized
    fun saveProfile(profile: MacroProfile, replacingName: String? = profile.name): MacroProfile {
        val profiles = getProfiles().toMutableList()
        val cleaned = sanitizeProfile(profile)
        val replacedIndex = profiles.indexOfFirst { it.name == replacingName }
        val namesToAvoid = profiles.mapIndexedNotNull { index, item ->
            item.name.takeUnless { index == replacedIndex }
        }.toSet()
        val finalProfile = cleaned.copy(name = uniqueName(cleaned.name, namesToAvoid))
        if (replacedIndex >= 0) profiles[replacedIndex] = finalProfile else profiles.add(finalProfile)
        writeProfiles(profiles, finalProfile.name)
        return finalProfile
    }

    @Synchronized
    fun updateProfilePoints(name: String, points: List<MacroPoint>): MacroProfile? {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.name == name }
        if (index < 0) return null
        val activeName = preferences.getString(KEY_ACTIVE_PROFILE, profiles.first().name) ?: profiles.first().name
        val merged = sanitizeProfile(profiles[index].copy(points = points))
        profiles[index] = merged
        writeProfiles(profiles, activeName)
        return merged
    }

    @Synchronized
    fun deleteProfile(name: String): MacroProfile {
        val profiles = getProfiles().filterNot { it.name == name }.toMutableList()
        if (profiles.isEmpty()) profiles.add(createDefaultProfile(DEFAULT_PROFILE_NAME))
        val active = profiles.first()
        writeProfiles(profiles, active.name)
        return active
    }

    @Synchronized
    fun exportJson(): String = buildRootJson(getProfiles(), getActiveProfile().name).toString(2)

    @Synchronized
    fun importJson(json: String): Int {
        val root = JSONObject(json)
        val schema = root.optInt(KEY_SCHEMA_VERSION, 1)
        require(schema in 1..SCHEMA_VERSION) { "Versi format profil tidak didukung" }
        val exportedActiveName = root.optString(KEY_ACTIVE_PROFILE, "")
        val importedArray = root.getJSONArray(KEY_PROFILES)
        val existing = getProfiles().toMutableList()
        val names = existing.map { it.name }.toMutableSet()
        val importedNameMap = mutableMapOf<String, String>()
        var importedCount = 0
        for (index in 0 until importedArray.length()) {
            val parsed = parseProfile(importedArray.getJSONObject(index)) ?: continue
            val renamed = parsed.copy(name = uniqueName(parsed.name, names))
            existing.add(renamed)
            names.add(renamed.name)
            importedNameMap[parsed.name] = renamed.name
            importedCount++
        }
        require(importedCount > 0) { "Tidak ada profil valid di dalam file" }
        writeProfiles(existing, importedNameMap[exportedActiveName] ?: existing.last().name)
        return importedCount
    }

    private fun readProfiles(): ProfileReadResult {
        val raw = preferences.getString(KEY_PROFILES_JSON, null) ?: return ProfileReadResult.Missing
        return runCatching {
            val root = JSONObject(raw)
            val schema = root.optInt(KEY_SCHEMA_VERSION, 1)
            require(schema in 1..SCHEMA_VERSION)
            val array = root.getJSONArray(KEY_PROFILES)
            val profiles = buildList {
                for (index in 0 until array.length()) parseProfile(array.getJSONObject(index))?.let(::add)
            }
            require(profiles.isNotEmpty() && profiles.size == array.length())
            ProfileReadResult.Loaded(profiles)
        }.getOrElse { ProfileReadResult.Corrupt(raw) }
    }

    private fun backupCorruptProfiles(raw: String) {
        if (!preferences.contains(KEY_CORRUPT_BACKUP)) {
            preferences.edit().putString(KEY_CORRUPT_BACKUP, raw).apply()
        }
    }

    private fun parseProfile(json: JSONObject): MacroProfile? = runCatching {
        val pointsJson = json.getJSONArray(KEY_POINTS)
        val points = buildList {
            for (index in 0 until pointsJson.length()) {
                val point = pointsJson.getJSONObject(index)
                val startX = normalized(point.optDouble(KEY_X, 0.5), 0.5f)
                val startY = normalized(point.optDouble(KEY_Y, 0.3), 0.3f)
                add(
                    MacroPoint(
                        id = point.optLong(KEY_ID, System.nanoTime() + index),
                        normalizedX = startX,
                        normalizedY = startY,
                        delayMs = point.optLong(KEY_DELAY_MS, ClickPreferences.DEFAULT_DELAY_MS),
                        actionType = parseAction(point.optString(KEY_ACTION_TYPE, PointActionType.TAP.name)),
                        actionDurationMs = point.optLong(KEY_ACTION_DURATION_MS, MacroPoint.DEFAULT_ACTION_DURATION_MS),
                        repeatCount = point.optInt(KEY_REPEAT_COUNT, 1),
                        endNormalizedX = normalized(
                            point.optDouble(KEY_END_X, (startX + 0.15f).coerceAtMost(0.95f).toDouble()),
                            (startX + 0.15f).coerceAtMost(0.95f)
                        ),
                        endNormalizedY = normalized(
                            point.optDouble(KEY_END_Y, (startY + 0.15f).coerceAtMost(0.95f).toDouble()),
                            (startY + 0.15f).coerceAtMost(0.95f)
                        )
                    )
                )
            }
        }.ifEmpty { listOf(defaultPoint()) }

        sanitizeProfile(
            MacroProfile(
                name = json.optString(KEY_NAME, DEFAULT_PROFILE_NAME),
                points = points,
                defaultDelayMs = json.optLong(KEY_DEFAULT_DELAY_MS, ClickPreferences.DEFAULT_DELAY_MS),
                loopDelayMs = json.optLong(KEY_LOOP_DELAY_MS, ClickPreferences.DEFAULT_DELAY_MS),
                loopLimit = json.optInt(KEY_LOOP_LIMIT, 0),
                countdownSeconds = json.optInt(KEY_COUNTDOWN, 3),
                keepScreenOn = json.optBoolean(KEY_KEEP_SCREEN_ON, false)
            )
        )
    }.getOrNull()

    private fun sanitizeProfile(profile: MacroProfile): MacroProfile {
        val sanitizedPoints = profile.points.ifEmpty { listOf(defaultPoint()) }.map { point ->
            point.copy(
                normalizedX = normalized(point.normalizedX.toDouble(), 0.5f),
                normalizedY = normalized(point.normalizedY.toDouble(), 0.3f),
                delayMs = ClickPreferences.sanitizeDelay(point.delayMs),
                actionDurationMs = ClickPreferences.quantizeToStep(
                    point.actionDurationMs,
                    if (point.actionType == PointActionType.LONG_PRESS) {
                        MacroPoint.MIN_LONG_PRESS_DURATION_MS
                    } else {
                        MacroPoint.MIN_ACTION_DURATION_MS
                    },
                    MacroPoint.MAX_ACTION_DURATION_MS
                ),
                repeatCount = point.repeatCount.coerceIn(MacroPoint.MIN_REPEAT_COUNT, MacroPoint.MAX_REPEAT_COUNT),
                endNormalizedX = normalized(point.endNormalizedX.toDouble(), 0.65f),
                endNormalizedY = normalized(point.endNormalizedY.toDouble(), 0.45f)
            )
        }
        return profile.copy(
            name = cleanName(profile.name),
            points = sanitizedPoints,
            defaultDelayMs = ClickPreferences.sanitizeDelay(profile.defaultDelayMs),
            loopDelayMs = ClickPreferences.sanitizeDelay(profile.loopDelayMs),
            loopLimit = profile.loopLimit.coerceIn(0, MacroProfile.MAX_LOOP_LIMIT),
            countdownSeconds = profile.countdownSeconds.coerceIn(0, MacroProfile.MAX_COUNTDOWN_SECONDS)
        )
    }

    private fun writeProfiles(profiles: List<MacroProfile>, activeName: String) {
        val sanitized = profiles.map(::sanitizeProfile)
        preferences.edit()
            .putString(KEY_PROFILES_JSON, buildRootJson(sanitized, activeName).toString())
            .putString(KEY_ACTIVE_PROFILE, activeName)
            .apply()
    }

    private fun buildRootJson(profiles: List<MacroProfile>, activeName: String) = JSONObject().apply {
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        put(KEY_ACTIVE_PROFILE, activeName)
        put(KEY_PROFILES, JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().apply {
                    put(KEY_NAME, profile.name)
                    put(KEY_DEFAULT_DELAY_MS, profile.defaultDelayMs)
                    put(KEY_LOOP_DELAY_MS, profile.loopDelayMs)
                    put(KEY_LOOP_LIMIT, profile.loopLimit)
                    put(KEY_COUNTDOWN, profile.countdownSeconds)
                    put(KEY_KEEP_SCREEN_ON, profile.keepScreenOn)
                    put(KEY_POINTS, JSONArray().apply {
                        profile.points.forEach { point ->
                            put(JSONObject().apply {
                                put(KEY_ID, point.id)
                                put(KEY_X, point.normalizedX.toDouble())
                                put(KEY_Y, point.normalizedY.toDouble())
                                put(KEY_DELAY_MS, point.delayMs)
                                put(KEY_ACTION_TYPE, point.actionType.name)
                                put(KEY_ACTION_DURATION_MS, point.actionDurationMs)
                                put(KEY_REPEAT_COUNT, point.repeatCount)
                                put(KEY_END_X, point.endNormalizedX.toDouble())
                                put(KEY_END_Y, point.endNormalizedY.toDouble())
                            })
                        }
                    })
                })
            }
        })
    }

    private fun createDefaultProfile(name: String) = MacroProfile(
        name = name,
        points = listOf(defaultPoint()),
        defaultDelayMs = legacyPreferences.defaultDelayMs,
        loopDelayMs = legacyPreferences.defaultDelayMs
    )

    private fun defaultPoint() = MacroPoint(
        id = System.nanoTime(),
        normalizedX = 0.5f,
        normalizedY = 0.3f,
        delayMs = legacyPreferences.defaultDelayMs,
        endNormalizedX = 0.65f,
        endNormalizedY = 0.45f
    )

    private fun parseAction(value: String): PointActionType =
        runCatching { PointActionType.valueOf(value) }.getOrDefault(PointActionType.TAP)

    private fun normalized(value: Double, fallback: Float): Float =
        if (value.isFinite()) value.toFloat().coerceIn(0f, 1f) else fallback

    private fun cleanName(value: String): String =
        value.trim().ifEmpty { DEFAULT_PROFILE_NAME }.take(MacroProfile.MAX_NAME_LENGTH)

    private fun uniqueName(baseValue: String, existing: Set<String>): String {
        val base = cleanName(baseValue)
        if (base !in existing) return base
        var suffix = 2
        while (true) {
            val suffixText = " ($suffix)"
            val candidate = base.take(MacroProfile.MAX_NAME_LENGTH - suffixText.length) + suffixText
            if (candidate !in existing) return candidate
            suffix++
        }
    }

    private sealed class ProfileReadResult {
        data object Missing : ProfileReadResult()
        data class Loaded(val profiles: List<MacroProfile>) : ProfileReadResult()
        data class Corrupt(val raw: String) : ProfileReadResult()
    }

    companion object {
        private const val PREFERENCES_NAME = "macro_profiles"
        private const val KEY_PROFILES_JSON = "profiles_json"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_CORRUPT_BACKUP = "profiles_json_corrupt_backup"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_NAME = "name"
        private const val KEY_POINTS = "points"
        private const val KEY_ID = "id"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_DELAY_MS = "delayMs"
        private const val KEY_ACTION_TYPE = "actionType"
        private const val KEY_ACTION_DURATION_MS = "actionDurationMs"
        private const val KEY_REPEAT_COUNT = "repeatCount"
        private const val KEY_END_X = "endX"
        private const val KEY_END_Y = "endY"
        private const val KEY_DEFAULT_DELAY_MS = "defaultDelayMs"
        private const val KEY_LOOP_DELAY_MS = "loopDelayMs"
        private const val KEY_LOOP_LIMIT = "loopLimit"
        private const val KEY_COUNTDOWN = "countdownSeconds"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val SCHEMA_VERSION = 2
        private const val DEFAULT_PROFILE_NAME = "Default"
        private const val RECOVERY_PROFILE_NAME = "Recovery"
    }
}
