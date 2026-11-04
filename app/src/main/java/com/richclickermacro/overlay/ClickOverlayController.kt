package com.richclickermacro.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
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
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import com.richclickermacro.data.ClickPreferences
import com.richclickermacro.data.MacroPoint
import com.richclickermacro.data.MacroProfile
import com.richclickermacro.data.Path2Config
import com.richclickermacro.data.Path2Format
import com.richclickermacro.data.Path2Schedule
import com.richclickermacro.data.Path2Store
import com.richclickermacro.data.PointActionType
import com.richclickermacro.diagnostics.MacroDiagnostics
import com.richclickermacro.safety.GestureSafety
import com.richclickermacro.safety.MarkerCoordinates
import com.richclickermacro.safety.ScreenFrame
import kotlin.math.roundToInt

@SuppressLint("SetTextI18n", "RtlHardcoded")
class ClickOverlayController(
    private val service: AutoClickAccessibilityService,
    private val onProfileChanged: (MacroProfile) -> MacroProfile?
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val targets = mutableListOf<TargetHolder>()
    private val path2Targets = mutableListOf<TargetHolder>()
    private val path2Store = Path2Store(service)
    private val path2Schedule = Path2Schedule()
    private var path2Config = Path2Config()
    private var path2Index = 0
    private var selectedPath2Index = -1
    private var isPath2PanelOpen = false
    private var isGestureInFlight = false
    private var lastPath2Status = ""

    private var currentProfile: MacroProfile? = null
    private var controlsView: LinearLayout? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var editorContent: LinearLayout? = null
    private var advancedContent: LinearLayout? = null
    private var isAdvancedOpen = false
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
    private var runScreenFrame: ScreenFrame? = null
    private var expectedTargetPackage: String? = null
    private var isDisposingToolbar = false

    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var compactStopButton: Button
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
    private lateinit var compactStatus: TextView
    private lateinit var advancedButton: Button
    private lateinit var path2Button: Button
    private lateinit var path2Panel: LinearLayout
    private lateinit var path2ToggleButton: Button
    private lateinit var path2MinutesButton: Button
    private lateinit var path2StatusLabel: TextView
    private lateinit var addPath2Button: Button
    private lateinit var removePath2Button: Button

    fun show(profile: MacroProfile) {
        dismissDelayDialog()
        stopClicking()
        if (!removeAllTargets()) {
            Toast.makeText(service, "Editor lama belum dapat dibersihkan", Toast.LENGTH_SHORT).show()
            return
        }

        currentProfile = profile
        loadPath2Config()
        var allTargetsAdded = true
        for (point in profile.points) {
            if (!addTarget(point, persist = false)) {
                allTargetsAdded = false
                break
            }
        }
        if (!allTargetsAdded || (targets.isEmpty() && !addTarget(createPoint(profile.defaultDelayMs), persist = false))) {
            removeAllTargets()
            removeAllPath2Targets()
            currentProfile = null
            Toast.makeText(service, "Profil tidak dapat ditampilkan sepenuhnya", Toast.LENGTH_SHORT).show()
            return
        }
        if (controlsView?.isAttachedToWindow != true || controlsView?.windowVisibility != View.VISIBLE) {
            controlsView?.let { runCatching { windowManager.removeViewImmediate(it) } }
            controlsView = null
            controlsParams = null
            editorContent = null
            advancedContent = null
            toolbarHandle = null
        }
        if (controlsView == null && !addControls()) {
            removeAllTargets()
            removeAllPath2Targets()
            currentProfile = null
            return
        }
        selectedIndex = 0
        selectTarget(0)
        updateControls()
        service.notifyTileStateChanged()
        Toast.makeText(service, "Profil ${profile.name} aktif di atas aplikasi lain", Toast.LENGTH_SHORT).show()
    }

    fun stopClicking(reason: String = "stop_requested") {
        if (runState != RunState.IDLE) MacroDiagnostics.record(service, "macro_stop", reason)
        runScreenFrame = null
        expectedTargetPackage = null
        service.cancelActiveAction()
        runGeneration++
        runState = RunState.IDLE
        handler.removeCallbacksAndMessages(null)
        currentIndex = 0
        currentRepeatIndex = 0
        completedLoops = 0
        isGestureInFlight = false
        path2Index = 0
        path2Schedule.pause(SystemClock.elapsedRealtime())
        targets.forEach {
            it.view.isActivePoint = false
            it.view.isMacroRunning = false
            it.endView?.isMacroRunning = false
        }
        path2Targets.forEach {
            it.view.isActivePoint = false
            it.view.isMacroRunning = false
        }
        setTargetsTouchable(true)
        setPath2Touchable(true)
        applyKeepScreenOn(false)
        updateControls()
        service.stopMemorySampling()
        service.notifyTileStateChanged()
    }

    fun onConfigurationChanged() {
        val newFrame = runCatching { screenFrame() }.getOrNull()
        if (runState != RunState.IDLE && (newFrame == null || runScreenFrame != newFrame)) {
            stopWithMessage("Layar berubah. Periksa marker sebelum memulai lagi.")
        }
        targets.forEach { holder ->
            applyNormalizedPosition(holder)
            runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
            applyEndpointNormalizedPosition(holder)
            val endView = holder.endView
            val endParams = holder.endParams
            if (endView != null && endParams != null) {
                runCatching { windowManager.updateViewLayout(endView, endParams) }
            }
        }
        path2Targets.forEach { holder ->
            applyNormalizedPosition(holder)
            runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
        }
        controlsView?.post { clampToolbarToDisplay() }
    }

    fun destroy() {
        isDisposingToolbar = true
        dismissDelayDialog()
        stopClicking("editor_closed")
        if (!removeAllTargets()) {
            isDisposingToolbar = false
            Toast.makeText(service, "Sebagian overlay belum dapat ditutup. Coba lagi.", Toast.LENGTH_SHORT).show()
            return
        }
        controlsView?.let { controls ->
            if (removeViewSafely(controls)) {
                controlsView = null
                controlsParams = null
                editorContent = null
                toolbarHandle = null
            }
        }
        if (!removeAllPath2Targets()) {
            isDisposingToolbar = false
            Toast.makeText(service, "Sebagian overlay Jalur 2 belum dapat ditutup. Coba lagi.", Toast.LENGTH_SHORT).show()
            return
        }
        path2Schedule.reset()
        path2Index = 0
        selectedPath2Index = -1
        isPath2PanelOpen = false
        path2Config = path2Config.copy(points = emptyList())
        currentProfile = null
        selectedIndex = 0
        isToolbarCollapsed = false
        isAdvancedOpen = false
        markersHidden = false
        isDisposingToolbar = false
        service.notifyTileStateChanged()
    }

    fun closeIfProfile(profileName: String) {
        if (currentProfile?.name == profileName) destroy()
    }

    fun updateProfileSettings(profile: MacroProfile) {
        val current = currentProfile ?: return
        if (current.name == profile.name) {
            currentProfile = profile.copy(points = current.points)
            if (runState != RunState.IDLE) applyKeepScreenOn(profile.keepScreenOn)
        }
    }

    fun toggleFromTile(): Boolean {
        if (currentProfile == null || targets.isEmpty()) return false
        if (runState == RunState.IDLE) startClicking() else stopClicking()
        return true
    }

    fun onApplicationWindowChanged(packageName: String) {
        val expected = expectedTargetPackage ?: return
        if (runState != RunState.IDLE && currentProfile?.stopOnAppChange == true && packageName != expected) {
            stopWithMessage("Aplikasi tujuan berganti. Macro dihentikan.")
        }
    }

    /** Tracked overlay windows, including hidden markers; not a GPU-buffer byte count. */
    fun overlayWindowCount(): Int {
        var count = targets.size
        count += targets.count { it.endView != null }
        count += path2Targets.size
        if (controlsView != null) count++
        return count
    }

    /**
     * Best-effort yielding when old Android versions report critical pressure.
     * Keep marker windows/profile intact: INVISIBLE does not reliably free their
     * graphics buffers, and removing windows would lose the user's editor state.
     * This cannot guarantee that the foreground game will not crash.
     */
    fun onSystemMemoryPressure(reason: String) {
        if (runState != RunState.IDLE) {
            MacroDiagnostics.record(service, "memory_pressure_stop", reason)
            stopClicking("memory_pressure")
            Toast.makeText(
                service,
                "Android melaporkan RAM kritis. Macro dihentikan sebagai tindakan pencegahan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun isMacroActive(): Boolean = runState != RunState.IDLE

    fun hasLoadedProfile(): Boolean =
        currentProfile != null && targets.isNotEmpty() && controlsView?.isAttachedToWindow == true

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
        if (persist) {
            persistProfile()
            if (targets.size == MANY_POINTS_WARNING) {
                MacroDiagnostics.record(service, "many_points_warning", "points=${targets.size}")
                Toast.makeText(
                    service,
                    "Titik sudah $MANY_POINTS_WARNING. Tiap marker menambah window grafis. " +
                        "Gunakan titik seperlunya; menyembunyikan marker tidak menjamin RAM turun.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
        if (runCatching { windowManager.addView(endpoint, params) }.isFailure) return false
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
        val endpointRemoved = removed.endView?.let(::removeViewSafely) ?: true
        if (endpointRemoved) {
            removed.endView = null
            removed.endParams = null
        }
        if (!endpointRemoved || !removeViewSafely(removed.view)) {
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
        val controls = ToolbarLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(5f), dp(3f), dp(5f), dp(5f))
            background = panelBackground()
            elevation = dp(6f).toFloat()
        }

        // Compact header: drag/collapse, always-available STOP while running, close.
        val headerRow = horizontalRow()
        val handle = TextView(service).apply {
            text = "⋮⋮ MACRO ▴"
            textSize = 11f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(203, 213, 225))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(5f), 0, dp(3f), 0)
            minHeight = dp(28f)
            contentDescription = "Geser toolbar atau ketuk untuk menciutkan"
            setOnClickListener { toggleToolbar() }
        }
        compactStopButton = Button(service).apply {
            text = "STOP"
            textSize = 9f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            minimumWidth = dp(46f); minWidth = dp(46f)
            minimumHeight = dp(28f); minHeight = dp(28f)
            setPadding(dp(4f), 0, dp(4f), 0)
            background = stopBackground()
            contentDescription = "Hentikan macro dari menu"
            visibility = View.GONE
            setOnClickListener { stopClicking() }
        }
        val closeButton = controlButton("×", "Tutup editor macro") { destroy() }.apply {
            textSize = 16f
            minimumWidth = dp(28f); minWidth = dp(28f)
            minimumHeight = dp(28f); minHeight = dp(28f)
            setPadding(0, 0, 0, 0)
        }
        attachToolbarDragHandler(handle)
        headerRow.addView(handle, LinearLayout.LayoutParams(0, dp(28f), 1f))
        headerRow.addView(compactStopButton, LinearLayout.LayoutParams(dp(46f), dp(28f)).apply { marginStart = dp(3f) })
        headerRow.addView(closeButton, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        controls.addView(headerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)))

        val content = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val primaryRow = horizontalRow()
        playButton = controlButton("▶", "Mulai macro") { startClicking() }
        stopButton = controlButton("STOP", "Hentikan macro") { stopClicking() }.apply {
            textSize = 10f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            background = stopBackground()
        }
        addButton = controlButton("+", "Tambah titik") { addNewTarget() }
        removeButton = controlButton("−", "Hapus titik terpilih") { removeSelectedTarget() }
        listOf(playButton, stopButton, addButton, removeButton).forEach {
            primaryRow.addView(it, LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginStart = dp(1f); marginEnd = dp(1f) })
        }
        content.addView(primaryRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)))

        val utilityRow = horizontalRow()
        markerVisibilityButton = controlButton("◉", "Sembunyikan atau tampilkan marker") { toggleMarkers() }
        advancedButton = controlButton("⚙ Atur", "Buka atau tutup pengaturan titik") { toggleAdvanced() }.apply {
            textSize = 11f; isAllCaps = false
        }
        compactStatus = TextView(service).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.rgb(203, 213, 225))
            setPadding(dp(3f), 0, dp(3f), 0)
        }
        path2Button = controlButton("J2", "Buka atau tutup pengaturan Jalur 2") { togglePath2Panel() }.apply {
            textSize = 11f
            isAllCaps = false
        }
        utilityRow.addView(markerVisibilityButton, LinearLayout.LayoutParams(dp(32f), dp(30f)))
        utilityRow.addView(path2Button, LinearLayout.LayoutParams(dp(40f), dp(30f)))
        utilityRow.addView(advancedButton, LinearLayout.LayoutParams(dp(58f), dp(30f)))
        utilityRow.addView(compactStatus, LinearLayout.LayoutParams(0, dp(30f), 1f))
        content.addView(utilityRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30f)))

        // Jalur 2: titik kedua + waktunya sendiri, diatur dari panel ini saja.
        path2Panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isPath2PanelOpen) View.VISIBLE else View.GONE
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 148, 163, 184)); cornerRadius = dp(6f).toFloat()
            }
        }
        val path2TimeRow = horizontalRow()
        path2ToggleButton = settingButton("J2 mati", "Aktifkan atau matikan Jalur 2") { togglePath2Enabled() }.apply {
            textSize = 10f
        }
        val path2MinusButton = controlButton("−", "Kurangi waktu Jalur 2 satu menit") { changePath2Minutes(-1) }
        path2MinutesButton = settingButton("10 mnt", "Ketuk untuk mengetik waktu Jalur 2 dalam menit") {
            showPath2MinutesDialog()
        }.apply { textSize = 10f }
        val path2PlusButton = controlButton("+", "Tambah waktu Jalur 2 satu menit") { changePath2Minutes(1) }
        path2TimeRow.addView(path2ToggleButton, LinearLayout.LayoutParams(0, dp(30f), 1f))
        path2TimeRow.addView(path2MinusButton, LinearLayout.LayoutParams(dp(28f), dp(30f)).apply { marginStart = dp(2f) })
        path2TimeRow.addView(path2MinutesButton, LinearLayout.LayoutParams(dp(56f), dp(30f)))
        path2TimeRow.addView(path2PlusButton, LinearLayout.LayoutParams(dp(28f), dp(30f)))
        path2Panel.addView(path2TimeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30f)))

        val path2PointRow = horizontalRow()
        addPath2Button = settingButton("+ Titik J2", "Tambah titik Jalur 2") { addNewPath2Target() }.apply {
            textSize = 10f
        }
        removePath2Button = settingButton("− Titik J2", "Hapus titik Jalur 2 terpilih") { removeSelectedPath2Target() }.apply {
            textSize = 10f
        }
        path2PointRow.addView(addPath2Button, LinearLayout.LayoutParams(0, dp(30f), 1f).apply { marginEnd = dp(2f) })
        path2PointRow.addView(removePath2Button, LinearLayout.LayoutParams(0, dp(30f), 1f))
        path2Panel.addView(path2PointRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30f)))

        path2StatusLabel = TextView(service).apply {
            textSize = 9f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(226, 232, 240))
            setPadding(dp(2f), dp(1f), dp(2f), 0)
        }
        path2Panel.addView(path2StatusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16f)))
        content.addView(path2Panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Advanced controls are one tap away, not permanently covering the game.
        val advanced = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isAdvancedOpen) View.VISIBLE else View.GONE
        }
        val orderRow = horizontalRow()
        moveUpButton = controlButton("↑", "Naikkan urutan titik") { moveSelectedPoint(-1) }
        moveDownButton = controlButton("↓", "Turunkan urutan titik") { moveSelectedPoint(1) }
        delayLabel = TextView(service).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(3f), 0, dp(3f), 0)
            contentDescription = "Ketuk untuk memasukkan jeda langsung"
            setOnClickListener { showDelayInputDialog() }
            background = GradientDrawable().apply {
                setColor(Color.argb(80, 255, 255, 255)); cornerRadius = dp(8f).toFloat()
            }
        }
        orderRow.addView(moveUpButton, LinearLayout.LayoutParams(dp(38f), dp(32f)))
        orderRow.addView(delayLabel, LinearLayout.LayoutParams(0, dp(32f), 1f).apply { marginStart = dp(3f); marginEnd = dp(3f) })
        orderRow.addView(moveDownButton, LinearLayout.LayoutParams(dp(38f), dp(32f)))
        advanced.addView(orderRow)

        val delayRow = horizontalRow()
        decreaseDelayButton = delayButton("−0.1", "Kurangi jeda 0,1 detik", -ClickPreferences.DELAY_STEP_MS)
        increaseDelayButton = delayButton("+0.1", "Tambah jeda 0,1 detik", ClickPreferences.DELAY_STEP_MS)
        listOf(decreaseDelayButton, increaseDelayButton).forEach {
            delayRow.addView(it, LinearLayout.LayoutParams(0, dp(30f), 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        }
        advanced.addView(delayRow)

        val gestureRow = horizontalRow()
        actionButton = settingButton("Tap", "Ubah jenis aksi") { cycleSelectedAction() }
        durationButton = settingButton("0.7 dtk", "Atur durasi aksi") { showDurationDialog() }
        repeatButton = settingButton("×1", "Atur jumlah pengulangan") { showRepeatDialog() }
        listOf(actionButton, durationButton, repeatButton).forEach {
            gestureRow.addView(it, LinearLayout.LayoutParams(0, dp(32f), 1f).apply { marginStart = dp(1f); marginEnd = dp(1f) })
        }
        advanced.addView(gestureRow)
        content.addView(advanced)
        controls.addView(content)

        val bounds = displayBounds()
        val panelWidth = minOf(dp(TOOLBAR_WIDTH_DP), (bounds.width() - dp(12f)).coerceAtLeast(1))
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(12f)
            y = (bounds.height() * 0.68f).roundToInt()
        }
        if (runCatching { windowManager.addView(controls, params) }.isFailure) {
            Toast.makeText(service, "Kontrol macro tidak dapat ditampilkan", Toast.LENGTH_SHORT).show()
            return false
        }
        controlsView = controls
        controlsParams = params
        editorContent = content
        advancedContent = advanced
        toolbarHandle = handle
        controls.onUnexpectedVisibilityChange = { visibility ->
            MacroDiagnostics.record(service, "toolbar_visibility_changed", "visibility=$visibility state=$runState")
            if (visibility != View.VISIBLE && runState != RunState.IDLE) {
                stopWithMessage("Toolbar disembunyikan sistem/game. Macro dihentikan demi keamanan.")
            }
        }
        controls.onUnexpectedDetach = {
            MacroDiagnostics.record(service, "toolbar_detached", "state=$runState")
            if (!isDisposingToolbar && runState != RunState.IDLE) stopClicking("toolbar_detached")
        }
        controls.post { clampToolbarToDisplay() }
        return true
    }

    private fun toggleAdvanced() {
        if (runState != RunState.IDLE) return
        isAdvancedOpen = !isAdvancedOpen
        applyToolbarVisibility()
    }

    private fun applyToolbarVisibility() {
        editorContent?.visibility = if (isToolbarCollapsed) View.GONE else View.VISIBLE
        advancedContent?.visibility = if (!isToolbarCollapsed && isAdvancedOpen) View.VISIBLE else View.GONE
        toolbarHandle?.text = if (isToolbarCollapsed) "⋮⋮ MACRO ▾" else "⋮⋮ MACRO ▴"
        if (::advancedButton.isInitialized) {
            advancedButton.text = if (isAdvancedOpen) "⚙ Tutup" else "⚙ Atur"
            advancedButton.contentDescription = if (isAdvancedOpen) "Tutup pengaturan titik" else "Buka pengaturan titik"
        }
        if (::path2Panel.isInitialized) {
            path2Panel.visibility = if (!isToolbarCollapsed && isPath2PanelOpen) View.VISIBLE else View.GONE
        }
        if (::path2Button.isInitialized) {
            path2Button.text = if (path2Config.enabled) "J2✓" else "J2"
        }
        val controls = controlsView ?: return
        val params = controlsParams ?: return
        runCatching { windowManager.updateViewLayout(controls, params) }
        controls.post { clampToolbarToDisplay() }
    }

    private fun startClicking() {
        dismissDelayDialog()
        val profile = currentProfile ?: return
        if (runState != RunState.IDLE || targets.isEmpty()) return
        if (isAdvancedOpen) {
            isAdvancedOpen = false
            applyToolbarVisibility()
            // Wait for the smaller layout before checking overlap; otherwise the
            // old measured height can reject a point that is no longer covered.
            handler.postDelayed({ startClicking() }, OVERLAY_FLAG_SETTLE_MS)
            return
        }

        val frame = runCatching { screenFrame() }.getOrElse {
            Toast.makeText(service, "Ukuran layar tidak dapat dibaca", Toast.LENGTH_SHORT).show()
            return
        }
        val overlap = findToolbarOverlap(frame)
        if (overlap != null) {
            Toast.makeText(
                service,
                "Titik $overlap tertutup toolbar. Pindahkan toolbar dulu agar klik tidak mengenai menu.",
                Toast.LENGTH_LONG
            ).show()
            MacroDiagnostics.record(service, "start_rejected_toolbar_overlap", "point=$overlap")
            return
        }
        if (path2Config.enabled) {
            val path2Overlap = findPath2ToolbarOverlap()
            if (path2Overlap != null) {
                Toast.makeText(
                    service,
                    "Titik Jalur 2 nomor $path2Overlap tertutup toolbar. Pindahkan toolbar dulu.",
                    Toast.LENGTH_LONG
                ).show()
                MacroDiagnostics.record(service, "start_rejected_path2_overlap", "point=$path2Overlap")
                return
            }
        }
        val targetPackage = if (profile.stopOnAppChange) {
            service.foregroundPackage() ?: run {
                Toast.makeText(
                    service,
                    "Aplikasi tujuan belum terdeteksi. Buka aplikasi tujuan setelah editor aktif, lalu coba lagi.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        } else null
        service.requestExclusiveRun(smart = false)
        expectedTargetPackage = targetPackage
        runScreenFrame = frame
        MacroDiagnostics.record(
            service, "macro_start",
            "points=${targets.size} appGuard=${profile.stopOnAppChange} target=$targetPackage countdown=${profile.countdownSeconds} loopLimit=${profile.loopLimit}"
        )

        runGeneration++
        val generation = runGeneration
        currentIndex = 0
        currentRepeatIndex = 0
        completedLoops = 0
        runState = if (profile.countdownSeconds > 0) RunState.COUNTDOWN else RunState.RUNNING
        targets.forEach {
            it.view.isMacroRunning = true
            it.endView?.isMacroRunning = true
        }

        val targetsReady = setTargetsTouchable(false)
        val path2Ready = setPath2Touchable(false)
        if (!targetsReady || !path2Ready) {
            stopWithMessage("Target tidak dapat disiapkan oleh sistem")
            return
        }
        applyKeepScreenOn(profile.keepScreenOn)
        if (runState == RunState.RUNNING) path2ResumeClock()
        updateControls()
        service.startMemorySampling()
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
        if (remainingSeconds <= 0) {
            runState = RunState.RUNNING
            path2ResumeClock()
            updateControls()
            executeCurrentPoint(generation)
            return
        }
        delayLabel.text = "Mulai $remainingSeconds…"
        compactStatus.text = "Mulai $remainingSeconds…"
        handler.postDelayed({ runCountdown(remainingSeconds - 1, generation) }, 1_000L)
    }

    private fun executeCurrentPoint(generation: Long) {
        if (runState != RunState.RUNNING || generation != runGeneration || targets.isEmpty()) return

        val frame = screenFrame()
        if (runScreenFrame != frame) {
            stopWithMessage("Ukuran atau orientasi layar berubah. Periksa marker sebelum memulai lagi.")
            return
        }
        val point = targets[currentIndex]
        val startAligned = isMarkerAligned(point.view, point.normalizedX, point.normalizedY, frame)
        val endAligned = point.actionType != PointActionType.SWIPE ||
            isMarkerAligned(point.endView, point.endNormalizedX, point.endNormalizedY, frame)
        if (!startAligned || !endAligned) {
            MacroDiagnostics.record(service, "marker_alignment_rejected", "point=${currentIndex + 1}")
            stopWithMessage("Posisi marker tidak cocok dengan layar. Periksa titik, lalu mulai lagi.")
            return
        }

        // Setters only invalidate changed markers. Do not redraw every overlay
        // window on every tap during a multi-hour session.
        targets.forEach {
            it.view.isActivePoint = false
            it.endView?.isActivePoint = false
        }
        point.view.isActivePoint = true
        point.endView?.isActivePoint = true
        updateControls()
        val location = IntArray(2)
        point.view.getLocationOnScreen(location)
        val rawX = location[0] + point.view.width / 2f
        val rawY = location[1] + point.view.height / 2f
        val endLocation = IntArray(2)
        point.endView?.getLocationOnScreen(endLocation)
        val rawEndX = point.endView?.let { endLocation[0] + it.width / 2f } ?: rawX
        val rawEndY = point.endView?.let { endLocation[1] + it.height / 2f } ?: rawY
        val (x, y) = GestureSafety.clampToFrame(frame, rawX, rawY)
        val (endX, endY) = GestureSafety.clampToFrame(frame, rawEndX, rawEndY)
        if (!GestureSafety.isValidGesture(x, y, endX, endY, point.actionDurationMs)) {
            stopWithMessage("Koordinat gesture tidak valid. Periksa marker.")
            return
        }
        if (gestureTouchesToolbar(x, y, endX, endY, point.actionType == PointActionType.SWIPE)) {
            stopWithMessage("Toolbar menutupi titik aksi. Pindahkan toolbar, lalu mulai lagi.")
            return
        }

        isGestureInFlight = true
        val accepted = service.performAction(
            actionType = point.actionType,
            startX = x,
            startY = y,
            endX = endX,
            endY = endY,
            durationMs = point.actionDurationMs,
            onCompleted = {
                isGestureInFlight = false
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
                        stopWithMessage("Macro selesai: $completedLoops loop")
                        return@performAction
                    }
                }
                val waitAfterPoint = if (wasLastPoint) {
                    currentProfile?.loopDelayMs ?: point.delayMs
                } else {
                    point.delayMs
                }
                currentIndex = (currentIndex + 1) % targets.size
                if (wasLastPoint) {
                    compactStatus.text = "Jeda loop ${ClickPreferences.formatDelaySeconds(waitAfterPoint)}s"
                } else {
                    updateControls()
                }
                handler.postDelayed({ executeCurrentPoint(generation) }, waitAfterPoint)
            },
            onCancelled = {
                isGestureInFlight = false
                if (runState == RunState.RUNNING && generation == runGeneration) {
                    MacroDiagnostics.record(service, "gesture_cancelled", "point=${currentIndex + 1} action=${point.actionType}")
                    stopWithMessage("Gesture dibatalkan oleh sistem")
                }
            }
        )

        if (!accepted) {
            isGestureInFlight = false
            MacroDiagnostics.record(service, "gesture_rejected", "point=${currentIndex + 1} action=${point.actionType}")
            stopWithMessage("Sistem menolak gesture. Macro dihentikan.")
        }
    }

    private fun stopWithMessage(message: String) {
        stopClicking(message)
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        val controls = controlsView ?: return
        val params = controlsParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        controls.keepScreenOn = enabled
        runCatching { windowManager.updateViewLayout(controls, params) }
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
        delayDialog?.let { dialog -> runCatching { dialog.dismiss() } }
        delayDialog = null
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
        if (!setTargetsTouchable(runState == RunState.IDLE) || !setPath2Touchable(runState == RunState.IDLE)) {
            markersHidden = previousHidden
            setTargetsTouchable(runState == RunState.IDLE)
            setPath2Touchable(runState == RunState.IDLE)
            Toast.makeText(service, "Marker belum dapat diubah oleh sistem", Toast.LENGTH_SHORT).show()
            return
        }
        val visibility = if (markersHidden) View.INVISIBLE else View.VISIBLE
        targets.forEach { holder ->
            holder.view.visibility = visibility
            holder.endView?.visibility = visibility
        }
        path2Targets.forEach { holder -> holder.view.visibility = visibility }
        markerVisibilityButton.text = if (markersHidden) "○" else "◉"
        markerVisibilityButton.contentDescription = if (markersHidden) "Tampilkan marker" else "Sembunyikan marker"
    }

    private fun setTargetsTouchable(touchable: Boolean): Boolean {
        var allUpdated = true
        val effectiveTouchable = touchable && !markersHidden
        // Android 12 may block touches that pass through another app's window when
        // that window is too opaque. While macro gestures are injected, marker
        // windows are pass-through and kept below the 0.8 opacity threshold.
        val windowAlpha = if (effectiveTouchable) 1f else 0.7f
        targets.forEach { holder ->
            holder.params.flags = touchFlags(holder.params.flags, effectiveTouchable)
            holder.params.alpha = windowAlpha
            if (runCatching { windowManager.updateViewLayout(holder.view, holder.params) }.isFailure) allUpdated = false
            val endView = holder.endView
            val endParams = holder.endParams
            if (endView != null && endParams != null) {
                endParams.flags = touchFlags(endParams.flags, effectiveTouchable)
                endParams.alpha = windowAlpha
                if (runCatching { windowManager.updateViewLayout(endView, endParams) }.isFailure) allUpdated = false
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
        compactStopButton.isEnabled = !idle
        compactStopButton.visibility = if (idle) View.GONE else View.VISIBLE
        listOf(
            addButton, removeButton, moveUpButton, moveDownButton,
            decreaseDelayButton, increaseDelayButton, actionButton, durationButton, repeatButton
        ).forEach {
            it.isEnabled = idle
            it.alpha = if (idle) 1f else 0.4f
        }
        playButton.alpha = if (playButton.isEnabled) 1f else 0.4f
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.4f
        compactStopButton.alpha = if (compactStopButton.isEnabled) 1f else 0.4f
        removeButton.isEnabled = idle && targets.size > 1
        removeButton.alpha = if (removeButton.isEnabled) 1f else 0.4f
        moveUpButton.isEnabled = idle && selectedIndex > 0
        moveDownButton.isEnabled = idle && selectedIndex < targets.lastIndex
        moveUpButton.alpha = if (moveUpButton.isEnabled) 1f else 0.4f
        moveDownButton.alpha = if (moveDownButton.isEnabled) 1f else 0.4f
        delayLabel.isEnabled = idle
        delayLabel.alpha = if (idle) 1f else 0.75f
        advancedButton.isEnabled = idle
        advancedButton.alpha = if (idle) 1f else 0.4f
        markerVisibilityButton.isEnabled = true
        markerVisibilityButton.alpha = 1f
        if (::path2ToggleButton.isInitialized) {
            path2ToggleButton.isEnabled = idle
            path2ToggleButton.alpha = if (idle) 1f else 0.4f
            path2ToggleButton.text = if (path2Config.enabled) "J2 aktif" else "J2 mati"
            path2MinutesButton.isEnabled = idle
            path2MinutesButton.alpha = if (idle) 1f else 0.4f
            path2MinutesButton.text = "${path2Config.intervalMinutes} mnt"
            addPath2Button.isEnabled = idle && path2Targets.size < Path2Config.MAX_POINTS
            addPath2Button.alpha = if (addPath2Button.isEnabled) 1f else 0.4f
            removePath2Button.isEnabled = idle && path2Targets.isNotEmpty()
            removePath2Button.alpha = if (removePath2Button.isEnabled) 1f else 0.4f
            path2Button.isEnabled = idle
            path2Button.alpha = if (idle) 1f else 0.55f
            path2Button.text = if (path2Config.enabled) "J2✓" else "J2"
            updatePath2Status()
        }

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
            RunState.IDLE -> "T${selectedIndex + 1}/${targets.size} • ${ClickPreferences.formatDelaySeconds(targets[selectedIndex].delayMs)}s"
            RunState.COUNTDOWN -> delayLabel.text
            RunState.RUNNING -> "L${completedLoops + 1} • T${currentIndex + 1} • ${currentRepeatIndex + 1}/${targets[currentIndex].repeatCount}"
            RunState.PATH2 -> "JALUR 2 • T${path2Index + 1}/${path2Targets.size}"
        }
        compactStatus.text = when (runState) {
            RunState.IDLE -> "T${selectedIndex + 1}/${targets.size}"
            RunState.COUNTDOWN -> compactStatus.text
            RunState.RUNNING -> "L${completedLoops + 1} • T${currentIndex + 1}"
            RunState.PATH2 -> "J2 • T${path2Index + 1}/${path2Targets.size}"
        }
    }

    // ---------------------------------------------------------------- Jalur 2
    // Jalur 2 = rangkaian titik kedua. Dipicu murni oleh waktu: setelah Jalur 1
    // berjalan selama N menit, Jalur 1 berhenti sejenak, Jalur 2 dijalankan dari
    // titik 1 sampai terakhir satu kali, lalu Jalur 1 lanjut dari titik 1.

    private val path2Tick = object : Runnable {
        override fun run() {
            if (runState != RunState.RUNNING) return
            if (!path2Config.enabled || path2Targets.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            if (!path2Schedule.isDue(now, path2Config.intervalMs)) {
                updatePath2Status(now)
                handler.postDelayed(this, PATH2_TICK_MS)
                return
            }
            // Jangan potong gesture yang sedang berjalan; cek lagi sesudahnya.
            if (isGestureInFlight) {
                handler.postDelayed(this, PATH2_TICK_MS)
                return
            }
            beginPath2()
        }
    }

    private fun loadPath2Config() {
        removeAllPath2Targets()
        path2Config = path2Store.load()
        path2Config.points.forEach { point -> addPath2Target(point, persist = false) }
        path2Schedule.reset()
        path2Index = 0
        selectedPath2Index = if (path2Targets.isEmpty()) -1 else 0
        relabelPath2Targets()
        if (::path2Panel.isInitialized) {
            path2Panel.visibility = if (isPath2PanelOpen) View.VISIBLE else View.GONE
        }
        updatePath2Status()
    }

    private fun togglePath2Panel() {
        if (runState != RunState.IDLE) return
        isPath2PanelOpen = !isPath2PanelOpen
        applyToolbarVisibility()
        updatePath2Status()
    }

    private fun togglePath2Enabled() {
        if (runState != RunState.IDLE) return
        if (!path2Config.enabled && path2Targets.isEmpty()) {
            Toast.makeText(service, "Tambahkan dulu minimal satu titik Jalur 2", Toast.LENGTH_SHORT).show()
            return
        }
        path2Config = path2Config.copy(enabled = !path2Config.enabled)
        path2Store.save(path2Config)
        updateControls()
    }

    private fun changePath2Minutes(delta: Int) {
        if (runState != RunState.IDLE) return
        path2Config = path2Config.copy(
            intervalMinutes = Path2Config.sanitizeMinutes(path2Config.intervalMinutes + delta)
        )
        path2Store.save(path2Config)
        updateControls()
    }

    private fun showPath2MinutesDialog() {
        if (runState != RunState.IDLE || delayDialog?.isShowing == true) return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(path2Config.intervalMinutes.toString())
            setSelectAllOnFocus(true)
        }
        val minimum = Path2Config.MIN_INTERVAL_MINUTES
        val maximum = Path2Config.MAX_INTERVAL_MINUTES
        // showSettingsDialog memasang TYPE_ACCESSIBILITY_OVERLAY. Dialog dari context
        // service tanpa tipe itu ditolak sistem (BadTokenException) di perangkat.
        showSettingsDialog(
            title = "Waktu Jalur 2 (menit)",
            message = "Jalur 2 dijalankan setelah makro pertama berjalan selama $minimum–$maximum menit.",
            input = input
        ) {
            val minutes = input.text.toString().trim().toIntOrNull()
            if (minutes == null || minutes !in minimum..maximum) {
                input.error = "Gunakan $minimum–$maximum"
                false
            } else {
                path2Config = path2Config.copy(intervalMinutes = minutes)
                path2Store.save(path2Config)
                updateControls()
                true
            }
        }
    }

    private fun addNewPath2Target() {
        if (runState != RunState.IDLE) return
        if (path2Targets.size >= Path2Config.MAX_POINTS) {
            Toast.makeText(service, "Jalur 2 maksimal ${Path2Config.MAX_POINTS} titik", Toast.LENGTH_SHORT).show()
            return
        }
        val index = path2Targets.size
        val normalizedX = (0.18 + ((index * 0.61803398875) % 0.62)).toFloat().coerceIn(0.08f, 0.92f)
        val normalizedY = (0.24 + ((index * 0.38196601125) % 0.54)).toFloat().coerceIn(0.1f, 0.9f)
        val delay = currentProfile?.defaultDelayMs ?: ClickPreferences.DEFAULT_DELAY_MS
        val added = addPath2Target(
            MacroPoint(
                id = System.nanoTime(),
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                delayMs = ClickPreferences.sanitizeDelay(delay)
            )
        )
        if (added && !path2Config.enabled) {
            path2Config = path2Config.copy(enabled = true)
            path2Store.save(path2Config)
            Toast.makeText(service, "Jalur 2 diaktifkan otomatis", Toast.LENGTH_SHORT).show()
            updateControls()
        }
    }

    private fun addPath2Target(point: MacroPoint, persist: Boolean = true): Boolean {
        if (runState != RunState.IDLE) return false
        if (path2Targets.size >= Path2Config.MAX_POINTS) return false

        val target = MacroTargetView(service).apply {
            sequenceNumber = path2Targets.size + 1
            isPath2Marker = true
            contentDescription = "Titik Jalur 2 nomor ${path2Targets.size + 1}, geser untuk memindahkan"
            visibility = if (markersHidden) View.INVISIBLE else View.VISIBLE
        }
        val params = createMarkerLayoutParams(point.normalizedX, point.normalizedY)
        if (markersHidden) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val holder = TargetHolder(
            id = point.id,
            view = target,
            params = params,
            normalizedX = point.normalizedX.coerceIn(0f, 1f),
            normalizedY = point.normalizedY.coerceIn(0f, 1f),
            delayMs = ClickPreferences.sanitizeDelay(point.delayMs),
            actionType = PointActionType.TAP,
            actionDurationMs = MacroPoint.MIN_ACTION_DURATION_MS,
            repeatCount = 1,
            endNormalizedX = point.normalizedX,
            endNormalizedY = point.normalizedY
        )
        attachPath2DragHandler(holder)
        if (runCatching { windowManager.addView(target, params) }.isFailure) {
            Toast.makeText(service, "Titik Jalur 2 tidak dapat ditambahkan oleh sistem", Toast.LENGTH_SHORT).show()
            return false
        }
        path2Targets.add(holder)
        selectedPath2Index = path2Targets.lastIndex
        relabelPath2Targets()
        if (persist) savePath2()
        updateControls()
        return true
    }

    private fun removeSelectedPath2Target() {
        if (runState != RunState.IDLE) return
        if (path2Targets.isEmpty()) {
            Toast.makeText(service, "Jalur 2 belum punya titik", Toast.LENGTH_SHORT).show()
            return
        }
        val index = selectedPath2Index.coerceIn(0, path2Targets.lastIndex)
        if (!removeViewSafely(path2Targets[index].view)) {
            Toast.makeText(service, "Titik Jalur 2 gagal dihapus. Coba tutup editor.", Toast.LENGTH_SHORT).show()
            return
        }
        path2Targets.removeAt(index)
        selectedPath2Index = path2Targets.lastIndex
        relabelPath2Targets()
        if (path2Targets.isEmpty()) {
            path2Config = path2Config.copy(enabled = false, points = emptyList())
            path2Store.save(path2Config)
        } else {
            savePath2()
        }
        updateControls()
    }

    private fun relabelPath2Targets() {
        path2Targets.forEachIndexed { index, holder ->
            holder.view.sequenceNumber = index + 1
            holder.view.isSelectedPoint = index == selectedPath2Index
            holder.view.contentDescription = "Titik Jalur 2 nomor ${index + 1}, geser untuk memindahkan"
        }
    }

    private fun removeAllPath2Targets(): Boolean {
        var allRemoved = true
        val iterator = path2Targets.iterator()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            if (removeViewSafely(holder.view)) iterator.remove() else allRemoved = false
        }
        selectedPath2Index = -1
        return allRemoved
    }

    private fun attachPath2DragHandler(holder: TargetHolder) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        holder.view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val index = path2Targets.indexOf(holder)
                    if (index < 0) return@setOnTouchListener false
                    selectedPath2Index = index
                    relabelPath2Targets()
                    updateControls()
                    initialX = holder.params.x
                    initialY = holder.params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    holder.params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    holder.params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    clampTarget(holder)
                    runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holder.view.performClick()
                    updateNormalizedPosition(holder)
                    savePath2()
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

    private fun savePath2() {
        path2Targets.forEach { holder -> updateNormalizedPosition(holder) }
        val points = path2Targets.map { holder ->
            MacroPoint(
                id = holder.id,
                normalizedX = holder.normalizedX,
                normalizedY = holder.normalizedY,
                delayMs = holder.delayMs,
                actionType = PointActionType.TAP,
                actionDurationMs = MacroPoint.MIN_ACTION_DURATION_MS
            )
        }
        path2Config = path2Config.copy(points = points)
        path2Store.save(path2Config)
        if (::path2Button.isInitialized) path2Button.text = if (path2Config.enabled) "J2✓" else "J2"
        updatePath2Status()
    }

    private fun setPath2Touchable(touchable: Boolean): Boolean {
        var allUpdated = true
        val effectiveTouchable = touchable && !markersHidden
        val windowAlpha = if (effectiveTouchable) 1f else 0.7f
        path2Targets.forEach { holder ->
            holder.params.flags = touchFlags(holder.params.flags, effectiveTouchable)
            holder.params.alpha = windowAlpha
            if (runCatching { windowManager.updateViewLayout(holder.view, holder.params) }.isFailure) {
                allUpdated = false
            }
        }
        return allUpdated
    }

    private fun findPath2ToolbarOverlap(): Int? {
        for (index in path2Targets.indices) {
            val holder = path2Targets[index]
            if (holder.view.width <= 0 || holder.view.height <= 0) continue
            val location = IntArray(2)
            holder.view.getLocationOnScreen(location)
            val centerX = location[0] + holder.view.width / 2f
            val centerY = location[1] + holder.view.height / 2f
            if (isPointUnderToolbar(centerX, centerY)) return index + 1
        }
        return null
    }

    private fun updatePath2Status(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!::path2StatusLabel.isInitialized) return
        // Tidak ada yang melihat label saat panel tertutup; lewati agar tick 500 ms
        // tidak membuat teks baru terus-menerus selama sesi berjam-jam.
        if (!isPath2PanelOpen || isToolbarCollapsed) return
        val text = when {
            !path2Config.enabled -> "J2 mati • ${path2Targets.size} titik"
            path2Targets.isEmpty() -> "J2 aktif • titik masih kosong"
            runState == RunState.PATH2 -> "J2 jalan • T${path2Index + 1}/${path2Targets.size}"
            runState == RunState.RUNNING ->
                "J2 ${path2Targets.size} titik • sisa ${Path2Format.formatRemaining(path2Schedule.remainingMs(nowMs, path2Config.intervalMs))}"
            else -> "J2 ${path2Targets.size} titik • tiap ${path2Config.intervalMinutes} mnt"
        }
        if (text != lastPath2Status) {
            lastPath2Status = text
            path2StatusLabel.text = text
        }
    }

    private fun path2ResumeClock() {
        if (!path2Config.enabled || path2Targets.isEmpty()) {
            updatePath2Status()
            return
        }
        path2Schedule.resume(SystemClock.elapsedRealtime())
        handler.removeCallbacks(path2Tick)
        handler.postDelayed(path2Tick, PATH2_TICK_MS)
        updatePath2Status()
    }

    private fun beginPath2() {
        if (runState != RunState.RUNNING || path2Targets.isEmpty()) return
        handler.removeCallbacksAndMessages(null)
        path2Schedule.pause(SystemClock.elapsedRealtime())
        runState = RunState.PATH2
        path2Index = 0
        // Jalur 1 akan dimulai lagi dari titik 1 setelah Jalur 2 selesai.
        currentIndex = 0
        currentRepeatIndex = 0
        isGestureInFlight = false
        targets.forEach {
            it.view.isActivePoint = false
            it.endView?.isActivePoint = false
            it.view.isMacroRunning = true
        }
        path2Targets.forEach {
            it.view.isActivePoint = false
            it.view.isMacroRunning = true
        }
        MacroDiagnostics.record(
            service, "jalur2_start",
            "points=${path2Targets.size} setiapMenit=${path2Config.intervalMinutes}"
        )
        updateControls()
        val generation = runGeneration
        handler.postDelayed({ executePath2Point(generation) }, OVERLAY_FLAG_SETTLE_MS)
    }

    private fun executePath2Point(generation: Long) {
        if (runState != RunState.PATH2 || generation != runGeneration) return
        if (path2Targets.isEmpty()) {
            finishPath2(generation)
            return
        }
        val frame = screenFrame()
        if (runScreenFrame != frame) {
            stopWithMessage("Ukuran atau orientasi layar berubah. Periksa marker sebelum memulai lagi.")
            return
        }
        val point = path2Targets[path2Index]
        if (!isMarkerAligned(point.view, point.normalizedX, point.normalizedY, frame)) {
            MacroDiagnostics.record(service, "jalur2_alignment_rejected", "point=${path2Index + 1}")
            stopWithMessage("Posisi marker Jalur 2 tidak cocok dengan layar. Periksa titik, lalu mulai lagi.")
            return
        }
        path2Targets.forEach { it.view.isActivePoint = false }
        point.view.isActivePoint = true
        updateControls()

        val location = IntArray(2)
        point.view.getLocationOnScreen(location)
        val (x, y) = GestureSafety.clampToFrame(
            frame,
            location[0] + point.view.width / 2f,
            location[1] + point.view.height / 2f
        )
        if (!GestureSafety.isValidGesture(x, y, x, y, point.actionDurationMs)) {
            stopWithMessage("Koordinat Jalur 2 tidak valid. Periksa marker.")
            return
        }
        if (gestureTouchesToolbar(x, y, x, y, false)) {
            stopWithMessage("Toolbar menutupi titik Jalur 2. Pindahkan toolbar, lalu mulai lagi.")
            return
        }

        isGestureInFlight = true
        val accepted = service.performAction(
            actionType = PointActionType.TAP,
            startX = x,
            startY = y,
            endX = x,
            endY = y,
            durationMs = point.actionDurationMs,
            onCompleted = {
                isGestureInFlight = false
                if (runState != RunState.PATH2 || generation != runGeneration) return@performAction
                point.view.isActivePoint = false
                if (path2Index >= path2Targets.lastIndex) {
                    finishPath2(generation)
                    return@performAction
                }
                path2Index++
                updateControls()
                handler.postDelayed({ executePath2Point(generation) }, point.delayMs)
            },
            onCancelled = {
                isGestureInFlight = false
                if (runState == RunState.PATH2 && generation == runGeneration) {
                    MacroDiagnostics.record(
                        service, "jalur2_gesture_cancelled",
                        "point=${path2Index + 1}"
                    )
                    stopWithMessage("Gesture Jalur 2 dibatalkan oleh sistem")
                }
            }
        )
        if (!accepted) {
            isGestureInFlight = false
            MacroDiagnostics.record(service, "jalur2_gesture_rejected", "point=${path2Index + 1}")
            stopWithMessage("Sistem menolak gesture Jalur 2. Macro dihentikan.")
        }
    }

    private fun finishPath2(generation: Long) {
        if (runState != RunState.PATH2 || generation != runGeneration) return
        path2Targets.forEach {
            it.view.isActivePoint = false
            it.view.isMacroRunning = false
        }
        path2Index = 0
        currentIndex = 0
        currentRepeatIndex = 0
        isGestureInFlight = false
        // Jalur 1 kembali berjalan; hitungan waktunya dimulai dari nol lagi.
        path2Schedule.reset()
        path2Schedule.resume(SystemClock.elapsedRealtime())
        runState = RunState.RUNNING
        MacroDiagnostics.record(service, "jalur2_finish", "resumeFromPoint=1")
        updateControls()
        handler.removeCallbacks(path2Tick)
        handler.postDelayed(path2Tick, PATH2_TICK_MS)
        handler.postDelayed({ executeCurrentPoint(generation) }, PATH2_RESUME_GAP_MS)
    }

    private fun toggleToolbar() {
        isToolbarCollapsed = !isToolbarCollapsed
        applyToolbarVisibility()
    }

    private fun attachToolbarDragHandler(handle: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (params != null && controls != null) {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        moved = moved || kotlin.math.abs(deltaX) > dp(4f) || kotlin.math.abs(deltaY) > dp(4f)
                        params.x = initialX + deltaX.roundToInt()
                        params.y = initialY + deltaY.roundToInt()
                        clampToolbarParams(params, controls)
                        runCatching { windowManager.updateViewLayout(controls, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) handle.performClick()
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    holder.params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    holder.params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    clampTarget(holder)
                    runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holder.view.performClick()
                    updateNormalizedPosition(holder)
                    persistProfile()
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    clampParams(params)
                    runCatching { windowManager.updateViewLayout(endpoint, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    endpoint.performClick()
                    updateEndpointNormalizedPosition(holder)
                    persistProfile()
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
        val size = dp(TARGET_TOUCH_SIZE_DP)
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            val frame = screenFrame()
            x = MarkerCoordinates.localLeft(normalizedX, frame, size)
            y = MarkerCoordinates.localTop(normalizedY, frame, size)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun applyNormalizedPosition(holder: TargetHolder) {
        val frame = screenFrame()
        holder.params.x = MarkerCoordinates.localLeft(holder.normalizedX, frame, holder.params.width)
        holder.params.y = MarkerCoordinates.localTop(holder.normalizedY, frame, holder.params.height)
        clampTarget(holder)
    }

    private fun updateNormalizedPosition(holder: TargetHolder) {
        val frame = screenFrame()
        holder.normalizedX = MarkerCoordinates.normalizedX(holder.params.x + holder.params.width / 2f, frame)
        holder.normalizedY = MarkerCoordinates.normalizedY(holder.params.y + holder.params.height / 2f, frame)
    }

    private fun updateEndpointNormalizedPosition(holder: TargetHolder) {
        val params = holder.endParams ?: return
        val frame = screenFrame()
        holder.endNormalizedX = MarkerCoordinates.normalizedX(params.x + params.width / 2f, frame)
        holder.endNormalizedY = MarkerCoordinates.normalizedY(params.y + params.height / 2f, frame)
    }

    private fun applyEndpointNormalizedPosition(holder: TargetHolder) {
        val params = holder.endParams ?: return
        val frame = screenFrame()
        params.x = MarkerCoordinates.localLeft(holder.endNormalizedX, frame, params.width)
        params.y = MarkerCoordinates.localTop(holder.endNormalizedY, frame, params.height)
        clampParams(params)
    }

    private fun clampTarget(holder: TargetHolder) {
        clampParams(holder.params)
    }

    private fun clampParams(params: WindowManager.LayoutParams) {
        val bounds = displayBounds()
        params.x = params.x.coerceIn(-params.width / 2, bounds.width() - params.width / 2)
        params.y = params.y.coerceIn(-params.height / 2, bounds.height() - params.height / 2)
    }

    private fun clampToolbarParams(params: WindowManager.LayoutParams, controls: View) {
        val bounds = displayBounds()
        val width = controls.width.takeIf { it > 0 } ?: params.width.takeIf { it > 0 } ?: dp(TOOLBAR_WIDTH_DP)
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

    private fun findToolbarOverlap(frame: ScreenFrame): Int? {
        targets.forEachIndexed { index, holder ->
            val location = IntArray(2)
            holder.view.getLocationOnScreen(location)
            val (x, y) = GestureSafety.clampToFrame(
                frame,
                location[0] + holder.view.width / 2f,
                location[1] + holder.view.height / 2f
            )
            if (isPointUnderToolbar(x, y)) return index + 1
            val endView = holder.endView
            if (holder.actionType == PointActionType.SWIPE && endView != null) {
                val endLocation = IntArray(2)
                endView.getLocationOnScreen(endLocation)
                val (endX, endY) = GestureSafety.clampToFrame(
                    frame,
                    endLocation[0] + endView.width / 2f,
                    endLocation[1] + endView.height / 2f
                )
                if (isPointUnderToolbar(endX, endY)) return index + 1
            }
        }
        return null
    }

    private fun gestureTouchesToolbar(x: Float, y: Float, endX: Float, endY: Float, swipe: Boolean): Boolean {
        if (isPointUnderToolbar(x, y)) return true
        if (!swipe) return false
        if (isPointUnderToolbar(endX, endY)) return true
        val controls = controlsView ?: return false
        if (!controls.isAttachedToWindow || controls.width <= 0 || controls.height <= 0) return false
        val location = IntArray(2)
        controls.getLocationOnScreen(location)
        return GestureSafety.segmentIntersectsRect(
            x, y, endX, endY,
            location[0].toFloat(), location[1].toFloat(),
            (location[0] + controls.width).toFloat(), (location[1] + controls.height).toFloat()
        )
    }

    private fun isPointUnderToolbar(x: Float, y: Float): Boolean {
        val controls = controlsView ?: return false
        if (!controls.isAttachedToWindow || controls.width <= 0 || controls.height <= 0) return false
        val location = IntArray(2)
        controls.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + controls.width &&
            y >= location[1] && y <= location[1] + controls.height
    }

    private fun isMarkerAligned(view: View?, normalizedX: Float, normalizedY: Float, frame: ScreenFrame): Boolean {
        if (view == null || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return MarkerCoordinates.isAligned(
            frame, normalizedX, normalizedY, location[0], location[1], view.width, view.height
        )
    }

    private fun screenFrame(): ScreenFrame {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return ScreenFrame(
                bounds.left, bounds.top,
                bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1), rotation
            )
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return ScreenFrame(0, 0, metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1), rotation)
    }

    /** Local layout/clamp bounds. Absolute screen validation uses screenFrame(). */
    private fun displayBounds(): Rect {
        val frame = screenFrame()
        return Rect(0, 0, frame.width, frame.height)
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
        textSize = 15f
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(2f), 0, dp(2f), 0)
        background = transparentButtonBackground()
        setOnClickListener { action() }
    }

    private fun settingButton(value: String, description: String, action: () -> Unit) = Button(service).apply {
        text = value
        textSize = 10f
        isAllCaps = false
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(2f), 0, dp(2f), 0)
        background = transparentButtonBackground()
        setOnClickListener { action() }
    }

    private fun delayButton(textValue: String, description: String, deltaMs: Long) = Button(service).apply {
        text = textValue
        textSize = 11f
        isAllCaps = false
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
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

    private fun stopBackground() = GradientDrawable().apply {
        setColor(Color.rgb(220, 38, 38))
        cornerRadius = dp(8f).toFloat()
        setStroke(dp(1f), Color.WHITE)
    }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Color.argb(242, 15, 23, 42))
        cornerRadius = dp(12f).toFloat()
        setStroke(dp(1f), Color.argb(100, 255, 255, 255))
    }

    private fun removeViewSafely(view: View): Boolean {
        if (!view.isAttachedToWindow) return true
        return runCatching { windowManager.removeViewImmediate(view) }.isSuccess || !view.isAttachedToWindow
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

    private enum class RunState { IDLE, COUNTDOWN, RUNNING, PATH2 }

    companion object {
        private const val TOOLBAR_WIDTH_DP = 220f
        private const val TARGET_TOUCH_SIZE_DP = 44f
        private const val OVERLAY_FLAG_SETTLE_MS = 50L
        private const val REPEAT_GAP_MS = 100L
        private const val MANY_POINTS_WARNING = 20
        private const val PATH2_TICK_MS = 500L
        private const val PATH2_RESUME_GAP_MS = 120L
    }
}

private class ToolbarLayout(context: Context) : LinearLayout(context) {
    var onUnexpectedVisibilityChange: ((Int) -> Unit)? = null
    var onUnexpectedDetach: (() -> Unit)? = null

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        onUnexpectedVisibilityChange?.invoke(visibility)
    }

    override fun onDetachedFromWindow() {
        onUnexpectedDetach?.invoke()
        super.onDetachedFromWindow()
    }
}

private class MacroTargetView(context: Context) : View(context) {
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private val density = resources.displayMetrics.density
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
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isSelectedPoint: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isActivePoint: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isMacroRunning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isEndpoint: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isPath2Marker: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (if (isMacroRunning) 8f else 11f) * density
        fillPaint.color = when {
            isActivePoint -> Color.rgb(34, 197, 94)
            isSelectedPoint && isPath2Marker -> Color.rgb(217, 119, 6)
            isSelectedPoint -> Color.rgb(79, 70, 229)
            isPath2Marker -> Color.rgb(245, 158, 11)
            isEndpoint -> Color.rgb(14, 165, 233)
            else -> Color.rgb(239, 68, 68)
        }
        // Keep running markers below Android 12's 0.8 untrusted-touch opacity threshold.
        fillPaint.alpha = if (isMacroRunning) 170 else 235
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        val extent = 16f * density
        canvas.drawLine(centerX - extent, centerY, centerX - radius, centerY, strokePaint)
        canvas.drawLine(centerX + radius, centerY, centerX + extent, centerY, strokePaint)
        canvas.drawLine(centerX, centerY - extent, centerX, centerY - radius, strokePaint)
        canvas.drawLine(centerX, centerY + radius, centerX, centerY + extent, strokePaint)

        textPaint.textSize = when {
            isPath2Marker -> 8f * density
            sequenceNumber < 100 -> 10f * density
            else -> 8f * density
        }
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        val label = when {
            isEndpoint -> "${sequenceNumber}E"
            isPath2Marker -> "J$sequenceNumber"
            else -> sequenceNumber.toString()
        }
        canvas.drawText(label, centerX, baseline, textPaint)
    }
}
