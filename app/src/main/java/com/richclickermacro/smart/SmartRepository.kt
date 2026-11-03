package com.richclickermacro.smart

import android.content.Context
import com.richclickermacro.diagnostics.MacroDiagnostics
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage for Multi Smart Click, deliberately kept in its own SharedPreferences
 * file and its own JSON schema. The classic macro repository is never touched, so
 * a malformed smart profile cannot damage existing timed macros (and vice versa).
 */
class SmartRepository(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getProfiles(): List<SmartProfile> {
        val raw = preferences.getString(KEY_PROFILES_JSON, null)
        if (raw == null) {
            val initial = defaultProfile(SmartLimits.DEFAULT_PROFILE_NAME)
            writeProfiles(listOf(initial), initial.name)
            return listOf(initial)
        }
        return runCatching {
            val root = JSONObject(raw)
            require(root.optInt(KEY_SCHEMA, SCHEMA_VERSION) in MIN_READ_SCHEMA..SCHEMA_VERSION)
            val array = root.getJSONArray(KEY_PROFILES)
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    parseProfile(array.getJSONObject(index))?.let(::add)
                }
            }
            require(parsed.isNotEmpty() && parsed.size == array.length())
            parsed
        }.getOrElse {
            if (!preferences.contains(KEY_CORRUPT_BACKUP)) {
                preferences.edit().putString(KEY_CORRUPT_BACKUP, raw).apply()
            }
            listOf(defaultProfile(RECOVERY_NAME))
        }
    }

    fun hasRecoveryBackup(): Boolean = preferences.contains(KEY_CORRUPT_BACKUP)

    @Synchronized
    fun getActiveProfile(): SmartProfile {
        val profiles = getProfiles()
        val active = preferences.getString(KEY_ACTIVE, null)
        return profiles.firstOrNull { it.name == active } ?: profiles.first()
    }

    @Synchronized
    fun setActiveProfile(name: String) {
        if (getProfiles().any { it.name == name }) {
            preferences.edit().putString(KEY_ACTIVE, name).apply()
        }
    }

    @Synchronized
    fun createProfile(requestedName: String): SmartProfile {
        val profiles = getProfiles().toMutableList()
        val name = uniqueName(SmartLimits.cleanName(requestedName), profiles.map { it.name }.toSet())
        val profile = defaultProfile(name)
        profiles.add(profile)
        writeProfiles(profiles, profile.name)
        return profile
    }

    @Synchronized
    fun saveProfile(profile: SmartProfile, replacingName: String? = profile.name): SmartProfile {
        val profiles = getProfiles().toMutableList()
        val cleaned = SmartLimits.sanitizeProfile(profile)
        val index = profiles.indexOfFirst { it.name == replacingName }
        val avoid = profiles.mapIndexedNotNull { position, item ->
            item.name.takeUnless { position == index }
        }.toSet()
        val finalProfile = cleaned.copy(name = uniqueName(cleaned.name, avoid))
        if (index >= 0) profiles[index] = finalProfile else profiles.add(finalProfile)
        writeProfiles(profiles, finalProfile.name)
        return finalProfile
    }

    /**
     * Saves a whole profile edited in the overlay, INCLUDING profile-level settings
     * (speed, loop count, idle guard, timings).
     *
     * [updatePoints] deliberately only replaces the point list, so using it for overlay
     * edits silently reverted every profile-level change: the caller received the stored
     * profile back and overwrote what the user had just chosen. That is why picking a
     * speed looked like it only blinked.
     *
     * The active-profile pointer is preserved, unlike [saveProfile].
     */
    @Synchronized
    fun updateProfile(profile: SmartProfile): SmartProfile? {
        val profiles = getProfiles().toMutableList()
        if (profiles.isEmpty()) return null
        var index = profiles.indexOfFirst { it.name == profile.name }
        if (index < 0) {
            // The editor was opened for a profile that has since been renamed (or the
            // stored list was replaced by an import). Falling back to the active profile
            // keeps the user's edit instead of silently dropping every change, which is
            // exactly what made settings look like they "did not stick".
            val activeName = preferences.getString(KEY_ACTIVE, profiles.first().name) ?: profiles.first().name
            index = profiles.indexOfFirst { it.name == activeName }
            if (index < 0) index = 0
            MacroDiagnostics.record(appContext, "smart_profile_relinked", "name=${profile.name}")
        }
        val active = preferences.getString(KEY_ACTIVE, profiles.first().name) ?: profiles.first().name
        // Keep the stored name so an overlay edit can never rename or duplicate a profile.
        val merged = SmartLimits.sanitizeProfile(profile.copy(name = profiles[index].name))
        profiles[index] = merged
        writeProfiles(profiles, active)
        return merged
    }

    @Synchronized
    fun updatePoints(name: String, points: List<SmartPoint>): SmartProfile? {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.name == name }
        if (index < 0) return null
        val active = preferences.getString(KEY_ACTIVE, profiles.first().name) ?: profiles.first().name
        val merged = SmartLimits.sanitizeProfile(profiles[index].copy(points = points))
        profiles[index] = merged
        writeProfiles(profiles, active)
        return merged
    }

    @Synchronized
    fun deleteProfile(name: String): SmartProfile {
        val profiles = getProfiles().filterNot { it.name == name }.toMutableList()
        if (profiles.isEmpty()) profiles.add(defaultProfile(SmartLimits.DEFAULT_PROFILE_NAME))
        val active = profiles.first()
        writeProfiles(profiles, active.name)
        return active
    }

    @Synchronized
    fun exportJson(): String = buildRoot(getProfiles(), getActiveProfile().name).toString(2)

    @Synchronized
    fun importJson(json: String): Int {
        val root = JSONObject(json)
        require(root.optString(KEY_KIND) == KIND) { "File ini bukan profil Multi Smart Click" }
        require(root.optInt(KEY_SCHEMA) in MIN_READ_SCHEMA..SCHEMA_VERSION) { "Versi format smart tidak didukung" }
        val array = root.getJSONArray(KEY_PROFILES)
        val existing = getProfiles().toMutableList()
        val names = existing.map { it.name }.toMutableSet()
        var count = 0
        var activeName: String? = null
        val exportedActive = root.optString(KEY_ACTIVE, "")
        for (index in 0 until array.length()) {
            val parsed = parseProfile(array.getJSONObject(index)) ?: continue
            val renamed = parsed.copy(name = uniqueName(parsed.name, names))
            existing.add(renamed)
            names.add(renamed.name)
            if (parsed.name == exportedActive) activeName = renamed.name
            count++
        }
        require(count > 0) { "Tidak ada profil smart valid di dalam file" }
        writeProfiles(existing, activeName ?: existing.last().name)
        return count
    }

    private fun parseSpeed(json: JSONObject): SmartSpeedMode {
        val stored = json.optString(KEY_SPEED, "")
        SmartSpeedMode.values().firstOrNull { it.name == stored }?.let { return it }
        val confirm = json.optInt(KEY_CONFIRM, SmartProfile.DEFAULT_CONFIRM_SAMPLES)
        val cooldown = json.optLong(KEY_COOLDOWN, SmartProfile.DEFAULT_COOLDOWN_MS)
        val scan = json.optLong(KEY_SCAN, SmartProfile.DEFAULT_SCAN_INTERVAL_MS)
        return when {
            confirm == SmartProfile.FAST_CONFIRM && cooldown == SmartProfile.FAST_COOLDOWN_MS &&
                scan == SmartProfile.FAST_SCAN_MS -> SmartSpeedMode.FAST
            confirm == SmartProfile.RECOMMENDED_CONFIRM &&
                cooldown == SmartProfile.RECOMMENDED_COOLDOWN_MS &&
                scan == SmartProfile.RECOMMENDED_SCAN_MS -> SmartSpeedMode.RECOMMENDED
            else -> SmartSpeedMode.CUSTOM
        }
    }

    private fun parseProfile(json: JSONObject): SmartProfile? = runCatching {
        val pointsJson = json.optJSONArray(KEY_POINTS) ?: JSONArray()
        val usedIds = mutableSetOf<Long>()
        val points = buildList {
            for (index in 0 until pointsJson.length()) {
                if (size >= SmartProfile.MAX_POINTS) break
                val point = pointsJson.getJSONObject(index)
                // Two points sharing an id would collapse into ONE sampled patch:
                // the runner would decide point A from point B's colour and click the
                // wrong place. Older exports and hand-edited files can contain that.
                var pointId = point.optLong(KEY_ID, System.nanoTime() + index)
                while (!usedIds.add(pointId)) pointId++
                val uniqueId = pointId
                val watchX = SmartLimits.unit(point.optDouble(KEY_WATCH_X, 0.5).toFloat(), 0.5f)
                val watchY = SmartLimits.unit(point.optDouble(KEY_WATCH_Y, 0.5).toFloat(), 0.5f)
                add(
                    SmartLimits.sanitizePoint(
                        SmartPoint(
                            id = uniqueId,
                            label = point.optString(KEY_LABEL, "Titik ${index + 1}"),
                            watchX = watchX,
                            watchY = watchY,
                            targetColor = point.optInt(KEY_COLOR, SmartPoint.DEFAULT_TARGET_COLOR),
                            colorCaptured = point.optBoolean(KEY_COLOR_CAPTURED, false),
                            tolerance = point.optInt(KEY_TOLERANCE, SmartPoint.DEFAULT_TOLERANCE),
                            condition = runCatching {
                                SmartCondition.valueOf(point.optString(KEY_CONDITION, SmartCondition.COLOR_MATCH.name))
                            }.getOrDefault(SmartCondition.COLOR_MATCH),
                            action = runCatching {
                                SmartAction.valueOf(point.optString(KEY_ACTION, SmartAction.TAP.name))
                            }.getOrDefault(SmartAction.TAP),
                            holdMs = point.optLong(KEY_HOLD_MS, SmartPoint.DEFAULT_HOLD_MS),
                            enabled = point.optBoolean(KEY_ENABLED, true),
                            repeatCount = point.optInt(KEY_REPEAT, SmartPoint.DEFAULT_REPEAT_COUNT),
                            isGuard = point.optBoolean(KEY_IS_GUARD, false)
                        )
                    )
                )
            }
        }
        SmartLimits.sanitizeProfile(
            SmartProfile(
                name = json.optString(KEY_NAME, SmartLimits.DEFAULT_PROFILE_NAME),
                points = points,
                scanIntervalMs = json.optLong(KEY_SCAN, SmartProfile.DEFAULT_SCAN_INTERVAL_MS),
                cooldownMs = json.optLong(KEY_COOLDOWN, SmartProfile.DEFAULT_COOLDOWN_MS),
                confirmSamples = json.optInt(KEY_CONFIRM, SmartProfile.DEFAULT_CONFIRM_SAMPLES),
                energyMode = runCatching {
                    EnergyMode.valueOf(json.optString(KEY_ENERGY_MODE, EnergyMode.OFF.name))
                }.getOrDefault(EnergyMode.OFF),
                energyGuardId = if (json.has(KEY_ENERGY_GUARD)) json.optLong(KEY_ENERGY_GUARD) else null,
                recoverySteps = buildList {
                    val stepsJson = json.optJSONArray(KEY_RECOVERY_STEPS) ?: JSONArray()
                    for (index in 0 until stepsJson.length()) {
                        if (size >= SmartProfile.MAX_RECOVERY_STEPS) break
                        val step = stepsJson.optJSONObject(index) ?: continue
                        add(
                            SmartLimits.sanitizeRecoveryStep(
                                SmartRecoveryStep(
                                    x = SmartLimits.unit(step.optDouble(KEY_STEP_X, 0.5).toFloat(), 0.5f),
                                    y = SmartLimits.unit(step.optDouble(KEY_STEP_Y, 0.5).toFloat(), 0.5f),
                                    waitMs = step.optLong(KEY_STEP_WAIT, SmartRecoveryStep.DEFAULT_WAIT_MS)
                                )
                            )
                        )
                    }
                },
                recoveryAttempts = json.optInt(KEY_RECOVERY_ATTEMPTS, SmartProfile.DEFAULT_RECOVERY_ATTEMPTS),
                recoveryBuyLimit = json.optInt(KEY_RECOVERY_BUY_LIMIT, SmartProfile.DEFAULT_RECOVERY_BUY_LIMIT),
                recoveryRetryDelayMs = json.optLong(KEY_RECOVERY_RETRY, SmartProfile.DEFAULT_RECOVERY_RETRY_DELAY_MS),
                idleTimeoutSec = json.optInt(KEY_IDLE, SmartProfile.DEFAULT_IDLE_TIMEOUT_SEC),
                maxActions = json.optInt(KEY_MAX_ACTIONS, 0),
                countdownSeconds = json.optInt(KEY_COUNTDOWN, 3),
                keepScreenOn = json.optBoolean(KEY_KEEP_SCREEN_ON, false),
                stopOnAppChange = json.optBoolean(KEY_STOP_ON_APP_CHANGE, true),
                orderMode = runCatching {
                    SmartOrderMode.valueOf(json.optString(KEY_ORDER, SmartOrderMode.SEQUENCE.name))
                }.getOrDefault(SmartOrderMode.SEQUENCE),
                // Older files have no speedMode. Derive it from the stored numbers so
                // the label describes what the profile actually does instead of guessing.
                speedMode = parseSpeed(json),
                loopLimit = json.optInt(KEY_LOOP, 0)
            )
        )
    }.getOrNull()

    private fun writeProfiles(profiles: List<SmartProfile>, activeName: String) {
        val sanitized = profiles.map(SmartLimits::sanitizeProfile)
        preferences.edit()
            .putString(KEY_PROFILES_JSON, buildRoot(sanitized, activeName).toString())
            .putString(KEY_ACTIVE, activeName)
            .apply()
    }

    private fun buildRoot(profiles: List<SmartProfile>, activeName: String) = JSONObject().apply {
        put(KEY_KIND, KIND)
        put(KEY_SCHEMA, SCHEMA_VERSION)
        put(KEY_ACTIVE, activeName)
        put(KEY_PROFILES, JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().apply {
                    put(KEY_NAME, profile.name)
                    put(KEY_SCAN, profile.scanIntervalMs)
                    put(KEY_COOLDOWN, profile.cooldownMs)
                    put(KEY_CONFIRM, profile.confirmSamples)
                    put(KEY_IDLE, profile.idleTimeoutSec)
                    put(KEY_MAX_ACTIONS, profile.maxActions)
                    put(KEY_COUNTDOWN, profile.countdownSeconds)
                    put(KEY_KEEP_SCREEN_ON, profile.keepScreenOn)
                    put(KEY_STOP_ON_APP_CHANGE, profile.stopOnAppChange)
                    put(KEY_ORDER, profile.orderMode.name)
                    put(KEY_SPEED, profile.speedMode.name)
                    put(KEY_LOOP, profile.loopLimit)
                    put(KEY_ENERGY_MODE, profile.energyMode.name)
                    profile.energyGuardId?.let { put(KEY_ENERGY_GUARD, it) }
                    put(KEY_RECOVERY_ATTEMPTS, profile.recoveryAttempts)
                    put(KEY_RECOVERY_BUY_LIMIT, profile.recoveryBuyLimit)
                    put(KEY_RECOVERY_RETRY, profile.recoveryRetryDelayMs)
                    put(KEY_RECOVERY_STEPS, JSONArray().apply {
                        profile.recoverySteps.forEach { step ->
                            put(JSONObject().apply {
                                put(KEY_STEP_X, step.x.toDouble())
                                put(KEY_STEP_Y, step.y.toDouble())
                                put(KEY_STEP_WAIT, step.waitMs)
                            })
                        }
                    })
                    put(KEY_POINTS, JSONArray().apply {
                        profile.points.forEach { point ->
                            put(JSONObject().apply {
                                put(KEY_ID, point.id)
                                put(KEY_LABEL, point.label)
                                put(KEY_WATCH_X, point.watchX.toDouble())
                                put(KEY_WATCH_Y, point.watchY.toDouble())
                                put(KEY_COLOR, point.targetColor)
                                put(KEY_COLOR_CAPTURED, point.colorCaptured)
                                put(KEY_TOLERANCE, point.tolerance)
                                put(KEY_CONDITION, point.condition.name)
                                put(KEY_ACTION, point.action.name)
                                put(KEY_HOLD_MS, point.holdMs)
                                put(KEY_ENABLED, point.enabled)
                                put(KEY_REPEAT, point.repeatCount)
                                if (point.isGuard) put(KEY_IS_GUARD, true)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun defaultProfile(name: String) = SmartProfile(name = name, points = emptyList())

    private fun uniqueName(baseValue: String, existing: Set<String>): String {
        val base = SmartLimits.cleanName(baseValue)
        if (base !in existing) return base
        var suffix = 2
        while (true) {
            val suffixText = " ($suffix)"
            val candidate = base.take(SmartProfile.MAX_NAME_LENGTH - suffixText.length) + suffixText
            if (candidate !in existing) return candidate
            suffix++
        }
    }

    companion object {
        const val KIND = "rich-clicker-smart"
        const val SCHEMA_VERSION = 4

        /** Schema 1 (v0.8.0) and 2 (v0.8.1-0.9.2) files stay importable. */
        const val MIN_READ_SCHEMA = 1
        const val EXPORT_FILE_PREFIX = "rich-clicker-smart-"

        private const val PREFERENCES_NAME = "smart_profiles"
        private const val KEY_PROFILES_JSON = "smart_profiles_json"
        private const val KEY_ACTIVE = "active_smart_profile"
        private const val KEY_CORRUPT_BACKUP = "smart_profiles_corrupt_backup"
        private const val KEY_KIND = "kind"
        private const val KEY_SCHEMA = "schemaVersion"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_NAME = "name"
        private const val KEY_POINTS = "points"
        private const val KEY_ID = "id"
        private const val KEY_LABEL = "label"
        private const val KEY_WATCH_X = "watchX"
        private const val KEY_WATCH_Y = "watchY"
        // Schema 1/2 files may still contain separateTap/tapX/tapY. They are accepted
        // and ignored so old exports keep importing; the point simply clicks where it watches.
        private const val KEY_COLOR = "targetColor"
        private const val KEY_COLOR_CAPTURED = "colorCaptured"
        private const val KEY_TOLERANCE = "tolerance"
        private const val KEY_CONDITION = "condition"
        private const val KEY_ACTION = "action"
        private const val KEY_HOLD_MS = "holdMs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REPEAT = "repeatCount"
        private const val KEY_ORDER = "orderMode"
        private const val KEY_SPEED = "speedMode"
        private const val KEY_ENERGY_MODE = "energyMode"
        private const val KEY_ENERGY_GUARD = "energyGuardId"
        private const val KEY_RECOVERY_STEPS = "recoverySteps"
        private const val KEY_RECOVERY_ATTEMPTS = "recoveryAttempts"
        private const val KEY_RECOVERY_BUY_LIMIT = "recoveryBuyLimit"
        private const val KEY_RECOVERY_RETRY = "recoveryRetryDelayMs"
        private const val KEY_STEP_X = "x"
        private const val KEY_STEP_Y = "y"
        private const val KEY_STEP_WAIT = "waitMs"
        private const val KEY_IS_GUARD = "isGuard"
        private const val KEY_LOOP = "loopLimit"
        private const val KEY_SCAN = "scanIntervalMs"
        private const val KEY_COOLDOWN = "cooldownMs"
        private const val KEY_CONFIRM = "confirmSamples"
        private const val KEY_IDLE = "idleTimeoutSec"
        private const val KEY_MAX_ACTIONS = "maxActions"
        private const val KEY_COUNTDOWN = "countdownSeconds"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_STOP_ON_APP_CHANGE = "stopOnAppChange"
        private const val RECOVERY_NAME = "Smart Recovery"
    }
}
