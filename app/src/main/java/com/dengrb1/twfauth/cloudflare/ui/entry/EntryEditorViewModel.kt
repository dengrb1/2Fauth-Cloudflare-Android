package com.dengrb1.twfauth.cloudflare.ui.entry

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.model.CooldownViewModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryDraft
import com.dengrb1.twfauth.cloudflare.ui.model.OtpKind
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiOperationError
import com.dengrb1.twfauth.cloudflare.ui.model.toOperationError
import com.dengrb1.twfauth.cloudflare.ui.model.runUiCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EntryValidationError { SecretOrUri, LabelRequired, Parameters }

@Immutable
data class EntryEditorUiState(
    val draft: OtpEntryDraft,
    val saving: Boolean = false,
    val error: UiOperationError? = null,
    val validationError: EntryValidationError? = null,
    val retryAfterSeconds: Long? = null,
    val completed: Boolean = false,
    val deleteConfirmation: Boolean = false,
)

class EntryEditorViewModel(
    private val gateway: UiGateway,
    private val savedStateHandle: SavedStateHandle,
    initial: OtpEntryDraft,
) : CooldownViewModel() {
    private var sessionToken: Int? = savedStateHandle[SESSION_TOKEN]
    private val _state = MutableStateFlow(EntryEditorUiState(restore(initial)))
    val state: StateFlow<EntryEditorUiState> = _state.asStateFlow()

    fun beginSession(token: Int, initial: OtpEntryDraft) {
        if (sessionToken == token) return
        sessionToken = token
        savedStateHandle[SESSION_TOKEN] = token
        clearPersistedDraft()
        _state.value = EntryEditorUiState(initial)
        persist(initial)
    }

    fun update(transform: (OtpEntryDraft) -> OtpEntryDraft) {
        _state.update { it.copy(draft = transform(it.draft), validationError = null, error = null) }
        persist(_state.value.draft)
    }

    fun applyOtpAuthUri(uri: String) = update { it.copy(otpauthUri = uri, secret = "") }

    fun setActive(active: Boolean) {
        if (!active) {
            cancelScreenTasks()
            _state.update { it.copy(saving = false) }
        }
    }

    fun save() {
        val draft = _state.value.draft
        val validation = validate(draft)
        if (validation != null) { _state.update { it.copy(validationError = validation) }; return }
        if (_state.value.saving || _state.value.retryAfterSeconds != null) return
        launchScreenTask {
            _state.update { it.copy(saving = true, error = null) }
            runUiCatching { if (draft.isEditing) gateway.updateEntry(draft) else gateway.createEntry(draft) }
                .onSuccess { _state.update { it.copy(saving = false, completed = true, deleteConfirmation = false) } }
                .onFailure(::showError)
        }
    }

    fun requestDelete() { _state.update { it.copy(deleteConfirmation = true) } }
    fun dismissDelete() { _state.update { it.copy(deleteConfirmation = false) } }

    fun delete() {
        val id = _state.value.draft.id ?: return
        if (_state.value.saving || _state.value.retryAfterSeconds != null) return
        launchScreenTask {
            _state.update { it.copy(saving = true, error = null) }
            runUiCatching { gateway.deleteEntry(id) }
                .onSuccess { _state.update { it.copy(saving = false, completed = true, deleteConfirmation = false) } }
                .onFailure(::showError)
        }
    }

    fun dismissError() { _state.update { it.copy(error = null, validationError = null) } }
    fun consumeCompleted() { _state.update { it.copy(completed = false) } }
    fun clearSensitive() { _state.update { it.copy(draft = it.draft.copy(secret = "", otpauthUri = "")) } }

    private fun showError(error: Throwable) {
        val uiError = error.toOperationError()
        _state.update { it.copy(saving = false, error = uiError, retryAfterSeconds = uiError.retryAfterSeconds) }
        beginCooldown(uiError.retryAfterSeconds) { remaining -> _state.update { it.copy(retryAfterSeconds = remaining) } }
    }

    private fun validate(draft: OtpEntryDraft): EntryValidationError? {
        val hasSecret = draft.secret.isNotBlank(); val hasUri = draft.otpauthUri.isNotBlank()
        if (!draft.isEditing && hasSecret == hasUri) return EntryValidationError.SecretOrUri
        if (draft.label.isBlank() && !hasUri) return EntryValidationError.LabelRequired
        if (draft.digits !in 6..8 || (draft.kind == OtpKind.Totp && draft.period !in 15..120) || draft.counter < 0) return EntryValidationError.Parameters
        return null
    }

    private fun restore(initial: OtpEntryDraft) = OtpEntryDraft(
        id = savedStateHandle.get<Long>(ID) ?: initial.id,
        label = savedStateHandle.get<String>(LABEL) ?: initial.label,
        issuer = savedStateHandle.get<String>(ISSUER) ?: initial.issuer,
        secret = initial.secret,
        otpauthUri = initial.otpauthUri,
        kind = savedStateHandle.get<String>(KIND)?.let(OtpKind::valueOf) ?: initial.kind,
        algorithm = savedStateHandle.get<String>(ALGORITHM)?.let(OtpAlgorithm::valueOf) ?: initial.algorithm,
        digits = savedStateHandle.get<Int>(DIGITS) ?: initial.digits,
        period = savedStateHandle.get<Int>(PERIOD) ?: initial.period,
        counter = savedStateHandle.get<Long>(COUNTER) ?: initial.counter,
        groupId = if (savedStateHandle.contains(GROUP_SET)) savedStateHandle.get<Long>(GROUP) else initial.groupId,
        enabled = savedStateHandle.get<Boolean>(ENABLED) ?: initial.enabled,
    )

    private fun persist(draft: OtpEntryDraft) {
        savedStateHandle[ID] = draft.id; savedStateHandle[LABEL] = draft.label; savedStateHandle[ISSUER] = draft.issuer
        savedStateHandle[KIND] = draft.kind.name; savedStateHandle[ALGORITHM] = draft.algorithm.name
        savedStateHandle[DIGITS] = draft.digits; savedStateHandle[PERIOD] = draft.period
        savedStateHandle[COUNTER] = draft.counter; savedStateHandle[GROUP] = draft.groupId
        savedStateHandle[GROUP_SET] = true; savedStateHandle[ENABLED] = draft.enabled
    }

    private fun clearPersistedDraft() {
        listOf(ID, LABEL, ISSUER, KIND, ALGORITHM, DIGITS, PERIOD, COUNTER, GROUP, GROUP_SET, ENABLED)
            .forEach { key -> savedStateHandle.remove<Any?>(key) }
    }

    private companion object {
        const val ID = "entry.id"; const val LABEL = "entry.label"; const val ISSUER = "entry.issuer"
        const val KIND = "entry.kind"
        const val ALGORITHM = "entry.algorithm"; const val DIGITS = "entry.digits"; const val PERIOD = "entry.period"
        const val COUNTER = "entry.counter"; const val GROUP = "entry.group"; const val GROUP_SET = "entry.group_set"
        const val ENABLED = "entry.enabled"
        const val SESSION_TOKEN = "entry.session_token"
    }
}
