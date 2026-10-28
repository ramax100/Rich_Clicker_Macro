package com.example.autoclicker.overlay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.autoclicker.accessibility.AutoClickAccessibilityService
import com.example.autoclicker.data.ClickPreferences
import com.example.autoclicker.data.MacroPoint
import com.example.autoclicker.data.MacroProfile
import com.example.autoclicker.data.PointActionType
import com.example.autoclicker.diagnostics.DiagnosticLog
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class ClickOverlayController(
    private val service: AutoClickAccessibilityService,
    private val onProfileChanged: (MacroProfile) -> MacroProfile?
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val targets = mutableListOf<TargetHolder>()

    private var currentProfile: MacroProfile? = null
    private var controlsView: LinearLayout? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var editorContent: LinearLayout? = null
    private var toolbarHandle: TextView? = null
    private var delayDialog: AlertDialog? = null
    private var selectedIndex = 0
    private var currentIndex = 0
    private var completedLoops = 0
    private var currentRepeatIndex = 0
    private var runGeneration = 0L
    private var runState = RunState.IDLE
    private var isToolbarCollapsed = false
    private var markersHidden = false

    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var addButton: Button
    private lateinit var removeButton: Button
    private lateinit var moveUpButton: Button
    private lateinit var moveDownButton: Button
    private lateinit var decreaseDelayButton: Button
    private lateinit var increaseDelayButton: Button
    private lateinit var actionButton: Button
    private lateinit var durationButton: Button
    private lateinit var repeatButton: Button
    private lateinit var markerVisibilityButton: Button
    private lateinit var delayLabel: TextView

    fun show(profile: MacroProfile): Boolean {
        DiagnosticLog.record(
            service,
            "editor_show_started",
            mapOf("pointCount" to profile.points.size)
        )
        dismissDelayDialog()
        stopClicking("editor_replaced")
        if (!removeAllTargets()) {
            DiagnosticLog.record(service, "editor_show_failed", mapOf("reason" to "old_overlay_cleanup"))
            Toast.makeText(service, "Editor lama belum dapat dibersihkan", Toast.LENGTH_SHORT).show()
            return false
        }

        currentProfile = profile
        var allTargetsAdded = true
        for (point in profile.points) {
            if (!addTarget(point, persist = false)) {
                allTargetsAdded = false
                break
            }
        }
        if (!allTargetsAdded || (targets.isEmpty() && !addTarget(createPoint(profile.defaultDelayMs), persist = false))) {
            removeAllTargets()
            currentProfile = null
            DiagnosticLog.record(service, "editor_show_failed", mapOf("reason" to "target_setup"))
            Toast.makeText(service, "Profil tidak dapat ditampilkan sepenuhnya", Toast.LENGTH_SHORT).show()
            return false
        }
        if (controlsView == null && !addControls()) {
            removeAllTargets()
            currentProfile = null
            DiagnosticLog.record(service, "editor_show_failed", mapOf("reason" to "toolbar_setup"))
            return false
        }
        selectedIndex = 0
        selectTarget(0)
        updateControls()
        service.notifyTileStateChanged()
        DiagnosticLog.record(service, "editor_show_succeeded", mapOf("pointCount" to profile.points.size))
        return true
    }

    fun stopClicking(reason: String = "requested") {
        val previousState = runState
        if (previousState != RunState.IDLE) {
            DiagnosticLog.record(
                service,
                "macro_stopped",
                mapOf(
                    "reason" to reason,
                    "state" to previousState.name,
                    "point" to (currentIndex + 1),
                    "repeat" to (currentRepeatIndex + 1),
                    "completedLoops" to completedLoops
                )
            )
        }
        service.cancelActiveAction()
        service.endMacroRun()
        runGeneration++
        runState = RunState.IDLE
        handler.removeCallbacksAndMessages(null)
        currentIndex = 0
        currentRepeatIndex = 0
        completedLoops = 0
        targets.forEach {
            it.view.isActivePoint = false
            it.view.isMacroRunning = false
            it.endView?.isMacroRunning = false
        }
        setTargetsTouchable(true)
        applyKeepScreenOn(false)
        updateControls()
        service.notifyTileStateChanged()
    }

    fun stopForForegroundChange() {
        if (runState != RunState.IDLE) {
            stopWithMessage("Aplikasi aktif berubah; macro dihentikan", "foreground_changed")
        }
    }

    fun stopForSystemUi() {
        if (runState != RunState.IDLE) {
            stopWithMessage("System UI dibuka; macro dihentikan", "system_ui")
        }
    }

    fun stopForScreenOff() {
        if (runState != RunState.IDLE) {
            stopWithMessage("Layar tidak aktif; macro dihentikan", "screen_off")
        }
    }

    fun onConfigurationChanged() {
        dismissDelayDialog()
        val stoppedActiveMacro = runState != RunState.IDLE
        if (stoppedActiveMacro) stopClicking("configuration_changed")
        val markerSize = dp(TARGET_TOUCH_SIZE_DP)
        var allMarkersUpdated = true
        targets.forEach { holder ->
            val oldStart = intArrayOf(
                holder.params.x,
                holder.params.y,
                holder.params.width,
                holder.params.height
            )
            holder.params.width = markerSize
            holder.params.height = markerSize
            applyNormalizedPosition(holder)
            if (runCatching { windowManager.updateViewLayout(holder.view, holder.params) }.isFailure) {
                holder.params.x = oldStart[0]
                holder.params.y = oldStart[1]
                holder.params.width = oldStart[2]
                holder.params.height = oldStart[3]
                allMarkersUpdated = false
            }
            val endParams = holder.endParams
            val endView = holder.endView
            if (endParams != null && endView != null) {
                val oldEnd = intArrayOf(endParams.x, endParams.y, endParams.width, endParams.height)
                endParams.width = markerSize
                endParams.height = markerSize
                applyEndpointNormalizedPosition(holder)
                if (runCatching { windowManager.updateViewLayout(endView, endParams) }.isFailure) {
                    endParams.x = oldEnd[0]
                    endParams.y = oldEnd[1]
                    endParams.width = oldEnd[2]
                    endParams.height = oldEnd[3]
                    allMarkersUpdated = false
                }
            }
        }
        if (!allMarkersUpdated) {
            Toast.makeText(service, "Sebagian marker gagal menyesuaikan layar", Toast.LENGTH_SHORT).show()
        }
        val bounds = displayBounds()
        val controls = controlsView
        val params = controlsParams
        if (controls != null && params != null) {
            val oldWidth = params.width
            params.width = toolbarWidth(bounds)
            runCatching { windowManager.updateViewLayout(controls, params) }
                .onFailure {
                    params.width = oldWidth
                    Toast.makeText(service, "Toolbar gagal menyesuaikan layar", Toast.LENGTH_SHORT).show()
                }
            controls.post { clampToolbarToDisplay() }
        }
        if (stoppedActiveMacro) {
            Toast.makeText(service, "Konfigurasi layar berubah; macro dihentikan", Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy(): Boolean {
        DiagnosticLog.record(service, "editor_close_requested")
        dismissDelayDialog()
        stopClicking("editor_closed")
        if (!removeAllTargets()) {
            DiagnosticLog.record(service, "editor_close_failed", mapOf("reason" to "target_cleanup"))
            Toast.makeText(service, "Sebagian overlay belum dapat ditutup. Coba lagi.", Toast.LENGTH_SHORT).show()
            return false
        }
        controlsView?.let { controls ->
            if (!removeViewSafely(controls)) {
                DiagnosticLog.record(service, "editor_close_failed", mapOf("reason" to "toolbar_cleanup"))
                Toast.makeText(service, "Toolbar belum dapat ditutup. Coba lagi.", Toast.LENGTH_SHORT).show()
                return false
            }
            controlsView = null
            controlsParams = null
            editorContent = null
            toolbarHandle = null
        }
        currentProfile = null
        selectedIndex = 0
        isToolbarCollapsed = false
        markersHidden = false
        service.notifyTileStateChanged()
        DiagnosticLog.record(service, "editor_closed")
        return true
    }

    fun closeIfProfile(profileName: String) {
        if (currentProfile?.name == profileName) destroy()
    }

    fun updateProfileSettings(profile: MacroProfile) {
        val current = currentProfile ?: return
        if (current.name == profile.name) {
            currentProfile = profile.copy(points = current.points)
            if (runState != RunState.IDLE) {
                stopWithMessage("Pengaturan profil berubah; macro dihentikan", "profile_settings_changed")
            }
        }
    }

    fun toggleFromTile(): Boolean {
        if (currentProfile == null || targets.isEmpty()) return false
        if (runState == RunState.IDLE) startClicking() else stopClicking("quick_settings")
        return true
    }

    fun isMacroActive(): Boolean = runState != RunState.IDLE

    fun hasLoadedProfile(): Boolean = currentProfile != null && targets.isNotEmpty()

    private fun addTarget(point: MacroPoint, persist: Boolean = true): Boolean {
        if (runState != RunState.IDLE) return false

        val target = MacroTargetView(service).apply {
            sequenceNumber = targets.size + 1
            contentDescription = "Titik klik $sequenceNumber, geser untuk memindahkan"
            visibility = if (markersHidden) View.INVISIBLE else View.VISIBLE
        }
        val params = createTargetLayoutParams(point)
        if (markersHidden) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val holder = TargetHolder(
            id = point.id,
            view = target,
            params = params,
            normalizedX = point.normalizedX.coerceIn(0f, 1f),
            normalizedY = point.normalizedY.coerceIn(0f, 1f),
            delayMs = ClickPreferences.sanitizeDelay(point.delayMs),
            actionType = point.actionType,
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
            endNormalizedX = point.endNormalizedX.coerceIn(0f, 1f),
            endNormalizedY = point.endNormalizedY.coerceIn(0f, 1f)
        )
        attachDragHandler(holder)

        if (runCatching { windowManager.addView(target, params) }.isFailure) {
            DiagnosticLog.record(service, "overlay_add_failed", mapOf("overlay" to "marker"))
            Toast.makeText(service, "Titik tidak dapat ditambahkan oleh sistem", Toast.LENGTH_SHORT).show()
            return false
        }
        if (holder.actionType == PointActionType.SWIPE && !addEndpoint(holder, targets.size + 1)) {
            if (!removeViewSafely(target)) targets.add(holder)
            Toast.makeText(service, "Endpoint swipe tidak dapat ditampilkan", Toast.LENGTH_SHORT).show()
            return false
        }

        targets.add(holder)
        selectTarget(targets.lastIndex)
        if (persist) persistProfile()
        return true
    }

    private fun addEndpoint(holder: TargetHolder, sequence: Int): Boolean {
        if (holder.endView != null) return true
        val endpoint = MacroTargetView(service).apply {
            sequenceNumber = sequence
            isEndpoint = true
            contentDescription = "Tujuan swipe titik $sequence"
            visibility = if (markersHidden) View.INVISIBLE else View.VISIBLE
        }
        val params = createMarkerLayoutParams(holder.endNormalizedX, holder.endNormalizedY)
        if (markersHidden) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        attachEndpointDragHandler(holder, endpoint, params)
        if (runCatching { windowManager.addView(endpoint, params) }.isFailure) {
            DiagnosticLog.record(service, "overlay_add_failed", mapOf("overlay" to "swipe_endpoint"))
            return false
        }
        holder.endView = endpoint
        holder.endParams = params
        return true
    }

    private fun removeEndpoint(holder: TargetHolder): Boolean {
        val endpoint = holder.endView ?: return true
        if (!removeViewSafely(endpoint)) return false
        holder.endView = null
        holder.endParams = null
        return true
    }

    private fun addNewTarget() {
        val profile = currentProfile ?: return
        addTarget(createPoint(profile.defaultDelayMs))
    }

    private fun removeSelectedTarget() {
        if (runState != RunState.IDLE) return
        if (targets.size <= 1) {
            Toast.makeText(service, "Macro harus memiliki minimal satu titik", Toast.LENGTH_SHORT).show()
            return
        }

        val removed = targets[selectedIndex]
        val hadEndpoint = removed.endView != null
        val endpointRemoved = removed.endView?.let(::removeViewSafely) ?: true
        if (endpointRemoved) {
            removed.endView = null
            removed.endParams = null
        }
        if (!endpointRemoved || !removeViewSafely(removed.view)) {
            if (endpointRemoved && hadEndpoint && removed.actionType == PointActionType.SWIPE) {
                addEndpoint(removed, selectedIndex + 1)
            }
            Toast.makeText(service, "Titik gagal dihapus. Coba tutup editor.", Toast.LENGTH_SHORT).show()
            return
        }
        targets.removeAt(selectedIndex)
        selectedIndex = selectedIndex.coerceAtMost(targets.lastIndex)
        relabelTargets()
        selectTarget(selectedIndex)
        persistProfile()
    }

    private fun moveSelectedPoint(direction: Int) {
        if (runState != RunState.IDLE || targets.isEmpty()) return
        val destination = (selectedIndex + direction).coerceIn(0, targets.lastIndex)
        if (destination == selectedIndex) return
        val selected = targets.removeAt(selectedIndex)
        targets.add(destination, selected)
        selectedIndex = destination
        relabelTargets()
        selectTarget(selectedIndex)
        persistProfile()
    }

    private fun relabelTargets() {
        targets.forEachIndexed { index, holder ->
            holder.view.sequenceNumber = index + 1
            holder.view.contentDescription = "Titik klik ${index + 1}, geser untuk memindahkan"
            holder.endView?.apply {
                sequenceNumber = index + 1
                contentDescription = "Tujuan swipe titik ${index + 1}"
            }
        }
    }

    private fun addControls(): Boolean {
        val controls = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7f), dp(4f), dp(7f), dp(7f))
            background = panelBackground()
            elevation = dp(10f).toFloat()
        }

        val handle = TextView(service).apply {
            text = "⋮⋮  MACRO  ▴"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(203, 213, 225))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            contentDescription = "Geser toolbar atau ketuk untuk menciutkan"
        }
        attachToolbarDragHandler(handle)
        stopButton = controlButton("■", "Hentikan macro") { stopClicking("toolbar") }
        val headerRow = horizontalRow().apply {
            addView(handle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton)
        }
        controls.addView(
            headerRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val primaryActionRow = horizontalRow()
        val secondaryActionRow = horizontalRow()
        playButton = controlButton("▶", "Mulai macro") { startClicking() }
        addButton = controlButton("+", "Tambah titik") { addNewTarget() }
        removeButton = controlButton("−", "Hapus titik terpilih") { removeSelectedTarget() }
        val closeButton = controlButton("×", "Tutup editor macro") { destroy() }
        markerVisibilityButton = controlButton("◉", "Sembunyikan atau tampilkan marker") { toggleMarkers() }
        listOf(playButton, addButton).forEach(primaryActionRow::addView)
        listOf(removeButton, markerVisibilityButton, closeButton).forEach(secondaryActionRow::addView)

        val orderRow = horizontalRow()
        moveUpButton = controlButton("↑", "Naikkan urutan titik") { moveSelectedPoint(-1) }
        delayLabel = TextView(service).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(5f), dp(6f), dp(5f), dp(6f))
            contentDescription = "Ketuk untuk memasukkan jeda langsung"
            setOnClickListener { showDelayInputDialog() }
            background = GradientDrawable().apply {
                setColor(Color.argb(80, 255, 255, 255))
                cornerRadius = dp(10f).toFloat()
            }
        }
        moveDownButton = controlButton("↓", "Turunkan urutan titik") { moveSelectedPoint(1) }
        orderRow.addView(moveUpButton)
        orderRow.addView(
            delayLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        orderRow.addView(moveDownButton)

        val delayRow = horizontalRow()
        decreaseDelayButton = delayButton("−0.1", "Kurangi jeda 0,1 detik", -ClickPreferences.DELAY_STEP_MS)
        increaseDelayButton = delayButton("+0.1", "Tambah jeda 0,1 detik", ClickPreferences.DELAY_STEP_MS)
        delayRow.addView(decreaseDelayButton)
        delayRow.addView(increaseDelayButton)

        val gestureRow = horizontalRow()
        actionButton = settingButton("Tap", "Ubah jenis aksi") { cycleSelectedAction() }
        durationButton = settingButton("0.7 dtk", "Atur durasi aksi") { showDurationDialog() }
        repeatButton = settingButton("×1", "Atur jumlah pengulangan") { showRepeatDialog() }
        listOf(actionButton, durationButton, repeatButton).forEach { button ->
            gestureRow.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        content.addView(primaryActionRow)
        content.addView(secondaryActionRow)
        content.addView(orderRow)
        content.addView(delayRow)
        content.addView(gestureRow)
        controls.addView(content)

        val bounds = displayBounds()
        val params = WindowManager.LayoutParams(
            toolbarWidth(bounds),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(24f)
            y = (bounds.height() * 0.62f).roundToInt()
        }

        if (runCatching { windowManager.addView(controls, params) }.isFailure) {
            DiagnosticLog.record(service, "overlay_add_failed", mapOf("overlay" to "toolbar"))
            Toast.makeText(service, "Kontrol macro tidak dapat ditampilkan", Toast.LENGTH_SHORT).show()
            return false
        }
        controlsView = controls
        controlsParams = params
        editorContent = content
        toolbarHandle = handle
        controls.post { clampToolbarToDisplay() }
        return true
    }

    private fun startClicking() {
        dismissDelayDialog()
        val profile = currentProfile ?: return
        if (runState != RunState.IDLE || targets.isEmpty()) return
        DiagnosticLog.record(
            service,
            "macro_start_requested",
            mapOf("pointCount" to targets.size)
        )
        if (!service.beginMacroRun()) {
            Toast.makeText(
                service,
                "Buka aplikasi target dan pastikan layar tidak terkunci",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        runGeneration++
        val generation = runGeneration
        currentIndex = 0
        currentRepeatIndex = 0
        completedLoops = 0
        runState = if (profile.countdownSeconds > 0) RunState.COUNTDOWN else RunState.RUNNING
        DiagnosticLog.record(
            service,
            "macro_started",
            mapOf("state" to runState.name, "countdownSeconds" to profile.countdownSeconds)
        )
        if (runState == RunState.COUNTDOWN) {
            delayLabel.text = "Mulai dalam ${profile.countdownSeconds}…"
        }
        targets.forEach {
            it.view.isMacroRunning = true
            it.endView?.isMacroRunning = true
        }

        if (!setTargetsTouchable(false)) {
            stopWithMessage("Target tidak dapat disiapkan oleh sistem", "target_touchability_failed")
            return
        }
        if (!applyKeepScreenOn(profile.keepScreenOn)) {
            stopWithMessage("Pengaturan layar tetap menyala gagal diterapkan", "keep_screen_on_failed")
            return
        }
        updateControls()
        service.notifyTileStateChanged()

        handler.postDelayed({
            if (profile.countdownSeconds > 0) {
                runCountdown(profile.countdownSeconds, generation)
            } else {
                executeCurrentPoint(generation)
            }
        }, OVERLAY_FLAG_SETTLE_MS)
    }

    private fun runCountdown(remainingSeconds: Int, generation: Long) {
        if (runState != RunState.COUNTDOWN || generation != runGeneration) return
        if (!service.canDispatchGestures()) {
            stopWithMessage("Layar terkunci atau tidak aktif; macro dihentikan", "screen_locked_or_inactive")
            return
        }
        if (!service.isMacroTargetForeground()) {
            stopWithMessage("Aplikasi target tidak aktif; macro dihentikan", "target_not_foreground")
            return
        }
        if (remainingSeconds <= 0) {
            runState = RunState.RUNNING
            updateControls()
            executeCurrentPoint(generation)
            return
        }
        delayLabel.text = "Mulai dalam $remainingSeconds…"
        handler.postDelayed({ runCountdown(remainingSeconds - 1, generation) }, 1_000L)
    }

    private fun executeCurrentPoint(generation: Long) {
        if (runState != RunState.RUNNING || generation != runGeneration || targets.isEmpty()) return
        if (!service.canDispatchGestures()) {
            stopWithMessage("Layar terkunci atau tidak aktif; macro dihentikan", "screen_locked_or_inactive")
            return
        }
        if (!service.isMacroTargetForeground()) {
            stopWithMessage("Aplikasi target tidak aktif; macro dihentikan", "target_not_foreground")
            return
        }

        val point = targets[currentIndex]
        if (point.view.width <= 0 || point.view.height <= 0 ||
            (point.actionType == PointActionType.SWIPE && point.endView == null)
        ) {
            stopWithMessage("Posisi titik belum siap. Coba mulai kembali.", "marker_not_ready")
            return
        }

        targets.forEach {
            it.view.isActivePoint = false
            it.endView?.isActivePoint = false
        }
        point.view.isActivePoint = true
        point.endView?.isActivePoint = true
        updateControls()
        val bounds = displayBounds()
        val start = markerGestureCenter(
            view = point.view,
            normalizedX = point.normalizedX,
            normalizedY = point.normalizedY,
            bounds = bounds
        )
        val end = if (point.actionType == PointActionType.SWIPE) {
            val endView = point.endView
            if (endView == null) null else markerGestureCenter(
                view = endView,
                normalizedX = point.endNormalizedX,
                normalizedY = point.endNormalizedY,
                bounds = bounds
            )
        } else {
            start
        }
        if (start == null || end == null) {
            stopWithMessage("Posisi marker tidak dapat diverifikasi. Macro dihentikan.", "marker_position_invalid")
            return
        }
        if (isCoveredByControls(start.first, start.second)) {
            stopWithMessage("Titik tertutup menu. Pindahkan menu lalu mulai kembali.", "marker_covered_by_controls")
            return
        }

        val accepted = service.performAction(
            pointNumber = currentIndex + 1,
            repeatIndex = currentRepeatIndex + 1,
            actionType = point.actionType,
            startX = start.first,
            startY = start.second,
            endX = end.first,
            endY = end.second,
            durationMs = point.actionDurationMs,
            onCompleted = {
                if (runState != RunState.RUNNING || generation != runGeneration) return@performAction
                point.view.isActivePoint = false
                point.endView?.isActivePoint = false
                if (currentRepeatIndex + 1 < point.repeatCount) {
                    currentRepeatIndex++
                    updateControls()
                    handler.postDelayed({ executeCurrentPoint(generation) }, REPEAT_GAP_MS)
                    return@performAction
                }
                currentRepeatIndex = 0
                val wasLastPoint = currentIndex == targets.lastIndex
                if (wasLastPoint) {
                    completedLoops++
                    val limit = currentProfile?.loopLimit ?: 0
                    if (limit > 0 && completedLoops >= limit) {
                        stopWithMessage("Macro selesai: $completedLoops loop", "loop_limit_reached")
                        return@performAction
                    }
                }
                val loopDelay = if (wasLastPoint) currentProfile?.loopDelayMs ?: 0L else 0L
                val waitAfterPoint = point.delayMs + loopDelay
                currentIndex = (currentIndex + 1) % targets.size
                if (wasLastPoint) {
                    delayLabel.text = "Jeda titik + loop • " +
                        "${ClickPreferences.formatDelaySeconds(point.delayMs)} + " +
                        "${ClickPreferences.formatDelaySeconds(loopDelay)} dtk"
                } else {
                    updateControls()
                }
                handler.postDelayed({ executeCurrentPoint(generation) }, waitAfterPoint)
            },
            onCancelled = {
                if (runState == RunState.RUNNING && generation == runGeneration) {
                    stopWithMessage("Gesture dibatalkan oleh sistem", "gesture_cancelled")
                }
            }
        )

        if (!accepted) stopWithMessage("Sistem menolak gesture. Macro dihentikan.", "gesture_rejected")
    }

    private fun stopWithMessage(message: String, reason: String = "runtime_error") {
        stopClicking(reason)
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    private fun applyKeepScreenOn(enabled: Boolean): Boolean {
        val controls = controlsView ?: return !enabled
        val params = controlsParams ?: return !enabled
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        controls.keepScreenOn = enabled
        return runCatching { windowManager.updateViewLayout(controls, params) }.isSuccess
    }

    private fun showDelayInputDialog() {
        if (runState != RunState.IDLE || targets.isEmpty() || delayDialog?.isShowing == true) return
        val point = targets[selectedIndex]
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(ClickPreferences.formatDelaySeconds(point.delayMs))
            setSelectAllOnFocus(true)
            hint = "Contoh: 1.5"
        }
        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Jeda Titik ${selectedIndex + 1}")
            .setMessage("Waktu setelah klik sebelum menuju titik berikutnya (0.1–3600.0 detik)")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val delay = ClickPreferences.parseDelaySeconds(input.text.toString())
                if (delay == null) {
                    input.error = "Gunakan 0.1–3600.0"
                } else {
                    point.delayMs = delay
                    updateControls()
                    persistProfile()
                    dialog.dismiss()
                }
            }
        }
        dialog.setOnDismissListener {
            if (delayDialog === dialog) delayDialog = null
        }
        delayDialog = dialog
        runCatching { dialog.show() }.onFailure {
            delayDialog = null
            Toast.makeText(service, "Input langsung tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissDelayDialog() {
        val dialog = delayDialog ?: return
        val dismissed = runCatching { dialog.dismiss() }.isSuccess
        if (!dismissed && dialog.isShowing) {
            Toast.makeText(service, "Dialog belum dapat ditutup", Toast.LENGTH_SHORT).show()
        } else if (delayDialog === dialog) {
            delayDialog = null
        }
    }

    private fun selectTarget(index: Int) {
        if (targets.isEmpty()) return
        selectedIndex = index.coerceIn(0, targets.lastIndex)
        targets.forEachIndexed { targetIndex, holder ->
            holder.view.isSelectedPoint = targetIndex == selectedIndex
        }
        updateControls()
    }

    private fun adjustSelectedDelay(deltaMs: Long) {
        if (runState != RunState.IDLE || targets.isEmpty()) return
        val point = targets[selectedIndex]
        point.delayMs = ClickPreferences.sanitizeDelay(point.delayMs + deltaMs)
        updateControls()
        persistProfile()
    }

    private fun cycleSelectedAction() {
        if (runState != RunState.IDLE || targets.isEmpty()) return
        val point = targets[selectedIndex]
        val next = when (point.actionType) {
            PointActionType.TAP -> PointActionType.LONG_PRESS
            PointActionType.LONG_PRESS -> PointActionType.SWIPE
            PointActionType.SWIPE -> PointActionType.TAP
        }
        val endpointReady = if (next == PointActionType.SWIPE) {
            addEndpoint(point, selectedIndex + 1)
        } else {
            removeEndpoint(point)
        }
        if (!endpointReady) {
            Toast.makeText(service, "Jenis aksi tidak dapat diubah", Toast.LENGTH_SHORT).show()
            return
        }
        point.actionType = next
        if (next == PointActionType.LONG_PRESS) {
            point.actionDurationMs = point.actionDurationMs.coerceAtLeast(MacroPoint.MIN_LONG_PRESS_DURATION_MS)
        }
        updateControls()
        persistProfile()
    }

    private fun showDurationDialog() {
        if (runState != RunState.IDLE || targets.isEmpty() || delayDialog?.isShowing == true) return
        val point = targets[selectedIndex]
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(ClickPreferences.formatDelaySeconds(point.actionDurationMs))
            setSelectAllOnFocus(true)
        }
        val minimum = if (point.actionType == PointActionType.LONG_PRESS) {
            MacroPoint.MIN_LONG_PRESS_DURATION_MS
        } else {
            MacroPoint.MIN_ACTION_DURATION_MS
        }
        showSettingsDialog(
            title = "Durasi ${actionName(point.actionType)}",
            message = "Gunakan ${ClickPreferences.formatDelaySeconds(minimum)}–60.0 detik",
            input = input
        ) {
            val value = ClickPreferences.parseDelaySeconds(input.text.toString())
            if (value == null || value !in minimum..MacroPoint.MAX_ACTION_DURATION_MS) {
                input.error = "Gunakan ${ClickPreferences.formatDelaySeconds(minimum)}–60.0"
                false
            } else {
                point.actionDurationMs = value
                persistProfile()
                updateControls()
                true
            }
        }
    }

    private fun showRepeatDialog() {
        if (runState != RunState.IDLE || targets.isEmpty() || delayDialog?.isShowing == true) return
        val point = targets[selectedIndex]
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(point.repeatCount.toString())
            setSelectAllOnFocus(true)
        }
        showSettingsDialog(
            title = "Jumlah Aksi Titik ${selectedIndex + 1}",
            message = "Aksi diulang 1–${MacroPoint.MAX_REPEAT_COUNT} kali sebelum lanjut",
            input = input
        ) {
            val value = input.text.toString().toIntOrNull()
            if (value == null || value !in MacroPoint.MIN_REPEAT_COUNT..MacroPoint.MAX_REPEAT_COUNT) {
                input.error = "Gunakan 1–${MacroPoint.MAX_REPEAT_COUNT}"
                false
            } else {
                point.repeatCount = value
                persistProfile()
                updateControls()
                true
            }
        }
    }

    private fun showSettingsDialog(
        title: String,
        message: String,
        input: EditText,
        save: () -> Boolean
    ) {
        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (save()) dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { if (delayDialog === dialog) delayDialog = null }
        delayDialog = dialog
        runCatching { dialog.show() }.onFailure {
            delayDialog = null
            Toast.makeText(service, "Pengaturan aksi tidak dapat dibuka", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMarkers() {
        val previousHidden = markersHidden
        val requestedHidden = !previousHidden
        markersHidden = requestedHidden
        if (!setTargetsTouchable(runState == RunState.IDLE)) {
            markersHidden = previousHidden
            setTargetsTouchable(runState == RunState.IDLE)
            Toast.makeText(service, "Marker belum dapat diubah oleh sistem", Toast.LENGTH_SHORT).show()
            return
        }
        val visibility = if (markersHidden) View.INVISIBLE else View.VISIBLE
        targets.forEach { holder ->
            holder.view.visibility = visibility
            holder.endView?.visibility = visibility
        }
        markerVisibilityButton.text = if (markersHidden) "○" else "◉"
        markerVisibilityButton.contentDescription = if (markersHidden) "Tampilkan marker" else "Sembunyikan marker"
    }

    private fun setTargetsTouchable(touchable: Boolean): Boolean {
        var allUpdated = true
        val effectiveTouchable = touchable && !markersHidden
        targets.forEach { holder ->
            val oldStartFlags = holder.params.flags
            holder.params.flags = touchFlags(holder.params.flags, effectiveTouchable)
            if (runCatching { windowManager.updateViewLayout(holder.view, holder.params) }.isFailure) {
                holder.params.flags = oldStartFlags
                allUpdated = false
            }
            val endView = holder.endView
            val endParams = holder.endParams
            if (endView != null && endParams != null) {
                val oldEndFlags = endParams.flags
                endParams.flags = touchFlags(endParams.flags, effectiveTouchable)
                if (runCatching { windowManager.updateViewLayout(endView, endParams) }.isFailure) {
                    endParams.flags = oldEndFlags
                    allUpdated = false
                }
            }
        }
        return allUpdated
    }

    private fun touchFlags(flags: Int, touchable: Boolean): Int = if (touchable) {
        flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    } else {
        flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun updateControls() {
        if (!::playButton.isInitialized || controlsView == null) return
        val idle = runState == RunState.IDLE
        playButton.isEnabled = idle && targets.isNotEmpty()
        stopButton.isEnabled = !idle
        listOf(
            addButton, removeButton, moveUpButton, moveDownButton,
            decreaseDelayButton, increaseDelayButton, actionButton, durationButton, repeatButton
        ).forEach {
            it.isEnabled = idle
            it.alpha = if (idle) 1f else 0.4f
        }
        playButton.alpha = if (playButton.isEnabled) 1f else 0.4f
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.4f
        removeButton.isEnabled = idle && targets.size > 1
        removeButton.alpha = if (removeButton.isEnabled) 1f else 0.4f
        moveUpButton.isEnabled = idle && selectedIndex > 0
        moveDownButton.isEnabled = idle && selectedIndex < targets.lastIndex
        moveUpButton.alpha = if (moveUpButton.isEnabled) 1f else 0.4f
        moveDownButton.alpha = if (moveDownButton.isEnabled) 1f else 0.4f
        delayLabel.isEnabled = idle
        delayLabel.alpha = if (idle) 1f else 0.75f

        if (targets.isEmpty()) return
        val selectedPoint = targets[selectedIndex]
        actionButton.text = actionName(selectedPoint.actionType)
        durationButton.text = if (selectedPoint.actionType == PointActionType.TAP) {
            "Durasi —"
        } else {
            "${ClickPreferences.formatDelaySeconds(selectedPoint.actionDurationMs)} dtk"
        }
        durationButton.isEnabled = idle && selectedPoint.actionType != PointActionType.TAP
        durationButton.alpha = if (durationButton.isEnabled) 1f else 0.4f
        repeatButton.text = "×${selectedPoint.repeatCount}"
        markerVisibilityButton.text = if (markersHidden) "○" else "◉"
        delayLabel.text = when (runState) {
            RunState.IDLE -> "Titik ${selectedIndex + 1} • ${ClickPreferences.formatDelaySeconds(targets[selectedIndex].delayMs)} dtk"
            RunState.COUNTDOWN -> delayLabel.text
            RunState.RUNNING -> "L${completedLoops + 1} • T${currentIndex + 1} • ${currentRepeatIndex + 1}/${targets[currentIndex].repeatCount}"
        }
    }

    private fun toggleToolbar() {
        isToolbarCollapsed = !isToolbarCollapsed
        editorContent?.visibility = if (isToolbarCollapsed) View.GONE else View.VISIBLE
        toolbarHandle?.text = if (isToolbarCollapsed) "⋮⋮  MACRO  ▾" else "⋮⋮  MACRO  ▴"
        val controls = controlsView ?: return
        val params = controlsParams ?: return
        runCatching { windowManager.updateViewLayout(controls, params) }
        controls.post { clampToolbarToDisplay() }
    }

    private fun attachToolbarDragHandler(handle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        var layoutUpdateFailed = false
        handle.setOnTouchListener { _, event ->
            val params = controlsParams
            val controls = controlsView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    layoutUpdateFailed = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (params != null && controls != null) {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        moved = moved || kotlin.math.abs(deltaX) > dp(4f) || kotlin.math.abs(deltaY) > dp(4f)
                        val previousX = params.x
                        val previousY = params.y
                        params.x = initialX + deltaX.roundToInt()
                        params.y = initialY + deltaY.roundToInt()
                        clampToolbarParams(params, controls)
                        if (runCatching { windowManager.updateViewLayout(controls, params) }.isFailure) {
                            params.x = previousX
                            params.y = previousY
                            layoutUpdateFailed = true
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleToolbar()
                    if (layoutUpdateFailed) {
                        Toast.makeText(service, "Toolbar gagal dipindahkan", Toast.LENGTH_SHORT).show()
                    }
                    clampToolbarToDisplay()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (params != null && controls != null) {
                        params.x = initialX
                        params.y = initialY
                        clampToolbarParams(params, controls)
                        runCatching { windowManager.updateViewLayout(controls, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachDragHandler(holder: TargetHolder) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var layoutUpdateFailed = false
        holder.view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val index = targets.indexOf(holder)
                    if (index < 0) return@setOnTouchListener false
                    selectTarget(index)
                    initialX = holder.params.x
                    initialY = holder.params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    layoutUpdateFailed = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val previousX = holder.params.x
                    val previousY = holder.params.y
                    holder.params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    holder.params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    clampTarget(holder)
                    if (runCatching { windowManager.updateViewLayout(holder.view, holder.params) }.isFailure) {
                        holder.params.x = previousX
                        holder.params.y = previousY
                        layoutUpdateFailed = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    updateNormalizedPosition(holder)
                    persistProfile()
                    if (layoutUpdateFailed) {
                        Toast.makeText(service, "Titik tidak dapat dipindahkan sepenuhnya", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holder.params.x = initialX
                    holder.params.y = initialY
                    clampTarget(holder)
                    runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachEndpointDragHandler(
        holder: TargetHolder,
        endpoint: MacroTargetView,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var layoutUpdateFailed = false
        endpoint.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val index = targets.indexOf(holder)
                    if (index < 0) return@setOnTouchListener false
                    selectTarget(index)
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    layoutUpdateFailed = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val previousX = params.x
                    val previousY = params.y
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    clampParams(params)
                    if (runCatching { windowManager.updateViewLayout(endpoint, params) }.isFailure) {
                        params.x = previousX
                        params.y = previousY
                        layoutUpdateFailed = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    updateEndpointNormalizedPosition(holder)
                    persistProfile()
                    if (layoutUpdateFailed) {
                        Toast.makeText(service, "Endpoint tidak dapat dipindahkan sepenuhnya", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    params.x = initialX
                    params.y = initialY
                    clampParams(params)
                    runCatching { windowManager.updateViewLayout(endpoint, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun persistProfile() {
        val profile = currentProfile ?: return
        targets.forEach {
            updateNormalizedPosition(it)
            updateEndpointNormalizedPosition(it)
        }
        val updated = profile.copy(points = targets.map { holder ->
            MacroPoint(
                id = holder.id,
                normalizedX = holder.normalizedX,
                normalizedY = holder.normalizedY,
                delayMs = holder.delayMs,
                actionType = holder.actionType,
                actionDurationMs = holder.actionDurationMs,
                repeatCount = holder.repeatCount,
                endNormalizedX = holder.endNormalizedX,
                endNormalizedY = holder.endNormalizedY
            )
        })
        currentProfile = onProfileChanged(updated) ?: profile
    }

    private fun createPoint(delayMs: Long): MacroPoint {
        val index = targets.size
        val normalizedX = (0.12 + ((index * 0.61803398875) % 0.76)).toFloat().coerceIn(0.08f, 0.92f)
        val normalizedY = (0.16 + ((index * 0.38196601125) % 0.64)).toFloat().coerceIn(0.1f, 0.9f)
        return MacroPoint(
            id = System.nanoTime(),
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            delayMs = ClickPreferences.sanitizeDelay(delayMs),
            endNormalizedX = (normalizedX + 0.18f).coerceAtMost(0.95f),
            endNormalizedY = (normalizedY + 0.12f).coerceAtMost(0.95f)
        )
    }

    private fun createTargetLayoutParams(point: MacroPoint): WindowManager.LayoutParams =
        createMarkerLayoutParams(point.normalizedX, point.normalizedY)

    private fun createMarkerLayoutParams(normalizedX: Float, normalizedY: Float): WindowManager.LayoutParams {
        val bounds = displayBounds()
        val size = dp(TARGET_TOUCH_SIZE_DP)
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = markerOffset(normalizedX, bounds.width(), size)
            y = markerOffset(normalizedY, bounds.height(), size)
        }
    }

    private fun applyNormalizedPosition(holder: TargetHolder) {
        val bounds = displayBounds()
        holder.params.x = markerOffset(holder.normalizedX, bounds.width(), holder.params.width)
        holder.params.y = markerOffset(holder.normalizedY, bounds.height(), holder.params.height)
    }

    private fun updateNormalizedPosition(holder: TargetHolder) {
        val bounds = displayBounds()
        holder.normalizedX = normalizedMarkerPosition(holder.params.x, bounds.width(), holder.params.width)
        holder.normalizedY = normalizedMarkerPosition(holder.params.y, bounds.height(), holder.params.height)
    }

    private fun updateEndpointNormalizedPosition(holder: TargetHolder) {
        val params = holder.endParams ?: return
        val bounds = displayBounds()
        holder.endNormalizedX = normalizedMarkerPosition(params.x, bounds.width(), params.width)
        holder.endNormalizedY = normalizedMarkerPosition(params.y, bounds.height(), params.height)
    }

    private fun applyEndpointNormalizedPosition(holder: TargetHolder) {
        val params = holder.endParams ?: return
        val bounds = displayBounds()
        params.x = markerOffset(holder.endNormalizedX, bounds.width(), params.width)
        params.y = markerOffset(holder.endNormalizedY, bounds.height(), params.height)
    }

    private fun clampTarget(holder: TargetHolder) {
        clampParams(holder.params)
    }

    private fun clampParams(params: WindowManager.LayoutParams) {
        val bounds = displayBounds()
        params.x = params.x.coerceIn(markerMinOffset(params.width), markerMaxOffset(bounds.width(), params.width))
        params.y = params.y.coerceIn(markerMinOffset(params.height), markerMaxOffset(bounds.height(), params.height))
    }

    private fun markerOffset(normalized: Float, displaySize: Int, markerSize: Int): Int {
        val desired = (normalized.coerceIn(0f, 1f) * maxCoordinate(displaySize) - markerSize / 2f).roundToInt()
        return desired.coerceIn(markerMinOffset(markerSize), markerMaxOffset(displaySize, markerSize))
    }

    private fun normalizedMarkerPosition(offset: Int, displaySize: Int, markerSize: Int): Float {
        val maximumCoordinate = maxCoordinate(displaySize)
        if (maximumCoordinate == 0) return 0f
        val minimumOffset = markerMinOffset(markerSize)
        val maximumOffset = markerMaxOffset(displaySize, markerSize)
        if (offset <= minimumOffset) return 0f
        if (offset >= maximumOffset) return 1f
        return ((offset + markerSize / 2f) / maximumCoordinate).coerceIn(0f, 1f)
    }

    private fun markerMinOffset(markerSize: Int): Int = ceil(-markerSize / 2f).toInt()

    private fun markerMaxOffset(displaySize: Int, markerSize: Int): Int =
        floor(maxCoordinate(displaySize) - markerSize / 2f).toInt()
            .coerceAtLeast(markerMinOffset(markerSize))

    private fun maxCoordinate(displaySize: Int): Int = (displaySize - 1).coerceAtLeast(0)

    private fun markerGestureCenter(
        view: View,
        normalizedX: Float,
        normalizedY: Float,
        bounds: Rect
    ): Pair<Float, Float>? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val observedX = location[0] + view.width / 2f
        val observedY = location[1] + view.height / 2f
        val minX = bounds.left.toFloat()
        val minY = bounds.top.toFloat()
        val maxX = (bounds.right - 1).coerceAtLeast(bounds.left).toFloat()
        val maxY = (bounds.bottom - 1).coerceAtLeast(bounds.top).toFloat()
        if (observedX !in (minX - POSITION_TOLERANCE_PX)..(maxX + POSITION_TOLERANCE_PX) ||
            observedY !in (minY - POSITION_TOLERANCE_PX)..(maxY + POSITION_TOLERANCE_PX)
        ) {
            return null
        }
        return Pair(
            resolveGestureCoordinate(normalizedX, observedX, minX, maxX),
            resolveGestureCoordinate(normalizedY, observedY, minY, maxY)
        )
    }

    private fun resolveGestureCoordinate(
        normalized: Float,
        observed: Float,
        minimum: Float,
        maximum: Float
    ): Float = when {
        normalized <= 0f -> minimum
        normalized >= 1f -> maximum
        else -> observed.coerceIn(minimum, maximum)
    }

    private fun isCoveredByControls(x: Float, y: Float): Boolean {
        val controls = controlsView ?: return false
        if (!controls.isAttachedToWindow || controls.visibility != View.VISIBLE ||
            controls.width <= 0 || controls.height <= 0
        ) {
            return false
        }
        val location = IntArray(2)
        controls.getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + controls.width &&
            y >= location[1] && y < location[1] + controls.height
    }

    private fun toolbarWidth(bounds: Rect): Int =
        minOf(dp(260f), (bounds.width() - dp(16f)).coerceAtLeast(1))

    private fun clampToolbarParams(params: WindowManager.LayoutParams, controls: View) {
        val bounds = displayBounds()
        val width = controls.width.takeIf { it > 0 } ?: params.width.takeIf { it > 0 } ?: dp(260f)
        val height = controls.height.takeIf { it > 0 } ?: dp(48f)
        params.x = params.x.coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height() - height).coerceAtLeast(0))
    }

    private fun clampToolbarToDisplay() {
        val controls = controlsView ?: return
        val params = controlsParams ?: return
        clampToolbarParams(params, controls)
        runCatching { windowManager.updateViewLayout(controls, params) }
    }

    private fun displayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Rect(windowManager.currentWindowMetrics.bounds)
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun removeAllTargets(): Boolean {
        var allRemoved = true
        val iterator = targets.iterator()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            val endpoint = holder.endView
            if (endpoint != null && !removeViewSafely(endpoint)) {
                allRemoved = false
                continue
            }
            holder.endView = null
            holder.endParams = null
            if (removeViewSafely(holder.view)) {
                iterator.remove()
            } else {
                if (holder.actionType == PointActionType.SWIPE && endpoint != null) {
                    addEndpoint(holder, holder.view.sequenceNumber)
                }
                allRemoved = false
            }
        }
        return allRemoved
    }

    private fun actionName(actionType: PointActionType): String = when (actionType) {
        PointActionType.TAP -> "Tap"
        PointActionType.LONG_PRESS -> "Tahan"
        PointActionType.SWIPE -> "Geser"
    }

    private fun horizontalRow() = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun controlButton(symbol: String, description: String, action: () -> Unit) = Button(service).apply {
        text = symbol
        textSize = 18f
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = dp(47f)
        minWidth = dp(47f)
        minimumHeight = dp(40f)
        minHeight = dp(40f)
        setPadding(dp(6f), 0, dp(6f), 0)
        background = transparentButtonBackground()
        setOnClickListener { action() }
    }

    private fun settingButton(value: String, description: String, action: () -> Unit) = Button(service).apply {
        text = value
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = 0
        minWidth = 0
        minimumHeight = dp(36f)
        minHeight = dp(36f)
        setPadding(dp(4f), 0, dp(4f), 0)
        background = transparentButtonBackground()
        setOnClickListener { action() }
    }

    private fun delayButton(textValue: String, description: String, deltaMs: Long) = Button(service).apply {
        text = textValue
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = dp(76f)
        minWidth = dp(76f)
        minimumHeight = dp(36f)
        minHeight = dp(36f)
        background = transparentButtonBackground()
        setOnClickListener { adjustSelectedDelay(deltaMs) }
        setOnLongClickListener {
            adjustSelectedDelay(if (deltaMs < 0) -ClickPreferences.LONG_DELAY_STEP_MS else ClickPreferences.LONG_DELAY_STEP_MS)
            true
        }
    }

    private fun transparentButtonBackground() = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(12f).toFloat()
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Color.argb(242, 15, 23, 42))
        cornerRadius = dp(17f).toFloat()
        setStroke(dp(1f), Color.argb(100, 255, 255, 255))
    }

    private fun removeViewSafely(view: View): Boolean {
        return runCatching {
            windowManager.removeViewImmediate(view)
            true
        }.getOrElse { error ->
            DiagnosticLog.record(
                service,
                "overlay_remove_failed",
                mapOf("view" to view.javaClass.simpleName, "error" to error.javaClass.simpleName)
            )
            !view.isAttachedToWindow
        }
    }

    private fun dp(value: Float): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private data class TargetHolder(
        val id: Long,
        val view: MacroTargetView,
        val params: WindowManager.LayoutParams,
        var normalizedX: Float,
        var normalizedY: Float,
        var delayMs: Long,
        var actionType: PointActionType,
        var actionDurationMs: Long,
        var repeatCount: Int,
        var endNormalizedX: Float,
        var endNormalizedY: Float,
        var endView: MacroTargetView? = null,
        var endParams: WindowManager.LayoutParams? = null
    )

    private enum class RunState { IDLE, COUNTDOWN, RUNNING }

    companion object {
        private const val TARGET_TOUCH_SIZE_DP = 44f
        private const val OVERLAY_FLAG_SETTLE_MS = 50L
        private const val REPEAT_GAP_MS = 100L
        private const val POSITION_TOLERANCE_PX = 1f
    }
}

private class MacroTargetView(context: Context) : View(context) {
    private val density: Float
        get() = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    var sequenceNumber: Int = 1
        set(value) { field = value; invalidate() }
    var isSelectedPoint: Boolean = false
        set(value) { field = value; invalidate() }
    var isActivePoint: Boolean = false
        set(value) { field = value; invalidate() }
    var isMacroRunning: Boolean = false
        set(value) { field = value; invalidate() }
    var isEndpoint: Boolean = false
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (if (isMacroRunning) 8f else 11f) * density
        strokePaint.strokeWidth = 1.5f * density
        fillPaint.color = when {
            isActivePoint -> Color.rgb(34, 197, 94)
            isEndpoint -> Color.rgb(14, 165, 233)
            isSelectedPoint -> Color.rgb(79, 70, 229)
            else -> Color.rgb(239, 68, 68)
        }
        fillPaint.alpha = if (isMacroRunning) 205 else 235
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        val extent = 16f * density
        canvas.drawLine(centerX - extent, centerY, centerX - radius, centerY, strokePaint)
        canvas.drawLine(centerX + radius, centerY, centerX + extent, centerY, strokePaint)
        canvas.drawLine(centerX, centerY - extent, centerX, centerY - radius, strokePaint)
        canvas.drawLine(centerX, centerY + radius, centerX, centerY + extent, strokePaint)

        textPaint.textSize = (if (sequenceNumber < 100) 10f else 8f) * density
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        val label = if (isEndpoint) "${sequenceNumber}E" else sequenceNumber.toString()
        canvas.drawText(label, centerX, baseline, textPaint)
    }
}
