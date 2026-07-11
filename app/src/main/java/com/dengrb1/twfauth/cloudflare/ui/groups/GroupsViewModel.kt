package com.dengrb1.twfauth.cloudflare.ui.groups

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

@Immutable data class GroupDraft(val id: Long? = null, val name: String = "", val color: Long = 0xFF0F766EL)

@Immutable
data class GroupsUiState(
    val groups: List<OtpGroupUiModel> = emptyList(),
    val counts: Map<Long, Int> = emptyMap(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val draft: GroupDraft? = null,
    val deleteTargetId: Long? = null,
    val error: UiOperationError? = null,
    val retryAfterSeconds: Long? = null,
)

class GroupsViewModel(
    private val gateway: UiGateway,
    private val savedStateHandle: SavedStateHandle,
) : CooldownViewModel() {
    private var active = true
    private val _state = MutableStateFlow(GroupsUiState(draft = restoreDraft(), deleteTargetId = savedStateHandle[DELETE_ID]))
    val state: StateFlow<GroupsUiState> = _state.asStateFlow()

    init { refresh() }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) refresh() else {
            cancelScreenTasks()
            _state.update { it.copy(loading = false, saving = false) }
        }
    }

    fun refresh() = launchScreenTask {
        _state.update { it.copy(loading = true, error = null) }
        runUiCatching { gateway.groups() to gateway.entries() }
            .onSuccess { (groups, entries) ->
                _state.update { it.copy(groups = groups, counts = entries.filter { entry -> entry.groupId != null }.groupingBy { entry -> requireNotNull(entry.groupId) }.eachCount(), loading = false) }
            }.onFailure(::showError)
    }

    fun openAdd() = setDraft(GroupDraft())
    fun openEdit(group: OtpGroupUiModel) = setDraft(GroupDraft(group.id, group.name, group.color))
    fun dismissDraft() = setDraft(null)
    fun setName(value: String) = setDraft(_state.value.draft?.copy(name = value))
    fun setColor(value: Long) = setDraft(_state.value.draft?.copy(color = value))
    fun requestDelete(id: Long) { savedStateHandle[DELETE_ID] = id; _state.update { it.copy(deleteTargetId = id) } }
    fun dismissDelete() { savedStateHandle[DELETE_ID] = null; _state.update { it.copy(deleteTargetId = null) } }

    fun save() {
        val draft = _state.value.draft ?: return
        if (draft.name.isBlank() || _state.value.saving || _state.value.retryAfterSeconds != null) return
        launchScreenTask {
            _state.update { it.copy(saving = true, error = null) }
            runUiCatching { if (draft.id == null) gateway.createGroup(draft.name, draft.color) else gateway.updateGroup(draft.id, draft.name, draft.color) }
                .onSuccess { setDraft(null); _state.update { it.copy(saving = false) }; refresh() }
                .onFailure(::showError)
        }
    }

    fun confirmDelete() {
        val id = _state.value.deleteTargetId ?: return
        if (_state.value.saving || _state.value.retryAfterSeconds != null) return
        launchScreenTask {
            _state.update { it.copy(saving = true, error = null) }
            runUiCatching { gateway.deleteGroup(id) }
                .onSuccess { dismissDelete(); _state.update { it.copy(saving = false) }; refresh() }
                .onFailure(::showError)
        }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    private fun showError(error: Throwable) {
        val uiError = error.toOperationError()
        _state.update { it.copy(loading = false, saving = false, error = uiError, retryAfterSeconds = uiError.retryAfterSeconds) }
        beginCooldown(uiError.retryAfterSeconds) { remaining -> _state.update { it.copy(retryAfterSeconds = remaining) } }
    }

    private fun setDraft(draft: GroupDraft?) {
        savedStateHandle[DRAFT_OPEN] = draft != null; savedStateHandle[DRAFT_ID] = draft?.id
        savedStateHandle[DRAFT_NAME] = draft?.name; savedStateHandle[DRAFT_COLOR] = draft?.color
        _state.update { it.copy(draft = draft) }
    }

    private fun restoreDraft(): GroupDraft? {
        if (savedStateHandle.get<Boolean>(DRAFT_OPEN) != true) return null
        return GroupDraft(savedStateHandle[DRAFT_ID], savedStateHandle.get<String>(DRAFT_NAME).orEmpty(), savedStateHandle.get<Long>(DRAFT_COLOR) ?: 0xFF0F766EL)
    }

    private companion object {
        const val DRAFT_OPEN = "groups.draft_open"; const val DRAFT_ID = "groups.draft_id"
        const val DRAFT_NAME = "groups.draft_name"; const val DRAFT_COLOR = "groups.draft_color"
        const val DELETE_ID = "groups.delete_id"
    }
}
