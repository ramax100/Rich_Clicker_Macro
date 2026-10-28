package com.example.autoclicker

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
import com.example.autoclicker.accessibility.AutoClickAccessibilityService
import com.example.autoclicker.data.ClickPreferences
import com.example.autoclicker.data.MacroProfile
import com.example.autoclicker.data.MacroRepository
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : Activity() {
    private lateinit var repository: MacroRepository
    private lateinit var statusText: TextView
    private lateinit var profileSpinner: Spinner
    private lateinit var profileAdapter: ArrayAdapter<String>
    private lateinit var profileNameInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var loopDelayInput: EditText
    private lateinit var loopLimitInput: EditText
    private lateinit var countdownInput: EditText
    private lateinit var keepScreenOnSwitch: Switch
    private lateinit var showControlsButton: Button

    private var selectedProfileName: String? = null
    private var isRefreshingProfiles = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = MacroRepository(this)
        setContentView(createContentView())
        refreshProfiles(repository.getActiveProfile().name)
        savedInstanceState?.let(::restoreDraft)
        if (repository.consumeRecoveryNotice()) {
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
    }

    private fun restoreDraft(state: Bundle) {
        profileNameInput.setText(state.getString(STATE_PROFILE_NAME, profileNameInput.text.toString()))
        delayInput.setText(state.getString(STATE_DEFAULT_DELAY, delayInput.text.toString()))
        loopDelayInput.setText(state.getString(STATE_LOOP_DELAY, loopDelayInput.text.toString()))
        loopLimitInput.setText(state.getString(STATE_LOOP_LIMIT, loopLimitInput.text.toString()))
        countdownInput.setText(state.getString(STATE_COUNTDOWN, countdownInput.text.toString()))
        keepScreenOnSwitch.isChecked = state.getBoolean(STATE_KEEP_SCREEN_ON, keepScreenOnSwitch.isChecked)
    }

    @Deprecated("Menggunakan Activity Result lama agar proyek tetap tanpa dependensi tambahan")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_EXPORT -> exportProfiles(data)
            REQUEST_IMPORT -> importProfiles(data)
        }
    }

    private fun createContentView(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        content.addView(ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Logo Rich Clicker Macro"
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            bottomMargin = dp(12)
        })
        content.addView(TextView(this).apply {
            text = "Rich Clicker Macro"
            textSize = 30f
            setTextColor(Color.rgb(15, 23, 42))
            setTypeface(typeface, Typeface.BOLD)
        }, matchWrapParams(bottom = 6))
        content.addView(TextView(this).apply {
            text = "Macro multi-aksi yang tetap berjalan di atas aplikasi lain."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(71, 85, 105))
        }, matchWrapParams(bottom = 22))

        content.addView(createServiceCard(), matchWrapParams(bottom = 14))
        content.addView(createProfileCard(), matchWrapParams(bottom = 14))
        content.addView(createExecutionCard(), matchWrapParams(bottom = 14))
        content.addView(createEditorCard(), matchWrapParams(bottom = 14))
        content.addView(createDataCard(), matchWrapParams(bottom = 14))
        content.addView(createUsageCard(), matchWrapParams())

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun createServiceCard() = verticalCard().apply {
        addView(label("1  LAYANAN"))
        statusText = TextView(this@MainActivity).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(statusText, matchWrapParams(top = 8, bottom = 10))
        addView(standardButton("Buka Pengaturan Aksesibilitas") {
            saveProfileSettings(showConfirmation = false)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWrapParams(bottom = 8))
        addView(helpText("Tambahkan tile “Rich Clicker” dari panel Quick Settings Android untuk mulai/berhenti macro yang sudah dimuat."))
    }

    private fun createProfileCard() = verticalCard().apply {
        addView(label("2  PROFIL"))
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
        addView(label("3  EKSEKUSI"))
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
        addView(primaryButton("Simpan Pengaturan") {
            saveProfileSettings(showConfirmation = true)
        })
    }

    private fun createEditorCard() = verticalCard().apply {
        addView(label("4  EDITOR MACRO"))
        addView(helpText("Setiap titik dapat menggunakan Tap, Tahan, atau Geser. Durasi aksi dan jumlah pengulangan diatur dari toolbar mengambang."), matchWrapParams(top = 8, bottom = 10))
        showControlsButton = primaryButton("Tampilkan Editor di Atas Aplikasi") {
            showFloatingControls()
        }
        addView(showControlsButton)
    }

    private fun createDataCard() = verticalCard().apply {
        addView(label("5  DATA PROFIL"))
        addView(helpText("Import dan export mencakup posisi, aksi, endpoint swipe, durasi, jumlah pengulangan, serta pengaturan eksekusi."), matchWrapParams(top = 8, bottom = 8))
        addView(buttonRow(
            standardButton("Import JSON") { openImportPicker() },
            standardButton("Export JSON") { openExportPicker() }
        ))
    }

    private fun createUsageCard() = TextView(this).apply {
        text = "Panduan singkat\n• + / −: tambah atau hapus titik.\n• ↑ / ↓: ubah urutan.\n• Ketuk label delay: atur jeda.\n• Ketuk jenis aksi: Tap → Tahan → Geser.\n• Ketuk durasi atau ×N: input langsung.\n• Marker E adalah tujuan swipe.\n• ▶ mulai, ■ di menu menghentikan."
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
        val saved = repository.saveProfile(
            current.copy(
                name = requestedName,
                defaultDelayMs = defaultDelay,
                loopDelayMs = loopDelay,
                loopLimit = loopLimit,
                countdownSeconds = countdown,
                keepScreenOn = keepScreenOnSwitch.isChecked
            ),
            previousName
        )
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
                    if (saveProfileSettings(showConfirmation = false, refreshUi = false) == null) return@setOnClickListener
                    val created = repository.createProfile(input.text.toString())
                    refreshProfiles(created.name)
                    dialog.dismiss()
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
                AutoClickAccessibilityService.instance?.notifyProfileDeleted(name)
                refreshProfiles(repository.deleteProfile(name).name)
            }.show()
    }

    private fun showFloatingControls() {
        val profile = saveProfileSettings(false) ?: return
        if (!AutoClickAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Aktifkan layanan aksesibilitas terlebih dahulu", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "rich-clicker-macro-profiles.json")
        }, REQUEST_EXPORT)
    }

    private fun openImportPicker() {
        if (saveProfileSettings(false) == null) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
        }, REQUEST_IMPORT)
    }

    private fun exportProfiles(data: Intent) {
        val uri = data.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(repository.exportJson()) }
                ?: error("Dokumen tidak dapat dibuka")
        }.onSuccess { Toast.makeText(this, "Semua profil berhasil diekspor", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(this, "Export gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun importProfiles(data: Intent) {
        val uri = data.data ?: return
        runCatching {
            val json = contentResolver.openInputStream(uri)?.use(::readBoundedUtf8)
                ?: error("Dokumen tidak dapat dibuka")
            repository.importJson(json)
        }.onSuccess {
            refreshProfiles(repository.getActiveProfile().name)
            Toast.makeText(this, "$it profil berhasil diimpor", Toast.LENGTH_LONG).show()
        }.onFailure { Toast.makeText(this, "Import gagal: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun readBoundedUtf8(input: InputStream): String {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > MAX_IMPORT_BYTES) {
                error("File profil terlalu besar; maksimum 2 MB")
            }
            result.write(buffer, 0, count)
        }
        return result.toString(Charsets.UTF_8.name())
    }

    private fun refreshServiceStatus() {
        val enabled = AutoClickAccessibilityService.isEnabled(this)
        statusText.text = if (enabled) "Aktif" else "Belum aktif"
        statusText.setTextColor(if (enabled) Color.rgb(22, 163, 74) else Color.rgb(220, 38, 38))
        showControlsButton.isEnabled = enabled
        showControlsButton.alpha = if (enabled) 1f else 0.55f
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
        private const val REQUEST_EXPORT = 1001
        private const val REQUEST_IMPORT = 1002
        private const val MAX_IMPORT_BYTES = 2_000_000
        private const val STATE_PROFILE_NAME = "draft_profile_name"
        private const val STATE_DEFAULT_DELAY = "draft_default_delay"
        private const val STATE_LOOP_DELAY = "draft_loop_delay"
        private const val STATE_LOOP_LIMIT = "draft_loop_limit"
        private const val STATE_COUNTDOWN = "draft_countdown"
        private const val STATE_KEEP_SCREEN_ON = "draft_keep_screen_on"
    }
}
