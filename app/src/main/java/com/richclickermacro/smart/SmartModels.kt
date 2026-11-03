package com.richclickermacro.smart

/**
 * Multi Smart Click is intentionally a separate feature from the classic timed macro.
 * It has its own models, storage, overlay and engine so a defect here cannot change
 * the behaviour of the existing timed macro.
 *
 * There is no per-click delay: an action is dispatched when a watched screen region
 * satisfies its colour condition, not when a timer expires.
 */
enum class SmartCondition {
    /** Fire when the watched region is close to the stored target colour. */
    COLOR_MATCH,

    /** Fire when the watched region moves away from the colour captured at arming time. */
    COLOR_CHANGE
}

enum class SmartAction {
    TAP,
    LONG_PRESS
}

/**
 * How the runner picks the next point.
 *
 * [ANY] keeps the original behaviour: whichever watched point satisfies its
 * condition may fire, round-robin so none starves.
 * [SEQUENCE] walks the points in list order (1 -> 2 -> ... -> last); reaching the
 * last point completes one loop. This mirrors the classic macro's ordered run,
 * except each step still waits for its screen condition instead of a delay.
 */
/**
 * Click speed. RECOMMENDED is the safe default, FAST removes the double-check,
 * CUSTOM lets the user type the three timing numbers directly.
 */
enum class SmartSpeedMode {
    RECOMMENDED,
    FAST,
    CUSTOM
}

enum class SmartOrderMode {
    ANY,
    SEQUENCE
}

/**
 * What to do when the watched energy area reports "empty".
 *
 * [OFF] keeps the old behaviour: the run simply keeps waiting for its normal conditions.
 * [WAIT] pauses the ordered run and waits for the game to refill on its own.
 * [BUY] runs the user's recovery steps (open shop, tap buy, close shop) and then checks
 * the guard area again. Buying is capped twice: per consecutive failure and per session.
 */
enum class EnergyMode {
    OFF,
    WAIT,
    BUY
}

/** One tap of a recovery sequence, followed by a fixed wait. Taps are time-based. */
data class SmartRecoveryStep(
    val x: Float,
    val y: Float,
    val waitMs: Long = DEFAULT_WAIT_MS
) {
    companion object {
        const val MIN_WAIT_MS = 100L
        const val MAX_WAIT_MS = 30_000L
        const val DEFAULT_WAIT_MS = 1_500L
    }
}

data class SmartPoint(
    val id: Long,
    val label: String,
    val watchX: Float,
    val watchY: Float,
    val targetColor: Int = DEFAULT_TARGET_COLOR,
    val colorCaptured: Boolean = false,
    val tolerance: Int = DEFAULT_TOLERANCE,
    val condition: SmartCondition = SmartCondition.COLOR_MATCH,
    val action: SmartAction = SmartAction.TAP,
    val holdMs: Long = DEFAULT_HOLD_MS,
    val enabled: Boolean = true,
    /**
     * How many clicks one satisfied condition produces. The condition is checked
     * once, then the burst is sent back-to-back; it is not re-checked between
     * repeats because the first click usually changes the screen.
     */
    val repeatCount: Int = DEFAULT_REPEAT_COUNT,
    /**
     * A guard point is watched but never clicked, and it is not part of the 1 -> last
     * order. It exists so the run can tell "energy is empty" apart from "the button is
     * simply not ready yet".
     */
    val isGuard: Boolean = false
) {
    /**
     * A point always clicks where it watches. The old "watch here, tap there" option
     * was removed in 0.9.2: it was the single biggest source of "it clicked the wrong
     * thing", and almost nobody needed it.
     */
    fun effectiveTapX(): Float = watchX
    fun effectiveTapY(): Float = watchY

    companion object {
        const val MIN_TOLERANCE = 1
        const val MAX_TOLERANCE = 120
        const val DEFAULT_TOLERANCE = 24
        const val MIN_HOLD_MS = 600L
        const val MAX_HOLD_MS = 10_000L
        const val DEFAULT_HOLD_MS = 700L
        const val MAX_LABEL_LENGTH = 24
        const val MIN_REPEAT_COUNT = 1
        const val MAX_REPEAT_COUNT = 1_000
        const val DEFAULT_REPEAT_COUNT = 1

        /** Opaque white so alpha never takes part in a comparison. */
        const val DEFAULT_TARGET_COLOR = -0x1
    }
}

data class SmartProfile(
    val name: String,
    val points: List<SmartPoint>,
    val scanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val confirmSamples: Int = DEFAULT_CONFIRM_SAMPLES,
    val idleTimeoutSec: Int = DEFAULT_IDLE_TIMEOUT_SEC,
    val maxActions: Int = 0,
    val countdownSeconds: Int = 3,
    val keepScreenOn: Boolean = false,
    val stopOnAppChange: Boolean = true,
    val orderMode: SmartOrderMode = SmartOrderMode.SEQUENCE,
    /** Completed 1 -> last cycles before stopping. 0 means unlimited. */
    val loopLimit: Int = 0,
    val speedMode: SmartSpeedMode = SmartSpeedMode.RECOMMENDED,
    /** Off by default: existing profiles behave exactly as before. */
    val energyMode: EnergyMode = EnergyMode.OFF,
    /** Id of the point used as the energy guard, or null when none is marked. */
    val energyGuardId: Long? = null,
    val recoverySteps: List<SmartRecoveryStep> = emptyList(),
    /** Consecutive failed recoveries before the run stops. */
    val recoveryAttempts: Int = DEFAULT_RECOVERY_ATTEMPTS,
    /** Hard ceiling on purchases per run, whatever else happens. */
    val recoveryBuyLimit: Int = DEFAULT_RECOVERY_BUY_LIMIT,
    /** Pause before trying again after a failed recovery. */
    val recoveryRetryDelayMs: Long = DEFAULT_RECOVERY_RETRY_DELAY_MS
) {
    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_POINTS = 10

        /**
         * AccessibilityService rejects screenshots requested less than 333 ms apart.
         * 400 ms keeps a margin so a scan is never rejected purely for pacing.
         */
        const val MIN_SCAN_INTERVAL_MS = 400L
        const val MAX_SCAN_INTERVAL_MS = 3_000L
        const val DEFAULT_SCAN_INTERVAL_MS = 400L

        /**
         * A satisfied condition should be able to fire again on the very next scan.
         * 300 ms is below the scan floor, so cooldown no longer adds extra waiting.
         */
        const val MIN_COOLDOWN_MS = 300L
        const val MAX_COOLDOWN_MS = 60_000L
        const val DEFAULT_COOLDOWN_MS = 600L

        const val MIN_CONFIRM_SAMPLES = 1
        const val MAX_CONFIRM_SAMPLES = 5
        const val DEFAULT_CONFIRM_SAMPLES = 2

        /** 0 disables the idle guard. */
        const val MAX_IDLE_TIMEOUT_SEC = 3_600
        const val DEFAULT_IDLE_TIMEOUT_SEC = 120

        const val MAX_ACTIONS_LIMIT = 1_000_000

        /** Recovery safety limits. Small numbers on purpose: failing loudly beats looping. */
        const val MIN_RECOVERY_ATTEMPTS = 1
        const val MAX_RECOVERY_ATTEMPTS = 10
        const val DEFAULT_RECOVERY_ATTEMPTS = 3
        const val MIN_RECOVERY_BUY_LIMIT = 1
        const val MAX_RECOVERY_BUY_LIMIT = 100
        const val DEFAULT_RECOVERY_BUY_LIMIT = 10
        const val MIN_RECOVERY_RETRY_DELAY_MS = 500L
        const val MAX_RECOVERY_RETRY_DELAY_MS = 60_000L
        const val DEFAULT_RECOVERY_RETRY_DELAY_MS = 3_000L
        const val MAX_RECOVERY_STEPS = 6
        const val MAX_COUNTDOWN_SECONDS = 10
        const val MAX_LOOP_LIMIT = 1_000_000

        // Recommended: two agreeing reads before firing, short cooldown.
        const val RECOMMENDED_SCAN_MS = 400L
        const val RECOMMENDED_COOLDOWN_MS = 600L
        const val RECOMMENDED_CONFIRM = 2

        // Fast: fire on the first matching read, re-arm on the next scan.
        const val FAST_SCAN_MS = MIN_SCAN_INTERVAL_MS
        const val FAST_COOLDOWN_MS = MIN_COOLDOWN_MS
        const val FAST_CONFIRM = 1
    }
}

/** Pure clamping helpers, shared by the repository, the UI and the engine. */
object SmartLimits {
    fun sanitizePoint(point: SmartPoint): SmartPoint = point.copy(
        label = cleanLabel(point.label),
        watchX = unit(point.watchX, 0.5f),
        watchY = unit(point.watchY, 0.5f),
        targetColor = point.targetColor or -0x1000000,
        tolerance = point.tolerance.coerceIn(SmartPoint.MIN_TOLERANCE, SmartPoint.MAX_TOLERANCE),
        holdMs = point.holdMs.coerceIn(SmartPoint.MIN_HOLD_MS, SmartPoint.MAX_HOLD_MS),
        repeatCount = point.repeatCount.coerceIn(SmartPoint.MIN_REPEAT_COUNT, SmartPoint.MAX_REPEAT_COUNT)
    )

    fun sanitizeProfile(profile: SmartProfile): SmartProfile = profile.copy(
        name = cleanName(profile.name),
        points = profile.points.take(SmartProfile.MAX_POINTS).map(::sanitizePoint),
        scanIntervalMs = profile.scanIntervalMs.coerceIn(
            SmartProfile.MIN_SCAN_INTERVAL_MS, SmartProfile.MAX_SCAN_INTERVAL_MS
        ),
        cooldownMs = profile.cooldownMs.coerceIn(
            SmartProfile.MIN_COOLDOWN_MS, SmartProfile.MAX_COOLDOWN_MS
        ),
        confirmSamples = profile.confirmSamples.coerceIn(
            SmartProfile.MIN_CONFIRM_SAMPLES, SmartProfile.MAX_CONFIRM_SAMPLES
        ),
        idleTimeoutSec = profile.idleTimeoutSec.coerceIn(0, SmartProfile.MAX_IDLE_TIMEOUT_SEC),
        maxActions = profile.maxActions.coerceIn(0, SmartProfile.MAX_ACTIONS_LIMIT),
        countdownSeconds = profile.countdownSeconds.coerceIn(0, SmartProfile.MAX_COUNTDOWN_SECONDS),
        loopLimit = profile.loopLimit.coerceIn(0, SmartProfile.MAX_LOOP_LIMIT),
        // v0.9.3: the run always walks point 1 -> last, then repeats. "Bebas" was
        // removed because the loop counter only means something in order.
        orderMode = SmartOrderMode.SEQUENCE,
        recoverySteps = profile.recoverySteps
            .take(SmartProfile.MAX_RECOVERY_STEPS)
            .map(::sanitizeRecoveryStep),
        recoveryAttempts = profile.recoveryAttempts.coerceIn(
            SmartProfile.MIN_RECOVERY_ATTEMPTS, SmartProfile.MAX_RECOVERY_ATTEMPTS
        ),
        recoveryBuyLimit = profile.recoveryBuyLimit.coerceIn(
            SmartProfile.MIN_RECOVERY_BUY_LIMIT, SmartProfile.MAX_RECOVERY_BUY_LIMIT
        ),
        recoveryRetryDelayMs = profile.recoveryRetryDelayMs.coerceIn(
            SmartProfile.MIN_RECOVERY_RETRY_DELAY_MS, SmartProfile.MAX_RECOVERY_RETRY_DELAY_MS
        ),
        // A guard that no longer exists must not leave the run stuck in recovery.
        energyGuardId = profile.energyGuardId?.takeIf { id ->
            profile.points.take(SmartProfile.MAX_POINTS).any { it.id == id }
        },
        energyMode = profile.energyMode
    )

    fun sanitizeRecoveryStep(step: SmartRecoveryStep): SmartRecoveryStep = step.copy(
        x = unit(step.x, 0.5f),
        y = unit(step.y, 0.5f),
        waitMs = step.waitMs.coerceIn(
            SmartRecoveryStep.MIN_WAIT_MS, SmartRecoveryStep.MAX_WAIT_MS
        )
    )

    /** Applies a speed preset. CUSTOM keeps whatever numbers are already stored. */
    fun applySpeed(profile: SmartProfile, mode: SmartSpeedMode): SmartProfile = when (mode) {
        SmartSpeedMode.RECOMMENDED -> sanitizeProfile(
            profile.copy(
                speedMode = mode,
                scanIntervalMs = SmartProfile.RECOMMENDED_SCAN_MS,
                cooldownMs = SmartProfile.RECOMMENDED_COOLDOWN_MS,
                confirmSamples = SmartProfile.RECOMMENDED_CONFIRM
            )
        )
        SmartSpeedMode.FAST -> sanitizeProfile(
            profile.copy(
                speedMode = mode,
                scanIntervalMs = SmartProfile.FAST_SCAN_MS,
                cooldownMs = SmartProfile.FAST_COOLDOWN_MS,
                confirmSamples = SmartProfile.FAST_CONFIRM
            )
        )
        SmartSpeedMode.CUSTOM -> sanitizeProfile(profile.copy(speedMode = mode))
    }

    fun cleanName(value: String): String =
        value.trim().ifEmpty { DEFAULT_PROFILE_NAME }.take(SmartProfile.MAX_NAME_LENGTH)

    fun cleanLabel(value: String): String =
        value.trim().take(SmartPoint.MAX_LABEL_LENGTH)

    fun unit(value: Float, fallback: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else fallback

    const val DEFAULT_PROFILE_NAME = "Smart 1"
}
