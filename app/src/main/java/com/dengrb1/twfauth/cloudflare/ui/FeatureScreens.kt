package com.dengrb1.twfauth.cloudflare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dengrb1.twfauth.cloudflare.R
import com.dengrb1.twfauth.cloudflare.ui.backups.BackupResult
import com.dengrb1.twfauth.cloudflare.ui.backups.BackupsViewModel
import com.dengrb1.twfauth.cloudflare.ui.components.EmptyPane
import com.dengrb1.twfauth.cloudflare.ui.components.ErrorBanner
import com.dengrb1.twfauth.cloudflare.ui.components.LoadingPane
import com.dengrb1.twfauth.cloudflare.ui.entry.EntryEditorViewModel
import com.dengrb1.twfauth.cloudflare.ui.entry.EntryValidationError
import com.dengrb1.twfauth.cloudflare.ui.groups.GroupsViewModel
import com.dengrb1.twfauth.cloudflare.ui.model.AppPreferencesUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.LanguagePreference
import com.dengrb1.twfauth.cloudflare.ui.model.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryDraft
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpKind
import com.dengrb1.twfauth.cloudflare.ui.model.ThemePreference
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiOperationError
import com.dengrb1.twfauth.cloudflare.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun EntryEditorDialog(
    initial: OtpEntryDraft,
    groups: List<OtpGroupUiModel>,
    gateway: UiGateway,
    platformActions: PlatformActions,
    editorSession: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val vm: EntryEditorViewModel = viewModel(key = "entry-editor", factory = savedStateFactory { EntryEditorViewModel(gateway, it, initial) })
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    var groupMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(editorSession) { vm.beginSession(editorSession, initial) }
    val validationMessage = state.validationError?.let {
        stringResource(when (it) {
            EntryValidationError.SecretOrUri -> R.string.error_secret_or_uri
            EntryValidationError.LabelRequired -> R.string.error_label_required
            EntryValidationError.Parameters -> R.string.error_otp_parameters
        })
    }
    LaunchedEffect(state.completed) { if (state.completed) { vm.clearSensitive(); vm.consumeCompleted(); onSaved() } }
    AlertDialog(
        onDismissRequest = { if (!state.saving) { vm.clearSensitive(); onDismiss() } },
        title = { Text(stringResource(if (draft.isEditing) R.string.edit_entry else R.string.new_entry)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(UiTestTags.ENTRY_FORM), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (state.error?.let { localizedOperationError(it) } ?: validationMessage)?.let { ErrorBanner(it, retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) }
                if (!draft.isEditing) {
                    OutlinedTextField(draft.otpauthUri, { value -> vm.update { it.copy(otpauthUri = value, secret = if (value.isNotBlank()) "" else it.secret) } }, label = { Text(stringResource(R.string.otpauth_uri)) }, modifier = Modifier.fillMaxWidth().testTag(UiTestTags.OTPAUTH_URI))
                    OutlinedButton(onClick = { scope.launch { platformActions.scanOtpAuth()?.let(vm::applyOtpAuthUri) } }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(UiTestTags.SCAN_ENTRY)) { Icon(painterResource(R.drawable.lucide_scan_line), null); Text(stringResource(R.string.scan), Modifier.padding(start = 8.dp)) }
                }
                OutlinedTextField(draft.label, { value -> vm.update { it.copy(label = value) } }, label = { Text(stringResource(R.string.label)) }, modifier = Modifier.fillMaxWidth().testTag(UiTestTags.ENTRY_LABEL))
                OutlinedTextField(draft.issuer, { value -> vm.update { it.copy(issuer = value) } }, label = { Text(stringResource(R.string.issuer)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(draft.secret, { value -> vm.update { it.copy(secret = value, otpauthUri = if (value.isNotBlank()) "" else it.otpauthUri) } }, label = { Text(stringResource(if (draft.isEditing) R.string.secret_optional else R.string.secret)) }, modifier = Modifier.fillMaxWidth().testTag(UiTestTags.ENTRY_SECRET), visualTransformation = PasswordVisualTransformation())
                Text(stringResource(R.string.type), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { vm.update { it.copy(kind = OtpKind.Totp) } }, label = { Text("TOTP${if (draft.kind == OtpKind.Totp) " ✓" else ""}") }, modifier = Modifier.sizeIn(minHeight = 48.dp))
                    AssistChip(onClick = { vm.update { it.copy(kind = OtpKind.Hotp) } }, label = { Text("HOTP${if (draft.kind == OtpKind.Hotp) " ✓" else ""}") }, modifier = Modifier.sizeIn(minHeight = 48.dp))
                }
                Text(stringResource(R.string.algorithm), style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(OtpAlgorithm.entries) { algorithm -> AssistChip(onClick = { vm.update { it.copy(algorithm = algorithm) } }, label = { Text(algorithm.wireName + if (draft.algorithm == algorithm) " ✓" else "") }, modifier = Modifier.sizeIn(minHeight = 48.dp)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.digits.toString(), { it.toIntOrNull()?.let { value -> vm.update { d -> d.copy(digits = value) } } }, label = { Text(stringResource(R.string.digits)) }, modifier = Modifier.weight(1f))
                    if (draft.kind == OtpKind.Totp) OutlinedTextField(draft.period.toString(), { it.toIntOrNull()?.let { value -> vm.update { d -> d.copy(period = value) } } }, label = { Text(stringResource(R.string.period)) }, modifier = Modifier.weight(1f))
                    else OutlinedTextField(draft.counter.toString(), { it.toLongOrNull()?.let { value -> vm.update { d -> d.copy(counter = value) } } }, label = { Text(stringResource(R.string.counter)) }, modifier = Modifier.weight(1f))
                }
                OutlinedButton(onClick = { groupMenu = true }, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text(groups.firstOrNull { it.id == draft.groupId }?.name ?: stringResource(R.string.no_group)) }
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.no_group)) }, onClick = { groupMenu = false; vm.update { it.copy(groupId = null) } })
                    groups.forEach { group -> DropdownMenuItem(text = { Text(group.name) }, onClick = { groupMenu = false; vm.update { it.copy(groupId = group.id) } }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.enabled, { value -> vm.update { it.copy(enabled = value) } }); Text(stringResource(R.string.entry_enabled)) }
                if (draft.isEditing) TextButton(onClick = vm::requestDelete, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Icon(Icons.Outlined.Delete, null); Text(stringResource(R.string.delete_entry)) }
            }
        },
        confirmButton = { Button(onClick = vm::save, enabled = !state.saving && state.retryAfterSeconds == null, modifier = Modifier.testTag(UiTestTags.ENTRY_SAVE)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { vm.clearSensitive(); onDismiss() }, enabled = !state.saving, modifier = Modifier.testTag(UiTestTags.ENTRY_CANCEL)) { Text(stringResource(android.R.string.cancel)) } },
    )
    if (state.deleteConfirmation) AlertDialog(
        onDismissRequest = vm::dismissDelete, title = { Text(stringResource(R.string.delete_entry)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.error?.let { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) }; Text(stringResource(R.string.delete_entry_confirm, draft.label)) } },
        confirmButton = { Button(onClick = vm::delete, enabled = !state.saving && state.retryAfterSeconds == null) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = vm::dismissDelete) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(gateway: UiGateway) {
    val vm: GroupsViewModel = viewModel(factory = savedStateFactory { GroupsViewModel(gateway, it) })
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    Scaffold(modifier = Modifier.testTag(UiTestTags.destination("groups")), topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_groups)) }) }, floatingActionButton = { FloatingActionButton(onClick = vm::openAdd, modifier = Modifier.testTag(UiTestTags.ADD_GROUP)) { Icon(Icons.Outlined.Add, stringResource(R.string.new_group)) } }) { padding ->
        when {
            state.loading -> LoadingPane()
            state.error != null && state.groups.isEmpty() -> Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { ErrorBanner(localizedOperationError(state.error!!), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError); Button(onClick = { vm.refresh() }, enabled = state.retryAfterSeconds == null) { Text(stringResource(R.string.refresh)) } }
            state.groups.isEmpty() -> EmptyPane(stringResource(R.string.no_groups), stringResource(R.string.no_groups_detail))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(UiTestTags.GROUPS_LIST), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp)) {
                state.error?.let { item { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) } }
                items(state.groups, key = { it.id }) { group -> Card(Modifier.fillMaxWidth().testTag(UiTestTags.group(group.id))) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(40.dp).semantics { contentDescription = "#%06X".format(group.color and 0xFFFFFF) }) { androidx.compose.material3.Surface(color = Color(group.color), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize()) {} }; Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(group.name, style = MaterialTheme.typography.titleMedium); Text(stringResource(R.string.entries_count, state.counts[group.id] ?: 0)) }; IconButton(onClick = { vm.openEdit(group) }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }; IconButton(onClick = { vm.requestDelete(group.id) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) } } } }
            }
        }
    }
    state.draft?.let { draft -> AlertDialog(
        onDismissRequest = vm::dismissDraft, title = { Text(stringResource(if (draft.id == null) R.string.new_group else R.string.edit_group)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { state.error?.let { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) }; OutlinedTextField(draft.name, vm::setName, label = { Text(stringResource(R.string.group_name)) }, modifier = Modifier.testTag(UiTestTags.GROUP_NAME)); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf(0xFF0F766EL, 0xFF6C4BEFL, 0xFFB45309L, 0xFFBE123CL, 0xFF0369A1L)) { value -> val description = stringResource(R.string.color_option, "#%06X".format(value and 0xFFFFFF)); AssistChip(onClick = { vm.setColor(value) }, label = { Text(if (draft.color == value) "● ✓" else "●", color = Color(value)) }, modifier = Modifier.sizeIn(minHeight = 48.dp).semantics { contentDescription = description }) } } } },
        confirmButton = { Button(onClick = vm::save, enabled = draft.name.isNotBlank() && !state.saving && state.retryAfterSeconds == null, modifier = Modifier.testTag(UiTestTags.GROUP_SAVE)) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = vm::dismissDraft) { Text(stringResource(android.R.string.cancel)) } },
    ) }
    state.deleteTargetId?.let { id -> state.groups.firstOrNull { it.id == id }?.let { group -> AlertDialog(
        onDismissRequest = vm::dismissDelete, title = { Text(stringResource(R.string.delete_group)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { state.error?.let { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) }; Text(stringResource(R.string.delete_group_keeps_entries, group.name)) } },
        confirmButton = { Button(onClick = vm::confirmDelete, enabled = !state.saving && state.retryAfterSeconds == null) { Text(stringResource(R.string.delete)) } }, dismissButton = { TextButton(onClick = vm::dismissDelete) { Text(stringResource(android.R.string.cancel)) } },
    ) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(gateway: UiGateway, platformActions: PlatformActions) {
    val vm: BackupsViewModel = viewModel(factory = savedStateFactory { BackupsViewModel(gateway, it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(state.pendingExport) { state.pendingExport?.let { vm.exportHandled(platformActions.writeTextFile(it.fileName, "application/json", it.content)) } }
    Scaffold(modifier = Modifier.testTag(UiTestTags.destination("backups")), topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_backups)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag(UiTestTags.BACKUP_SCREEN), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.error?.let { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) }
            state.result?.let { Text(when (it) { is BackupResult.OtpAuth -> stringResource(R.string.otp_import_summary, it.found, it.imported, it.failed, it.errors.joinToString()); is BackupResult.EncryptedImport -> stringResource(R.string.encrypted_import_summary, it.groups, it.entries); is BackupResult.Exported -> stringResource(R.string.exported_file, it.fileName) }, color = MaterialTheme.colorScheme.primary) }
            Text(stringResource(R.string.otpauth_import), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(state.importText, vm::setImportText, label = { Text(stringResource(R.string.import_content)) }, modifier = Modifier.fillMaxWidth().testTag(UiTestTags.IMPORT_CONTENT), minLines = 3)
            OutlinedButton(onClick = { scope.launch { platformActions.readTextFile(arrayOf("text/plain", "*/*"))?.let(vm::setImportText) } }, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag(UiTestTags.PICK_IMPORT_FILE)) { Icon(Icons.Outlined.FileOpen, null); Text(stringResource(R.string.choose_import_file)) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { item { AssistChip(onClick = { vm.setGroup(null) }, label = { Text(stringResource(R.string.no_group) + if (state.groupId == null) " ✓" else "") }, modifier = Modifier.sizeIn(minHeight = 48.dp)) }; items(state.groups, key = { it.id }) { group -> AssistChip(onClick = { vm.setGroup(group.id) }, label = { Text(group.name + if (state.groupId == group.id) " ✓" else "") }, modifier = Modifier.sizeIn(minHeight = 48.dp)) } }
            Button(enabled = !state.busy && state.retryAfterSeconds == null && state.importText.isNotBlank(), onClick = vm::importOtpAuth) { Text(stringResource(R.string.import_otp)) }
            Text(stringResource(R.string.encrypted_backup), style = MaterialTheme.typography.titleLarge); Text(stringResource(R.string.backup_passphrase_policy))
            OutlinedTextField(state.passphrase, vm::setPassphrase, label = { Text(stringResource(R.string.backup_passphrase)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag(UiTestTags.BACKUP_PASSPHRASE))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilledTonalButton(enabled = !state.busy && state.retryAfterSeconds == null && state.passphrase.length in 12..256, onClick = { scope.launch { platformActions.readTextFile(arrayOf("application/json", "*/*"))?.let(vm::importEncrypted) } }) { Text(stringResource(R.string.import_encrypted)) }; Button(enabled = !state.busy && state.retryAfterSeconds == null && state.passphrase.length in 12..256, onClick = vm::exportEncrypted, modifier = Modifier.testTag(UiTestTags.EXPORT_ENCRYPTED)) { Icon(Icons.Outlined.FileUpload, null); Text(stringResource(R.string.export_encrypted)) } }
            Text(stringResource(R.string.no_plaintext_exports), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(gateway: UiGateway, preferences: AppPreferencesUiModel, onPreferencesChanged: (AppPreferencesUiModel) -> Unit, onLoggedOut: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = savedStateFactory { SettingsViewModel(gateway, it, preferences) })
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    LaunchedEffect(state.preferences) { onPreferencesChanged(state.preferences) }
    LaunchedEffect(state.signedOut) { if (state.signedOut) { vm.consumeSignedOut(); onLoggedOut() } }
    Scaffold(modifier = Modifier.testTag(UiTestTags.destination("settings")), topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag(UiTestTags.SETTINGS_SCREEN), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) } }
            item { SettingCard(stringResource(R.string.account), "${state.user?.username.orEmpty()} · ${state.user?.role.orEmpty()}") }
            item { Button(onClick = vm::openPasswordDialog, enabled = !state.busy && state.retryAfterSeconds == null, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag(UiTestTags.CHANGE_PASSWORD)) { Text(stringResource(R.string.change_password)) } }
            item { Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleMedium); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ThemePreference.entries) { value -> AssistChip(onClick = { vm.setTheme(value) }, label = { Text(stringResource(value.labelResource()) + if (state.preferences.theme == value) " ✓" else "") }, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag(UiTestTags.themeOption(value.name))) } } }
            item { Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium); LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(LanguagePreference.entries) { value -> AssistChip(onClick = { vm.setLanguage(value) }, label = { Text(stringResource(value.labelResource()) + if (state.preferences.language == value) " ✓" else "") }, modifier = Modifier.sizeIn(minHeight = 48.dp).testTag(UiTestTags.languageOption(value.name))) } } }
            item { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.app_lock)); Text(stringResource(R.string.app_lock_detail), style = MaterialTheme.typography.bodySmall) }; Switch(state.preferences.appLockEnabled, vm::setAppLock, modifier = Modifier.testTag(UiTestTags.APP_LOCK)) } } }
            item { SettingCard(stringResource(R.string.server_api_info), "${gateway.serverUrl}\nAPI ${state.capabilities?.apiVersion.orEmpty()} · app ${gateway.appVersion}") }
            item { SettingCard(stringResource(R.string.about), stringResource(R.string.about_detail)) }
            item { OutlinedButton(onClick = vm::logout, enabled = !state.busy, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) { Text(stringResource(R.string.logout)) } }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (state.passwordDialogOpen) AlertDialog(
        onDismissRequest = vm::closePasswordDialog, title = { Text(stringResource(R.string.change_password)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { state.error?.let { ErrorBanner(localizedOperationError(it), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissError) }; OutlinedTextField(state.currentPassword, vm::setCurrentPassword, label = { Text(stringResource(R.string.current_password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag(UiTestTags.CURRENT_PASSWORD)); OutlinedTextField(state.newPassword, vm::setNewPassword, label = { Text(stringResource(R.string.new_password)) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag(UiTestTags.NEW_PASSWORD)); Text(stringResource(R.string.password_policy)) } },
        confirmButton = { Button(enabled = !state.busy && state.retryAfterSeconds == null && validPassword(state.newPassword) && state.currentPassword.isNotBlank(), onClick = vm::changePassword, modifier = Modifier.testTag(UiTestTags.PASSWORD_SAVE)) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = vm::closePasswordDialog) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable private fun SettingCard(title: String, detail: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, style = MaterialTheme.typography.bodyMedium) } } }
private fun ThemePreference.labelResource() = when (this) { ThemePreference.System -> R.string.theme_system; ThemePreference.Light -> R.string.theme_light; ThemePreference.Dark -> R.string.theme_dark }
private fun LanguagePreference.labelResource() = when (this) { LanguagePreference.System -> R.string.language_system; LanguagePreference.English -> R.string.language_english; LanguagePreference.Chinese -> R.string.language_chinese }
private fun validPassword(value: String) = value.length in 12..256 && value.any(Char::isUpperCase) && value.any(Char::isLowerCase) && value.any(Char::isDigit) && value.any { !it.isLetterOrDigit() }

@Composable
private fun localizedOperationError(error: UiOperationError): String {
    if (error.serverMessage && error.message.isNotBlank()) return error.message
    return stringResource(
        when (error.clientCode) {
            "api_version" -> R.string.error_api_version
            "android_client" -> R.string.error_android_client
            "bearer_auth" -> R.string.error_bearer_auth
            "api_routes" -> R.string.error_api_routes
            "network" -> R.string.error_network_unavailable
            else -> when (error.status) {
                400 -> R.string.error_invalid_request; 401 -> R.string.error_auth_required
                403 -> R.string.error_forbidden; 404 -> R.string.error_not_found
                409 -> R.string.error_conflict; 413 -> R.string.error_payload_too_large
                429 -> R.string.error_rate_limited; 503 -> R.string.error_service_unavailable
                else -> R.string.unknown_error
            }
        },
    )
}
