package com.richclickermacro.smart

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import com.richclickermacro.data.MacroPoint
import com.richclickermacro.data.Path2Config
import com.richclickermacro.data.Path2Point
import com.richclickermacro.data.Path2Format
import com.richclickermacro.data.Path2Schedule
import com.richclickermacro.data.Path2Store
import com.richclickermacro.data.PointActionType
import com.richclickermacro.diagnostics.MacroDiagnostics
import com.richclickermacro.safety.GestureSafety
import com.richclickermacro.safety.MarkerCoordinates
import com.richclickermacro.safety.ScreenFrame
import kotlin.math.roundToInt

/**
 * Overlay editor and runner for Multi Smart Click.
 *
 * Separate from ClickOverlayController on purpose: the classic timed macro keeps
 * its own code path, so a regression here cannot change how it behaves.
 *
 * Important consequence of reading the screen: the accessibility screenshot also
 * captures our own windows. Markers are therefore hidden while sampling/running,
 * and any watched patch that would fall under the toolbar is refused instead of
 * silently reading our own UI.
 */
@SuppressLint("SetTextI18n", "RtlHardcoded")
class SmartOverlayController(
    private val service: AutoClickAccessibilityService,
    private val sampler: ScreenSampler,
    private val onProfileChanged: (SmartProfile) -> SmartProfile?
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val holders = mutableListOf<SmartHolder>()
    private val path2Markers = mutableListOf<Path2Holder>()
    private val path2Store = Path2Store(service)
    private val path2Schedule = Path2Schedule()
    private var path2Config = Path2Config()
    private var path2Index = 0
    private var path2RepeatIndex = 0
    private var selectedPath2Index = -1
    private var isPath2Open = false

    private var currentProfile: SmartProfile? = null
    /** When the current colour capture started, so a stuck latch can be released. */
    private var captureStartedAtMs = 0L
    private var toolbarHiddenForCapture = false
    private var toolbar: SmartToolbarLayout? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var advancedContent: LinearLayout? = null
    private var isAdvancedOpen = false
    private var isCollapsed = false
    private var selectedIndex = 0
    private var runState = SmartRunState.IDLE
    private var runGeneration = 0L
    private var engine: SmartEngine? = null
    private var runFrame: ScreenFrame? = null
    private var expectedPackage: String? = null
    private var capturingColor = false
    /** Picker sedang dipakai untuk menaruh titik Jalur 2, bukan titik smart biasa. */
    private var pickForPath2 = false
    /** Panel Jalur 2 dibuka lagi setelah penandaan selesai. */
    private var reopenPath2AfterPick = false
    private var activeDialog: AlertDialog? = null
    private var disposing = false
    private var lastNotice = ""
    private var unreadableScans = 0


    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var compactStopButton: Button
    private lateinit var addButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var advancedButton: Button
    private lateinit var statusLabel: TextView
    private lateinit var pointLabel: TextView
    private lateinit var colorSwatch: View
    private lateinit var guideLabel: TextView
    private lateinit var pointSettingsButton: Button
    private lateinit var runSettingsButton: Button
    private lateinit var testClickButton: Button
    private lateinit var path2Button: Button
    private lateinit var path2Panel: LinearLayout
    private lateinit var path2ToggleButton: Button
    private lateinit var path2MinutesButton: Button
    private lateinit var path2StatusLabel: TextView
    private lateinit var addPath2Button: Button
    private lateinit var removePath2Button: Button
    private lateinit var path2ColorButton: Button
    private lateinit var path2ToleranceButton: Button
    private lateinit var path2HoldButton: Button
    private lateinit var path2RepeatButton: Button
    private var pickerView: View? = null

    fun show(profile: SmartProfile) {
        dismissDialog()
        stop("show_profile")
        if (!removeAllHolders()) {
            toast("Editor smart lama belum bisa dibersihkan.")
            return
        }
        currentProfile = SmartLimits.sanitizeProfile(profile)
        val points = currentProfile?.points.orEmpty()
        for (point in points) {
            if (!addHolder(point)) {
                removeAllHolders()
                currentProfile = null
                toast("Titik smart tidak dapat ditampilkan sepenuhnya.")
                return
            }
        }
        if (toolbar?.isAttachedToWindow != true) {
            toolbar?.let { runCatching { windowManager.removeViewImmediate(it) } }
            toolbar = null
            toolbarParams = null
            advancedContent = null
        }
        loadPath2()
        if (toolbar == null && !addToolbar()) {
            removeAllHolders()
            removeAllPath2Markers()
            currentProfile = null
            return
        }
        selectedIndex = 0
        updateUi()
        service.notifyTileStateChanged()
        toast("Multi Smart Click aktif: ${profile.name}")
    }

    fun isRunning(): Boolean = runState != SmartRunState.IDLE

    fun hasLoadedProfile(): Boolean =
        currentProfile != null && toolbar?.isAttachedToWindow == true

    fun overlayWindowCount(): Int {
        var count = holders.size + path2Markers.size
        if (toolbar != null) count++
        if (pickerView != null) count++
        return count
    }

    fun toggleFromTile(): Boolean {
        if (currentProfile == null) return false
        if (runState == SmartRunState.IDLE) start() else stop("tile_stop")
        return true
    }

    fun stop(reason: String = "stop_requested") {
        // Opening or closing the smart editor while the classic macro runs must not
        // cancel its in-flight gesture or stop its memory sampling.
        val wasRunning = runState != SmartRunState.IDLE
        if (wasRunning) MacroDiagnostics.record(service, "smart_stop", reason)
        runGeneration++
        runState = SmartRunState.IDLE
        engine = null
        unreadableScans = 0
        runFrame = null
        expectedPackage = null
        handler.removeCallbacksAndMessages(null)
        // A capture in flight loses its callbacks above; clear the latch so the
        // editor does not come back with permanently disabled buttons.
        capturingColor = false
        toolbarHiddenForCapture = false
        path2Schedule.pause(SystemClock.elapsedRealtime())
        path2Index = 0
        removePicker()
        toolbar?.visibility = View.VISIBLE
        if (wasRunning) service.cancelActiveAction()
        sampler.reset()
        setMarkersVisible(true)
        setMarkersTouchable(true)
        setPath2Visible(true)
        setPath2Touchable(true)
        applyKeepScreenOn(false)
        updateUi()
        if (wasRunning) service.stopMemorySampling()
        service.notifyTileStateChanged()
    }

    fun onApplicationWindowChanged(packageName: String) {
        val expected = expectedPackage ?: return
        if (isRunning() && currentProfile?.stopOnAppChange == true && packageName != expected) {
            stopWithNotice("Aplikasi tujuan berganti. Smart click dihentikan.", "app_change")
        }
    }

    fun onConfigurationChanged() {
        val frame = runCatching { screenFrame() }.getOrNull()
        if (isRunning() && (frame == null || frame != runFrame)) {
            stopWithNotice("Layar berubah. Periksa titik smart lalu mulai lagi.", "screen_change")
        }
        holders.forEach { holder ->
            applyPosition(holder)
            runCatching { windowManager.updateViewLayout(holder.watchView, holder.watchParams) }
        }
        path2Markers.forEach { holder ->
            applyPath2Position(holder)
            runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
        }
        toolbar?.post { clampToolbar() }
    }

    fun onSystemMemoryPressure(reason: String) {
        if (isRunning()) {
            MacroDiagnostics.record(service, "smart_memory_pressure_stop", reason)
            stopWithNotice("Android melaporkan RAM kritis. Smart click dihentikan.", "memory_pressure")
        }
    }

    fun updateProfileSettings(profile: SmartProfile) {
        val current = currentProfile ?: return
        if (current.name == profile.name) {
            currentProfile = SmartLimits.sanitizeProfile(profile.copy(points = current.points))
            if (isRunning()) applyKeepScreenOn(profile.keepScreenOn)
            updateUi()
        }
    }

    fun closeIfProfile(name: String) {
        if (currentProfile?.name == name) destroy()
    }

    fun destroy() {
        disposing = true
        dismissDialog()
        cancelPick()
        stop("editor_closed")
        if (!removeAllHolders()) {
            disposing = false
            toast("Sebagian overlay smart belum bisa ditutup. Coba lagi.")
            return
        }
        if (!removeAllPath2Markers()) {
            disposing = false
            toast("Sebagian overlay Jalur 2 belum bisa ditutup. Coba lagi.")
            return
        }
        toolbar?.let { view ->
            if (removeViewSafely(view)) {
                toolbar = null
                toolbarParams = null
                advancedContent = null
            }
        }
        currentProfile = null
        selectedIndex = 0
        selectedPath2Index = -1
        isPath2Open = false
        path2Schedule.reset()
        isAdvancedOpen = false
        isCollapsed = false
        disposing = false
        service.notifyTileStateChanged()
    }

    // ---------------------------------------------------------------- run loop

    private fun start() {
        val profile = currentProfile ?: return
        if (holders.isEmpty()) {
            toast("Tambahkan minimal satu titik smart.")
            return
        }
        sampler.capabilityProblem()?.let {
            toast(it)
            MacroDiagnostics.record(service, "smart_capability_blocked", it)
            return
        }
        holders.forEach { syncFromViews(it) }
        val runnable = profile.copy(points = holders.map(::toPoint))
        SmartGeometry.validationProblem(runnable)?.let {
            toast(it)
            return
        }
        val frame = runCatching { screenFrame() }.getOrNull()
        if (frame == null) {
            toast("Ukuran layar tidak terbaca.")
            return
        }
        // A patch under our own toolbar would read the toolbar, not the game.
        val toolbarBox = toolbarNormalizedBox()
        if (toolbarBox != null) {
            val radius = PATCH_RADIUS_NORMALIZED
            val blocked = runnable.points.filter { it.enabled }.firstOrNull { point ->
                SmartGeometry.isPatchObscured(
                    SmartGeometry.patchBox(point.watchX, point.watchY, radius),
                    listOf(toolbarBox)
                )
            }
            if (blocked != null) {
                toast("Titik ${blocked.label} tertutup toolbar. Pindahkan toolbar atau titiknya.")
                return
            }
        }
        val duplicated = findTooClosePair(runnable)
        if (duplicated != null) {
            toast("Titik $duplicated terlalu berdekatan dan akan membaca area yang sama.")
            return
        }
        if (path2Config.enabled) {
            val blockedPath2 = findPath2UnderToolbar()
            if (blockedPath2 != null) {
                toast("Titik Jalur 2 nomor $blockedPath2 tertutup toolbar. Pindahkan toolbar dulu.")
                return
            }
        }

        val targetPackage = if (runnable.stopOnAppChange) {
            service.foregroundPackage() ?: run {
                toast("Aplikasi tujuan belum terdeteksi. Buka aplikasi tujuan setelah editor aktif, lalu coba lagi.")
                return
            }
        } else null
        // Never let both runners inject gestures at the same time.
        service.requestExclusiveRun(smart = true)
        currentProfile = runnable
        persist()
        runFrame = frame
        expectedPackage = targetPackage
        engine = SmartEngine(runnable).also { it.start(SystemClock.uptimeMillis()) }
        sampler.reset()
        val generation = ++runGeneration
        runState = if (runnable.countdownSeconds > 0) SmartRunState.COUNTDOWN else SmartRunState.RUNNING
        // Hide our markers so the screenshot contains only the target app.
        setMarkersTouchable(false)
        setMarkersVisible(false)
        // Marker Jalur 2 juga jendela kita: harus hilang agar tidak ikut terekam.
        setPath2Touchable(false)
        setPath2Visible(false)
        path2Schedule.reset()
        path2Index = 0
        applyKeepScreenOn(runnable.keepScreenOn)
        service.startMemorySampling()
        MacroDiagnostics.record(
            service,
            "smart_start",
            "points=${runnable.points.count { it.enabled }} scan=${runnable.scanIntervalMs} " +
                "cooldown=${runnable.cooldownMs} confirm=${runnable.confirmSamples} target=$expectedPackage"
        )
        updateUi()
        service.notifyTileStateChanged()
        if (runnable.countdownSeconds > 0) {
            countdown(runnable.countdownSeconds, generation)
        } else {
            path2ResumeClock()
            scheduleScan(generation, 0L)
        }
    }

    private fun countdown(remaining: Int, generation: Long) {
        if (runState != SmartRunState.COUNTDOWN || generation != runGeneration) return
        if (remaining <= 0) {
            runState = SmartRunState.RUNNING
            path2ResumeClock()
            updateUi()
            scheduleScan(generation, 0L)
            return
        }
        statusLabel.text = "Mulai $remaining…"
        handler.postDelayed({ countdown(remaining - 1, generation) }, 1_000L)
    }

    private fun scheduleScan(generation: Long, delayMs: Long) {
        if (generation != runGeneration || runState != SmartRunState.RUNNING) return
        handler.postDelayed({ scan(generation) }, delayMs)
    }

    private fun scan(generation: Long) {
        if (generation != runGeneration || runState != SmartRunState.RUNNING) return
        // Jalur 2 diperiksa di detak loop smart: murni waktu, tanpa membaca warna.
        if (path2Due()) {
            beginPath2(generation)
            return
        }
        val profile = currentProfile ?: return
        val active = engine ?: return
        val frame = runCatching { screenFrame() }.getOrNull()
        if (frame == null || frame != runFrame) {
            stopWithNotice("Layar berubah. Smart click dihentikan.", "screen_change")
            return
        }
        val points = active.enabledPoints()
        val patches = points.associate { it.id to (it.watchX to it.watchY) }
        val forbidden = listOfNotNull(toolbarNormalizedBox())
        sampler.sample(patches, forbidden) { result, error ->
            if (generation != runGeneration || runState != SmartRunState.RUNNING) return@sample
            if (result == null) {
                if (error == "paced" || error == "busy") {
                    scheduleScan(generation, ScreenSampler.MIN_REQUEST_GAP_MS)
                    return@sample
                }
                if (sampler.failureCount() >= ScreenSampler.MAX_CONSECUTIVE_FAILURES) {
                    stopWithNotice(
                        error ?: "Layar tidak dapat dibaca berulang kali. Smart click dihentikan.",
                        "sample_failed"
                    )
                } else {
                    scheduleScan(generation, profile.scanIntervalMs)
                }
                return@sample
            }
            // If the capture is scaled or cropped relative to the display, a normalized
            // position no longer means the same place in the image and on the screen.
            // Stop instead of clicking somewhere the user never chose.
            val captureW = sampler.lastCaptureWidth
            val captureH = sampler.lastCaptureHeight
            if (captureW > 0 && captureH > 0 &&
                !SmartGeometry.captureMatchesFrame(
                    captureW, captureH, frame.width, frame.height, CAPTURE_FRAME_TOLERANCE
                )
            ) {
                MacroDiagnostics.record(
                    service, "smart_capture_frame_mismatch",
                    "capture=${captureW}x$captureH frame=${frame.width}x${frame.height}"
                )
                stopWithNotice(
                    "Ukuran tangkapan layar tidak cocok dengan layar, jadi posisi klik bisa meleset. " +
                        "Smart click dihentikan. Coba matikan lalu nyalakan lagi layanan aksesibilitas.",
                    "capture_frame_mismatch"
                )
                return@sample
            }
            // Every patch unreadable while the capture itself succeeded usually means
            // our own toolbar was dragged over the watched areas.
            if (points.isNotEmpty() && points.all { result[it.id] == null }) {
                unreadableScans++
                if (unreadableScans >= MAX_UNREADABLE_SCANS) {
                    stopWithNotice(
                        "Area yang diawasi tidak terbaca. Kemungkinan tertutup toolbar. Pindahkan toolbar lalu mulai lagi.",
                        "patch_unreadable"
                    )
                    return@sample
                }
            } else {
                unreadableScans = 0
            }
            when (val decision = active.evaluate(SystemClock.uptimeMillis(), result)) {
                is SmartEngine.Decision.Wait -> {
                    updateUi()
                    scheduleScan(generation, profile.scanIntervalMs)
                }
                is SmartEngine.Decision.Stop -> {
                    MacroDiagnostics.record(service, "smart_engine_stop", decision.reason)
                    stopWithNotice(decision.message, decision.reason)
                }
                is SmartEngine.Decision.Fire -> dispatch(decision, generation)
            }
        }
    }

    private fun dispatch(decision: SmartEngine.Decision.Fire, generation: Long) {
        val profile = currentProfile ?: return
        val frame = runFrame ?: return
        val point = decision.point
        val holder = holders.firstOrNull { it.id == point.id }
        if (holder == null) {
            stopWithNotice("Titik smart hilang dari editor.", "holder_missing")
            return
        }
        val x = frame.centerX(point.effectiveTapX())
        val y = frame.centerY(point.effectiveTapY())
        val (safeX, safeY) = GestureSafety.clampToFrame(frame, x, y)
        if (!GestureSafety.isValidGesture(safeX, safeY, safeX, safeY, point.holdMs)) {
            stopWithNotice("Koordinat titik smart tidak valid.", "invalid_coordinate")
            return
        }
        if (isUnderToolbar(safeX, safeY)) {
            stopWithNotice("Toolbar menutupi titik klik. Pindahkan toolbar.", "toolbar_overlap")
            return
        }
        statusLabel.text = if (decision.repeatTotal > 1) {
            "Klik ${point.label} ${decision.repeatIndex}/${decision.repeatTotal}"
        } else {
            "Klik ${point.label}"
        }
        MacroDiagnostics.record(
            service,
            "smart_fire",
            "point=${point.label} repeat=${decision.repeatIndex}/${decision.repeatTotal} " +
                "condition=${point.condition} color=${ColorMatcher.toHex(decision.sampleColor)} d=${decision.distance}"
        )
        val accepted = service.performAction(
            actionType = if (point.action == SmartAction.TAP) PointActionType.TAP else PointActionType.LONG_PRESS,
            startX = safeX,
            startY = safeY,
            endX = safeX,
            endY = safeY,
            durationMs = if (point.action == SmartAction.TAP) 40L else point.holdMs,
            onCompleted = {
                if (generation == runGeneration && runState == SmartRunState.RUNNING) {
                    updateUi()
                    val active = engine
                    if (active != null && active.hasPendingBurst()) {
                        // Remaining repeats of the same condition are sent back-to-back:
                        // re-reading the screen here would fail because our own click
                        // already changed it.
                        handler.postDelayed({ drainRepeat(generation) }, REPEAT_GAP_MS)
                    } else {
                        // The click just changed the screen. Re-read immediately and let
                        // the sampler enforce the platform pacing; sleeping a full scan
                        // interval here only added dead time between clicks.
                        scheduleScan(generation, 0L)
                    }
                }
            },
            onCancelled = {
                if (generation == runGeneration && runState == SmartRunState.RUNNING) {
                    MacroDiagnostics.record(service, "smart_gesture_cancelled", "point=${point.label}")
                    stopWithNotice("Gesture dibatalkan sistem. Smart click dihentikan.", "gesture_cancelled")
                }
            }
        )
        if (!accepted) {
            MacroDiagnostics.record(service, "smart_gesture_rejected", "point=${point.label}")
            engine?.onActionFailed(point.id, SystemClock.uptimeMillis())
            stopWithNotice("Sistem menolak gesture. Smart click dihentikan.", "gesture_rejected")
        }
    }

    /** Sends the next click of an in-progress repeat burst without a new screenshot. */
    private fun drainRepeat(generation: Long) {
        if (generation != runGeneration || runState != SmartRunState.RUNNING) return
        val active = engine ?: return
        when (val decision = active.nextRepeat(SystemClock.uptimeMillis())) {
            is SmartEngine.Decision.Fire -> dispatch(decision, generation)
            is SmartEngine.Decision.Stop -> {
                MacroDiagnostics.record(service, "smart_engine_stop", decision.reason)
                stopWithNotice(decision.message, decision.reason)
            }
            is SmartEngine.Decision.Wait -> scheduleScan(generation, currentProfile?.scanIntervalMs ?: 450L)
        }
    }

    // ------------------------------------------------------- Jalur 2 (smart)
    // Rangkaian titik kedua khusus mode Multi Smart Click. Dipicu waktu, lalu tiap
    // titik menunggu warna pixel yang cocok sebelum diklik — sama seperti titik smart
    // biasa, bukan klik buta berdasarkan waktu.

    private fun loadPath2() {
        removeAllPath2Markers()
        path2Config = path2Store.load()
        path2Config.points.forEach { point -> addPath2Marker(point) }
        relabelPath2()
        path2Schedule.reset()
        path2Index = 0
        selectedPath2Index = if (path2Markers.isEmpty()) -1 else 0
        if (::path2Panel.isInitialized) {
            path2Panel.visibility = if (isPath2Open) View.VISIBLE else View.GONE
        }
    }

    private fun path2Due(): Boolean {
        if (!path2Config.enabled) return false
        if (path2Markers.none { it.point.isReady }) return false
        return path2Schedule.isDue(SystemClock.elapsedRealtime(), path2Config.intervalMs)
    }

    private fun path2ResumeClock() {
        if (!path2Config.enabled || path2Markers.isEmpty()) return
        path2Schedule.resume(SystemClock.elapsedRealtime())
    }

    private fun addPath2Marker(point: Path2Point): Boolean {
        val sanitized = Path2Point.sanitize(point)
        val view = SmartMarkerView(service).apply {
            isWatchMarker = true
            isPath2Marker = true
            label = "J${path2Markers.size + 1}"
            sampledColor = sanitized.targetColor
            isDisabledPoint = !sanitized.isReady
            contentDescription =
                "Titik Jalur 2 nomor ${path2Markers.size + 1}, geser untuk memindahkan"
            visibility = View.VISIBLE
        }
        val params = markerParams(sanitized.normalizedX, sanitized.normalizedY)
        val holder = Path2Holder(
            id = sanitized.id,
            view = view,
            params = params,
            normalizedX = sanitized.normalizedX,
            normalizedY = sanitized.normalizedY,
            point = sanitized
        )
        attachPath2Drag(holder)
        if (runCatching { windowManager.addView(view, params) }.isFailure) {
            toast("Titik Jalur 2 tidak dapat ditampilkan.")
            return false
        }
        path2Markers.add(holder)
        selectedPath2Index = path2Markers.lastIndex
        if (path2Markers.size == Path2Config.MANY_POINTS_WARNING) {
            MacroDiagnostics.record(service, "smart_path2_many_points", "points=${path2Markers.size}")
            toast(
                "Titik Jalur 2 sudah ${Path2Config.MANY_POINTS_WARNING}. Tiap marker menambah jendela " +
                    "grafis; tambah seperlunya agar RAM tidak penuh."
            )
        }
        return true
    }

    private fun addNewPath2Marker() {
        if (isRunning() || capturingColor || pickerView != null) return
        // Sama seperti titik smart biasa: titik diletakkan dengan mengetuk layar.
        // Menaruhnya otomatis di koordinat tetap membuat marker sering berada di
        // bawah toolbar sendiri, sehingga tidak terlihat dan warnanya tak terbaca.
        startPick(forPath2 = true)
    }

    /** Dipanggil setelah pengguna mengetuk layar untuk titik Jalur 2. */
    private fun restorePath2PanelAfterPick() {
        if (!reopenPath2AfterPick) return
        reopenPath2AfterPick = false
        if (::path2Panel.isInitialized && !isRunning()) {
            isPath2Open = true
            path2Panel.visibility = View.VISIBLE
            toolbar?.post { clampToolbar() }
        }
    }

    private fun placePickedPath2Point(nx: Float, ny: Float) {
        val point = Path2Point(id = System.nanoTime(), normalizedX = nx, normalizedY = ny)
        if (!addPath2Marker(point)) {
            updateUi()
            return
        }
        relabelPath2()
        savePath2()
        updateUi()
        // Langsung baca warna di titik itu supaya tidak ada langkah yang terlewat.
        capturePath2Color()
        restorePath2PanelAfterPick()
    }

    private fun removeSelectedPath2Marker() {
        if (isRunning() || capturingColor || pickerView != null) return
        if (path2Markers.isEmpty()) {
            toast("Jalur 2 belum punya titik.")
            return
        }
        val index = selectedPath2Index.coerceIn(0, path2Markers.lastIndex)
        if (!removeViewSafely(path2Markers[index].view)) {
            toast("Titik Jalur 2 gagal dihapus. Coba tutup editor.")
            return
        }
        path2Markers.removeAt(index)
        selectedPath2Index = path2Markers.lastIndex
        relabelPath2()
        savePath2()
        updateUi()
    }

    private fun relabelPath2() {
        path2Markers.forEachIndexed { index, holder ->
            holder.view.label = "J${index + 1}"
            holder.view.isSelectedPoint = index == selectedPath2Index
            holder.view.sampledColor = holder.point.targetColor
            holder.view.isDisabledPoint = !holder.point.isReady
            holder.view.contentDescription =
                "Titik Jalur 2 nomor ${index + 1}, geser untuk memindahkan"
        }
    }

    private fun removeAllPath2Markers(): Boolean {
        var allRemoved = true
        val iterator = path2Markers.iterator()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            if (removeViewSafely(holder.view)) iterator.remove() else allRemoved = false
        }
        selectedPath2Index = -1
        return allRemoved
    }

    private fun attachPath2Drag(holder: Path2Holder) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        holder.view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val index = path2Markers.indexOf(holder)
                    if (index < 0) return@setOnTouchListener false
                    selectedPath2Index = index
                    relabelPath2()
                    updateUi()
                    initialX = holder.params.x
                    initialY = holder.params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    holder.params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    holder.params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    clampMarker(holder.params)
                    runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    holder.view.performClick()
                    val moved = syncPath2FromView(holder)
                    if (moved) {
                        // Warna lama tidak lagi menggambarkan posisi yang baru.
                        holder.point = holder.point.copy(targetColor = null)
                        holder.view.sampledColor = null
                        holder.view.isDisabledPoint = true
                        toast("Posisi bergeser: ambil warna ulang untuk titik ini.")
                    }
                    savePath2()
                    updateUi()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    holder.params.x = initialX
                    holder.params.y = initialY
                    clampMarker(holder.params)
                    runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
                    true
                }
                else -> false
            }
        }
    }

    /** @return true bila posisi berubah cukup jauh sehingga warna lama tidak berlaku. */
    private fun syncPath2FromView(holder: Path2Holder): Boolean {
        val frame = screenFrame()
        val previousX = holder.normalizedX
        val previousY = holder.normalizedY
        holder.normalizedX = SmartGeometry.fromMarkerLeft(
            holder.params.x, holder.params.width, frame.left, frame.width
        )
        holder.normalizedY = SmartGeometry.fromMarkerTop(
            holder.params.y, holder.params.height, frame.top, frame.height
        )
        holder.point = holder.point.copy(
            normalizedX = holder.normalizedX,
            normalizedY = holder.normalizedY
        )
        return kotlin.math.abs(previousX - holder.normalizedX) > POSITION_EPSILON ||
            kotlin.math.abs(previousY - holder.normalizedY) > POSITION_EPSILON
    }

    private fun applyPath2Position(holder: Path2Holder) {
        val frame = screenFrame()
        holder.params.x = SmartGeometry.markerLeft(
            holder.normalizedX, frame.left, frame.width, holder.params.width
        )
        holder.params.y = SmartGeometry.markerTop(
            holder.normalizedY, frame.top, frame.height, holder.params.height
        )
        clampMarker(holder.params)
    }

    private fun savePath2() {
        val points = path2Markers.map { holder ->
            Path2Point.sanitize(
                holder.point.copy(normalizedX = holder.normalizedX, normalizedY = holder.normalizedY)
            )
        }
        path2Config = path2Config.copy(points = points)
        path2Store.save(path2Config)
        if (::path2Button.isInitialized) path2Button.text = if (path2Config.enabled) "J2✓" else "J2"
        updatePath2Status()
    }

    private fun setPath2Visible(visible: Boolean) {
        path2Markers.forEach { holder ->
            holder.view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Titik Jalur 2 diredupkan saat Jalur 2 mati: kelihatan, tapi jelas tidak aktif. */
    private fun applyPath2EnabledLook() {
        val alpha = if (path2Config.enabled) 1f else 0.45f
        path2Markers.forEach { holder -> holder.view.alpha = alpha }
    }

    private fun setPath2Touchable(touchable: Boolean) {
        path2Markers.forEach { holder ->
            holder.params.flags = touchFlags(holder.params.flags, touchable)
            holder.params.alpha = if (touchable) 1f else 0.7f
            runCatching { windowManager.updateViewLayout(holder.view, holder.params) }
        }
    }

    private fun findPath2UnderToolbar(): Int? {
        for (index in path2Markers.indices) {
            val holder = path2Markers[index]
            val frame = runCatching { screenFrame() }.getOrNull() ?: return null
            val x = frame.centerX(holder.normalizedX)
            val y = frame.centerY(holder.normalizedY)
            if (isUnderToolbar(x, y)) return index + 1
        }
        return null
    }

    private fun updatePath2Status() {
        if (!::path2StatusLabel.isInitialized) return
        if (!isPath2Open || isCollapsed) return
        val ready = path2Markers.count { it.point.isReady }
        val problem = runCatching { sampler.capabilityProblem() }.getOrNull()
        applyPath2EnabledLook()
        val text = when {
            // Keadaan saklar selalu disebut lebih dulu: OFF berarti Jalur 2 tidak akan
            // menyentuh macro biasa sama sekali.
            !path2Config.enabled && path2Markers.isEmpty() ->
                "Jalur 2 OFF • tekan + Titik J2 untuk mulai"
            !path2Config.enabled && ready == 0 ->
                "Jalur 2 OFF • ambil warna dulu (0/${path2Markers.size} siap)"
            !path2Config.enabled ->
                "Jalur 2 OFF • $ready/${path2Markers.size} siap • tekan J2 ON untuk menyalakan"
            path2Markers.isEmpty() -> "Jalur 2 ON • titik masih kosong"
            // Tanpa izin baca layar, warna tidak mungkin terbaca — sebutkan sebabnya
            // supaya panel tidak terlihat "mati" tanpa alasan.
            problem != null && ready == 0 -> "Jalur 2 ON • izin baca layar mati"
            runState == SmartRunState.PATH2 ->
                "Jalur 2 ON • jalan titik ${path2Index + 1}/${path2Markers.size} • J2 untuk stop"
            runState == SmartRunState.RUNNING ->
                "Jalur 2 ON • tiap ${path2Config.intervalMinutes} mnt • sisa " +
                    Path2Format.formatRemaining(
                        path2Schedule.remainingMs(SystemClock.elapsedRealtime(), path2Config.intervalMs)
                    )
            else -> "Jalur 2 ON • $ready/${path2Markers.size} siap • tiap ${path2Config.intervalMinutes} mnt"
        }
        if (path2StatusLabel.text != text) path2StatusLabel.text = text
    }

    private fun togglePath2Panel() {
        if (capturingColor || pickerView != null) return
        isPath2Open = !isPath2Open
        path2Panel.visibility = if (isPath2Open) View.VISIBLE else View.GONE
        path2Button.text = if (path2Config.enabled) "J2✓" else "J2"
        relabelPath2()
        toolbar?.post { clampToolbar() }
        updatePath2Status()
    }

    private fun togglePath2Enabled() = setPath2Enabled(!path2Config.enabled)

    /**
     * Saklar utama Jalur 2.
     *
     * Mematikan SELALU boleh — termasuk saat macro sedang berjalan atau saat Jalur 2
     * sendiri sedang bekerja — karena satu-satunya cara Jalur 2 bisa bentrok dengan
     * macro biasa adalah kalau ia tidak bisa dimatikan. Bila Jalur 2 sedang bekerja,
     * rangkaiannya dihentikan saat itu juga dan macro biasa langsung dilanjutkan.
     * Timer ikut direset supaya menyalakannya lagi mulai dari nol.
     */
    private fun setPath2Enabled(enabled: Boolean) {
        if (capturingColor || pickerView != null) return
        if (enabled) {
            if (path2Markers.isEmpty()) {
                toast("Tambahkan dulu minimal satu titik Jalur 2.")
                return
            }
            if (path2Markers.none { it.point.isReady }) {
                toast("Ambil warna dulu untuk minimal satu titik Jalur 2.")
                return
            }
        }
        if (path2Config.enabled == enabled) return
        path2Config = path2Config.copy(enabled = enabled)
        path2Store.save(path2Config)
        if (!enabled) {
            path2Schedule.reset()
            if (runState == SmartRunState.PATH2) {
                // Hentikan rangkaian Jalur 2 sekarang juga, lalu lanjutkan macro biasa.
                finishPath2(runGeneration)
                toast("Jalur 2 dimatikan. Macro biasa lanjut.")
            } else {
                toast("Jalur 2 dimatikan.")
            }
        } else {
            if (runState == SmartRunState.RUNNING) path2ResumeClock()
            toast("Jalur 2 dinyalakan.")
        }
        MacroDiagnostics.record(service, "smart_jalur2_toggle", if (enabled) "on" else "off")
        updateUi()
    }

    /** Menekan tombol J2 saat macro berjalan = matikan Jalur 2, tanpa menghentikan macro. */
    private fun handlePath2ButtonPressed() {
        if (isRunning()) {
            if (path2Config.enabled) {
                setPath2Enabled(false)
            } else {
                toast("Jalur 2 sedang mati. Macro biasa tetap berjalan.")
            }
            return
        }
        togglePath2Panel()
    }

    private fun changePath2Minutes(delta: Int) {
        if (isRunning()) return
        path2Config = path2Config.copy(
            intervalMinutes = Path2Config.sanitizeMinutes(path2Config.intervalMinutes + delta)
        )
        path2Store.save(path2Config)
        updateUi()
    }

    private fun editPath2Minutes() {
        if (isRunning()) return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(path2Config.intervalMinutes.toString())
            setSelection(text.length)
        }
        val minimum = Path2Config.MIN_INTERVAL_MINUTES
        val maximum = Path2Config.MAX_INTERVAL_MINUTES
        showNumberDialog(
            title = "Waktu Jalur 2 (menit)",
            message = "Jalur 2 dijalankan setelah smart click berjalan selama $minimum–$maximum menit.",
            input = input,
            min = minimum,
            max = maximum
        ) { minutes ->
            path2Config = path2Config.copy(intervalMinutes = minutes)
            path2Store.save(path2Config)
            updateUi()
        }
    }

    private fun cyclePath2Repeat() {
        if (isRunning()) return
        val holder = path2Markers.getOrNull(selectedPath2Index) ?: return
        val next = if (holder.point.repeatCount >= 5) 1 else holder.point.repeatCount + 1
        holder.point = holder.point.copy(repeatCount = next)
        savePath2()
        updateUi()
    }

    private fun togglePath2Hold() {
        if (isRunning()) return
        val holder = path2Markers.getOrNull(selectedPath2Index) ?: return
        holder.point = holder.point.copy(
            isHold = !holder.point.isHold,
            holdMs = if (!holder.point.isHold) {
                holder.point.holdMs.coerceAtLeast(Path2Point.MIN_HOLD_MS)
            } else {
                holder.point.holdMs
            }
        )
        savePath2()
        updateUi()
    }

    private fun editPath2Tolerance() {
        if (isRunning()) return
        val holder = path2Markers.getOrNull(selectedPath2Index) ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(holder.point.tolerance.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            title = "Kepekaan warna J${selectedPath2Index + 1}",
            message = "Semakin besar, semakin mudah dianggap cocok (${Path2Point.MIN_TOLERANCE}–${Path2Point.MAX_TOLERANCE}).",
            input = input,
            min = Path2Point.MIN_TOLERANCE,
            max = Path2Point.MAX_TOLERANCE
        ) { tolerance ->
            holder.point = holder.point.copy(tolerance = tolerance)
            savePath2()
            updateUi()
        }
    }

    /** Membaca warna pixel di titik Jalur 2 terpilih, sama seperti titik smart biasa. */
    private fun capturePath2Color() {
        if (isRunning()) {
            toast("Hentikan dulu smart click sebelum mengambil warna.")
            return
        }
        if (capturingColor && !captureIsStuck()) {
            toast("Sedang membaca layar. Tunggu sebentar.")
            return
        }
        capturingColor = false
        val holder = path2Markers.getOrNull(selectedPath2Index) ?: run {
            toast("Tambahkan titik Jalur 2 dulu.")
            return
        }
        sampler.capabilityProblem()?.let {
            toast(it)
            return
        }
        syncPath2FromView(holder)
        val frame = runCatching { screenFrame() }.getOrNull() ?: run {
            toast("Ukuran layar tidak terbaca. Tutup lalu buka lagi editor smart.")
            return
        }
        val toolbarBox = toolbarNormalizedBox()
        if (toolbarBox != null && SmartGeometry.isPatchObscured(
                SmartGeometry.patchBox(holder.normalizedX, holder.normalizedY, PATCH_RADIUS_NORMALIZED),
                listOf(toolbarBox)
            )
        ) {
            toast("Titik Jalur 2 tertutup toolbar. Pindahkan dulu.")
            return
        }
        capturingColor = true
        captureStartedAtMs = SystemClock.uptimeMillis()
        statusLabel.text = "Ambil warna Jalur 2…"
        setMarkersVisible(false)
        setPath2Visible(false)
        toolbarHiddenForCapture = toolbar?.visibility == View.VISIBLE
        if (toolbarHiddenForCapture) toolbar?.visibility = View.INVISIBLE
        handler.postDelayed({
            requestPath2Color(holder, toolbarBox, attempt = 0, firstReading = null)
        }, CAPTURE_SETTLE_MS)
        handler.postDelayed({
            if (capturingColor) {
                endCapture()
                toast("Pengambilan warna terlalu lama. Coba lagi.")
            }
        }, CAPTURE_SETTLE_MS + ScreenSampler.SCREENSHOT_TIMEOUT_MS + 800L)
        MacroDiagnostics.record(
            service, "smart_path2_capture_requested",
            "point=${selectedPath2Index + 1} frame=${frame.width}x${frame.height}"
        )
    }

    private fun requestPath2Color(
        holder: Path2Holder,
        toolbarBox: SmartGeometry.Box?,
        attempt: Int,
        firstReading: Int?
    ) {
        if (!capturingColor) return
        sampler.sample(
            mapOf(holder.id to (holder.normalizedX to holder.normalizedY)),
            listOfNotNull(toolbarBox)
        ) { result, error ->
            if (!capturingColor) return@sample
            if (result == null && (error == "paced" || error == "busy") && attempt < CAPTURE_MAX_RETRIES) {
                handler.postDelayed(
                    { requestPath2Color(holder, toolbarBox, attempt + 1, firstReading) },
                    ScreenSampler.MIN_REQUEST_GAP_MS
                )
                return@sample
            }
            val color = result?.get(holder.id)
            if (color == null) {
                endCapture()
                MacroDiagnostics.record(service, "smart_path2_capture_failed", error ?: "null_patch")
                toast(
                    if (error == "paced" || error == "busy") {
                        "Layar sedang sibuk. Coba ambil warna lagi."
                    } else {
                        error ?: "Warna tidak terbaca. Pastikan titik tidak tertutup toolbar."
                    }
                )
                updateUi()
                return@sample
            }
            if (firstReading == null) {
                handler.postDelayed(
                    { requestPath2Color(holder, toolbarBox, 0, color) },
                    ScreenSampler.MIN_REQUEST_GAP_MS
                )
                return@sample
            }
            endCapture()
            if (!ColorCapture.readingsAgree(firstReading, color, holder.point.tolerance)) {
                MacroDiagnostics.record(
                    service, "smart_path2_capture_unstable",
                    "${ColorMatcher.toHex(firstReading)}->${ColorMatcher.toHex(color)}"
                )
                toast(ColorCapture.unstableMessage(firstReading, color))
                updateUi()
                return@sample
            }
            holder.point = holder.point.copy(targetColor = color)
            holder.view.sampledColor = color
            holder.view.isDisabledPoint = false
            savePath2()
            if (!path2Config.enabled) {
                path2Config = path2Config.copy(enabled = true)
                path2Store.save(path2Config)
                toast("Warna J${selectedPath2Index + 1} tersimpan dan Jalur 2 diaktifkan.")
            } else {
                toast("Warna J${selectedPath2Index + 1} tersimpan: ${ColorMatcher.toHex(color)}")
            }
            updateUi()
            MacroDiagnostics.record(service, "smart_path2_color_captured", ColorMatcher.toHex(color))
        }
    }

    private fun beginPath2(generation: Long) {
        if (runState != SmartRunState.RUNNING) return
        if (path2Markers.none { it.point.isReady }) return
        handler.removeCallbacksAndMessages(null)
        path2Schedule.pause(SystemClock.elapsedRealtime())
        runState = SmartRunState.PATH2
        path2Index = nextReadyPath2Index(0)
        if (path2Index < 0) {
            finishPath2(generation)
            return
        }
        MacroDiagnostics.record(
            service, "smart_jalur2_start",
            "points=${path2Markers.size} setiapMenit=${path2Config.intervalMinutes}"
        )
        updateUi()
        scanPath2(generation, 0L, SystemClock.elapsedRealtime())
    }

    private fun nextReadyPath2Index(from: Int): Int {
        for (index in from until path2Markers.size) {
            if (path2Markers[index].point.isReady) return index
        }
        return -1
    }

    /**
     * Menunggu warna pixel titik Jalur 2 ini cocok, lalu mengkliknya. Bila warna
     * tidak pernah cocok dalam batas waktu, titik dilewati agar macro tidak macet.
     */
    private fun scanPath2(generation: Long, initialDelayMs: Long, pointStartedAtMs: Long) {
        if (generation != runGeneration || runState != SmartRunState.PATH2) return
        val holder = path2Markers.getOrNull(path2Index) ?: run {
            finishPath2(generation)
            return
        }
        val profile = currentProfile ?: return
        val frame = runCatching { screenFrame() }.getOrNull()
        if (frame == null || frame != runFrame) {
            stopWithNotice("Layar berubah. Smart click dihentikan.", "screen_change")
            return
        }
        val forbidden = listOfNotNull(toolbarNormalizedBox())
        val patch = SmartGeometry.patchBox(
            holder.point.normalizedX, holder.point.normalizedY, PATCH_RADIUS_NORMALIZED
        )
        if (SmartGeometry.isPatchObscured(patch, forbidden)) {
            stopWithNotice(
                "Titik Jalur 2 nomor ${path2Index + 1} tertutup toolbar. Pindahkan toolbar.",
                "path2_toolbar_overlap"
            )
            return
        }
        statusLabel.text = "Jalur 2 • J${path2Index + 1} • baca warna…"
        sampler.sample(
            mapOf(holder.id to (holder.point.normalizedX to holder.point.normalizedY)),
            forbidden
        ) { result, error ->
            if (generation != runGeneration || runState != SmartRunState.PATH2) return@sample
            if (result == null) {
                if (error == "paced" || error == "busy") {
                    handler.postDelayed(
                        { scanPath2(generation, 0L, pointStartedAtMs) },
                        ScreenSampler.MIN_REQUEST_GAP_MS
                    )
                    return@sample
                }
                if (sampler.failureCount() >= ScreenSampler.MAX_CONSECUTIVE_FAILURES) {
                    stopWithNotice(
                        error ?: "Layar tidak dapat dibaca berulang kali. Smart click dihentikan.",
                        "path2_sample_failed"
                    )
                } else {
                    handler.postDelayed(
                        { scanPath2(generation, profile.scanIntervalMs, pointStartedAtMs) },
                        profile.scanIntervalMs
                    )
                }
                return@sample
            }
            val sample = result[holder.id]
            val target = holder.point.targetColor
            val matched = sample != null && target != null &&
                ColorMatcher.isMatch(sample, target, holder.point.tolerance)
            if (!matched) {
                val waited = SystemClock.elapsedRealtime() - pointStartedAtMs
                if (waited >= PATH2_MATCH_TIMEOUT_MS) {
                    MacroDiagnostics.record(
                        service, "smart_jalur2_point_timeout",
                        "point=${path2Index + 1} waited=$waited"
                    )
                    advancePath2(generation)
                    return@sample
                }
                handler.postDelayed(
                    { scanPath2(generation, 0L, pointStartedAtMs) },
                    profile.scanIntervalMs
                )
                return@sample
            }
            MacroDiagnostics.record(
                service, "smart_jalur2_fire",
                "point=${path2Index + 1} color=${ColorMatcher.toHex(sample ?: 0)}"
            )
            firePath2(holder, generation)
        }
    }

    private fun firePath2(holder: Path2Holder, generation: Long) {
        if (generation != runGeneration || runState != SmartRunState.PATH2) return
        val frame = runFrame ?: return
        val (safeX, safeY) = GestureSafety.clampToFrame(
            frame, frame.centerX(holder.normalizedX), frame.centerY(holder.normalizedY)
        )
        val durationMs = if (holder.point.isHold) holder.point.holdMs else PATH2_TAP_MS
        if (!GestureSafety.isValidGesture(safeX, safeY, safeX, safeY, durationMs)) {
            stopWithNotice("Koordinat titik Jalur 2 tidak valid.", "path2_invalid_coordinate")
            return
        }
        if (isUnderToolbar(safeX, safeY)) {
            stopWithNotice("Toolbar menutupi titik Jalur 2. Pindahkan toolbar.", "path2_toolbar_overlap")
            return
        }
        statusLabel.text = "Jalur 2 • klik J${path2Index + 1}"
        val accepted = service.performAction(
            actionType = if (holder.point.isHold) PointActionType.LONG_PRESS else PointActionType.TAP,
            startX = safeX,
            startY = safeY,
            endX = safeX,
            endY = safeY,
            durationMs = durationMs,
            onCompleted = {
                if (generation == runGeneration && runState == SmartRunState.PATH2) {
                    if (path2RepeatIndex + 1 < holder.point.repeatCount) {
                        path2RepeatIndex++
                        updateUi()
                        handler.postDelayed({ firePath2(holder, generation) }, REPEAT_GAP_MS)
                    } else {
                        path2RepeatIndex = 0
                        handler.postDelayed({ advancePath2(generation) }, holder.point.delayMs)
                    }
                }
            },
            onCancelled = {
                if (generation == runGeneration && runState == SmartRunState.PATH2) {
                    MacroDiagnostics.record(
                        service, "smart_jalur2_cancelled", "point=${path2Index + 1}"
                    )
                    stopWithNotice(
                        "Gesture Jalur 2 dibatalkan sistem. Smart click dihentikan.",
                        "path2_cancelled"
                    )
                }
            }
        )
        if (!accepted) {
            MacroDiagnostics.record(service, "smart_jalur2_rejected", "point=${path2Index + 1}")
            stopWithNotice("Sistem menolak gesture Jalur 2. Smart click dihentikan.", "path2_rejected")
        }
    }

    private fun advancePath2(generation: Long) {
        if (generation != runGeneration || runState != SmartRunState.PATH2) return
        val next = nextReadyPath2Index(path2Index + 1)
        if (next < 0) {
            finishPath2(generation)
            return
        }
        path2Index = next
        path2RepeatIndex = 0
        updateUi()
        scanPath2(generation, 0L, SystemClock.elapsedRealtime())
    }

    private fun finishPath2(generation: Long) {
        if (generation != runGeneration || runState != SmartRunState.PATH2) return
        path2Index = 0
        path2RepeatIndex = 0
        // Smart click lanjut; hitungan waktu Jalur 2 dimulai dari nol lagi.
        path2Schedule.reset()
        path2Schedule.resume(SystemClock.elapsedRealtime())
        runState = SmartRunState.RUNNING
        MacroDiagnostics.record(service, "smart_jalur2_finish", "")
        updateUi()
        scheduleScan(generation, PATH2_RESUME_GAP_MS)
    }

    private fun stopWithNotice(message: String, reason: String) {
        stop(reason)
        toast(message)
    }

    // ------------------------------------------------------------ colour capture

    /**
     * Reads the colour at one point and stores it.
     *
     * @param pointId the point the user opened the settings for. Resolving by id
     * instead of by the current selection means a changed selection during the
     * dialog can never recapture the wrong point.
     */
    private fun captureColor(pointId: Long? = null) {
        if (isRunning()) {
            toast("Hentikan dulu smart click sebelum mengambil warna.")
            return
        }
        if (capturingColor && !captureIsStuck()) {
            toast("Sedang membaca layar. Tunggu sebentar.")
            return
        }
        // A previous attempt whose callback never arrived must not lock the feature
        // out forever; that made the row look like it did nothing at all.
        capturingColor = false
        val holder = pointId?.let { id -> holders.firstOrNull { it.id == id } }
            ?: holders.getOrNull(selectedIndex)
            ?: run {
                toast("Tambahkan titik smart dulu.")
                return
            }
        sampler.capabilityProblem()?.let {
            toast(it)
            return
        }
        syncFromViews(holder)
        val frame = runCatching { screenFrame() }.getOrNull() ?: run {
            toast("Ukuran layar tidak terbaca. Tutup lalu buka lagi editor smart.")
            return
        }
        val toolbarBox = toolbarNormalizedBox()
        if (toolbarBox != null && SmartGeometry.isPatchObscured(
                SmartGeometry.patchBox(holder.watchX, holder.watchY, PATCH_RADIUS_NORMALIZED),
                listOf(toolbarBox)
            )
        ) {
            toast("Titik pengawas tertutup toolbar. Pindahkan dulu.")
            return
        }
        capturingColor = true
        captureStartedAtMs = SystemClock.uptimeMillis()
        statusLabel.text = "Ambil warna…"
        // Both our markers and our toolbar are our own windows, and a screenshot
        // contains them. Hiding only the markers left the toolbar free to be read.
        setMarkersVisible(false)
        toolbarHiddenForCapture = toolbar?.visibility == View.VISIBLE
        if (toolbarHiddenForCapture) toolbar?.visibility = View.INVISIBLE
        // The settings dialog animates out for a couple of hundred milliseconds.
        // Sampling before it is gone photographed the dialog, which is why this
        // feature appeared to do nothing: it stored a real colour of the wrong thing.
        handler.postDelayed({
            requestCapture(holder, toolbarBox, attempt = 0, firstReading = null)
        }, CAPTURE_SETTLE_MS)
        handler.postDelayed({
            if (capturingColor) {
                endCapture()
                toast("Pengambilan warna terlalu lama. Coba lagi.")
            }
        }, CAPTURE_SETTLE_MS + ScreenSampler.SCREENSHOT_TIMEOUT_MS + 800L)
        MacroDiagnostics.record(service, "smart_capture_requested", "frame=${frame.width}x${frame.height}")
    }

    /** A capture that has been "in progress" for too long is treated as lost. */
    private fun captureIsStuck(): Boolean =
        capturingColor && SystemClock.uptimeMillis() - captureStartedAtMs > CAPTURE_STUCK_MS

    private fun endCapture() {
        capturingColor = false
        setMarkersVisible(true)
        // Titik Jalur 2 juga disembunyikan selama pembacaan warna. Dulu hanya titik
        // smart yang dipulihkan, sehingga titik J2 hilang dari layar setelah warna
        // diambil dan terlihat seperti "tidak ada reaksi".
        setPath2Visible(true)
        if (toolbarHiddenForCapture) toolbar?.visibility = View.VISIBLE
        toolbarHiddenForCapture = false
    }

    /**
     * The platform paces screenshots. A capture that lands inside the cooldown is
     * retried instead of surfacing an internal "paced" string to the user.
     *
     * The first successful reading is only a candidate: a second reading of the same
     * patch must agree before it is stored, so a moving screen can never be saved as
     * the target colour.
     */
    private fun requestCapture(
        holder: SmartHolder,
        toolbarBox: SmartGeometry.Box?,
        attempt: Int,
        firstReading: Int?
    ) {
        if (!capturingColor) return
        sampler.sample(
            mapOf(holder.id to (holder.watchX to holder.watchY)),
            listOfNotNull(toolbarBox)
        ) { result, error ->
            if (!capturingColor) return@sample
            if (result == null && (error == "paced" || error == "busy") && attempt < CAPTURE_MAX_RETRIES) {
                handler.postDelayed(
                    { requestCapture(holder, toolbarBox, attempt + 1, firstReading) },
                    ScreenSampler.MIN_REQUEST_GAP_MS
                )
                return@sample
            }
            val color = result?.get(holder.id)
            if (color == null) {
                endCapture()
                statusLabel.text = "Gagal"
                MacroDiagnostics.record(service, "smart_capture_failed", error ?: "null_patch")
                toast(
                    if (error == "paced" || error == "busy") {
                        "Layar sedang sibuk. Coba ambil warna lagi."
                    } else {
                        error ?: "Warna tidak terbaca. Pastikan titik tidak tertutup toolbar."
                    }
                )
                updateUi()
                return@sample
            }
            if (firstReading == null) {
                // Confirm the reading before trusting it.
                handler.postDelayed(
                    { requestCapture(holder, toolbarBox, 0, color) },
                    ScreenSampler.MIN_REQUEST_GAP_MS
                )
                return@sample
            }
            endCapture()
            if (!ColorCapture.readingsAgree(firstReading, color, holder.point.tolerance)) {
                MacroDiagnostics.record(
                    service, "smart_capture_unstable",
                    "${ColorMatcher.toHex(firstReading)}->${ColorMatcher.toHex(color)}"
                )
                statusLabel.text = "Warna tidak stabil"
                toast(ColorCapture.unstableMessage(firstReading, color))
                updateUi()
                return@sample
            }
            holder.point = holder.point.copy(targetColor = color, colorCaptured = true)
            holder.watchView.sampledColor = color
            persist()
            updateUi()
            MacroDiagnostics.record(service, "smart_color_captured", ColorMatcher.toHex(color))
            toast("Warna ${holder.point.label} tersimpan: ${ColorMatcher.toHex(color)}")
        }
    }

    // ---------------------------------------------------------------- editing

    private fun addPoint() {
        if (isRunning()) return
        val profile = currentProfile ?: return
        if (holders.size >= SmartProfile.MAX_POINTS) {
            toast("Maksimal ${SmartProfile.MAX_POINTS} titik smart.")
            return
        }
        val index = holders.size
        val x = (0.2f + index * 0.13f).coerceAtMost(0.85f)
        val y = (0.25f + index * 0.11f).coerceAtMost(0.85f)
        val point = SmartPoint(
            id = System.nanoTime(),
            label = "T${index + 1}",
            watchX = x,
            watchY = y
        )
        if (addHolder(point)) {
            selectedIndex = holders.lastIndex
            persist()
            updateUi()
        }
    }

    private fun removePoint() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: return
        if (!removeViewSafely(holder.watchView)) {
            toast("Titik gagal dihapus. Coba tutup editor.")
            return
        }
        holders.removeAt(selectedIndex)
        selectedIndex = selectedIndex.coerceIn(0, (holders.size - 1).coerceAtLeast(0))
        relabel()
        persist()
        updateUi()
    }

    private fun selectDelta(delta: Int) {
        if (holders.isEmpty()) return
        selectedIndex = ((selectedIndex + delta) % holders.size + holders.size) % holders.size
        updateUi()
    }

    private fun cycleCondition() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: return
        holder.point = holder.point.copy(
            condition = if (holder.point.condition == SmartCondition.COLOR_MATCH) {
                SmartCondition.COLOR_CHANGE
            } else {
                SmartCondition.COLOR_MATCH
            }
        )
        persist()
        updateUi()
    }

    private fun cycleAction() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: return
        holder.point = holder.point.copy(
            action = if (holder.point.action == SmartAction.TAP) SmartAction.LONG_PRESS else SmartAction.TAP
        )
        persist()
        updateUi()
    }

    private fun toggleEnabled() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: return
        holder.point = holder.point.copy(enabled = !holder.point.enabled)
        holder.watchView.isDisabledPoint = !holder.point.enabled
        persist()
        updateUi()
    }

    private fun editTolerance() {
        val holder = holders.getOrNull(selectedIndex) ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(holder.point.tolerance.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            "Toleransi warna",
            "Beda maksimum tiap kanal warna (${SmartPoint.MIN_TOLERANCE}–${SmartPoint.MAX_TOLERANCE}). " +
                "Lebih besar = lebih longgar.",
            input,
            SmartPoint.MIN_TOLERANCE,
            SmartPoint.MAX_TOLERANCE
        ) { value ->
            holder.point = holder.point.copy(tolerance = value)
            persist()
            updateUi()
        }
    }

    private fun editHexColor() {
        val holder = holders.getOrNull(selectedIndex) ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(ColorMatcher.toHex(holder.point.targetColor))
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Warna target")
            .setMessage("Format #RRGGBB. Disarankan pakai tombol Ambil Warna.")
            .setView(input)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = ColorMatcher.parseHex(input.text.toString())
                if (parsed == null) {
                    input.error = "Gunakan #RRGGBB"
                } else {
                    holder.point = holder.point.copy(targetColor = parsed, colorCaptured = true)
                    holder.watchView.sampledColor = parsed
                    persist()
                    updateUi()
                    dialog.dismiss()
                }
            }
        }
        showDialog(dialog)
    }

    private fun showNumberDialog(
        title: String,
        message: String,
        input: EditText,
        min: Int,
        max: Int,
        onSaved: (Int) -> Unit
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
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in min..max) {
                    input.error = "Gunakan $min–$max"
                } else {
                    onSaved(value)
                    dialog.dismiss()
                }
            }
        }
        showDialog(dialog)
    }

    private fun showDialog(dialog: AlertDialog) {
        dismissDialog()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        runCatching { dialog.show() }.onFailure {
            activeDialog = null
            toast("Pengaturan tidak dapat dibuka.")
        }
    }

    private fun dismissDialog() {
        activeDialog?.let { runCatching { it.dismiss() } }
        activeDialog = null
    }

    /**
     * Closes the dialog and runs [action] only once its window is really gone.
     *
     * Calling dismiss() and then immediately taking a screenshot captured the dialog
     * itself while it was still fading out. Anything that reads the screen must go
     * through here, not through dismiss() followed by a capture.
     */
    private fun dismissDialogThen(action: () -> Unit) {
        val dialog = activeDialog
        if (dialog == null) {
            action()
            return
        }
        activeDialog = null
        dialog.setOnDismissListener { action() }
        runCatching { dialog.dismiss() }.onFailure {
            dialog.setOnDismissListener(null)
            action()
        }
    }

    // ---------------------------------------------------------------- toolbar

    private fun addToolbar(): Boolean {
        val root = SmartToolbarLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6f), dp(4f), dp(6f), dp(6f))
            background = panelBackground()
            elevation = dp(6f).toFloat()
        }
        val header = row()
        val handle = TextView(service).apply {
            text = "⋮⋮ SMART ▴"
            textSize = 11f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(167, 243, 208))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(5f), 0, dp(3f), 0)
            minHeight = dp(28f)
            contentDescription = "Geser toolbar smart atau ketuk untuk menciutkan"
            setOnClickListener { toggleCollapsed() }
        }
        compactStopButton = smallButton("STOP", "Hentikan smart click") { stop("toolbar_stop") }.apply {
            background = stopBackground()
            visibility = View.GONE
        }
        val close = smallButton("×", "Tutup editor smart") { destroy() }.apply { textSize = 16f }
        attachToolbarDrag(handle)
        header.addView(handle, LinearLayout.LayoutParams(0, dp(28f), 1f))
        header.addView(compactStopButton, LinearLayout.LayoutParams(dp(46f), dp(28f)).apply { marginStart = dp(3f) })
        header.addView(close, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)))

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            tag = "content"
        }

        // Row 1: the only two buttons most people ever need.
        val primary = row()
        playButton = filledButton("▶ MULAI", "Mulai smart click", Color.rgb(5, 150, 105)) { start() }
        stopButton = filledButton("■ STOP", "Hentikan smart click", Color.rgb(220, 38, 38)) { stop("button_stop") }
        listOf(playButton, stopButton).forEach {
            primary.addView(it, LinearLayout.LayoutParams(0, dp(36f), 1f).apply {
                marginStart = dp(2f); marginEnd = dp(2f)
            })
        }
        content.addView(primary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36f)))

        // Row 2: one tap to place a point AND read its colour. No dragging needed.
        addButton = filledButton("＋ Ketuk Titik di Layar", "Tandai titik dengan mengetuk layar", Color.rgb(13, 148, 136)) {
            startPick()
        }.apply { textSize = 12f }
        content.addView(addButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)).apply {
            topMargin = dp(3f)
        })

        // Row 3: which point is selected, and its settings.
        val selector = row()
        prevButton = smallButton("‹", "Titik sebelumnya") { selectDelta(-1) }
        pointLabel = TextView(service).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.rgb(226, 232, 240))
        }
        nextButton = smallButton("›", "Titik berikutnya") { selectDelta(1) }
        colorSwatch = View(service).apply {
            background = swatchBackground(Color.WHITE)
            contentDescription = "Warna target titik terpilih"
            setOnClickListener { if (!isRunning()) editHexColor() }
        }
        advancedButton = smallButton("⚙", "Buka atau tutup pengaturan titik") { toggleAdvanced() }.apply {
            textSize = 15f
        }
        path2Button = smallButton("J2", "Buka panel Jalur 2; saat berjalan, menekan ini mematikan Jalur 2") {
            handlePath2ButtonPressed()
        }.apply {
            textSize = 11f
        }
        selector.addView(prevButton, LinearLayout.LayoutParams(dp(26f), dp(28f)))
        selector.addView(pointLabel, LinearLayout.LayoutParams(0, dp(28f), 1f))
        selector.addView(nextButton, LinearLayout.LayoutParams(dp(26f), dp(28f)))
        selector.addView(colorSwatch, LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
            marginStart = dp(4f); marginEnd = dp(4f)
        })
        selector.addView(path2Button, LinearLayout.LayoutParams(dp(30f), dp(28f)).apply { marginEnd = dp(2f) })
        selector.addView(advancedButton, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        content.addView(selector, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)).apply {
            topMargin = dp(2f)
        })

        // Always tells the user what to do next, in plain words.
        guideLabel = TextView(service).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(Color.rgb(167, 243, 208))
            setPadding(dp(2f), dp(2f), dp(2f), 0)
        }
        content.addView(guideLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusLabel = TextView(service).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(Color.rgb(203, 213, 225))
        }
        content.addView(statusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16f)))

        // Panel Jalur 2: hanya tampil saat dibuka, jadi toolbar tetap ringkas.
        path2Panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isPath2Open) View.VISIBLE else View.GONE
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 245, 158, 11)); cornerRadius = dp(6f).toFloat()
            }
        }
        val path2TimeRow = row()
        path2ToggleButton = smallButton("J2", "Aktifkan atau matikan Jalur 2") { togglePath2Enabled() }.apply {
            textSize = 10f
        }
        val path2Minus = smallButton("−", "Kurangi waktu Jalur 2 satu menit") { changePath2Minutes(-1) }
        path2MinutesButton = smallButton("10 mnt", "Ketuk untuk mengetik waktu Jalur 2") { editPath2Minutes() }.apply {
            textSize = 10f
        }
        val path2Plus = smallButton("+", "Tambah waktu Jalur 2 satu menit") { changePath2Minutes(1) }
        path2TimeRow.addView(path2ToggleButton, LinearLayout.LayoutParams(0, dp(28f), 1f))
        path2TimeRow.addView(path2Minus, LinearLayout.LayoutParams(dp(26f), dp(28f)))
        path2TimeRow.addView(path2MinutesButton, LinearLayout.LayoutParams(dp(52f), dp(28f)))
        path2TimeRow.addView(path2Plus, LinearLayout.LayoutParams(dp(26f), dp(28f)))
        path2Panel.addView(path2TimeRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)))

        val path2PointRow = row()
        addPath2Button = smallButton("+ Titik J2", "Tambah titik Jalur 2") { addNewPath2Marker() }.apply {
            textSize = 10f
        }
        removePath2Button = smallButton("− Titik J2", "Hapus titik Jalur 2 terpilih") {
            removeSelectedPath2Marker()
        }.apply { textSize = 10f }
        path2PointRow.addView(addPath2Button, LinearLayout.LayoutParams(0, dp(28f), 1f).apply { marginEnd = dp(2f) })
        path2PointRow.addView(removePath2Button, LinearLayout.LayoutParams(0, dp(28f), 1f))
        path2Panel.addView(path2PointRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)))

        // Warna pixel: inti cara kerja Jalur 2 di mode smart.
        path2ColorButton = smallButton("Ambil Warna J2", "Baca warna pixel di titik Jalur 2 terpilih") {
            capturePath2Color()
        }.apply { textSize = 11f }
        path2Panel.addView(path2ColorButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(30f)
        ).apply { topMargin = dp(2f) })

        val path2SettingRow = row()
        path2ToleranceButton = smallButton("Kepekaan", "Atur kepekaan warna titik Jalur 2") {
            editPath2Tolerance()
        }.apply { textSize = 10f }
        path2HoldButton = smallButton("Ketuk", "Ubah ketuk atau tahan untuk titik Jalur 2") {
            togglePath2Hold()
        }.apply { textSize = 10f }
        path2RepeatButton = smallButton("×1", "Atur jumlah klik titik Jalur 2") {
            cyclePath2Repeat()
        }.apply { textSize = 10f }
        listOf(path2ToleranceButton, path2HoldButton, path2RepeatButton).forEach {
            path2SettingRow.addView(it, LinearLayout.LayoutParams(0, dp(28f), 1f).apply {
                marginStart = dp(1f); marginEnd = dp(1f)
            })
        }
        path2Panel.addView(path2SettingRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)))

        path2StatusLabel = TextView(service).apply {
            textSize = 9f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(254, 215, 170))
        }
        path2Panel.addView(path2StatusLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16f)))
        content.addView(path2Panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2f) })

        val advanced = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isAdvancedOpen) View.VISIBLE else View.GONE
        }
        pointSettingsButton = filledButton(
            "Atur Titik Ini", "Pengaturan titik terpilih", Color.rgb(13, 148, 136)
        ) { showPointSettings() }.apply { textSize = 12f }
        advanced.addView(pointSettingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)
        ).apply { topMargin = dp(3f) })

        val runBtn = filledButton(
            "Loop & Kecepatan", "Atur loop dan kecepatan untuk semua titik", Color.rgb(71, 85, 105)
        ) { showRunOptions() }.apply { textSize = 12f }
        advanced.addView(runBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)
        ).apply { topMargin = dp(3f) })
        runSettingsButton = runBtn

        val testBtn = filledButton(
            "Tes Klik Titik Ini", "Coba satu klik untuk memeriksa posisi", Color.rgb(180, 83, 9)
        ) { testClickSelected() }.apply { textSize = 12f }
        advanced.addView(testBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)
        ).apply { topMargin = dp(3f) })
        testClickButton = testBtn

        val help = settingButton("? Cara Pakai", "Lihat panduan singkat") { showHelp() }
        advanced.addView(help, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28f)))
        content.addView(advanced)
        advancedContent = advanced

        root.addView(content)
        val params = WindowManager.LayoutParams(
            dp(TOOLBAR_WIDTH_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(8f)
            y = dp(90f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        root.onUnexpectedVisibilityChange = { visibility ->
            if (!disposing && visibility != View.VISIBLE) {
                MacroDiagnostics.record(service, "smart_toolbar_hidden", "visibility=$visibility")
                if (isRunning()) stopWithNotice("Toolbar smart disembunyikan sistem. Dihentikan.", "toolbar_hidden")
            }
        }
        root.onUnexpectedDetach = {
            if (!disposing && toolbar === root) {
                MacroDiagnostics.record(service, "smart_toolbar_detached", "")
                toolbar = null
                toolbarParams = null
                if (isRunning()) stop("toolbar_detached")
            }
        }
        return runCatching { windowManager.addView(root, params) }.onSuccess {
            toolbar = root
            toolbarParams = params
            applyCollapsed()
        }.isFailure.also { failed ->
            if (failed) toast("Toolbar smart tidak dapat ditampilkan oleh sistem.")
        }.let { !it }
    }

    private fun toggleCollapsed() {
        isCollapsed = !isCollapsed
        applyCollapsed()
    }

    private fun applyCollapsed() {
        val root = toolbar ?: return
        val content = root.findViewWithTag<View>("content") ?: return
        content.visibility = if (isCollapsed) View.GONE else View.VISIBLE
        if (isCollapsed) {
            isAdvancedOpen = false
            advancedContent?.visibility = View.GONE
        }
        root.post { clampToolbar() }
    }

    private fun toggleAdvanced() {
        isAdvancedOpen = !isAdvancedOpen
        advancedContent?.visibility = if (isAdvancedOpen) View.VISIBLE else View.GONE
        toolbar?.post { clampToolbar() }
    }

    private fun updateUi() {
        if (!::playButton.isInitialized || toolbar == null) return
        val idle = runState == SmartRunState.IDLE
        playButton.isEnabled = idle && holders.isNotEmpty()
        stopButton.isEnabled = !idle
        compactStopButton.isEnabled = !idle
        compactStopButton.visibility = if (idle) View.GONE else View.VISIBLE
        listOf(
            addButton, prevButton, nextButton, advancedButton,
            pointSettingsButton, runSettingsButton, testClickButton
        ).forEach {
            it.isEnabled = idle && !capturingColor && pickerView == null
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }
        playButton.alpha = if (playButton.isEnabled) 1f else 0.4f
        stopButton.alpha = if (stopButton.isEnabled) 1f else 0.4f
        if (::path2ToggleButton.isInitialized) {
            // Warna tombol = keadaan Jalur 2: hijau menyala, abu-abu mati.
            path2ToggleButton.text = if (path2Config.enabled) "J2 ON" else "J2 OFF"
            path2ToggleButton.setTextColor(Color.WHITE)
            path2ToggleButton.background = GradientDrawable().apply {
                setColor(if (path2Config.enabled) Color.rgb(5, 150, 105) else Color.rgb(71, 85, 105))
                cornerRadius = dp(10f).toFloat()
            }
            path2MinutesButton.text = "${path2Config.intervalMinutes} mnt"
            listOf(
                path2MinutesButton, addPath2Button, removePath2Button,
                path2ColorButton, path2ToleranceButton, path2HoldButton, path2RepeatButton
            ).forEach {
                it.isEnabled = idle && !capturingColor && pickerView == null
                it.alpha = if (it.isEnabled) 1f else 0.4f
            }
            // Saklar utama tetap bisa dipakai saat macro berjalan: mematikan Jalur 2
            // tidak boleh menunggu macro berhenti, kalau tidak keduanya bisa bentrok.
            path2ToggleButton.isEnabled = !capturingColor && pickerView == null
            path2ToggleButton.alpha = if (path2ToggleButton.isEnabled) 1f else 0.4f
            removePath2Button.isEnabled = removePath2Button.isEnabled && path2Markers.isNotEmpty()
            val selectedPath2 = path2Markers.getOrNull(selectedPath2Index)
            listOf(path2ColorButton, path2ToleranceButton, path2HoldButton, path2RepeatButton).forEach {
                it.isEnabled = it.isEnabled && selectedPath2 != null
                it.alpha = if (it.isEnabled) 1f else 0.4f
            }
            if (idle && selectedPath2 != null) {
                path2ColorButton.text = if (selectedPath2.point.isReady) {
                    "Ambil Warna J2 (${ColorMatcher.toHex(selectedPath2.point.targetColor ?: 0)})"
                } else {
                    "Ambil Warna J2"
                }
                path2ToleranceButton.text = "Kepekaan ${selectedPath2.point.tolerance}"
                path2HoldButton.text = if (selectedPath2.point.isHold) "Tahan" else "Ketuk"
                path2RepeatButton.text = "×${selectedPath2.point.repeatCount}"
            }
            // Saat berjalan tombol ini menjadi saklar OFF darurat, jadi harus hidup.
            path2Button.isEnabled = !capturingColor && pickerView == null
            path2Button.alpha = if (path2Button.isEnabled) 1f else 0.55f
            if (path2Config.enabled) {
                path2Button.text = "J2✓"
            } else {
                path2Button.text = "J2"
            }
            updatePath2Status()
        }
        val profile = currentProfile
        val holder = holders.getOrNull(selectedIndex)
        if (holder == null) {
            pointLabel.text = "Belum ada titik"
            colorSwatch.background = swatchBackground(Color.DKGRAY)
            guideLabel.text = "Langkah 1: ketuk \"＋ Ketuk Titik di Layar\", lalu ketuk bagian layar yang ingin diklik."
            if (idle) statusLabel.text = "0 titik"
            return
        }
        val point = holder.point
        val readyCount = holders.count {
            it.point.enabled && (it.point.colorCaptured || it.point.condition == SmartCondition.COLOR_CHANGE)
        }
        pointLabel.text = "Titik ${selectedIndex + 1}/${holders.size}"
        colorSwatch.background = swatchBackground(
            if (point.colorCaptured) point.targetColor else Color.DKGRAY
        )
        pointSettingsButton.text = "Atur Titik ${selectedIndex + 1} (${point.label})"
        guideLabel.text = when {
            !idle -> "Sedang berjalan. Tekan STOP untuk berhenti."
            capturingColor -> "Membaca warna layar…"
            point.condition == SmartCondition.COLOR_MATCH && !point.colorCaptured ->
                "Langkah 2: buka ⚙ lalu ketuk \"Contoh warna\" untuk titik ini."
            else -> "Siap. Tekan ▶ MULAI. Tambah titik lagi bila perlu, atau ⚙ untuk atur ulang/urutan."
        }
        if (idle && !capturingColor) {
            statusLabel.text = "$readyCount/${holders.size} titik siap"
        } else if (runState == SmartRunState.RUNNING) {
            val active = engine
            statusLabel.text = "Titik ${active?.sequencePosition() ?: 1}/${holders.size} • " +
                "putaran ${(active?.loopCount ?: 0) + 1} • aksi ${active?.actionCount ?: 0}"
        } else if (runState == SmartRunState.PATH2) {
            statusLabel.text = "Jalur 2 • titik ${path2Index + 1}/${path2Markers.size}"
        }
        holders.forEachIndexed { index, item ->
            item.watchView.isSelectedPoint = index == selectedIndex
            item.watchView.isDisabledPoint = !item.point.enabled
        }
    }

    // ------------------------------------------------------- tap-to-place picker

    /**
     * Replaces "add point, then drag the marker, then capture colour" with one
     * action: the user taps the screen, we place the point there and immediately
     * read its colour. The picker window is removed before sampling so it cannot
     * appear in the screenshot.
     */
    private fun startPick(forPath2: Boolean = false) {
        if (isRunning() || capturingColor || pickerView != null) return
        if (!forPath2 && holders.size >= SmartProfile.MAX_POINTS) {
            toast("Maksimal ${SmartProfile.MAX_POINTS} titik smart.")
            return
        }
        // Menaruh titik tidak butuh tangkapan layar; hanya pembacaan warnanya yang
        // butuh. Mengunci lapisan penanda di sini membuat tombol tambah titik
        // tampak "tidak ada reaksi" ketika izin tangkap layar belum aktif.
        val frame = runCatching { screenFrame() }.getOrNull() ?: return
        pickForPath2 = forPath2
        if (forPath2) {
            // Panel Jalur 2 membuat toolbar jauh lebih tinggi, dan toolbar itu
            // menutupi area ketuk. Ciutkan dulu supaya pengguna bisa menandai
            // hampir di mana saja, lalu buka lagi setelah selesai.
            reopenPath2AfterPick = isPath2Open
            if (isPath2Open) {
                isPath2Open = false
                path2Panel.visibility = View.GONE
                toolbar?.post { clampToolbar() }
            }
        }
        val root = SmartPickerLayout(service).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            contentDescription = "Ketuk layar untuk menandai titik smart"
        }
        val banner = TextView(service).apply {
            text = if (forPath2) {
                "Ketuk bagian layar untuk titik JALUR 2"
            } else {
                "Ketuk bagian layar yang mau DIKLIK"
            }
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            setBackgroundColor(Color.argb(220, 6, 46, 38))
        }
        root.addView(banner, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        val cancel = filledButton("Batal", "Batasi penandaan titik", Color.rgb(71, 85, 105)) { cancelPick() }
        root.addView(cancel, android.widget.FrameLayout.LayoutParams(dp(90f), dp(38f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(28f)
        })
        root.onPick = { rawX, rawY ->
            val nx = SmartGeometry.normalize(rawX, frame.left, frame.width)
            val ny = SmartGeometry.normalize(rawY, frame.top, frame.height)
            finishPick(nx, ny)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (runCatching { windowManager.addView(root, params) }.isFailure) {
            toast("Lapisan penanda tidak bisa dibuka. Pakai tombol ⚙ untuk menambah titik manual.")
            return
        }
        pickerView = root
        // The toolbar must not sit on top of the area being picked.
        toolbar?.visibility = View.INVISIBLE
        updateUi()
    }

    private fun cancelPick() {
        removePicker()
        pickForPath2 = false
        restorePath2PanelAfterPick()
        toolbar?.visibility = View.VISIBLE
        updateUi()
    }

    private fun removePicker() {
        pickerView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        pickerView = null
    }

    private fun finishPick(nx: Float, ny: Float) {
        removePicker()
        toolbar?.visibility = View.VISIBLE
        val box = toolbarNormalizedBox()
        if (box != null && SmartGeometry.isPatchObscured(SmartGeometry.patchBox(nx, ny, PATCH_RADIUS_NORMALIZED), listOf(box))) {
            val message = "Posisi itu tertutup toolbar. Geser toolbar dulu, lalu coba lagi."
            if (pickForPath2) {
                pickForPath2 = false
                toast(message)
                updateUi()
                restorePath2PanelAfterPick()
                return
            }
            toast(message)
            updateUi()
            return
        }
        if (pickForPath2) {
            pickForPath2 = false
            placePickedPath2Point(nx, ny)
            return
        }
        val index = holders.size
        val point = SmartPoint(
            id = System.nanoTime(),
            label = "T${index + 1}",
            watchX = nx,
            watchY = ny
        )
        if (!addHolder(point)) {
            updateUi()
            return
        }
        selectedIndex = holders.lastIndex
        persist()
        updateUi()
        // Read the colour straight away so the user never has to find a separate button.
        captureColor(point.id)
    }

    // ------------------------------------------------------- repeat / order / loop

    private fun editRepeat() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(holder.point.repeatCount.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            "Ulang klik titik ini",
            "Berapa kali titik ini diklik setiap kali kondisi layarnya cocok " +
                "(${SmartPoint.MIN_REPEAT_COUNT}–${SmartPoint.MAX_REPEAT_COUNT}). " +
                "1 = sekali klik. Kondisi diperiksa sekali, lalu klik dikirim berurutan.",
            input,
            SmartPoint.MIN_REPEAT_COUNT,
            SmartPoint.MAX_REPEAT_COUNT
        ) { value ->
            holder.point = holder.point.copy(repeatCount = value)
            persist()
            updateUi()
        }
    }

    private fun editLoopLimit() {
        if (isRunning()) return
        val profile = currentProfile ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(profile.loopLimit.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            "Jumlah putaran",
            "Satu putaran = semua titik dijalankan berurutan dari titik 1 sampai terakhir. " +
                "0 = terus berulang tanpa batas.",
            input,
            0,
            SmartProfile.MAX_LOOP_LIMIT
        ) { value ->
            currentProfile = SmartLimits.sanitizeProfile(profile.copy(loopLimit = value))
            persist()
            updateUi()
            showRunOptions()
        }
    }

    // ------------------------------------------------------- plain-language rows

    /** A tappable row: plain title, current value, and one sentence explaining it. */
    private fun optionRow(title: String, value: String, explanation: String, action: () -> Unit) =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            background = GradientDrawable().apply {
                setColor(Color.rgb(248, 250, 252))
                cornerRadius = dp(10f).toFloat()
                setStroke(dp(1f), Color.rgb(226, 232, 240))
            }
            val head = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(TextView(service).apply {
                text = title
                textSize = 14f
                setTextColor(Color.rgb(15, 23, 42))
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(TextView(service).apply {
                text = value
                textSize = 14f
                setTextColor(Color.rgb(13, 148, 136))
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(head)
            addView(TextView(service).apply {
                text = explanation
                textSize = 12f
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, dp(3f), 0, 0)
            })
            contentDescription = "$title, $value. $explanation"
            setOnClickListener { action() }
        }

    private fun optionList(vararg rows: View): View {
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(6f))
        }
        rows.forEach {
            box.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) })
        }
        return ScrollView(service).apply { addView(box) }
    }

    /** Rebuilds the dialog so a changed value is shown without closing it. */
    private fun reopenPointSettings() {
        dismissDialog()
        showPointSettings()
    }

    /**
     * Point settings, exactly the list the user asked for:
     * recapture colour, how many clicks, tap vs long-press, delete.
     * Legacy-only rows appear only if an old profile actually uses them, so no stored
     * setting becomes unreachable.
     */
    private fun showPointSettings() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: run {
            toast("Tambahkan titik dulu.")
            return
        }
        val point = holder.point
        val rows = mutableListOf<View>()

        rows += optionRow(
            "Ambil warna ulang",
            if (point.colorCaptured) ColorMatcher.toHex(point.targetColor) else "belum diambil",
            "Baca ulang warna di titik ini dari layar sekarang. Lakukan saat tombolnya sedang siap."
        ) {
            // Capture only after the dialog window has left the screen, and pin the
            // point so a changed selection cannot recapture a different one.
            dismissDialogThen { captureColor(point.id) }
        }

        rows += optionRow(
            "Berapa kali diklik", "${point.repeatCount}×",
            "Jumlah klik tiap kali warnanya cocok. 1 = sekali klik. Bisa diisi 1–${SmartPoint.MAX_REPEAT_COUNT}."
        ) { dismissDialog(); editRepeat() }

        val longPress = point.action == SmartAction.LONG_PRESS
        rows += optionRow(
            "Tahan tekan lama", if (longPress) "ON" else "OFF",
            "OFF = sentuh singkat (ketuk biasa). ON = tekan dan tahan, untuk tombol yang perlu ditahan."
        ) { cycleAction(); reopenPointSettings() }

        if (longPress) {
            rows += optionRow(
                "Lama menahan", "${point.holdMs} ms",
                "Berapa lama tombol ditahan. 700 ms biasanya cukup."
            ) { dismissDialog(); editHoldDuration() }
        }

        // Only shown for profiles saved by older versions, so nothing gets stranded.
        if (point.condition == SmartCondition.COLOR_CHANGE) {
            rows += optionRow(
                "Kapan diklik", "Warna berubah",
                "Setelan lama: klik saat warna sudah berbeda. Ketuk untuk kembali ke \"warna cocok\"."
            ) { cycleCondition(); reopenPointSettings() }
        }
        if (!point.enabled) {
            rows += optionRow(
                "Titik ini", "Dimatikan",
                "Titik ini dilewati. Ketuk untuk mengaktifkan lagi."
            ) { toggleEnabled(); reopenPointSettings() }
        }

        rows += optionRow(
            "Kepekaan warna", "±${point.tolerance}",
            "Kalau titik tidak mau diklik, naikkan sedikit. Kalau klik di waktu salah, turunkan."
        ) { dismissDialog(); editTolerance() }

        rows += optionRow(
            "Hapus titik ini", "",
            "Titik ini dihapus. Titik lain tidak berubah."
        ) { dismissDialog(); confirmRemovePoint() }

        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Titik ${selectedIndex + 1} dari ${holders.size} (${point.label})")
            .setView(optionList(*rows.toTypedArray()))
            .setPositiveButton("Selesai", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        showDialog(dialog)
    }

    private fun editHoldDuration() {
        if (isRunning()) return
        val holder = holders.getOrNull(selectedIndex) ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(holder.point.holdMs.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            "Lama menahan (ms)",
            "Berapa lama tombol ditahan, dalam milidetik. " +
                "${SmartPoint.MIN_HOLD_MS}–${SmartPoint.MAX_HOLD_MS} ms. 1000 ms = 1 detik.",
            input,
            SmartPoint.MIN_HOLD_MS.toInt(),
            SmartPoint.MAX_HOLD_MS.toInt()
        ) { value ->
            holder.point = holder.point.copy(holdMs = value.toLong())
            persist()
            updateUi()
        }
    }

    private fun confirmRemovePoint() {
        val holder = holders.getOrNull(selectedIndex) ?: return
        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Hapus ${holder.point.label}?")
            .setMessage("Titik ini akan dihapus dari profil.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> removePoint() }
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        showDialog(dialog)
    }

    /** Loop + speed, with a plain explanation of what each choice actually does. */
    private fun showRunOptions() {
        if (isRunning()) return
        val profile = currentProfile ?: return
        val rows = mutableListOf<View>()

        rows += sectionLabel("Loop berulang")
        rows += optionRow(
            "Ulang dari titik 1 sampai terakhir",
            if (profile.loopLimit == 0) "Tanpa batas" else "${profile.loopLimit}× lalu berhenti",
            "Titik dijalankan berurutan 1 → ${holders.size}, lalu mulai lagi dari titik 1. " +
                "Tanpa batas = terus sampai ditekan STOP."
        ) { dismissDialog(); editLoopLimit() }

        rows += sectionLabel("Kecepatan klik — semua titik")
        rows += optionRow(
            "Kecepatan semua titik", speedTitle(profile.speedMode),
            speedSummary(profile.speedMode) + " Berlaku untuk semua ${holders.size} titik, bukan per titik."
        ) { dismissDialog(); chooseSpeed() }

        if (profile.speedMode == SmartSpeedMode.CUSTOM) {
            rows += optionRow(
                "Baca layar setiap", "${profile.scanIntervalMs} ms",
                "Seberapa sering layar diperiksa, untuk semua titik. Minimum ${SmartProfile.MIN_SCAN_INTERVAL_MS} ms karena batas Android."
            ) { dismissDialog(); editTiming(1) }
            rows += optionRow(
                "Jeda antar klik", "${profile.cooldownMs} ms",
                "Jeda antara satu klik dan klik berikutnya, dihitung untuk seluruh titik. " +
                    "Minimum ${SmartProfile.MIN_COOLDOWN_MS} ms."
            ) { dismissDialog(); editTiming(2) }
            rows += optionRow(
                "Warna harus cocok", "${profile.confirmSamples}× berturut-turut",
                "Berlaku untuk semua titik. 1 = langsung klik. 2 atau lebih = lebih yakin, tapi lebih lambat."
            ) { dismissDialog(); editTiming(3) }
        }

        rows += sectionLabel("Pengaman")
        rows += optionRow(
            "Berhenti kalau diam", if (profile.idleTimeoutSec == 0) "Dimatikan" else "${profile.idleTimeoutSec} detik",
            "Kalau tidak ada warna yang cocok selama ini, aplikasi berhenti sendiri. " +
                "Isi 0 supaya jalan terus walau lama menunggu."
        ) { dismissDialog(); editIdleTimeout() }
        rows += optionRow(
            "Batas total klik", if (profile.maxActions == 0) "Tanpa batas" else "${profile.maxActions} klik",
            "Berhenti setelah jumlah klik ini tercapai. 0 = tanpa batas."
        ) { dismissDialog(); editMaxActions() }

        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Loop & Kecepatan")
            .setView(optionList(*rows.toTypedArray()))
            .setPositiveButton("Selesai", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        showDialog(dialog)
    }

    private fun speedTitle(mode: SmartSpeedMode): String = when (mode) {
        SmartSpeedMode.RECOMMENDED -> "Rekomendasi"
        SmartSpeedMode.FAST -> "Cepat"
        SmartSpeedMode.CUSTOM -> "Custom"
    }

    private fun speedSummary(mode: SmartSpeedMode): String = when (mode) {
        SmartSpeedMode.RECOMMENDED -> "Periksa warna 2× dulu, paling aman. Jeda 0,6 detik."
        SmartSpeedMode.FAST -> "Langsung klik saat warna cocok. Jeda 0,3 detik."
        SmartSpeedMode.CUSTOM -> "Angka diisi sendiri."
    }

    /**
     * One simple choice list. Selecting an item applies it and closes the list, so there
     * is no dismiss-and-reopen flicker and the new value is visible on the row behind it.
     */
    private fun chooseSpeed() {
        if (isRunning()) return
        val profile = currentProfile ?: return
        val modes = listOf(SmartSpeedMode.RECOMMENDED, SmartSpeedMode.FAST, SmartSpeedMode.CUSTOM)
        val labels = arrayOf(
            "Rekomendasi — paling aman",
            "Cepat — langsung klik",
            "Custom — isi angka sendiri"
        )
        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Kecepatan semua titik")
            .setSingleChoiceItems(labels, modes.indexOf(profile.speedMode)) { which, position ->
                val chosen = modes[position]
                currentProfile = SmartLimits.applySpeed(profile, chosen)
                persist()
                updateUi()
                which.dismiss()
                toast("Kecepatan semua titik: ${speedTitle(chosen)}")
                // Reopen the parent menu so the row shows the value that was just saved.
                showRunOptions()
            }
            .setNegativeButton("Batal", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        showDialog(dialog)
    }

    private fun sectionLabel(text: String) = TextView(service).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(13, 148, 136))
        setPadding(dp(4f), dp(8f), 0, dp(3f))
    }

    /** which: 1 = scan interval, 2 = cooldown, 3 = confirm samples. */
    private fun editTiming(which: Int) {
        if (isRunning()) return
        val profile = currentProfile ?: return
        val current = when (which) {
            1 -> profile.scanIntervalMs.toInt()
            2 -> profile.cooldownMs.toInt()
            else -> profile.confirmSamples
        }
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelectAllOnFocus(true)
        }
        val title = when (which) {
            1 -> "Baca layar setiap (ms)"
            2 -> "Jeda antar klik (ms)"
            else -> "Warna harus cocok (kali)"
        }
        val min = when (which) {
            1 -> SmartProfile.MIN_SCAN_INTERVAL_MS.toInt()
            2 -> SmartProfile.MIN_COOLDOWN_MS.toInt()
            else -> SmartProfile.MIN_CONFIRM_SAMPLES
        }
        val max = when (which) {
            1 -> SmartProfile.MAX_SCAN_INTERVAL_MS.toInt()
            2 -> SmartProfile.MAX_COOLDOWN_MS.toInt()
            else -> SmartProfile.MAX_CONFIRM_SAMPLES
        }
        showNumberDialog(title, "Gunakan $min–$max.", input, min, max) { value ->
            val updated = when (which) {
                1 -> profile.copy(scanIntervalMs = value.toLong())
                2 -> profile.copy(cooldownMs = value.toLong())
                else -> profile.copy(confirmSamples = value)
            }
            // Editing a number by hand means it is no longer a preset.
            currentProfile = SmartLimits.sanitizeProfile(updated.copy(speedMode = SmartSpeedMode.CUSTOM))
            persist()
            updateUi()
            showRunOptions()
        }
    }

    private fun editIdleTimeout() {
        if (isRunning()) return
        val profile = currentProfile ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(profile.idleTimeoutSec.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            "Berhenti kalau diam (detik)",
            "Kalau tidak ada warna yang cocok selama ini, smart click berhenti. " +
                "0 = dimatikan, jalan terus sampai ditekan STOP. Maksimal ${SmartProfile.MAX_IDLE_TIMEOUT_SEC} detik.",
            input, 0, SmartProfile.MAX_IDLE_TIMEOUT_SEC
        ) { value ->
            currentProfile = SmartLimits.sanitizeProfile(profile.copy(idleTimeoutSec = value))
            persist()
            updateUi()
            showRunOptions()
        }
    }

    private fun editMaxActions() {
        if (isRunning()) return
        val profile = currentProfile ?: return
        val input = EditText(service).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(profile.maxActions.toString())
            setSelectAllOnFocus(true)
        }
        showNumberDialog(
            "Batas total klik",
            "Berhenti setelah jumlah klik ini. 0 = tanpa batas.",
            input, 0, SmartProfile.MAX_ACTIONS_LIMIT
        ) { value ->
            currentProfile = SmartLimits.sanitizeProfile(profile.copy(maxActions = value))
            persist()
            updateUi()
            showRunOptions()
        }
    }

    /**
     * Fires exactly one click at the selected point, ignoring its colour condition.
     * This is how the user verifies the position is right before running a long session.
     */
    private fun testClickSelected() {
        if (isRunning() || capturingColor) return
        val holder = holders.getOrNull(selectedIndex) ?: run {
            toast("Tambahkan titik dulu.")
            return
        }
        syncFromViews(holder)
        val frame = runCatching { screenFrame() }.getOrNull() ?: return
        val point = holder.point
        val (safeX, safeY) = GestureSafety.clampToFrame(
            frame, frame.centerX(point.effectiveTapX()), frame.centerY(point.effectiveTapY())
        )
        if (isUnderToolbar(safeX, safeY)) {
            toast("Titik klik tertutup toolbar. Geser toolbar dulu.")
            return
        }
        dismissDialog()
        // One-shot: never starts the run loop, never touches the classic macro.
        service.requestExclusiveRun(smart = true)
        setMarkersVisible(false)
        setMarkersTouchable(false)
        MacroDiagnostics.record(
            service, "smart_test_click",
            "point=${point.label} x=${safeX.toInt()} y=${safeY.toInt()} frame=${frame.width}x${frame.height}"
        )
        val accepted = service.performAction(
            actionType = if (point.action == SmartAction.TAP) PointActionType.TAP else PointActionType.LONG_PRESS,
            startX = safeX, startY = safeY, endX = safeX, endY = safeY,
            durationMs = if (point.action == SmartAction.TAP) 40L else point.holdMs,
            onCompleted = { restoreAfterTest("Tes klik terkirim di ${point.label}.") },
            onCancelled = { restoreAfterTest("Tes klik dibatalkan sistem.") }
        )
        if (!accepted) restoreAfterTest("Sistem menolak tes klik.")
    }

    private fun restoreAfterTest(message: String) {
        handler.post {
            if (disposing) return@post
            setMarkersVisible(true)
            setMarkersTouchable(true)
            updateUi()
            toast(message)
        }
    }

    private fun showHelp() {
        val dialog = AlertDialog.Builder(service, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Cara Pakai")
            .setMessage(
                "CARA PAKAI\n\n" +
                    "1. Tekan ＋ Tambah Titik, lalu ketuk bagian layar yang mau diklik. " +
                    "Warnanya langsung dibaca.\n" +
                    "2. Tekan ▶ MULAI.\n\n" +
                    "Titik dijalankan berurutan: titik 1, lalu 2, sampai terakhir, " +
                    "lalu ulang dari titik 1. Bawaan tanpa batas sampai ditekan STOP.\n\n" +
                    "ATUR TITIK (⚙ → Atur Titik Ini)\n" +
                    "• Ambil warna ulang — baca ulang warna titik ini.\n" +
                    "• Berapa kali diklik — 1 sampai ${SmartPoint.MAX_REPEAT_COUNT} kali.\n" +
                    "• Tahan tekan lama — OFF = sentuh singkat, ON = tekan dan tahan.\n" +
                    "• Kepekaan warna — naikkan kalau tidak mau klik, turunkan kalau salah klik.\n" +
                    "• Hapus titik ini.\n\n" +
                    "LOOP & KECEPATAN (⚙ → Loop & Kecepatan)\n" +
                    "• Ulang dari titik 1 sampai terakhir: Tanpa batas, atau isi jumlah putaran.\n" +
                    "• Kecepatan: Rekomendasi (paling aman), Cepat (langsung klik), " +
                    "atau Custom (isi angka sendiri).\n" +
                    "• Berhenti kalau diam: isi 0 supaya tidak berhenti sendiri saat menunggu lama.\n\n" +
                    "SEBELUM MULAI\n" +
                    "• Geser toolbar menjauh dari titik yang ditandai.\n" +
                    "• Tekan Tes Klik Titik Ini untuk memastikan posisinya benar.\n" +
                    "• Jangan putar layar setelah menandai titik."
            )
            .setPositiveButton("Mengerti", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        showDialog(dialog)
    }

    // ---------------------------------------------------------------- markers

    private fun addHolder(point: SmartPoint): Boolean {
        val sanitized = SmartLimits.sanitizePoint(point)
        val view = SmartMarkerView(service).apply {
            label = sanitized.label
            isWatchMarker = true
            sampledColor = if (sanitized.colorCaptured) sanitized.targetColor else null
            isDisabledPoint = !sanitized.enabled
            contentDescription = "Titik pengawas ${sanitized.label}, geser untuk memindahkan"
        }
        val params = markerParams(sanitized.watchX, sanitized.watchY)
        val holder = SmartHolder(
            id = sanitized.id,
            point = sanitized,
            watchView = view,
            watchParams = params,
            watchX = sanitized.watchX,
            watchY = sanitized.watchY
        )
        attachMarkerDrag(holder, view, params)
        if (runCatching { windowManager.addView(view, params) }.isFailure) {
            toast("Titik pengawas tidak dapat ditampilkan.")
            return false
        }
        holders.add(holder)
        return true
    }

    private fun markerParams(normalizedX: Float, normalizedY: Float): WindowManager.LayoutParams {
        val size = dp(MARKER_SIZE_DP)
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
            x = SmartGeometry.markerLeft(normalizedX, frame.left, frame.width, size)
            y = SmartGeometry.markerTop(normalizedY, frame.top, frame.height, size)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun attachMarkerDrag(
        holder: SmartHolder,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            if (isRunning()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val index = holders.indexOf(holder)
                    if (index < 0) return@setOnTouchListener false
                    selectedIndex = index
                    updateUi()
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).roundToInt()
                    params.y = initialY + (event.rawY - touchY).roundToInt()
                    clampMarker(params)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    syncFromViews(holder)
                    persist()
                    updateUi()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    params.x = initialX
                    params.y = initialY
                    clampMarker(params)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }
    }

    private fun syncFromViews(holder: SmartHolder) {
        val frame = screenFrame()
        holder.watchX = SmartGeometry.fromMarkerLeft(
            holder.watchParams.x, holder.watchParams.width, frame.left, frame.width
        )
        holder.watchY = SmartGeometry.fromMarkerTop(
            holder.watchParams.y, holder.watchParams.height, frame.top, frame.height
        )
        // Sub-pixel drift from rounding must not invalidate a captured colour.
        val moved = kotlin.math.abs(holder.point.watchX - holder.watchX) > POSITION_EPSILON ||
            kotlin.math.abs(holder.point.watchY - holder.watchY) > POSITION_EPSILON
        holder.point = holder.point.copy(
            watchX = holder.watchX,
            watchY = holder.watchY,
            // A moved watch region no longer describes the stored colour.
            colorCaptured = if (moved) false else holder.point.colorCaptured
        )
        if (moved) holder.watchView.sampledColor = null
    }

    private fun applyPosition(holder: SmartHolder) {
        val frame = screenFrame()
        holder.watchParams.x = SmartGeometry.markerLeft(
            holder.watchX, frame.left, frame.width, holder.watchParams.width
        )
        holder.watchParams.y = SmartGeometry.markerTop(
            holder.watchY, frame.top, frame.height, holder.watchParams.height
        )
        clampMarker(holder.watchParams)
    }

    private fun clampMarker(params: WindowManager.LayoutParams) {
        val frame = screenFrame()
        params.x = params.x.coerceIn(
            frame.left - params.width / 2, frame.left + frame.width - params.width / 2
        )
        params.y = params.y.coerceIn(
            frame.top - params.height / 2, frame.top + frame.height - params.height / 2
        )
    }

    private fun setMarkersVisible(visible: Boolean) {
        holders.forEach { holder ->
            holder.watchView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun setMarkersTouchable(touchable: Boolean) {
        holders.forEach { holder ->
            holder.watchParams.flags = touchFlags(holder.watchParams.flags, touchable)
            holder.watchParams.alpha = if (touchable) 1f else 0.7f
            runCatching { windowManager.updateViewLayout(holder.watchView, holder.watchParams) }
        }
    }

    private fun touchFlags(flags: Int, touchable: Boolean): Int = if (touchable) {
        flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    } else {
        flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun relabel() {
        holders.forEachIndexed { index, holder ->
            val name = "T${index + 1}"
            holder.point = holder.point.copy(label = name)
            holder.watchView.label = name
        }
    }

    private fun removeAllHolders(): Boolean {
        var allRemoved = true
        val iterator = holders.iterator()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            if (removeViewSafely(holder.watchView)) iterator.remove() else allRemoved = false
        }
        return allRemoved
    }

    private fun toPoint(holder: SmartHolder): SmartPoint = holder.point

    private fun persist() {
        val profile = currentProfile ?: return
        holders.forEach(::syncFromViews)
        val updated = profile.copy(points = holders.map(::toPoint))
        currentProfile = onProfileChanged(updated) ?: updated
    }

    private fun findTooClosePair(profile: SmartProfile): String? {
        val active = profile.points.filter { it.enabled }
        for (i in active.indices) {
            for (j in i + 1 until active.size) {
                if (SmartGeometry.patchesTooClose(
                        active[i].watchX, active[i].watchY,
                        active[j].watchX, active[j].watchY,
                        PATCH_RADIUS_NORMALIZED * 2
                    )
                ) {
                    return "${active[i].label} dan ${active[j].label}"
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- geometry

    private fun toolbarNormalizedBox(): SmartGeometry.Box? {
        val view = toolbar ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val frame = screenFrame()
        // Bingkai layar yang tidak masuk akal (mis. 1x1 saat metrik belum siap) akan
        // membuat kotak toolbar menutupi SELURUH layar, sehingga setiap ketukan
        // ditolak dengan pesan "tertutup toolbar". Lebih baik tidak memeriksa
        // daripada menolak semua titik.
        if (frame.width <= 1 || frame.height <= 1) return null
        return SmartGeometry.Box(
            SmartGeometry.normalize(location[0].toFloat(), frame.left, frame.width),
            SmartGeometry.normalize(location[1].toFloat(), frame.top, frame.height),
            SmartGeometry.normalize((location[0] + view.width).toFloat(), frame.left, frame.width),
            SmartGeometry.normalize((location[1] + view.height).toFloat(), frame.top, frame.height)
        )
    }

    private fun isUnderToolbar(x: Float, y: Float): Boolean {
        val view = toolbar ?: return false
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + view.width &&
            y >= location[1] && y <= location[1] + view.height
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        val view = toolbar ?: return
        val params = toolbarParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun screenFrame(): ScreenFrame {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The accessibility screenshot covers the FULL display. currentWindowMetrics
            // can exclude system insets, which shifted every sampled/clicked position.
            val bounds = windowManager.maximumWindowMetrics.bounds
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

    private fun attachToolbarDrag(handle: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            val params = toolbarParams
            val view = toolbar
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (params != null && view != null) {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        moved = moved || kotlin.math.abs(dx) > dp(4f) || kotlin.math.abs(dy) > dp(4f)
                        params.x = initialX + dx.roundToInt()
                        params.y = initialY + dy.roundToInt()
                        clampToolbarParams(params, view)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) handle.performClick()
                    clampToolbar()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (params != null && view != null) {
                        params.x = initialX
                        params.y = initialY
                        clampToolbarParams(params, view)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampToolbarParams(params: WindowManager.LayoutParams, view: View) {
        val frame = screenFrame()
        val width = view.width.takeIf { it > 0 } ?: params.width.takeIf { it > 0 } ?: dp(TOOLBAR_WIDTH_DP)
        val height = view.height.takeIf { it > 0 } ?: dp(48f)
        params.x = params.x.coerceIn(0, (frame.width - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (frame.height - height).coerceAtLeast(0))
    }

    private fun clampToolbar() {
        val view = toolbar ?: return
        val params = toolbarParams ?: return
        clampToolbarParams(params, view)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun removeViewSafely(view: View): Boolean {
        if (!view.isAttachedToWindow) return true
        return runCatching { windowManager.removeViewImmediate(view) }.isSuccess || !view.isAttachedToWindow
    }

    private fun row() = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }

    private fun smallButton(textValue: String, description: String, action: () -> Unit) = Button(service).apply {
        text = textValue
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        contentDescription = description
        minimumWidth = 0
        minWidth = 0
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(2f), 0, dp(2f), 0)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(10f).toFloat()
        }
        setOnClickListener { action() }
    }

    private fun filledButton(textValue: String, description: String, fill: Int, action: () -> Unit) =
        Button(service).apply {
            text = textValue
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            contentDescription = description
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setPadding(dp(4f), 0, dp(4f), 0)
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(9f).toFloat()
            }
            setOnClickListener { action() }
        }

    private fun settingButton(textValue: String, description: String, action: () -> Unit) =
        smallButton(textValue, description, action).apply { textSize = 10f }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Color.argb(242, 6, 46, 38))
        cornerRadius = dp(12f).toFloat()
        setStroke(dp(1f), Color.argb(110, 167, 243, 208))
    }

    private fun stopBackground() = GradientDrawable().apply {
        setColor(Color.rgb(220, 38, 38))
        cornerRadius = dp(8f).toFloat()
        setStroke(dp(1f), Color.WHITE)
    }

    private fun swatchBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(4f).toFloat()
        setStroke(dp(1f), Color.WHITE)
    }

    private fun dp(value: Float): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) {
        if (message == lastNotice) return
        lastNotice = message
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
        handler.postDelayed({ if (lastNotice == message) lastNotice = "" }, 2_000L)
    }

    private class SmartHolder(
        val id: Long,
        var point: SmartPoint,
        val watchView: SmartMarkerView,
        val watchParams: WindowManager.LayoutParams,
        var watchX: Float,
        var watchY: Float
    )

    /**
     * Titik Jalur 2 di dalam editor smart: klik biasa tanpa pembacaan warna,
     * sehingga hanya butuh posisi dan jeda.
     */
    private class Path2Holder(
        val id: Long,
        val view: SmartMarkerView,
        val params: WindowManager.LayoutParams,
        var normalizedX: Float,
        var normalizedY: Float,
        var point: Path2Point
    )

    private enum class SmartRunState { IDLE, COUNTDOWN, RUNNING, PATH2 }

    companion object {
        private const val TOOLBAR_WIDTH_DP = 224f
        private const val MARKER_SIZE_DP = 44f
        private const val MARKER_HIDE_SETTLE_MS = 150L

        /**
         * Time given to our own windows to leave the screen before the screenshot.
         * The settings dialog animates out; 400 ms also covers slow devices.
         */
        private const val CAPTURE_SETTLE_MS = 400L

        /** A capture still flagged after this long never got its callback. */
        private const val CAPTURE_STUCK_MS = 6_000L
        private const val CAPTURE_MAX_RETRIES = 4
        private const val MAX_UNREADABLE_SCANS = 6

        /**
         * Minimum spacing between two clicks of the same burst so the platform can
         * retire the previous gesture. This is not a user-facing per-click delay.
         */
        private const val REPEAT_GAP_MS = 60L

        /** Jeda sebelum smart click lanjut setelah Jalur 2 selesai. */
        private const val PATH2_RESUME_GAP_MS = 150L

        /** Durasi sentuh tiap titik Jalur 2: ketukan singkat seperti tap pengguna. */
        private const val PATH2_TAP_MS = 40L

        /**
         * Berapa lama satu titik Jalur 2 menunggu warnanya cocok sebelum dilewati.
         * Tanpa batas ini, satu warna yang tidak pernah muncul akan menghentikan
         * seluruh rangkaian Jalur 2.
         */
        private const val PATH2_MATCH_TIMEOUT_MS = 20_000L

        /** Jeda bawaan antar titik Jalur 2 yang baru ditambahkan dari editor smart. */
        private const val DEFAULT_PATH2_GAP_MS = 1_000L

        /** Half patch width expressed as a fraction of a 1080p-class display. */
        private const val PATCH_RADIUS_NORMALIZED = 0.006f

        /** Roughly one pixel on a 1080p-class display; ignores rounding noise. */
        private const val POSITION_EPSILON = 0.0015f

        /**
         * The screenshot must describe the same rectangle as the display, otherwise a
         * normalized position means one place in the image and another on the screen.
         * 1% is roughly 24 px on a 2400 px display: small enough that a system-bar or
         * crop difference is caught, large enough to ignore rounding.
         */
        private const val CAPTURE_FRAME_TOLERANCE = 0.01f
    }
}

/**
 * Full-screen tap target used to place a point. It overrides performClick so an
 * accessibility service can trigger the same path as a real touch.
 */
@SuppressLint("ViewConstructor")
private class SmartPickerLayout(context: Context) : android.widget.FrameLayout(context) {
    var onPick: ((Float, Float) -> Unit)? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                lastX = event.rawX
                lastY = event.rawY
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onPick?.invoke(lastX, lastY)
        return true
    }
}

private class SmartToolbarLayout(context: Context) : LinearLayout(context) {
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

private class SmartMarkerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 9f * density
    }

    var label: String = "T1"
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var isWatchMarker: Boolean = true
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var isSelectedPoint: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var isDisabledPoint: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var sampledColor: Int? = null
        set(value) {
            if (field != value) { field = value; invalidate() }
        }
    var isPath2Marker: Boolean = false
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = 12f * density
        paint.style = Paint.Style.FILL
        paint.color = when {
            isDisabledPoint -> Color.rgb(100, 116, 139)
            isPath2Marker && isSelectedPoint -> Color.rgb(217, 119, 6)
            isPath2Marker -> Color.rgb(245, 158, 11)
            !isWatchMarker -> Color.rgb(14, 165, 233)
            isSelectedPoint -> Color.rgb(16, 185, 129)
            else -> Color.rgb(5, 150, 105)
        }
        paint.alpha = 200
        if (isWatchMarker) {
            // Ring: the centre stays transparent so the watched pixel is visible.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * density
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.FILL
            sampledColor?.let {
                paint.color = it
                paint.alpha = 255
                canvas.drawCircle(cx, cy + radius + 5f * density, 4f * density, paint)
            }
        } else {
            canvas.drawCircle(cx, cy, radius * 0.7f, paint)
        }
        canvas.drawCircle(cx, cy, radius, stroke)
        val extent = 17f * density
        canvas.drawLine(cx - extent, cy, cx - radius, cy, stroke)
        canvas.drawLine(cx + radius, cy, cx + extent, cy, stroke)
        canvas.drawLine(cx, cy - extent, cx, cy - radius, stroke)
        canvas.drawLine(cx, cy + radius, cx, cy + extent, stroke)
        val baseline = cy - (text.ascent() + text.descent()) / 2f
        canvas.drawText(
            when {
                isPath2Marker -> label
                isWatchMarker -> label
                else -> "$label•"
            },
            cx, baseline, text
        )
    }
}
