package com.richclickermacro.smart

/**
 * Pure decision engine for Multi Smart Click.
 *
 * It owns no threads, no handlers and no Android objects: the caller feeds it a
 * scan result and gets back a decision. That makes the whole "when do we click"
 * rule set testable without a device, and keeps the overlay controller small.
 *
 * Clicks are never scheduled by a per-point delay. A point fires only after its
 * colour condition has been satisfied for [SmartProfile.confirmSamples]
 * consecutive scans, and never again before its cooldown expires.
 *
 * Two behaviours mirror the classic macro without reintroducing per-click timing:
 *  - [SmartPoint.repeatCount] turns one satisfied condition into a burst of clicks.
 *    The condition is checked once; the remaining repeats are drained back-to-back
 *    because the first click normally changes the screen being watched.
 *  - [SmartOrderMode.SEQUENCE] walks points 1 -> last instead of firing whichever
 *    matches, and counts completed passes so [SmartProfile.loopLimit] can end the run.
 */
class SmartEngine(profile: SmartProfile) {

    sealed class Decision {
        /** Nothing to do; keep scanning. */
        data object Wait : Decision()

        /** Dispatch this point now. */
        data class Fire(
            val point: SmartPoint,
            val sampleColor: Int,
            val distance: Int,
            val repeatIndex: Int = 1,
            val repeatTotal: Int = 1
        ) : Decision()

        /** Stop the run; the reason is safe to show and to log. */
        data class Stop(val reason: String, val message: String) : Decision()
    }

    private val sanitized = SmartLimits.sanitizeProfile(profile)
    private val states = LinkedHashMap<Long, PointState>()
    private var rotation = 0
    private var startedAtMs = 0L
    private var lastActionAtMs = 0L
    /**
     * One shared gate for the WHOLE run, not one per point.
     *
     * The speed setting is a profile-level (global) choice, so the gap between clicks
     * must also be global. Previously each point had its own cooldown, so with 3 points
     * the run clicked roughly 3x faster than the selected speed and changing the speed
     * did not feel like it applied to everything.
     */
    private var nextActionAtMs = 0L
    private var totalActions = 0
    private var started = false

    // Burst state: one condition match, several clicks.
    private var burstPointId: Long? = null
    private var burstRemaining = 0
    private var burstTotal = 0
    private var burstColor = 0
    private var burstDistance = 0

    // Ordered-run state.
    private var sequenceIndex = 0
    private var completedLoops = 0

    val actionCount: Int get() = totalActions
    val loopCount: Int get() = completedLoops

    private class PointState {
        var baseline: Int? = null
        var consecutive: Int = 0
        var lastFireAtMs: Long = Long.MIN_VALUE
        var readyAtMs: Long = 0L
        var lastColor: Int? = null
    }

    fun start(nowMs: Long) {
        started = true
        startedAtMs = nowMs
        lastActionAtMs = nowMs
        nextActionAtMs = nowMs
        totalActions = 0
        rotation = 0
        sequenceIndex = 0
        completedLoops = 0
        clearBurst()
        states.clear()
        sanitized.points.filter { it.enabled }.forEach { point ->
            states[point.id] = PointState().apply { readyAtMs = nowMs }
        }
    }

    fun enabledPoints(): List<SmartPoint> = sanitized.points.filter { it.enabled }

    fun lastObservedColor(pointId: Long): Int? = states[pointId]?.lastColor

    /** True while a repeat burst still owes clicks; the caller should not re-scan. */
    fun hasPendingBurst(): Boolean = burstPointId != null && burstRemaining > 0

    /** The point whose burst is in progress, so the caller can reuse its coordinates. */
    fun pendingBurstPoint(): SmartPoint? {
        val id = burstPointId ?: return null
        return sanitized.points.firstOrNull { it.id == id }
    }

    /** 1-based position in the ordered run; 0 when order mode is ANY. */
    fun sequencePosition(): Int =
        if (sanitized.orderMode == SmartOrderMode.SEQUENCE) sequenceIndex + 1 else 0

    /**
     * Re-arms a COLOR_CHANGE point after the caller has changed the screen (for
     * example right after a click), so the next difference is measured against the
     * new state rather than the colour from before the click.
     */
    fun resetBaseline(pointId: Long) {
        states[pointId]?.baseline = null
        states[pointId]?.consecutive = 0
    }

    /**
     * @param samples average colour per point id; a missing key or null value means
     * the sample failed and must never be interpreted as a colour.
     */
    fun evaluate(nowMs: Long, samples: Map<Long, Int?>): Decision {
        if (!started) return Decision.Stop("engine_not_started", "Mesin smart belum dimulai.")
        val active = enabledPoints()
        if (active.isEmpty()) {
            return Decision.Stop("no_enabled_point", "Tidak ada titik smart yang aktif.")
        }

        // A burst in progress owns the runner: finish it before reading conditions again.
        if (hasPendingBurst()) return continueBurst(nowMs)

        preFlightStop(nowMs)?.let { return it }

        // Update every point first so a point that is cooling down still tracks the
        // screen; otherwise it would need to re-observe N samples after cooldown.
        for (point in active) {
            val state = states.getOrPut(point.id) { PointState().apply { readyAtMs = nowMs } }
            val sample = samples[point.id]
            if (sample == null) {
                state.consecutive = 0
                continue
            }
            state.lastColor = sample
            val satisfied = when (point.condition) {
                SmartCondition.COLOR_MATCH -> ColorMatcher.isMatch(sample, point.targetColor, point.tolerance)
                SmartCondition.COLOR_CHANGE -> {
                    val baseline = state.baseline
                    if (baseline == null) {
                        // First valid sample arms the reference colour.
                        state.baseline = sample
                        false
                    } else {
                        ColorMatcher.isChanged(sample, baseline, point.tolerance)
                    }
                }
            }
            state.consecutive = if (satisfied) (state.consecutive + 1).coerceAtMost(Int.MAX_VALUE - 1) else 0
        }

        // Global speed gate: no point may fire before the shared gap has elapsed.
        if (nowMs < nextActionAtMs) return Decision.Wait

        for (point in candidates(active)) {
            val state = states[point.id] ?: continue
            if (state.consecutive < sanitized.confirmSamples) continue
            if (nowMs < state.readyAtMs) continue
            val sample = samples[point.id] ?: continue
            val reference = if (point.condition == SmartCondition.COLOR_MATCH) {
                point.targetColor
            } else {
                state.baseline ?: continue
            }
            val distance = ColorMatcher.distance(sample, reference)
            state.consecutive = 0
            state.lastFireAtMs = nowMs
            state.readyAtMs = nowMs + sanitized.cooldownMs
            nextActionAtMs = nowMs + sanitized.cooldownMs
            if (point.condition == SmartCondition.COLOR_CHANGE) state.baseline = null
            lastActionAtMs = nowMs
            totalActions++
            rotation = (active.indexOf(point) + 1) % active.size

            // Arm the burst; repeatCount == 1 finishes immediately.
            burstPointId = point.id
            burstTotal = point.repeatCount
            burstRemaining = point.repeatCount - 1
            burstColor = sample
            burstDistance = distance
            val total = burstTotal
            if (!hasPendingBurst()) finishBurst(active)
            return Decision.Fire(point, sample, distance, 1, total)
        }
        return Decision.Wait
    }

    /**
     * Drains the next click of an in-progress burst. The caller invokes this after
     * the previous gesture completed, without taking another screenshot.
     */
    fun nextRepeat(nowMs: Long): Decision {
        if (!hasPendingBurst()) return Decision.Wait
        return continueBurst(nowMs)
    }

    private fun continueBurst(nowMs: Long): Decision {
        val point = pendingBurstPoint() ?: run {
            clearBurst()
            return Decision.Wait
        }
        preFlightStop(nowMs)?.let {
            clearBurst()
            return it
        }
        val total = burstTotal
        val index = total - burstRemaining + 1
        burstRemaining--
        totalActions++
        lastActionAtMs = nowMs
        if (!hasPendingBurst()) {
            nextActionAtMs = nowMs + sanitized.cooldownMs
            finishBurst(enabledPoints())
        }
        return Decision.Fire(point, burstColor, burstDistance, index, total)
    }

    /** Limits that must also interrupt a burst, not just the scan loop. */
    private fun preFlightStop(nowMs: Long): Decision.Stop? {
        val limit = sanitized.maxActions
        if (limit > 0 && totalActions >= limit) {
            return Decision.Stop("max_actions", "Selesai: $totalActions aksi terkirim.")
        }
        val loopLimit = sanitized.loopLimit
        if (sanitized.orderMode == SmartOrderMode.SEQUENCE && loopLimit > 0 && completedLoops >= loopLimit) {
            return Decision.Stop("loop_limit", "Selesai: $completedLoops putaran penuh.")
        }
        val idleLimitMs = sanitized.idleTimeoutSec * 1_000L
        if (idleLimitMs > 0 && nowMs - lastActionAtMs >= idleLimitMs) {
            return Decision.Stop(
                "idle_timeout",
                "Tidak ada kondisi yang cocok selama ${sanitized.idleTimeoutSec} detik. Smart click dihentikan."
            )
        }
        return null
    }

    /**
     * ANY: round-robin so a permanently-matching point cannot starve the others.
     * SEQUENCE: only the current step may fire, which is what makes 1 -> last ordering real.
     */
    private fun candidates(active: List<SmartPoint>): List<SmartPoint> {
        if (sanitized.orderMode == SmartOrderMode.SEQUENCE) {
            if (sequenceIndex >= active.size) sequenceIndex = 0
            return listOf(active[sequenceIndex])
        }
        if (active.size <= 1) return active
        val offset = rotation % active.size
        return active.drop(offset) + active.take(offset)
    }

    /** Called once the last click of a burst has been handed out. */
    private fun finishBurst(active: List<SmartPoint>) {
        if (sanitized.orderMode == SmartOrderMode.SEQUENCE && active.isNotEmpty()) {
            sequenceIndex++
            if (sequenceIndex >= active.size) {
                sequenceIndex = 0
                completedLoops++
            }
        }
        clearBurst()
    }

    private fun clearBurst() {
        burstPointId = null
        burstRemaining = 0
        burstTotal = 0
    }

    /** Called when the gesture failed so the point may retry after its cooldown. */
    fun onActionFailed(pointId: Long, nowMs: Long) {
        // A failed click cancels the rest of its burst; the ordered step is not
        // advanced, so the same point is retried instead of silently skipping it.
        if (burstPointId == pointId) clearBurst()
        nextActionAtMs = nowMs + sanitized.cooldownMs
        val state = states[pointId] ?: return
        state.consecutive = 0
        state.readyAtMs = nowMs + sanitized.cooldownMs
    }

    fun statusText(): String = when {
        !started -> "Siap"
        sanitized.orderMode == SmartOrderMode.SEQUENCE ->
            "Aksi: $totalActions • Putaran: $completedLoops"
        else -> "Aksi: $totalActions"
    }

    fun profile(): SmartProfile = sanitized
}
