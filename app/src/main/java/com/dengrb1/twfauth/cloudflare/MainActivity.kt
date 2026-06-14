package com.dengrb1.twfauth.cloudflare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dengrb1.twfauth.cloudflare.databinding.ActivityMainBinding
import com.dengrb1.twfauth.cloudflare.databinding.DialogEntryBinding
import com.dengrb1.twfauth.cloudflare.databinding.DialogGroupBinding
import com.dengrb1.twfauth.cloudflare.databinding.DialogImportExportBinding
import com.dengrb1.twfauth.cloudflare.databinding.DialogImportTextBinding
import com.dengrb1.twfauth.cloudflare.databinding.DialogPasswordBinding
import com.dengrb1.twfauth.cloudflare.databinding.ItemEntryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applySaved(newBase))
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var tokenStore: TokenStore
    private lateinit var scannerLauncher: ActivityResultLauncher<ScanOptions>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private val settings by lazy { AppSettings(this) }
    private val api = ApiClient(BuildConfig.WORKER_URL)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = EntryAdapter(
        onEntryClick = { entry -> handleEntryTap(entry) },
        onEditClick = { entry -> showEntryDialog(entry = entry) },
        onDeleteClick = { entry -> confirmDelete(entry) },
        onEnabledChange = { entry, enabled -> setEntryEnabled(entry, enabled) },
    )

    private var isUnlocked = false
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var accessExpiresAtMillis = 0L
    private var entries = emptyList<Entry>()
    private var groups = emptyList<Group>()
    private var searchQuery = ""
    private var selectedGroupId: Long? = null
    private var showUngroupedOnly = false
    private var isManageMode = false
    private var refreshInFlight = false
    private var pendingExport: ExportRequest? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateCountdowns()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeMode(settings.themeMode)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scannerLauncher = registerForActivityResult(ScanContract()) { result ->
            val contents = result.contents?.trim()
            if (!contents.isNullOrBlank()) {
                showEntryDialog(initialUri = contents)
            }
        }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writePendingExport(it) }
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readImportFile(it) }
        }

        tokenStore = TokenStore(this)
        binding.serverText.text = BuildConfig.WORKER_URL
        binding.versionText.text = "v${BuildConfig.VERSION_NAME}"
        binding.entriesList.layoutManager = LinearLayoutManager(this)
        binding.entriesList.adapter = adapter

        binding.loginButton.setOnClickListener { login() }
        binding.newButton.setOnClickListener { showEntryDialog() }
        binding.scanButton.setOnClickListener { startQrScan() }
        binding.manageButton.setOnClickListener { showManageMenu() }
        binding.filterText.setOnClickListener { showGroupFilter() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                applyEntryFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.passwordInput.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (done) {
                login()
                true
            } else {
                false
            }
        }

        val saved = tokenStore.load()
        if (saved != null) {
            accessToken = saved.accessToken
            refreshToken = saved.refreshToken
            accessExpiresAtMillis = saved.accessExpiresAtMillis
            authenticateUser()
        } else {
            showLogin()
        }
    }

    private fun authenticateUser() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            showError(getString(R.string.auth_unavailable))
            showLogin()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isUnlocked = true
                    showContent()
                    refreshEntries()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    showError(getString(R.string.auth_error, errString))
                    showLogin()
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_title))
            .setSubtitle(getString(R.string.auth_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun login() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            showError(getString(R.string.username_required))
            return
        }

        setLoading(true)
        showError(null)
        executor.execute {
            try {
                val session = api.login(username, password)
                tokenStore.save(session)
                accessToken = session.accessToken
                refreshToken = session.refreshToken
                accessExpiresAtMillis = session.accessExpiresAtMillis
                isUnlocked = true
                runOnUiThread {
                    binding.passwordInput.text = null
                    showContent()
                    refreshEntries()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.login_failed, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun refreshEntries() {
        if (!isUnlocked || refreshInFlight) return
        refreshInFlight = true
        setLoading(true)
        showError(null)
        executor.execute {
            try {
                val token = validAccessToken()
                val appData = api.appData(token)
                val loadedGroups = appData.groups
                val loadedEntries = appData.entries
                val totpIds = loadedEntries.filter { it.enabled && it.otpType != "hotp" }.map { it.id }
                val codes = if (totpIds.isNotEmpty()) api.codesBatch(token, totpIds) else emptyMap()
                val merged = loadedEntries.map { entry ->
                    val code = codes[entry.id]
                    if (entry.enabled && code != null) {
                        entry.copy(code = code.code, expiresIn = code.expiresIn)
                    } else {
                        entry.copy(code = "", expiresIn = null)
                    }
                }
                entries = merged
                groups = loadedGroups
                runOnUiThread {
                    applyEntryFilter()
                    setLoading(false)
                    refreshInFlight = false
                }
            } catch (e: UnauthorizedException) {
                runOnUiThread {
                    refreshInFlight = false
                    clearSession()
                    showError(getString(R.string.session_expired))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    refreshInFlight = false
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun handleEntryTap(entry: Entry) {
        if (isManageMode) {
            showEntryDialog(entry = entry)
            return
        }
        if (!entry.enabled) {
            Toast.makeText(this, R.string.disabled_entry, Toast.LENGTH_SHORT).show()
            return
        }
        if (entry.otpType == "hotp") {
            generateHotp(entry)
        } else if (entry.code.isNotBlank()) {
            copyCode(entry.code)
        }
    }

    private fun generateHotp(entry: Entry) {
        if (!entry.enabled) return
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                val code = api.consumeHotp(token, entry.id)
                val updated = entries.map {
                    if (it.id == entry.id) {
                        it.copy(code = code.code, expiresIn = null, hotpCounter = code.nextCounter)
                    } else {
                        it
                    }
                }
                entries = updated
                runOnUiThread {
                    applyEntryFilter()
                    setLoading(false)
                    copyCode(code.code)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun validAccessToken(): String {
        val token = accessToken
        val refresh = refreshToken
        if (!token.isNullOrBlank() && System.currentTimeMillis() < accessExpiresAtMillis - TOKEN_REFRESH_SKEW_MS) {
            return token
        }
        if (refresh.isNullOrBlank()) throw UnauthorizedException()
        val session = api.refresh(refresh)
        tokenStore.save(session)
        accessToken = session.accessToken
        refreshToken = session.refreshToken
        accessExpiresAtMillis = session.accessExpiresAtMillis
        return session.accessToken
    }

    private fun logout() {
        val token = accessToken
        if (!token.isNullOrBlank()) {
            executor.execute {
                runCatching { api.logout(token) }
            }
        }
        clearSession()
    }

    private fun startQrScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(true)
        scannerLauncher.launch(options)
    }

    private fun showEntryDialog(entry: Entry? = null, initialUri: String = "") {
        val dialogBinding = DialogEntryBinding.inflate(layoutInflater)
        val isEdit = entry != null
        val typeValues = listOf("totp", "hotp")
        val algorithmValues = listOf("SHA-256", "SHA-512")
        val groupLabels = listOf(getString(R.string.no_group)) + groups.map { it.name }

        dialogBinding.uriInput.setText(initialUri)
        dialogBinding.uriInput.isEnabled = !isEdit
        dialogBinding.secretInput.isEnabled = !isEdit
        dialogBinding.labelInput.setText(entry?.label.orEmpty())
        dialogBinding.issuerInput.setText(entry?.issuer.orEmpty())
        dialogBinding.secretInput.setText("")
        dialogBinding.typeInput.setSimpleItems(typeValues)
        dialogBinding.algorithmInput.setSimpleItems(algorithmValues)
        dialogBinding.groupInput.setSimpleItems(groupLabels)
        dialogBinding.typeInput.setText(entry?.otpType ?: "totp", false)
        dialogBinding.algorithmInput.setText(entry?.algorithm?.takeIf { it != "SHA-1" } ?: "SHA-256", false)
        dialogBinding.digitsInput.setText((entry?.digits ?: 6).toString())
        dialogBinding.periodInput.setText((entry?.period ?: 30).toString())
        dialogBinding.counterInput.setText((entry?.hotpCounter ?: 0L).toString())
        val selectedGroup = groups.firstOrNull { it.id == entry?.groupId }?.name ?: getString(R.string.no_group)
        dialogBinding.groupInput.setText(selectedGroup, false)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isEdit) R.string.edit_entry else R.string.new_entry)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val draft = dialogBinding.toEntryDraft(groups, isEdit, entry?.enabled ?: true)
                if (draft == null) {
                    showError(getString(R.string.entry_required))
                    return@setOnClickListener
                }
                dialog.dismiss()
                saveEntry(entry, draft)
            }
        }
        dialog.show()
    }

    private fun DialogEntryBinding.toEntryDraft(
        availableGroups: List<Group>,
        isEdit: Boolean,
        enabled: Boolean,
    ): EntryDraft? {
        val uri = uriInput.text?.toString()?.trim().orEmpty()
        val label = labelInput.text?.toString()?.trim().orEmpty()
        val secret = secretInput.text?.toString()?.trim().orEmpty()
        if (!isEdit && uri.isBlank() && (label.isBlank() || secret.isBlank())) return null
        if (isEdit && label.isBlank()) return null
        val groupName = groupInput.text?.toString()?.trim().orEmpty()
        return EntryDraft(
            otpauthUri = uri,
            label = label,
            issuer = issuerInput.text?.toString()?.trim().orEmpty(),
            secret = secret,
            otpType = typeInput.text?.toString()?.trim()?.lowercase()?.takeIf { it == "hotp" } ?: "totp",
            algorithm = algorithmInput.text?.toString()?.trim()?.takeIf { it in listOf("SHA-256", "SHA-512") } ?: "SHA-256",
            digits = digitsInput.text?.toString()?.toIntOrNull() ?: 6,
            period = periodInput.text?.toString()?.toIntOrNull() ?: 30,
            hotpCounter = counterInput.text?.toString()?.toLongOrNull() ?: 0L,
            groupId = availableGroups.firstOrNull { it.name == groupName }?.id,
            includeSecret = !isEdit || secret.isNotBlank(),
            enabled = enabled,
        )
    }

    private fun AutoCompleteTextView.setSimpleItems(values: List<String>) {
        setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, values))
    }

    private fun saveEntry(entry: Entry?, draft: EntryDraft) {
        setLoading(true)
        showError(null)
        executor.execute {
            try {
                val token = validAccessToken()
                if (entry == null) {
                    api.createEntry(token, draft)
                } else {
                    api.updateEntry(token, entry.id, draft)
                }
                runOnUiThread {
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    refreshInFlight = false
                    refreshEntries()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun setEntryEnabled(entry: Entry, enabled: Boolean) {
        val previous = entries
        entries = entries.map {
            if (it.id == entry.id) it.copy(enabled = enabled, code = "", expiresIn = null) else it
        }
        applyEntryFilter()
        executor.execute {
            try {
                val token = validAccessToken()
                api.setEntryEnabled(token, entry.id, enabled)
                if (enabled) {
                    runOnUiThread { refreshEntries() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    entries = previous
                    applyEntryFilter()
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun confirmDelete(entry: Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_entry)
            .setMessage(getString(R.string.delete_entry_confirm, entry.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteEntry(entry) }
            .show()
    }

    private fun deleteEntry(entry: Entry) {
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                api.deleteEntry(token, entry.id)
                entries = entries.filterNot { it.id == entry.id }
                runOnUiThread {
                    applyEntryFilter()
                    setLoading(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun toggleManageMode() {
        isManageMode = !isManageMode
        adapter.setManageMode(isManageMode)
        binding.manageButton.text = getString(if (isManageMode) R.string.done else R.string.manage)
    }

    private fun showGroupFilter() {
        val labels = listOf(getString(R.string.filter_all), getString(R.string.no_group)) + groups.map { it.name }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        selectedGroupId = null
                        showUngroupedOnly = false
                    }
                    which == 1 -> {
                        selectedGroupId = null
                        showUngroupedOnly = true
                    }
                    else -> {
                        selectedGroupId = groups.getOrNull(which - 2)?.id
                        showUngroupedOnly = false
                    }
                }
                applyEntryFilter()
            }
            .show()
    }

    private fun showManageMenu() {
        val labels = listOf(
            getString(if (isManageMode) R.string.done else R.string.manage_entries),
            getString(R.string.export_otp),
            getString(R.string.import_otp),
            getString(R.string.new_group),
            getString(R.string.manage_groups),
            getString(R.string.change_password),
            getString(R.string.theme_named, themeLabel(settings.themeMode)),
            getString(R.string.refresh),
            getString(R.string.language_named, LocaleHelper.displayNameFor(LocaleHelper.getSavedLanguage(this))),
            getString(R.string.logout),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> toggleManageMode()
                    1 -> showExportDialog()
                    2 -> showImportChoice()
                    3 -> showGroupManageDialog()
                    4 -> showChangePasswordDialog()
                    5 -> showThemeDialog()
                    6 -> refreshEntries()
                    7 -> toggleLanguage()
                    8 -> logout()
                }
            }
            .show()
    }

    private fun showGroupDialog() {
        val dialogBinding = DialogGroupBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_group)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                val color = dialogBinding.colorInput.text?.toString()?.trim().orEmpty().ifBlank { "#0f766e" }
                if (name.isBlank()) {
                    showError(getString(R.string.group_name_required))
                } else {
                    createGroup(name, color)
                }
            }
            .show()
    }

    private fun showGroupManageDialog() {
        val labels = listOf(getString(R.string.new_group)) + groups.map { it.name }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manage_groups)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    showGroupDialog()
                } else {
                    groups.getOrNull(which - 1)?.let { showGroupActions(it) }
                }
            }
            .show()
    }

    private fun showGroupActions(group: Group) {
        MaterialAlertDialogBuilder(this)
            .setTitle(group.name)
            .setItems(arrayOf(getString(R.string.delete_group))) { _, _ -> deleteGroup(group) }
            .show()
    }

    private fun createGroup(name: String, color: String) {
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                api.createGroup(token, name, color)
                runOnUiThread {
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    refreshEntries()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun showDeleteGroupDialog() {
        if (groups.isEmpty()) {
            showError(getString(R.string.no_group_to_delete))
            return
        }
        val labels = groups.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_group)
            .setItems(labels) { _, which ->
                val group = groups.getOrNull(which) ?: return@setItems
                deleteGroup(group)
            }
            .show()
    }

    private fun deleteGroup(group: Group) {
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                api.deleteGroup(token, group.id)
                if (selectedGroupId == group.id) selectedGroupId = null
                runOnUiThread {
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
                    refreshEntries()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun showExportDialog() {
        val dialogBinding = DialogImportExportBinding.inflate(layoutInflater)
        val formats = listOf(
            getString(R.string.format_encrypted_json),
            getString(R.string.format_plain_json),
            getString(R.string.format_otpauth),
        )
        dialogBinding.formatInput.setSimpleItems(formats)
        dialogBinding.formatInput.setText(formats.first(), false)

        fun updateFields() {
            val encrypted = dialogBinding.formatInput.text.toString() == formats[0]
            dialogBinding.passphraseLayout.isVisible = encrypted
            dialogBinding.passwordLayout.isVisible = !encrypted
        }
        dialogBinding.formatInput.setOnItemClickListener { _, _, _, _ -> updateFields() }
        updateFields()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_otp)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export_otp, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val format = when (dialogBinding.formatInput.text.toString()) {
                    formats[1] -> ExportFormat.PLAIN_JSON
                    formats[2] -> ExportFormat.OTPAUTH
                    else -> ExportFormat.ENCRYPTED_JSON
                }
                val password = dialogBinding.passwordInput.text?.toString().orEmpty()
                val passphrase = dialogBinding.passphraseInput.text?.toString().orEmpty()
                if (format == ExportFormat.ENCRYPTED_JSON && passphrase.isBlank()) {
                    showError(getString(R.string.passphrase_required))
                    return@setOnClickListener
                }
                if (format != ExportFormat.ENCRYPTED_JSON && password.isBlank()) {
                    showError(getString(R.string.password_required))
                    return@setOnClickListener
                }
                if (format != ExportFormat.ENCRYPTED_JSON) {
                    Toast.makeText(this, R.string.plaintext_export_warning, Toast.LENGTH_LONG).show()
                }
                pendingExport = ExportRequest(format, password, passphrase)
                exportLauncher.launch(exportFileName(format))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun writePendingExport(uri: Uri) {
        val request = pendingExport ?: return
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                val text = when (request.format) {
                    ExportFormat.ENCRYPTED_JSON -> api.exportEncrypted(token, request.passphrase)
                    ExportFormat.PLAIN_JSON -> api.exportPlain(token, request.password)
                    ExportFormat.OTPAUTH -> api.exportOtpAuth(token, request.password)
                }
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw ApiException("Cannot open export target")
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            } finally {
                pendingExport = null
            }
        }
    }

    private fun showImportChoice() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_otp)
            .setItems(arrayOf(getString(R.string.choose_import_file), getString(R.string.paste_import_content))) { _, which ->
                if (which == 0) {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                } else {
                    showImportTextDialog()
                }
            }
            .show()
    }

    private fun readImportFile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }.orEmpty()
            showImportTextDialog(text)
        } catch (e: Exception) {
            showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
        }
    }

    private fun showImportTextDialog(initialText: String = "") {
        val dialogBinding = DialogImportTextBinding.inflate(layoutInflater)
        val formats = listOf(
            getString(R.string.format_plain_json),
            getString(R.string.format_otpauth),
            getString(R.string.format_encrypted_json),
        )
        dialogBinding.formatInput.setSimpleItems(formats)
        dialogBinding.formatInput.setText(detectImportFormat(initialText, formats), false)
        dialogBinding.importTextInput.setText(initialText)

        fun updateFields() {
            dialogBinding.passphraseLayout.isVisible = dialogBinding.formatInput.text.toString() == formats[2]
        }
        dialogBinding.formatInput.setOnItemClickListener { _, _, _, _ -> updateFields() }
        updateFields()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_otp)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_otp, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = dialogBinding.importTextInput.text?.toString().orEmpty()
                if (text.isBlank()) {
                    showError(getString(R.string.entry_required))
                    return@setOnClickListener
                }
                val format = when (dialogBinding.formatInput.text.toString()) {
                    formats[1] -> ImportFormat.OTPAUTH
                    formats[2] -> ImportFormat.ENCRYPTED_JSON
                    else -> ImportFormat.PLAIN_JSON
                }
                val passphrase = dialogBinding.passphraseInput.text?.toString().orEmpty()
                if (format == ImportFormat.ENCRYPTED_JSON && passphrase.isBlank()) {
                    showError(getString(R.string.passphrase_required))
                    return@setOnClickListener
                }
                dialog.dismiss()
                importOtp(text, format, passphrase)
            }
        }
        dialog.show()
    }

    private fun importOtp(text: String, format: ImportFormat, passphrase: String) {
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                when (format) {
                    ImportFormat.PLAIN_JSON -> api.importPlain(token, JSONObject(text))
                    ImportFormat.OTPAUTH -> api.importOtpAuth(token, text)
                    ImportFormat.ENCRYPTED_JSON -> {
                        val json = JSONObject(text)
                        api.importEncrypted(token, json.optJSONObject("encrypted") ?: json, passphrase)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, R.string.imported, Toast.LENGTH_SHORT).show()
                    refreshEntries()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun showChangePasswordDialog() {
        val dialogBinding = DialogPasswordBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_password)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = dialogBinding.currentPasswordInput.text?.toString().orEmpty()
                val next = dialogBinding.newPasswordInput.text?.toString().orEmpty()
                if (current.isBlank() || next.isBlank()) {
                    showError(getString(R.string.password_required))
                    return@setOnClickListener
                }
                dialog.dismiss()
                changePassword(current, next)
            }
        }
        dialog.show()
    }

    private fun changePassword(currentPassword: String, newPassword: String) {
        setLoading(true)
        executor.execute {
            try {
                val token = validAccessToken()
                api.changePassword(token, currentPassword, newPassword)
                runOnUiThread {
                    Toast.makeText(this, R.string.password_changed, Toast.LENGTH_LONG).show()
                    clearSession()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    showError(getString(R.string.network_error, e.message ?: getString(R.string.unknown_error)))
                }
            }
        }
    }

    private fun showThemeDialog() {
        val themes = ThemeMode.values()
        val labels = themes.map { themeLabel(it) }.toTypedArray()
        val checked = themes.indexOf(settings.themeMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                settings.themeMode = themes[which]
                applyThemeMode(themes[which])
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun applyThemeMode(themeMode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    private fun applyEntryFilter() {
        val query = searchQuery.trim().lowercase()
        val groupFiltered = when {
            showUngroupedOnly -> entries.filter { it.groupId == null }
            selectedGroupId != null -> entries.filter { it.groupId == selectedGroupId }
            else -> entries
        }
        val visible = if (query.isBlank()) {
            groupFiltered
        } else {
            groupFiltered.filter {
                it.label.lowercase().contains(query) ||
                    it.issuer.lowercase().contains(query) ||
                    it.groupName.lowercase().contains(query)
            }
        }
        adapter.submit(visible)
        binding.emptyText.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        val filterName = when {
            showUngroupedOnly -> getString(R.string.no_group)
            selectedGroupId != null -> groups.firstOrNull { it.id == selectedGroupId }?.name ?: getString(R.string.filter_all)
            else -> getString(R.string.filter_all)
        }
        binding.filterText.text = getString(R.string.filter_count, filterName, visible.size)
    }

    private fun exportFileName(format: ExportFormat): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = if (format == ExportFormat.OTPAUTH) "txt" else "json"
        return "2fauth-$stamp.$suffix"
    }

    private fun detectImportFormat(text: String, labels: List<String>): String {
        val trimmed = text.trim()
        return when {
            trimmed.contains("otpauth://", ignoreCase = true) -> labels[1]
            trimmed.contains("\"encrypted\"") || trimmed.contains("\"ciphertext\"") -> labels[2]
            else -> labels[0]
        }
    }

    private fun themeLabel(theme: ThemeMode): String {
        return getString(
            when (theme) {
                ThemeMode.SYSTEM -> R.string.theme_system
                ThemeMode.LIGHT -> R.string.theme_light
                ThemeMode.DARK -> R.string.theme_dark
            }
        )
    }

    private fun clearSession() {
        tokenStore.clear()
        accessToken = null
        refreshToken = null
        accessExpiresAtMillis = 0L
        isUnlocked = false
        entries = emptyList()
        groups = emptyList()
        adapter.submit(emptyList())
        showLogin()
    }

    private fun showLogin() {
        setLoading(false)
        binding.loginPanel.visibility = View.VISIBLE
        binding.contentPanel.visibility = View.GONE
        binding.emptyText.visibility = View.GONE
    }

    private fun showContent() {
        binding.loginPanel.visibility = View.GONE
        binding.contentPanel.visibility = View.VISIBLE
    }

    private fun setLoading(isLoading: Boolean) {
        binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !isLoading
        binding.newButton.isEnabled = !isLoading
        binding.scanButton.isEnabled = !isLoading
        binding.manageButton.isEnabled = !isLoading
    }

    private fun showError(message: String?) {
        binding.errorText.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.errorText.text = message.orEmpty()
    }

    private fun toggleLanguage() {
        LocaleHelper.toggle(this)
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun copyCode(code: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("2FA code", code))
        Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
    }

    private fun updateCountdowns() {
        if (entries.isEmpty()) return
        val changed = entries.map { entry ->
            val next = if (entry.enabled) entry.expiresIn?.let { max(0, it - 1) } else null
            entry.copy(expiresIn = next)
        }
        entries = changed
        applyEntryFilter()
        if (changed.any { it.enabled && it.otpType != "hotp" && it.expiresIn == 0 }) {
            refreshEntries()
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TOKEN_REFRESH_SKEW_MS = 60_000L
    }
}

private class EntryAdapter(
    private val onEntryClick: (Entry) -> Unit,
    private val onEditClick: (Entry) -> Unit,
    private val onDeleteClick: (Entry) -> Unit,
    private val onEnabledChange: (Entry, Boolean) -> Unit,
) : ListAdapter<Entry, EntryAdapter.EntryViewHolder>(EntryDiffCallback) {
    private var isManageMode = false

    fun submit(next: List<Entry>) {
        submitList(next)
    }

    fun setManageMode(value: Boolean) {
        isManageMode = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val binding = ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EntryViewHolder(binding, onEntryClick, onEditClick, onDeleteClick, onEnabledChange)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(getItem(position), isManageMode)
    }

    class EntryViewHolder(
        private val binding: ItemEntryBinding,
        private val onEntryClick: (Entry) -> Unit,
        private val onEditClick: (Entry) -> Unit,
        private val onDeleteClick: (Entry) -> Unit,
        private val onEnabledChange: (Entry, Boolean) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: Entry, isManageMode: Boolean) {
            binding.iconText.text = serviceIcon(entry)
            binding.labelText.text = entry.label
            binding.metaText.text = buildMeta(entry)
            binding.codeText.text = if (!entry.enabled) {
                "------"
            } else if (entry.code.isBlank() && entry.otpType == "hotp") {
                binding.root.context.getString(R.string.generate)
            } else {
                entry.code
            }
            binding.expiresText.text = entry.expiresIn?.let { "${it}s" }.orEmpty()
            binding.editButton.visibility = if (isManageMode) View.VISIBLE else View.GONE
            binding.deleteButton.visibility = if (isManageMode) View.VISIBLE else View.GONE
            binding.enabledSwitch.visibility = if (isManageMode) View.VISIBLE else View.GONE
            binding.codeText.visibility = if (isManageMode) View.GONE else View.VISIBLE
            binding.expiresText.visibility = if (isManageMode) View.GONE else View.VISIBLE
            binding.statusText.visibility = if (entry.enabled) View.GONE else View.VISIBLE
            binding.root.alpha = if (entry.enabled) 1f else 0.62f
            binding.root.setOnClickListener { onEntryClick(entry) }
            binding.editButton.setOnClickListener { onEditClick(entry) }
            binding.deleteButton.setOnClickListener { onDeleteClick(entry) }
            binding.enabledSwitch.setOnCheckedChangeListener(null)
            binding.enabledSwitch.isChecked = entry.enabled
            binding.enabledSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked != entry.enabled) onEnabledChange(entry, checked)
            }
        }

        private fun buildMeta(entry: Entry): String {
            val parts = mutableListOf<String>()
            if (entry.issuer.isNotBlank()) parts += entry.issuer
            if (entry.groupName.isNotBlank()) parts += entry.groupName
            parts += entry.otpType.uppercase()
            if (entry.otpType == "hotp") {
                parts += binding.root.context.getString(R.string.counter_format, entry.hotpCounter)
            }
            return parts.joinToString(" / ")
        }

        private fun serviceIcon(entry: Entry): String {
            val source = entry.issuer.ifBlank { entry.label }.trim()
            if (source.isBlank()) return "#"
            val normalized = source.lowercase()
            return when {
                "amazon" in normalized -> "a"
                "apple" in normalized -> "A"
                "dropbox" in normalized -> "D"
                "facebook" in normalized -> "f"
                "github" in normalized -> "G"
                "google" in normalized -> "G"
                "instagram" in normalized -> "I"
                "linkedin" in normalized -> "in"
                else -> source.take(1).uppercase()
            }
        }
    }

    companion object {
        private val EntryDiffCallback = object : DiffUtil.ItemCallback<Entry>() {
            override fun areItemsTheSame(oldItem: Entry, newItem: Entry): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Entry, newItem: Entry): Boolean = oldItem == newItem
        }
    }
}

private class TokenStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "api_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): ApiSession? {
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return null
        return ApiSession(access, refresh, expiresAt)
    }

    fun save(session: ApiSession) {
        prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_EXPIRES_AT, session.accessExpiresAtMillis)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "access_expires_at"
    }
}

private class ApiClient(workerUrl: String) {
    private val baseUrl = workerUrl.trim().trimEnd('/')

    fun login(username: String, password: String): ApiSession {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .put("clientType", "android")
        return parseSession(request("POST", "/api/v1/auth/login", body = body))
    }

    fun refresh(refreshToken: String): ApiSession {
        val body = JSONObject()
            .put("refreshToken", refreshToken)
            .put("clientType", "android")
        return parseSession(request("POST", "/api/v1/auth/refresh", body = body))
    }

    fun logout(accessToken: String) {
        request("POST", "/api/v1/auth/logout", accessToken = accessToken)
    }

    fun appData(accessToken: String): AppData {
        return try {
            val json = request("GET", "/api/app-data", accessToken = accessToken)
            AppData(
                entries = parseEntries(json.optJSONArray("entries") ?: JSONArray()),
                groups = parseGroups(json.optJSONArray("groups") ?: JSONArray()),
            )
        } catch (e: ApiException) {
            AppData(entries(accessToken), groups(accessToken))
        }
    }

    fun entries(accessToken: String): List<Entry> {
        val json = request("GET", "/api/v1/entries", accessToken = accessToken)
        val array = json.optJSONArray("entries") ?: JSONArray()
        return parseEntries(array)
    }

    private fun parseEntries(array: JSONArray): List<Entry> {
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Entry(
                id = item.getLong("id"),
                label = item.optString("label"),
                issuer = item.optString("issuer"),
                otpType = item.optString("otp_type", "totp"),
                period = item.optInt("period", 30),
                digits = item.optInt("digits", 6),
                algorithm = item.optString("algorithm", "SHA-256"),
                groupName = item.optString("group_name"),
                groupId = item.optLongOrNull("group_id"),
                hotpCounter = item.optLong("hotp_counter", 0L),
                enabled = item.optBooleanCompat("enabled", true),
            )
        }
    }

    fun groups(accessToken: String): List<Group> {
        val json = request("GET", "/api/v1/groups", accessToken = accessToken)
        val array = json.optJSONArray("groups") ?: JSONArray()
        return parseGroups(array)
    }

    private fun parseGroups(array: JSONArray): List<Group> {
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Group(
                id = item.getLong("id"),
                name = item.optString("name"),
                color = item.optString("color"),
            )
        }
    }

    fun createEntry(accessToken: String, draft: EntryDraft) {
        request("POST", "/api/v1/entries", accessToken = accessToken, body = draft.toJson(isPatch = false))
    }

    fun updateEntry(accessToken: String, entryId: Long, draft: EntryDraft) {
        request("PATCH", "/api/v1/entries/$entryId", accessToken = accessToken, body = draft.toJson(isPatch = true))
    }

    fun deleteEntry(accessToken: String, entryId: Long) {
        request("DELETE", "/api/v1/entries/$entryId", accessToken = accessToken)
    }

    fun setEntryEnabled(accessToken: String, entryId: Long, enabled: Boolean) {
        val body = JSONObject().put("enabled", enabled)
        request("PATCH", "/api/v1/entries/$entryId", accessToken = accessToken, body = body)
    }

    fun createGroup(accessToken: String, name: String, color: String) {
        val body = JSONObject()
            .put("name", name)
            .put("color", color)
        request("POST", "/api/v1/groups", accessToken = accessToken, body = body)
    }

    fun deleteGroup(accessToken: String, groupId: Long) {
        request("DELETE", "/api/v1/groups/$groupId", accessToken = accessToken)
    }

    fun exportPlain(accessToken: String, confirmPassword: String): String {
        val body = JSONObject().put("confirmPassword", confirmPassword)
        return requestText("POST", "/api/export", accessToken = accessToken, body = body)
    }

    fun exportOtpAuth(accessToken: String, confirmPassword: String): String {
        val body = JSONObject().put("confirmPassword", confirmPassword)
        return requestText("POST", "/api/export/otpauth", accessToken = accessToken, body = body)
    }

    fun exportEncrypted(accessToken: String, passphrase: String): String {
        val body = JSONObject().put("passphrase", passphrase)
        return requestText("POST", "/api/export/encrypted", accessToken = accessToken, body = body)
    }

    fun importPlain(accessToken: String, payload: JSONObject) {
        request("POST", "/api/import", accessToken = accessToken, body = payload)
    }

    fun importOtpAuth(accessToken: String, text: String) {
        request("POST", "/api/import/otpauth", accessToken = accessToken, body = JSONObject().put("text", text))
    }

    fun importEncrypted(accessToken: String, encrypted: JSONObject, passphrase: String) {
        val body = JSONObject()
            .put("encrypted", encrypted)
            .put("passphrase", passphrase)
        request("POST", "/api/import/encrypted", accessToken = accessToken, body = body)
    }

    fun changePassword(accessToken: String, currentPassword: String, newPassword: String) {
        val body = JSONObject()
            .put("currentPassword", currentPassword)
            .put("newPassword", newPassword)
        request("PATCH", "/api/v1/me/password", accessToken = accessToken, body = body)
    }

    fun codesBatch(accessToken: String, entryIds: List<Long>): Map<Long, CodeResult> {
        val body = JSONObject().put("entryIds", JSONArray(entryIds))
        val json = request("POST", "/api/v1/codes/batch", accessToken = accessToken, body = body)
        val items = json.optJSONArray("items") ?: JSONArray()
        val results = mutableMapOf<Long, CodeResult>()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            results[item.getLong("id")] = CodeResult(
                code = item.optString("code"),
                expiresIn = item.optInt("expiresIn", 0),
            )
        }
        return results
    }

    fun consumeHotp(accessToken: String, entryId: Long): HotpResult {
        val json = request("POST", "/api/v1/entries/$entryId/hotp", accessToken = accessToken)
        return HotpResult(
            code = json.optString("code"),
            counter = json.optLong("counter", 0L),
            nextCounter = json.optLong("nextCounter", 0L),
        )
    }

    private fun parseSession(json: JSONObject): ApiSession {
        val expiresIn = json.optLong("expiresIn", 0L)
        return ApiSession(
            accessToken = json.getString("accessToken"),
            refreshToken = json.getString("refreshToken"),
            accessExpiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L,
        )
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String? = null,
        body: JSONObject? = null,
    ): JSONObject {
        return parseJsonObject(requestText(method, path, accessToken, body))
    }

    private fun requestText(
        method: String,
        path: String,
        accessToken: String? = null,
        body: JSONObject? = null,
    ): String {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Client-Type", "android")
            if (!accessToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            if (body != null) {
                doOutput = true
                outputStream.use { stream ->
                    stream.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
        }

        val status = connection.responseCode
        val text = readResponse(connection)
        connection.disconnect()

        val json = parseJsonObject(text)
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException()
        if (status !in 200..299) {
            throw ApiException(json.optString("error", "HTTP $status"))
        }
        return text
    }

    private fun parseJsonObject(text: String): JSONObject {
        if (text.isBlank()) return JSONObject()
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            JSONObject().put("error", text)
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }
}

private fun JSONObject.optLongOrNull(name: String): Long? {
    if (!has(name) || isNull(name)) return null
    return optLong(name)
}

private fun JSONObject.optBooleanCompat(name: String, defaultValue: Boolean): Boolean {
    if (!has(name) || isNull(name)) return defaultValue
    val value = opt(name)
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> defaultValue
    }
}

private data class AppData(
    val entries: List<Entry>,
    val groups: List<Group>,
)

private data class ApiSession(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
)

private data class Entry(
    val id: Long,
    val label: String,
    val issuer: String,
    val otpType: String,
    val period: Int,
    val digits: Int,
    val algorithm: String,
    val groupName: String,
    val groupId: Long?,
    val hotpCounter: Long,
    val enabled: Boolean = true,
    val code: String = "",
    val expiresIn: Int? = null,
)

private data class Group(
    val id: Long,
    val name: String,
    val color: String,
)

private data class EntryDraft(
    val otpauthUri: String,
    val label: String,
    val issuer: String,
    val secret: String,
    val otpType: String,
    val algorithm: String,
    val digits: Int,
    val period: Int,
    val hotpCounter: Long,
    val groupId: Long?,
    val includeSecret: Boolean,
    val enabled: Boolean = true,
) {
    fun toJson(isPatch: Boolean): JSONObject {
        val json = JSONObject()
            .put("label", label)
            .put("issuer", issuer)
            .put("otpType", otpType)
            .put("algorithm", algorithm)
            .put("digits", digits)
            .put("period", period)
            .put("hotpCounter", hotpCounter)
            .put("groupId", groupId ?: JSONObject.NULL)
            .put("enabled", enabled)
        if (!isPatch && otpauthUri.isNotBlank()) json.put("otpauthUri", otpauthUri)
        if (includeSecret && secret.isNotBlank()) json.put("secret", secret)
        return json
    }
}

private data class CodeResult(
    val code: String,
    val expiresIn: Int,
)

private data class HotpResult(
    val code: String,
    val counter: Long,
    val nextCounter: Long,
)

private data class ExportRequest(
    val format: ExportFormat,
    val password: String,
    val passphrase: String,
)

private enum class ExportFormat {
    ENCRYPTED_JSON,
    PLAIN_JSON,
    OTPAUTH,
}

private enum class ImportFormat {
    PLAIN_JSON,
    OTPAUTH,
    ENCRYPTED_JSON,
}

private enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

private class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    companion object {
        private const val KEY_THEME = "theme"
    }
}

private class UnauthorizedException : Exception()
private class ApiException(message: String) : Exception(message)
