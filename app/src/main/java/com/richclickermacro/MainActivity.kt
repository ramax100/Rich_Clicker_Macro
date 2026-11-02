package com.richclickermacro

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.richclickermacro.accessibility.AutoClickAccessibilityService
import com.richclickermacro.data.ClickPreferences
import com.richclickermacro.data.MacroProfile
import com.richclickermacro.data.MacroRepository
import com.richclickermacro.data.DocumentIntents
import com.richclickermacro.data.PublicDownloads
import com.richclickermacro.data.SafePickerLauncher
import com.richclickermacro.diagnostics.MacroDiagnostics
import com.richclickermacro.diagnostics.MemoryMonitor
import com.richclickermacro.smart.SmartCondition
import com.richclickermacro.smart.SmartSpeedMode
import com.richclickermacro.smart.SmartProfile
import com.richclickermacro.smart.SmartRepository
import java.io.Reader

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var repository: MacroRepository
    private lateinit var smartRepository: SmartRepository
    private lateinit var statusText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var profileAdapter: ArrayAdapter<String>
    private lateinit var profileNameInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var loopDelayInput: EditText
    private lateinit var loopLimitInput: EditText
    private lateinit var countdownInput: EditText
    private lateinit var keepScreenOnSwitch: Switch
    private lateinit var stopOnAppChangeSwitch: Switch
    private lateinit var showControlsButton: Button
    private lateinit var memoryText: TextView
    private lateinit var checkMemoryButton: Button
    private lateinit var smartStatusText: TextView
    private lateinit var smartProfileSpinner: Spinner
    private lateinit var smartProfileAdapter: ArrayAdapter<String>
    private lateinit var smartNameInput: EditText
    private lateinit var smartCountdownInput: EditText
    private lateinit var smartKeepScreenSwitch: Switch
    private lateinit var smartAppGuardSwitch: Switch
    private lateinit var showSmartButton: Button
    private var selectedSmartName: String? = null
    private var isRefreshingSmart = false
    private lateinit var classicGroup: LinearLayout
    private lateinit var smartGroup: LinearLayout
    private lateinit var toolsGroup: LinearLayout
    private lateinit var classicTab: TextView
    private lateinit var smartTab: TextView
    private lateinit var modeHint: TextView
    private lateinit var toolsToggle: TextView
    private var homeMode = HOME_MODE_CLASSIC
    private var toolsExpanded = false

    private var selectedProfileName: String? = null
    private var isRefreshingProfiles = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MacroRepository(this)
        smartRepository = SmartRepository(this)
        savedInstanceState?.let {
            homeMode = it.getInt(STATE_HOME_MODE, HOME_MODE_CLASSIC)
            toolsExpanded = it.getBoolean(STATE_TOOLS_EXPANDED, false)
        }
        setContentView(createContentView())
        refreshProfiles(repository.getActiveProfile().name)
        refreshSmartProfiles(smartRepository.getActiveProfile().name)
        savedInstanceState?.let(::restoreDraft)
        if (repository.hasRecoveryBackup()) {
            Toast.makeText(
                this,
                "Data profil rusak diamankan sebagai backup; profil Recovery digunakan sementara.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PROFILE_NAME, profileNameInput.text.toString())
        outState.putString(STATE_DEFAULT_DELAY, delayInput.text.toString())
        outState.putString(STATE_LOOP_DELAY, loopDelayInput.text.toString())
        outState.putString(STATE_LOOP_LIMIT, loopLimitInput.text.toString())
        outState.putString(STATE_COUNTDOWN, countdownInput.text.toString())
        outState.putBoolean(STATE_KEEP_SCREEN_ON, keepScreenOnSwitch.isChecked)
        outState.putBoolean(STATE_STOP_ON_APP_CHANGE, stopOnAppChangeSwitch.isChecked)
        outState.putInt(STATE_HOME_MODE, homeMode)
        outState.putBoolean(STATE_TOOLS_EXPANDED, toolsExpanded)
    }

    private fun restoreDraft(state: Bundle) {
        profileNameInput.setText(state.getString(STATE_PROFILE_NAME, profileNameInput.text.toString()))
        delayInput.setText(state.getString(STATE_DEFAULT_DELAY, delayInput.text.toString()))
        loopDelayInput.setText(state.getString(STATE_LOOP_DELAY, loopDelayInput.text.toString()))
        loopLimitInput.setText(state.getString(STATE_LOOP_LIMIT, loopLimitInput.text.toString()))
        countdownInput.setText(state.getString(STATE_COUNTDOWN, countdownInput.text.toString()))
        keepScreenOnSwitch.isChecked = state.getBoolean(STATE_KEEP_SCREEN_ON, keepScreenOnSwitch.isChecked)
        stopOnAppChangeSwitch.isChecked = state.getBoolean(STATE_STOP_ON_APP_CHANGE, stopOnAppChangeSwitch.isChecked)
    }

    @Deprecated("Menggunakan Activity Result lama agar proyek tetap tanpa dependensi tambahan")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val payload = data?.data ?: return
        runCatching {
            when (requestCode) {
                REQUEST_EXPORT -> exportProfiles(payload)
                REQUEST_IMPORT -> importProfiles(payload)
                REQUEST_EXPORT_LOG -> exportDiagnostics(payload)
            }
        }.onFailure { reportFailure("Operasi file gagal", it) }
    }

    private fun createContentView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(28))
            setBackgroundColor(Color.rgb(241, 245, 249))
        }

        // Compact header: the previous 96 dp logo pushed the actual controls off-screen.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo Rich Clicker Macro"
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = "Rich Clicker Macro"
            textSize = 20f
            setTextColor(Color.rgb(15, 23, 42))
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(this).apply {
            text = "Versi $APP_VERSION_LABEL"
            textSize = 12f
            setTextColor(Color.rgb(100, 116, 139))
        })
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, matchWrapParams(bottom = 16))

        content.addView(createServiceCard(), matchWrapParams(bottom = 12))
        content.addView(createModeCard(), matchWrapParams(bottom = 12))

        // Only the selected mode's cards are shown, so the page is short and focused.
        classicGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        classicGroup.addView(createProfileCard(), matchWrapParams(bottom = 12))
        classicGroup.addView(createExecutionCard(), matchWrapParams(bottom = 12))
        classicGroup.addView(createEditorCard(), matchWrapParams(bottom = 12))
        classicGroup.addView(createDataCard(), matchWrapParams(bottom = 12))
        content.addView(classicGroup)

        smartGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        smartGroup.addView(createSmartCard(), matchWrapParams(bottom = 12))
        content.addView(smartGroup)

        content.addView(createToolsHeader(), matchWrapParams(bottom = 8))
        toolsGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolsGroup.addView(createMemoryCard(), matchWrapParams(bottom = 12))
        toolsGroup.addView(createUsageCard(), matchWrapParams())
        content.addView(toolsGroup)

        applyHomeMode()
        applyToolsExpanded()

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    /** Shows only the cards belonging to the selected mode. */
    private fun applyHomeMode() {
        if (!::classicGroup.isInitialized || !::smartGroup.isInitialized) return
        val smart = homeMode == HOME_MODE_SMART
        classicGroup.visibility = if (smart) View.GONE else View.VISIBLE
        smartGroup.visibility = if (smart) View.VISIBLE else View.GONE
        if (::classicTab.isInitialized) {
            classicTab.background = roundedBackground(
                if (smart) Color.TRANSPARENT else Color.rgb(79, 70, 229), 10f
            )
            classicTab.setTextColor(if (smart) Color.rgb(71, 85, 105) else Color.WHITE)
            smartTab.background = roundedBackground(
                if (smart) Color.rgb(13, 148, 136) else Color.TRANSPARENT, 10f
            )
            smartTab.setTextColor(if (smart) Color.WHITE else Color.rgb(71, 85, 105))
            modeHint.text = if (smart) {
                "Klik saat warna layar cocok. Tanpa jeda per klik. Butuh Android 11+."
            } else {
                "Klik mengikuti jeda waktu yang Anda atur. Tidak membaca layar."
            }
        }
    }

    private fun applyToolsExpanded() {
        if (!::toolsGroup.isInitialized) return
        toolsGroup.visibility = if (toolsExpanded) View.VISIBLE else View.GONE
        if (::toolsToggle.isInitialized) {
            toolsToggle.text = if (toolsExpanded) "▾  Alat & Panduan" else "▸  Alat & Panduan"
        }
    }

    private fun createToolsHeader() = TextView(this).apply {
        toolsToggle = this
        textSize = 13f
        setTextColor(Color.rgb(79, 70, 229))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(10), dp(4), dp(10))
        contentDescription = "Buka atau tutup alat dan panduan"
        setOnClickListener {
            toolsExpanded = !toolsExpanded
            applyToolsExpanded()
        }
    }

    private fun createServiceCard() = verticalCard().apply {
        addView(label("STATUS LAYANAN"))
        statusText = TextView(this@MainActivity).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(statusText, matchWrapParams(top = 8, bottom = 10))
        addView(standardButton("Buka Pengaturan Aksesibilitas") {
            saveProfileSettings(showConfirmation = false)
            openAccessibilitySettings()
        }, matchWrapParams(bottom = 8))
        addView(helpText("Tambahkan tile “Rich Clicker” dari panel Quick Settings Android untuk mulai/berhenti macro yang sudah dimuat."))
    }

    private fun createModeCard() = verticalCard().apply {
        addView(label("PILIH MODE"))
        val tabs = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBackground(Color.rgb(241, 245, 249), 12f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        classicTab = TextView(this@MainActivity).apply {
            text = "MACRO BIASA"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            contentDescription = "Tampilkan pengaturan Macro Biasa"
            setOnClickListener {
                homeMode = HOME_MODE_CLASSIC
                applyHomeMode()
            }
        }
        smartTab = TextView(this@MainActivity).apply {
            text = "MULTI SMART CLICK"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(12), dp(6), dp(12))
            contentDescription = "Tampilkan pengaturan Multi Smart Click"
            setOnClickListener {
                homeMode = HOME_MODE_SMART
                applyHomeMode()
            }
        }
        tabs.addView(classicTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(smartTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(tabs, matchWrapParams(top = 10, bottom = 8))
        modeHint = helpText("")
        addView(modeHint)
        addView(helpText("Hanya satu mode berjalan. Memulai salah satunya menghentikan yang lain." ), matchWrapParams(top = 6))
    }

    private fun createSmartCard() = verticalCard().apply {
        addView(label("MULTI SMART CLICK"))
        addView(helpText(
            "Klik otomatis saat warna di titik yang Anda pilih sudah cocok. Tidak perlu mengatur jeda per klik."
        ), matchWrapParams(top = 8, bottom = 8))
        addView(helpText(
            "Butuh Android 11+. Setelah update, matikan lalu nyalakan lagi layanan aksesibilitas agar izin baca layar aktif."
        ), matchWrapParams(bottom = 10))

        smartProfileAdapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        smartProfileSpinner = Spinner(this@MainActivity).apply {
            adapter = smartProfileAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isRefreshingSmart || position !in 0 until smartProfileAdapter.count) return
                    val newName = smartProfileAdapter.getItem(position) ?: return
                    val previous = selectedSmartName
                    if (previous != null && previous != newName) {
                        if (saveSmartSettings(showConfirmation = false, refreshUi = false) == null) {
                            refreshSmartProfiles(previous)
                            return
                        }
                    }
                    selectedSmartName = newName
                    smartRepository.setActiveProfile(newName)
                    loadSmartFields()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(fieldLabel("Profil smart"))
        addView(smartProfileSpinner, matchWrapParams(top = 4, bottom = 8))
        addView(buttonRow(
            standardButton("Profil Smart Baru") { showNewSmartProfileDialog() },
            standardButton("Hapus Smart") { deleteSelectedSmartProfile() }
        ), matchWrapParams(bottom = 10))
        addView(fieldLabel("Nama profil smart"))
        smartNameInput = textInput("Nama profil smart", InputType.TYPE_CLASS_TEXT)
        addView(smartNameInput)
        addView(fieldLabel("Countdown 0–10 detik"), matchWrapParams(top = 8))
        smartCountdownInput = textInput("3", InputType.TYPE_CLASS_NUMBER)
        addView(smartCountdownInput)
        smartKeepScreenSwitch = Switch(this@MainActivity).apply {
            text = "Jaga layar tetap menyala"
            textSize = 14f
            setPadding(0, dp(5), 0, dp(8))
        }
        addView(smartKeepScreenSwitch)
        smartAppGuardSwitch = Switch(this@MainActivity).apply {
            text = "Stop saat aplikasi tujuan berganti"
            textSize = 14f
            setPadding(0, dp(5), 0, dp(4))
        }
        addView(smartAppGuardSwitch)
        addView(primaryButton("Simpan Pengaturan Smart") {
            saveSmartSettings(showConfirmation = true)
        }, matchWrapParams(top = 8, bottom = 8))
        showSmartButton = primaryButton("Buka Editor Smart Click") { showSmartEditor() }
        addView(showSmartButton)
        addView(buttonRow(
            standardButton("Export Smart") { exportSmartToDownloads() },
            standardButton("Import Smart") { importSmartFromDownloads() }
        ), matchWrapParams(top = 8))
        smartStatusText = helpText("Belum ada titik. Buka editor, lalu ketuk ＋ Ketuk Titik di Layar.")
        addView(smartStatusText, matchWrapParams(top = 8))
        addView(helpText(
            "Cara pakai (3 langkah): buka game → Buka Editor Smart Click → tekan ＋ Ketuk Titik di Layar " +
                "lalu ketuk bagian layar yang mau diklik. Warna langsung dibaca otomatis. Tekan ▶ MULAI.\n\n" +
                "Pengaturan tambahan ada di tombol ⚙ pada toolbar:\n" +
                "• Ulang N× — satu titik diklik beberapa kali setiap kali kondisinya cocok.\n" +
                "• Urutan 1→akhir — titik dijalankan berurutan seperti macro biasa, lalu ulang dari titik 1.\n" +
                "• Putaran — berapa kali urutan penuh diulang (0 = tanpa batas)."
        ), matchWrapParams(top = 8))
    }

    private fun createProfileCard() = verticalCard().apply {
        addView(label("PROFIL MACRO"))
        profileAdapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_item,
            mutableListOf<String>()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        profileSpinner = Spinner(this@MainActivity).apply {
            adapter = profileAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isRefreshingProfiles || position !in 0 until profileAdapter.count) return
                    val newName = profileAdapter.getItem(position) ?: return
                    val previousName = selectedProfileName
                    if (previousName != null && previousName != newName) {
                        if (saveProfileSettings(showConfirmation = false, refreshUi = false) == null) {
                            refreshProfiles(previousName)
                            return
                        }
                    }
                    selectedProfileName = newName
                    repository.setActiveProfile(newName)
                    loadProfileFields()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        addView(profileSpinner, matchWrapParams(top = 8, bottom = 8))
        addView(buttonRow(
            standardButton("Profil Baru") { showNewProfileDialog() },
            standardButton("Hapus Profil") { deleteSelectedProfile() }
        ), matchWrapParams(bottom = 10))
        addView(fieldLabel("Nama profil"))
        profileNameInput = textInput("Nama profil", InputType.TYPE_CLASS_TEXT)
        addView(profileNameInput)
    }

    private fun createExecutionCard() = verticalCard().apply {
        addView(label("PENGATURAN WAKTU"))
        addView(fieldLabel("Jeda default titik baru (detik)"), matchWrapParams(top = 8))
        delayInput = decimalInput("1.0")
        addView(delayInput)
        addView(fieldLabel("Jeda antar-loop sebelum kembali ke titik 1 (detik)"))
        loopDelayInput = decimalInput("1.0")
        addView(loopDelayInput)
        addView(fieldLabel("Batas loop — 0 berarti tanpa batas"))
        loopLimitInput = textInput("0", InputType.TYPE_CLASS_NUMBER)
        addView(loopLimitInput)
        addView(fieldLabel("Countdown 0–10 detik"))
        countdownInput = textInput("3", InputType.TYPE_CLASS_NUMBER)
        addView(countdownInput)
        keepScreenOnSwitch = Switch(this@MainActivity).apply {
            text = "Jaga layar tetap menyala saat macro berjalan"
            textSize = 14f
            setPadding(0, dp(5), 0, dp(8))
        }
        addView(keepScreenOnSwitch)
        stopOnAppChangeSwitch = Switch(this@MainActivity).apply {
            text = "Stop saat aplikasi tujuan berganti"
            textSize = 14f
            setPadding(0, dp(5), 0, dp(4))
        }
        addView(stopOnAppChangeSwitch)
        addView(helpText("Disarankan aktif. Macro juga berhenti jika ukuran, posisi, atau orientasi layar berubah; periksa marker sebelum memulai lagi."), matchWrapParams(bottom = 10))
        addView(primaryButton("Simpan Pengaturan") {
            saveProfileSettings(showConfirmation = true)
        })
    }

    private fun createEditorCard() = verticalCard().apply {
        addView(label("EDITOR MACRO"))
        addView(helpText("Setiap titik dapat menggunakan Tap, Tahan, atau Geser. Durasi aksi dan jumlah pengulangan diatur dari toolbar mengambang."), matchWrapParams(top = 8, bottom = 10))
        showControlsButton = primaryButton("Tampilkan Editor di Atas Aplikasi") {
            showFloatingControls()
        }
        addView(showControlsButton)
    }

    private fun createDataCard() = verticalCard().apply {
        addView(label("IMPORT / EXPORT"))
        addView(helpText("Import dan export mencakup posisi, aksi, endpoint swipe, durasi, jumlah pengulangan, serta pengaturan eksekusi."), matchWrapParams(top = 8, bottom = 8))
        addView(buttonRow(
            standardButton("Import JSON") { openImportPicker() },
            standardButton("Export JSON") { openExportPicker() }
        ))
        addView(buttonRow(
            standardButton("Export ke Download") { exportProfilesToDownloads() },
            standardButton("Import dari Download") { importFromDownloads() }
        ), matchWrapParams(top = 8))
        addView(helpText("Alternatif Android 10+ jika pemilih file bermasalah: export langsung ke Download, atau import file .json hasil export aplikasi ini dari Download."), matchWrapParams(top = 6, bottom = 4))
        addView(standardButton("Export Log Diagnostik") { openDiagnosticsExportPicker() }, matchWrapParams(top = 8))
        addView(helpText("Log lokal berisi kejadian macro dan pergantian aplikasi, bukan isi layar. Bagikan hanya jika diperlukan untuk diagnosis."), matchWrapParams(top = 6))
    }

    private fun createMemoryCard() = verticalCard().apply {
        addView(label("CEK RAM CLICKER"))
        addView(helpText("Ukur memori proses clicker saat ini. Untuk membandingkan pemakaian selama macro, gunakan Export Log Diagnostik: sampel otomatis dicatat setiap 60 detik hanya saat macro berjalan."), matchWrapParams(top = 8, bottom = 8))
        checkMemoryButton = standardButton("Cek RAM Clicker") { measureClickerMemory() }
        addView(checkMemoryButton)
        memoryText = helpText("Belum diukur. Angka ini bukan RAM game dan tidak menentukan penyebab force close.")
        addView(memoryText, matchWrapParams(top = 8))
    }

    private fun measureClickerMemory() {
        if (!checkMemoryButton.isEnabled) return
        checkMemoryButton.isEnabled = false
        val windows = AutoClickAccessibilityService.instance?.overlayWindowCount() ?: 0
        val accepted = MacroDiagnostics.measureMemory(this, windows) { snapshot ->
            if (!isDestroyed && !isFinishing) {
                checkMemoryButton.isEnabled = true
                memoryText.text = snapshot?.let(MemoryMonitor::display)
                    ?: "Pengukuran belum berhasil. Coba lagi; pemakaian game tidak dapat dibaca oleh clicker."
            }
        }
        if (!accepted) {
            checkMemoryButton.isEnabled = true
            Toast.makeText(this, "Pengukuran sedang sibuk. Coba lagi sesaat.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createUsageCard() = TextView(this).apply {
        text = "Panduan singkat\n• + / −: tambah atau hapus titik.\n• ↑ / ↓: ubah urutan.\n• ⚙ Atur: buka pengaturan titik.\n• Ketuk label delay: atur jeda.\n• Ketuk jenis aksi: Tap → Tahan → Geser.\n• Ketuk durasi atau ×N: input langsung.\n• Marker E adalah tujuan swipe.\n• ▶ mulai, tombol STOP di menu menghentikan."
        textSize = 14f
        setTextColor(Color.rgb(51, 65, 85))
        setLineSpacing(0f, 1.18f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = roundedBackground(Color.rgb(238, 242, 255), 14f)
    }

    private fun refreshProfiles(preferredName: String?) {
        isRefreshingProfiles = true
        val profiles = repository.getProfiles()
        profileAdapter.clear()
        profileAdapter.addAll(profiles.map { it.name })
        profileAdapter.notifyDataSetChanged()
        val activeName = preferredName?.takeIf { name -> profiles.any { it.name == name } }
            ?: repository.getActiveProfile().name
        val index = profiles.indexOfFirst { it.name == activeName }.coerceAtLeast(0)
        selectedProfileName = profiles[index].name
        profileSpinner.setSelection(index, false)
        repository.setActiveProfile(profiles[index].name)
        isRefreshingProfiles = false
        loadProfileFields()
    }

    private fun loadProfileFields() {
        val profile = selectedProfileName?.let(repository::getProfile) ?: return
        profileNameInput.setText(profile.name)
        delayInput.setText(ClickPreferences.formatDelaySeconds(profile.defaultDelayMs))
        loopDelayInput.setText(ClickPreferences.formatDelaySeconds(profile.loopDelayMs))
        loopLimitInput.setText(profile.loopLimit.toString())
        countdownInput.setText(profile.countdownSeconds.toString())
        keepScreenOnSwitch.isChecked = profile.keepScreenOn
        stopOnAppChangeSwitch.isChecked = profile.stopOnAppChange
    }

    private fun saveProfileSettings(
        showConfirmation: Boolean,
        refreshUi: Boolean = true
    ): MacroProfile? {
        val previousName = selectedProfileName ?: return null
        val current = repository.getProfile(previousName) ?: return null
        val requestedName = profileNameInput.text.toString().trim()
        if (requestedName.isEmpty()) return fieldError(profileNameInput, "Nama profil tidak boleh kosong")
        val defaultDelay = ClickPreferences.parseDelaySeconds(delayInput.text.toString())
            ?: return fieldError(delayInput, "Gunakan 0.1–3600.0 detik")
        val loopDelay = ClickPreferences.parseDelaySeconds(loopDelayInput.text.toString())
            ?: return fieldError(loopDelayInput, "Gunakan 0.1–3600.0 detik")
        val loopLimit = loopLimitInput.text.toString().toIntOrNull()
        if (loopLimit == null || loopLimit !in 0..MacroProfile.MAX_LOOP_LIMIT) {
            return fieldError(loopLimitInput, "Gunakan 0–${MacroProfile.MAX_LOOP_LIMIT}")
        }
        val countdown = countdownInput.text.toString().toIntOrNull()
        if (countdown == null || countdown !in 0..MacroProfile.MAX_COUNTDOWN_SECONDS) {
            return fieldError(countdownInput, "Gunakan 0–${MacroProfile.MAX_COUNTDOWN_SECONDS}")
        }
        val saved = runCatching {
            repository.saveProfile(
                current.copy(
                    name = requestedName,
                    defaultDelayMs = defaultDelay,
                    loopDelayMs = loopDelay,
                    loopLimit = loopLimit,
                    countdownSeconds = countdown,
                    keepScreenOn = keepScreenOnSwitch.isChecked,
                    stopOnAppChange = stopOnAppChangeSwitch.isChecked
                ),
                previousName
            )
        }.getOrElse { reportFailure("Profil gagal disimpan", it); return null }
        AutoClickAccessibilityService.instance?.notifyProfileSaved(previousName, saved)
        selectedProfileName = saved.name
        if (refreshUi) refreshProfiles(saved.name)
        if (showConfirmation) Toast.makeText(this, "Profil ${saved.name} disimpan", Toast.LENGTH_SHORT).show()
        return saved
    }

    private fun <T> fieldError(field: EditText, message: String): T? {
        field.error = message
        return null
    }

    private fun showNewProfileDialog() {
        val input = EditText(this).apply { hint = "Nama profil"; setSingleLine(true) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Profil Baru").setView(input).setNegativeButton("Batal", null)
            .setPositiveButton("Buat", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().trim().isEmpty()) input.error = "Masukkan nama profil"
                else {
                    runCatching {
                        val created = repository.createProfile(input.text.toString())
                        refreshProfiles(created.name)
                        dialog.dismiss()
                    }.onFailure { reportFailure("Profil gagal dibuat", it) }
                }
            }
        }
        dialog.show()
    }

    private fun deleteSelectedProfile() {
        val name = selectedProfileName ?: return
        AlertDialog.Builder(this).setTitle("Hapus profil?")
            .setMessage("Profil $name beserta semua titiknya akan dihapus.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                runCatching {
                    val next = repository.deleteProfile(name)
                    AutoClickAccessibilityService.instance?.notifyProfileDeleted(name)
                    refreshProfiles(next.name)
                }.onFailure { reportFailure("Profil gagal dihapus", it) }
            }.show()
    }

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure {
            reportFailure("Pengaturan aksesibilitas tidak dapat dibuka", it)
        }
    }

    private fun showFloatingControls() {
        val profile = saveProfileSettings(false) ?: return
        if (!AutoClickAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Aktifkan layanan aksesibilitas terlebih dahulu", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Layanan sedang disiapkan. Coba lagi sesaat.", Toast.LENGTH_SHORT).show()
            return
        }
        service.showProfile(profile)
        Toast.makeText(this, "Editor aktif di atas aplikasi lain", Toast.LENGTH_LONG).show()
    }

    private fun openExportPicker() {
        if (saveProfileSettings(false) == null) return
        val fileName = "rich-clicker-macro-profiles.json"
        val result = SafePickerLauncher.launchFirstAvailable(
            listOf(DocumentIntents.createJsonDocument(fileName))
        ) { startActivityForResult(it, REQUEST_EXPORT) }

        if (result is SafePickerLauncher.Result.Unavailable) {
            MacroDiagnostics.record(this, "export_picker_unavailable", result.reasons.joinToString())
            val json = runCatching { repository.exportJson() }.getOrElse {
                reportFailure("Export gagal", it); return
            }
            saveToDownloadsOrExplain(fileName, "application/json", json, "Semua profil")
        }
    }

    private fun openImportPicker() {
        if (saveProfileSettings(false) == null) return
        val result = SafePickerLauncher.launchFirstAvailable(
            listOf(
                DocumentIntents.openJsonDocument(),
                DocumentIntents.getJsonContent(),
                DocumentIntents.getAnyContent()
            )
        ) { startActivityForResult(it, REQUEST_IMPORT) }

        if (result is SafePickerLauncher.Result.Unavailable) {
            MacroDiagnostics.record(this, "import_picker_unavailable", result.reasons.joinToString())
            AlertDialog.Builder(this)
                .setTitle("Pemilih file tidak tersedia")
                .setMessage(
                    "Android tidak menemukan aplikasi pemilih file. Aktifkan kembali aplikasi " +
                        "\"Files\" atau pasang file manager, lalu coba lagi.\n\n" +
                        "Alternatif: gunakan tombol \"Import dari Download\" untuk membaca file " +
                        "hasil export aplikasi ini."
                )
                .setNegativeButton("Tutup", null)
                .setPositiveButton("Import dari Download") { _, _ -> importFromDownloads() }
                .show()
        }
    }

    private fun openDiagnosticsExportPicker() {
        val fileName = "rich-clicker-diagnostics.txt"
        val result = SafePickerLauncher.launchFirstAvailable(
            listOf(DocumentIntents.createTextDocument(fileName))
        ) { startActivityForResult(it, REQUEST_EXPORT_LOG) }

        if (result is SafePickerLauncher.Result.Unavailable) {
            val log = runCatching {
                java.io.ByteArrayOutputStream().use { buffer ->
                    MacroDiagnostics.export(this, buffer)
                    buffer.toString(Charsets.UTF_8.name())
                }
            }.getOrElse { reportFailure("Export log gagal", it); return }
            saveToDownloadsOrExplain(fileName, "text/plain", log, "Log diagnostik")
        }
    }

    /** Manual Download export, also useful when a picker exists but misbehaves. */
    private fun exportProfilesToDownloads() {
        if (saveProfileSettings(false) == null) return
        val json = runCatching { repository.exportJson() }.getOrElse {
            reportFailure("Export gagal", it); return
        }
        saveToDownloadsOrExplain(
            "rich-clicker-macro-profiles.json", "application/json", json, "Semua profil",
            pickerFallback = false
        )
    }

    /** Used when no document picker exists: write straight into public Downloads. */
    private fun saveToDownloadsOrExplain(
        fileName: String,
        mimeType: String,
        content: String,
        label: String,
        pickerFallback: Boolean = true
    ) {
        val location = PublicDownloads.saveText(this, fileName, mimeType, content)
        if (location != null) {
            MacroDiagnostics.record(this, "export_downloads_ok", location)
            AlertDialog.Builder(this)
                .setTitle("$label tersimpan")
                .setMessage(
                    if (pickerFallback) {
                        "Pemilih file tidak tersedia di perangkat ini, jadi file disimpan ke:\n\n$location"
                    } else {
                        "File disimpan ke:\n\n$location"
                    }
                )
                .setPositiveButton("OK", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("$label gagal")
                .setMessage(
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                        "Alternatif Download otomatis memerlukan Android 10 atau lebih baru. " +
                            "Aktifkan aplikasi Files atau pasang file manager, lalu coba lagi."
                    } else {
                        "File tidak dapat disimpan ke Download. Periksa ruang penyimpanan, " +
                            "aktifkan aplikasi Files, atau pasang file manager, lalu coba lagi."
                    }
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Reads a previously exported profile file from public Downloads (Android 10+). */
    private fun importFromDownloads() {
        runCatching {
            val candidates = queryDownloadProfiles()
            if (candidates.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Tidak ada file profil")
                    .setMessage(
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                            "Alternatif ini memerlukan Android 10 atau lebih baru. " +
                                "Gunakan Import JSON dengan aplikasi Files atau file manager."
                        } else {
                            "Belum ada file .json hasil export aplikasi ini yang dapat dibaca " +
                                "di folder Download. Lakukan Export JSON terlebih dahulu, atau " +
                                "gunakan Import JSON untuk memilih file dari lokasi lain."
                        }
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            val labels = candidates.map { it.second }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Pilih file profil")
                .setItems(labels) { _, index ->
                    importProfiles(candidates[index].first)
                }
                .setNegativeButton("Batal", null)
                .show()
        }.onFailure { reportFailure("Import dari Download gagal", it) }
    }

    private fun queryDownloadProfiles(): List<Pair<android.net.Uri, String>> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return emptyList()
        val projection = arrayOf(
            android.provider.MediaStore.Downloads._ID,
            android.provider.MediaStore.Downloads.DISPLAY_NAME
        )
        val results = mutableListOf<Pair<android.net.Uri, String>>()
        contentResolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${android.provider.MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!name.endsWith(".json", ignoreCase = true)) continue
                results.add(
                    android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    ) to name
                )
            }
        }
        return results
    }

    private fun reportFailure(title: String, error: Throwable) {
        MacroDiagnostics.record(this, "file_operation_failed", "$title: ${error.javaClass.simpleName} ${error.message}")
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Some providers reject the truncate mode "wt"; fall back to plain "w".
     */
    private fun openOutput(uri: android.net.Uri): java.io.OutputStream? =
        runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: contentResolver.openOutputStream(uri, "w")

    private fun exportDiagnostics(uri: android.net.Uri) {
        runCatching {
            openOutput(uri)?.use { MacroDiagnostics.export(this, it) }
                ?: error("Dokumen tidak dapat dibuka")
        }.onSuccess {
            Toast.makeText(this, "Log diagnostik berhasil diekspor", Toast.LENGTH_LONG).show()
        }.onFailure { reportFailure("Export log gagal", it) }
    }

    private fun exportProfiles(uri: android.net.Uri) {
        runCatching {
            val json = repository.exportJson()
            val stream = openOutput(uri) ?: error("Dokumen tidak dapat dibuka")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }.onSuccess {
            MacroDiagnostics.record(this, "export_ok")
            Toast.makeText(this, "Semua profil berhasil diekspor", Toast.LENGTH_LONG).show()
        }.onFailure { reportFailure("Export gagal", it) }
    }

    private fun importProfiles(uri: android.net.Uri) {
        runCatching {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                ?.use(::readBoundedText)
                ?: error("Dokumen tidak dapat dibuka")
            val json = DocumentIntents.stripByteOrderMark(raw)
            require(json.isNotEmpty()) { "File profil kosong" }
            repository.importJson(json)
        }.onSuccess {
            refreshProfiles(repository.getActiveProfile().name)
            MacroDiagnostics.record(this, "import_ok", "profiles=$it")
            Toast.makeText(this, "$it profil berhasil diimpor", Toast.LENGTH_LONG).show()
        }.onFailure { reportFailure("Import gagal", it) }
    }

    private fun readBoundedText(reader: Reader): String {
        val result = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (result.length + count > MAX_IMPORT_CHARS) {
                error("File profil terlalu besar; maksimum 2 MB")
            }
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private fun refreshServiceStatus() {
        val enabled = AutoClickAccessibilityService.isEnabled(this)
        statusText.text = if (enabled) "Aktif" else "Belum aktif"
        statusText.setTextColor(if (enabled) Color.rgb(22, 163, 74) else Color.rgb(220, 38, 38))
        showControlsButton.isEnabled = enabled
        showControlsButton.alpha = if (enabled) 1f else 0.55f
        if (::showSmartButton.isInitialized) {
            showSmartButton.isEnabled = enabled
            showSmartButton.alpha = if (enabled) 1f else 0.55f
        }
        if (::smartStatusText.isInitialized) updateSmartStatus()
    }

    private fun refreshSmartProfiles(preferredName: String?) {
        isRefreshingSmart = true
        val profiles = smartRepository.getProfiles()
        smartProfileAdapter.clear()
        smartProfileAdapter.addAll(profiles.map { it.name })
        smartProfileAdapter.notifyDataSetChanged()
        val activeName = preferredName?.takeIf { name -> profiles.any { it.name == name } }
            ?: smartRepository.getActiveProfile().name
        val index = profiles.indexOfFirst { it.name == activeName }.coerceAtLeast(0)
        selectedSmartName = profiles[index].name
        smartProfileSpinner.setSelection(index, false)
        smartRepository.setActiveProfile(profiles[index].name)
        isRefreshingSmart = false
        loadSmartFields()
    }

    private fun loadSmartFields() {
        val profile = selectedSmartName?.let { name ->
            smartRepository.getProfiles().firstOrNull { it.name == name }
        } ?: return
        smartNameInput.setText(profile.name)
        smartCountdownInput.setText(profile.countdownSeconds.toString())
        smartKeepScreenSwitch.isChecked = profile.keepScreenOn
        smartAppGuardSwitch.isChecked = profile.stopOnAppChange
        updateSmartStatus()
    }

    private fun updateSmartStatus() {
        val name = selectedSmartName ?: return
        val profile = smartRepository.getProfiles().firstOrNull { it.name == name } ?: return
        if (profile.points.isEmpty()) {
            smartStatusText.text = "Belum ada titik. Buka editor, lalu ketuk ＋ Ketuk Titik di Layar."
            return
        }
        val ready = profile.points.count {
            it.enabled && (it.colorCaptured || it.condition == SmartCondition.COLOR_CHANGE)
        }
        val speed = when (profile.speedMode) {
            SmartSpeedMode.RECOMMENDED -> "Rekomendasi"
            SmartSpeedMode.FAST -> "Cepat"
            SmartSpeedMode.CUSTOM -> "Custom"
        }
        val loop = if (profile.loopLimit == 0) "loop tanpa batas" else "loop ${profile.loopLimit}×"
        smartStatusText.text =
            "${profile.points.size} titik, $ready siap.\n" +
            "Urut 1→${profile.points.size} • $loop • $speed"
    }

    private fun saveSmartSettings(showConfirmation: Boolean, refreshUi: Boolean = true): SmartProfile? {
        val previousName = selectedSmartName ?: return null
        val current = smartRepository.getProfiles().firstOrNull { it.name == previousName } ?: return null
        val requested = smartNameInput.text.toString().trim()
        if (requested.isEmpty()) return fieldError(smartNameInput, "Nama tidak boleh kosong")
        val countdown = smartCountdownInput.text.toString().toIntOrNull()
        if (countdown == null || countdown !in 0..SmartProfile.MAX_COUNTDOWN_SECONDS) {
            return fieldError(smartCountdownInput, "Gunakan 0–${SmartProfile.MAX_COUNTDOWN_SECONDS}")
        }
        val saved = runCatching {
            smartRepository.saveProfile(
                current.copy(
                    name = requested,
                    countdownSeconds = countdown,
                    keepScreenOn = smartKeepScreenSwitch.isChecked,
                    stopOnAppChange = smartAppGuardSwitch.isChecked
                ),
                previousName
            )
        }.getOrElse { reportFailure("Profil smart gagal disimpan", it); return null }
        AutoClickAccessibilityService.instance?.notifySmartProfileSaved(previousName, saved)
        selectedSmartName = saved.name
        if (refreshUi) refreshSmartProfiles(saved.name)
        if (showConfirmation) Toast.makeText(this, "Profil ${saved.name} disimpan", Toast.LENGTH_SHORT).show()
        return saved
    }

    private fun showNewSmartProfileDialog() {
        val input = EditText(this).apply { hint = "Nama profil smart"; setSingleLine(true) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Profil Smart Baru").setView(input).setNegativeButton("Batal", null)
            .setPositiveButton("Buat", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.toString().trim().isEmpty()) input.error = "Masukkan nama profil"
                else runCatching {
                    val created = smartRepository.createProfile(input.text.toString())
                    refreshSmartProfiles(created.name)
                    dialog.dismiss()
                }.onFailure { reportFailure("Profil smart gagal dibuat", it) }
            }
        }
        dialog.show()
    }

    private fun deleteSelectedSmartProfile() {
        val name = selectedSmartName ?: return
        AlertDialog.Builder(this).setTitle("Hapus profil smart?")
            .setMessage("Profil $name beserta titik smart-nya akan dihapus. Macro biasa tidak terpengaruh.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                runCatching {
                    val next = smartRepository.deleteProfile(name)
                    AutoClickAccessibilityService.instance?.notifySmartProfileDeleted(name)
                    refreshSmartProfiles(next.name)
                }.onFailure { reportFailure("Profil smart gagal dihapus", it) }
            }.show()
    }

    private fun showSmartEditor() {
        val service = AutoClickAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Layanan aksesibilitas belum aktif.", Toast.LENGTH_LONG).show()
            return
        }
        val saved = saveSmartSettings(showConfirmation = false) ?: return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            AlertDialog.Builder(this).setTitle("Belum didukung")
                .setMessage("Multi Smart Click membutuhkan Android 11 atau lebih baru. Macro Biasa tetap dapat dipakai.")
                .setPositiveButton("Tutup", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Izin baca layar")
            .setMessage(
                "Multi Smart Click mengambil gambar layar untuk membaca warna di titik yang Anda pilih. " +
                    "Gambar tidak disimpan dan tidak dikirim ke jaringan.\n\n" +
                    "Jika tombol baca layar belum aktif, matikan lalu nyalakan ulang layanan aksesibilitas."
            )
            .setNegativeButton("Batal", null)
            .setPositiveButton("Buka Editor") { _, _ ->
                service.showSmartProfile(saved)
                moveTaskToBack(true)
            }.show()
    }

    /** Smart profiles use the same guarded Download path as the classic profiles. */
    private fun exportSmartToDownloads() {
        if (saveSmartSettings(showConfirmation = false) == null) return
        val json = runCatching { smartRepository.exportJson() }.getOrElse {
            reportFailure("Export smart gagal", it); return
        }
        saveToDownloadsOrExplain(
            "rich-clicker-smart-profiles.json", "application/json", json, "Profil smart",
            pickerFallback = false
        )
    }

    private fun importSmartFromDownloads() {
        runCatching {
            val candidates = queryDownloadProfiles()
            if (candidates.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Tidak ada file profil")
                    .setMessage(
                        "Belum ada file .json hasil export aplikasi ini di folder Download. " +
                            "Lakukan Export Smart terlebih dahulu. Alternatif ini butuh Android 10+."
                    )
                    .setPositiveButton("OK", null).show()
                return
            }
            val labels = candidates.map { it.second }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Pilih file smart")
                .setItems(labels) { _, index -> importSmartProfiles(candidates[index].first) }
                .setNegativeButton("Batal", null).show()
        }.onFailure { reportFailure("Import smart gagal", it) }
    }

    private fun importSmartProfiles(uri: android.net.Uri) {
        runCatching {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                ?.use(::readBoundedText)
                ?: error("Dokumen tidak dapat dibuka")
            val json = DocumentIntents.stripByteOrderMark(raw)
            require(json.isNotEmpty()) { "File profil kosong" }
            smartRepository.importJson(json)
        }.onSuccess { count ->
            refreshSmartProfiles(smartRepository.getActiveProfile().name)
            MacroDiagnostics.record(this, "smart_import_ok", "profiles=$count")
            Toast.makeText(this, "$count profil smart diimpor", Toast.LENGTH_LONG).show()
        }.onFailure { reportFailure("Import smart gagal", it) }
    }

    private fun verticalCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = roundedBackground(Color.WHITE, 16f, Color.rgb(226, 232, 240))
        elevation = dp(2).toFloat()
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 12f; setTextColor(Color.rgb(79, 70, 229))
        setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(Color.rgb(71, 85, 105))
    }

    private fun helpText(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(Color.rgb(71, 85, 105))
    }

    private fun textInput(hintValue: String, typeValue: Int) = EditText(this).apply {
        hint = hintValue; inputType = typeValue; setSingleLine(true); setSelectAllOnFocus(true)
    }

    private fun decimalInput(hintValue: String) = textInput(
        hintValue,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    )

    private fun standardButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setOnClickListener { action() }
    }

    private fun primaryButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(Color.WHITE)
        background = roundedBackground(Color.rgb(79, 70, 229), 12f)
        setOnClickListener { action() }
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun roundedBackground(color: Int, radiusDp: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radiusDp.toInt()).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun matchWrapParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val APP_VERSION_LABEL = "0.9.4"
        private const val HOME_MODE_CLASSIC = 0
        private const val HOME_MODE_SMART = 1
        private const val STATE_HOME_MODE = "home_mode"
        private const val STATE_TOOLS_EXPANDED = "tools_expanded"
        private const val REQUEST_EXPORT = 1001
        private const val REQUEST_IMPORT = 1002
        private const val REQUEST_EXPORT_LOG = 1003
        private const val MAX_IMPORT_CHARS = 2_000_000
        private const val STATE_PROFILE_NAME = "draft_profile_name"
        private const val STATE_DEFAULT_DELAY = "draft_default_delay"
        private const val STATE_LOOP_DELAY = "draft_loop_delay"
        private const val STATE_LOOP_LIMIT = "draft_loop_limit"
        private const val STATE_COUNTDOWN = "draft_countdown"
        private const val STATE_KEEP_SCREEN_ON = "draft_keep_screen_on"
        private const val STATE_STOP_ON_APP_CHANGE = "draft_stop_on_app_change"
    }
}
