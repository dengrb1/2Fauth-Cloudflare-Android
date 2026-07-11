package com.dengrb1.twfauth.cloudflare.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dengrb1.twfauth.cloudflare.R
import com.dengrb1.twfauth.cloudflare.ui.auth.AuthScreen
import com.dengrb1.twfauth.cloudflare.ui.auth.AuthViewModel
import com.dengrb1.twfauth.cloudflare.ui.auth.SessionGateViewModel
import com.dengrb1.twfauth.cloudflare.ui.codes.CodeSort
import com.dengrb1.twfauth.cloudflare.ui.codes.BatchAction
import com.dengrb1.twfauth.cloudflare.ui.codes.CodesUiState
import com.dengrb1.twfauth.cloudflare.ui.codes.CodesViewModel
import com.dengrb1.twfauth.cloudflare.ui.codes.GroupFilter
import com.dengrb1.twfauth.cloudflare.ui.components.EmptyPane
import com.dengrb1.twfauth.cloudflare.ui.components.ErrorBanner
import com.dengrb1.twfauth.cloudflare.ui.components.LoadingPane
import com.dengrb1.twfauth.cloudflare.ui.model.AppPreferencesUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.LanguagePreference
import com.dengrb1.twfauth.cloudflare.ui.model.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryDraft
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpKind
import com.dengrb1.twfauth.cloudflare.ui.model.ThemePreference
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.runUiCatching
import com.dengrb1.twfauth.cloudflare.ui.theme.TwoFactorTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MainDestination(val route: String, val label: Int, val icon: Int) {
    Codes("codes", R.string.nav_codes, R.drawable.lucide_key_round),
    Groups("groups", R.string.nav_groups, R.drawable.lucide_folders),
    Backups("backups", R.string.nav_backups, R.drawable.lucide_archive_restore),
    Settings("settings", R.string.nav_settings, R.drawable.lucide_settings),
}

@Composable
fun TwoFactorApp(gateway: UiGateway, platformActions: PlatformActions = PlatformActions()) {
    var preferences by remember { mutableStateOf(AppPreferencesUiModel()) }
    val sessionGate: SessionGateViewModel = viewModel()
    LaunchedEffect(Unit) { runUiCatching { gateway.preferences() }.onSuccess { preferences = it } }
    TwoFactorTheme(preference = preferences.theme, dynamicColor = false) {
        if (!sessionGate.authenticated) {
            val authViewModel: AuthViewModel = viewModel(
                factory = savedStateFactory { savedState -> AuthViewModel(gateway, savedState) },
            )
            val state by authViewModel.state.collectAsStateWithLifecycle()
            LifecycleStartEffect(authViewModel) {
                authViewModel.setActive(true)
                onStopOrDispose { authViewModel.setActive(false) }
            }
            LaunchedEffect(state.signedInUser) {
                if (state.signedInUser != null) { sessionGate.unlock(); authViewModel.consumeSignedInUser() }
            }
            AuthScreen(
                state = state, serverUrl = gateway.serverUrl,
                onUsernameChange = authViewModel::setUsername, onPasswordChange = authViewModel::setPassword,
                onLogin = { authViewModel.login(platformActions.requestTurnstileToken) },
                onUnlock = { authViewModel.unlock(platformActions.requestDeviceUnlock) },
                onUsePassword = authViewModel::usePasswordInstead, onDismissError = authViewModel::clearError,
            )
        } else {
            MainShell(
                gateway, platformActions, preferences,
                onPreferencesChanged = { preferences = it },
                onLoggedOut = sessionGate::lock,
            )
        }
    }
}

@Composable
private fun MainShell(
    gateway: UiGateway,
    platformActions: PlatformActions,
    preferences: AppPreferencesUiModel,
    onPreferencesChanged: (AppPreferencesUiModel) -> Unit,
    onLoggedOut: () -> Unit,
) {
    val navController = rememberNavController()
    val route = navController.currentBackStackEntryAsState().value?.destination?.route ?: MainDestination.Codes.route
    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag(UiTestTags.BOTTOM_NAV)) {
                MainDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(UiTestTags.destinationTab(destination.route)),
                        selected = route == destination.route,
                        onClick = { navController.navigate(destination.route) { launchSingleTop = true; popUpTo(MainDestination.Codes.route) { saveState = true }; restoreState = true } },
                        icon = { Icon(painterResource(destination.icon), null) }, label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = MainDestination.Codes.route, modifier = Modifier.padding(padding)) {
            composable(MainDestination.Codes.route) {
                val vm: CodesViewModel = viewModel(factory = savedStateFactory { savedState -> CodesViewModel(gateway, savedState) })
                val state by vm.state.collectAsStateWithLifecycle()
                CodesScreen(state, vm, gateway, platformActions)
            }
            composable(MainDestination.Groups.route) { GroupsScreen(gateway) }
            composable(MainDestination.Backups.route) { BackupsScreen(gateway, platformActions) }
            composable(MainDestination.Settings.route) {
                SettingsScreen(gateway, preferences, onPreferencesChanged, onLoggedOut)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodesScreen(state: CodesUiState, vm: CodesViewModel, gateway: UiGateway, platformActions: PlatformActions) {
    val scope = rememberCoroutineScope()
    val clipboardLabel = stringResource(R.string.clipboard_code_label)
    LifecycleStartEffect(vm) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    var editorId by rememberSaveable { mutableStateOf<Long?>(null) }
    var addingEntry by rememberSaveable { mutableStateOf(false) }
    var editorEpoch by rememberSaveable { mutableIntStateOf(0) }
    var scannedInitialUri by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.testTag(UiTestTags.destination("codes")),
        topBar = {
            TopAppBar(
                title = { Text(if (state.selectionMode) stringResource(R.string.selected_count, state.selectedIds.size) else stringResource(R.string.nav_codes)) },
                actions = {
                    if (state.selectionMode) {
                        IconButton(onClick = { moveMenu = true }, enabled = state.retryAfterSeconds == null, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Folder, stringResource(R.string.move_to_group)) }
                        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.no_group)) }, onClick = { moveMenu = false; vm.moveSelected(null) })
                            state.groups.forEach { group -> DropdownMenuItem(text = { Text(group.name) }, onClick = { moveMenu = false; vm.moveSelected(group.id) }) }
                        }
                        IconButton(onClick = { deleteConfirm = true }, enabled = state.retryAfterSeconds == null) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete)) }
                    } else {
                        IconButton(onClick = vm::toggleSort) { Icon(Icons.AutoMirrored.Outlined.Sort, if (state.sort == CodeSort.Ascending) "Z–A" else "A–Z") }
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { scope.launch { platformActions.scanOtpAuth()?.let { uri -> scannedInitialUri = uri; addingEntry = true; editorId = null; editorEpoch++ } } }, modifier = Modifier.testTag(UiTestTags.SCAN_FAB)) { Icon(painterResource(R.drawable.lucide_scan_line), stringResource(R.string.scan)) }
                ExtendedFloatingActionButton(
                    onClick = { scannedInitialUri = ""; addingEntry = true; editorId = null; editorEpoch++ },
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(stringResource(R.string.new_entry_short)) },
                    modifier = Modifier.testTag(UiTestTags.ADD_ENTRY),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query, onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTestTags.CODES_SEARCH),
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, label = { Text(stringResource(R.string.search)) }, singleLine = true,
            )
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(UiTestTags.GROUP_FILTERS),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip(selected = state.groupFilter == GroupFilter.All, onClick = { vm.setGroupFilter(GroupFilter.All) }, label = { Text(stringResource(R.string.filter_all)) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) }
                item { FilterChip(selected = state.groupFilter == GroupFilter.Ungrouped, onClick = { vm.setGroupFilter(GroupFilter.Ungrouped) }, label = { Text(stringResource(R.string.no_group)) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) }
                items(state.groups, key = { it.id }) { group -> FilterChip(selected = state.groupFilter == GroupFilter.Group(group.id), onClick = { vm.setGroupFilter(GroupFilter.Group(group.id)) }, label = { Text(group.name, maxLines = 1) }, modifier = Modifier.sizeIn(minHeight = 48.dp)) }
            }
            state.error?.let { ErrorBanner(localizedStatusError(it, state.errorStatus, state.errorServerMessage, state.errorClientCode), retryAfterSeconds = state.retryAfterSeconds, onDismiss = vm::dismissMessage, modifier = Modifier.padding(16.dp)) }
            state.operation?.let {
                Text(
                    stringResource(if (it.action == BatchAction.Delete) R.string.batch_delete_result else R.string.batch_move_result, it.succeeded, it.failed),
                    Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                state.isLoading -> LoadingPane(stringResource(R.string.loading_codes))
                state.visibleEntries.isEmpty() -> EmptyPane(
                    title = stringResource(R.string.empty_entries),
                    detail = stringResource(R.string.empty_entries_detail),
                    actionLabel = stringResource(R.string.new_entry_short),
                    onAction = { scannedInitialUri = ""; addingEntry = true; editorId = null; editorEpoch++ },
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(300.dp), modifier = Modifier.fillMaxSize().testTag(UiTestTags.CODES_LIST),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    items(state.visibleEntries, key = { it.id }) { entry ->
                        OtpCard(
                            entry, entry.id in state.selectedIds,
                            onClick = { if (state.selectionMode) vm.toggleSelection(entry.id) else entry.code?.let { platformActions.copyToClipboard(clipboardLabel, it) } },
                            onLongClick = { vm.toggleSelection(entry.id) }, onGenerate = { vm.generateHotp(entry.id) },
                            onEdit = { addingEntry = false; editorId = entry.id; editorEpoch++ }, onEnabled = { vm.setEnabled(entry.id, it) }, actionsEnabled = state.retryAfterSeconds == null,
                        )
                    }
                }
            }
        }
    }
    val editorDraft = when {
        addingEntry -> OtpEntryDraft(otpauthUri = scannedInitialUri)
        editorId != null -> state.entries.firstOrNull { it.id == editorId }?.toDraft()
        else -> null
    }
    editorDraft?.let { draft ->
        EntryEditorDialog(
            draft, state.groups, gateway, platformActions, editorSession = editorEpoch,
            onDismiss = { addingEntry = false; editorId = null },
            onSaved = { addingEntry = false; editorId = null; vm.refresh() },
        )
    }
    if (deleteConfirm) AlertDialog(
        onDismissRequest = { deleteConfirm = false }, title = { Text(stringResource(R.string.delete_entry)) },
        text = { Text(stringResource(R.string.delete_selected_confirm, state.selectedIds.size)) },
        confirmButton = { Button(onClick = { deleteConfirm = false; vm.deleteSelected() }, enabled = state.retryAfterSeconds == null) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OtpCard(
    entry: OtpEntryUiModel, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
    onGenerate: () -> Unit, onEdit: () -> Unit, onEnabled: (Boolean) -> Unit,
    actionsEnabled: Boolean,
) {
    val enabledDescription = stringResource(if (entry.enabled) R.string.disable_entry else R.string.enable_entry)
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(entry.kind, entry.codeValidUntilEpochSeconds) {
        if (entry.kind == OtpKind.Totp && entry.codeValidUntilEpochSeconds != null) {
            while (true) { delay(1_000); now = System.currentTimeMillis() / 1_000 }
        }
    }
    val remaining = entry.codeValidUntilEpochSeconds?.minus(now)?.coerceAtLeast(0)
    val progress = if (remaining == null) 0f else (remaining.toFloat() / entry.period.coerceAtLeast(1)).coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (entry.enabled) 1f else .62f)
            .testTag(UiTestTags.entry(entry.id)).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Text((entry.issuer.ifBlank { entry.label }).take(1).uppercase(), fontWeight = FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.issuer.ifBlank { entry.label }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.issuer.isNotBlank()) Text(entry.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(entry.groupName, if (entry.kind == OtpKind.Hotp) "HOTP" else "TOTP").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (selected) Checkbox(true, { onLongClick() }) else IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                Switch(entry.enabled, onCheckedChange = onEnabled, enabled = actionsEnabled, modifier = Modifier.semantics { contentDescription = enabledDescription })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.kind == OtpKind.Hotp) {
                    FilledTonalButton(onClick = onGenerate, enabled = actionsEnabled, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(entry.code ?: stringResource(R.string.generate)) }
                } else {
                    Text(formatCode(entry.code, entry.digits), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).testTag(UiTestTags.entryCode(entry.id)))
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
                        Text(remaining?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.code_copied), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

private fun formatCode(code: String?, digits: Int): String {
    val value = code ?: "—".repeat(digits)
    val split = value.length / 2
    return if (value.length >= 6) value.take(split) + " " + value.drop(split) else value
}

private fun OtpEntryUiModel.toDraft() = OtpEntryDraft(id, label, issuer, kind = kind, algorithm = algorithm, digits = digits, period = period, counter = counter, groupId = groupId, enabled = enabled)

@Composable
private fun localizedStatusError(message: String, status: Int?, serverMessage: Boolean, clientCode: String?): String {
    if (serverMessage && message.isNotBlank()) return message
    return stringResource(
        when (clientCode) {
            "api_version" -> R.string.error_api_version; "android_client" -> R.string.error_android_client
            "bearer_auth" -> R.string.error_bearer_auth; "api_routes" -> R.string.error_api_routes
            "network" -> R.string.error_network_unavailable
            else -> when (status) {
                400 -> R.string.error_invalid_request; 401 -> R.string.error_auth_required
                403 -> R.string.error_forbidden; 404 -> R.string.error_not_found
                409 -> R.string.error_conflict; 413 -> R.string.error_payload_too_large
                429 -> R.string.error_rate_limited; 503 -> R.string.error_service_unavailable
                else -> R.string.unknown_error
            }
        },
    )
}
