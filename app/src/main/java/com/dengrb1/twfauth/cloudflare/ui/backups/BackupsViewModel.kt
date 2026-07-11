package com.dengrb1.twfauth.cloudflare.ui.backups

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.model.CooldownViewModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiOperationError
import com.dengrb1.twfauth.cloudflare.ui.model.toOperationError
import com.dengrb1.twfauth.cloudflare.ui.model.runUiCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface BackupResult {
    data class OtpAuth(val found: Int, val imported: Int, val failed: Int, val errors: List<String>) : BackupResult
    data class EncryptedImport(val groups: Int, val entries: Int) : BackupResult
    data class Exported(val fileName: String) : BackupResult
}

@Immutable data class PendingExport(val fileName: String, val content: String)

@Immutable
data class BackupsUiState(
    val groups: List<OtpGroupUiModel> = emptyList(),
    val groupId: Long? = null,
    val importText: String = "",
    val passphrase: String = "",
    val busy: Boolean = false,
    val error: UiOperationError? = null,
    val retryAfterSeconds: Long? = null,
    val result: BackupResult? = null,
    val pendingExport: PendingExport? = null,
)

class BackupsViewModel(
    private val gateway: UiGateway,
    private val savedStateHandle: SavedStateHandle,
) : CooldownViewModel() {
    private var active = true
    private val _state = MutableStateFlow(
        BackupsUiState(groupId = savedStateHandle[GROUP_ID]),
    )
    val state: StateFlow<BackupsUiState> = _state.asStateFlow()

    init { loadGroups() }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) loadGroups() else {
            cancelScreenTasks()
            _state.update { it.copy(busy = false) }
        }
    }

    fun setGroup(id: Long?) { savedStateHandle[GROUP_ID] = id; _state.update { it.copy(groupId = id) } }
    fun setImportText(value: String) { _state.update { it.copy(importText = value) } }
    fun setPassphrase(value: String) { _state.update { it.copy(passphrase = value) } }

    fun importOtpAuth() = runOperation {
        val result = gateway.importOtpAuth(_state.value.importText, _state.value.groupId)
        _state.update { it.copy(result = BackupResult.OtpAuth(result.found, result.imported, result.failed, result.errors.take(5))) }
    }

    fun importEncrypted(content: String) = runOperation {
        val result = gateway.importEncrypted(content, _state.value.passphrase.toCharArray())
        _state.update { it.copy(result = BackupResult.EncryptedImport(result.groups, result.entries)) }
    }

    fun exportEncrypted() = runOperation {
        val content = gateway.exportEncrypted(_state.value.passphrase.toCharArray())
        _state.update { it.copy(pendingExport = PendingExport("2fauth-encrypted-${System.currentTimeMillis()}.json", content)) }
    }

    fun exportHandled(success: Boolean) {
        val pending = _state.value.pendingExport
        _state.update { it.copy(pendingExport = null, result = if (success && pending != null) BackupResult.Exported(pending.fileName) else it.result) }
    }
    fun dismissError() { _state.update { it.copy(error = null) } }

    private fun runOperation(block: suspend () -> Unit) {
        if (_state.value.busy || _state.value.retryAfterSeconds != null) return
        launchScreenTask {
            _state.update { it.copy(busy = true, error = null, result = null) }
            runUiCatching { block() }.onSuccess { _state.update { it.copy(busy = false) } }.onFailure(::showError)
        }
    }

    private fun showError(error: Throwable) {
        val uiError = error.toOperationError()
        _state.update { it.copy(busy = false, error = uiError, retryAfterSeconds = uiError.retryAfterSeconds) }
        beginCooldown(uiError.retryAfterSeconds) { remaining -> _state.update { it.copy(retryAfterSeconds = remaining) } }
    }

    private fun loadGroups() = launchScreenTask {
        runUiCatching { gateway.groups() }
            .onSuccess { groups -> _state.update { it.copy(groups = groups) } }
            .onFailure(::showError)
    }

    private companion object { const val GROUP_ID = "backups.group" }
}
