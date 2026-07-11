package com.dengrb1.twfauth.cloudflare.ui.codes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpKind
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiGatewayException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import com.dengrb1.twfauth.cloudflare.ui.model.runUiCatching

enum class CodeSort { Ascending, Descending }

sealed interface GroupFilter {
    data object All : GroupFilter
    data object Ungrouped : GroupFilter
    data class Group(val id: Long) : GroupFilter
}

@Immutable
data class CodesUiState(
    val entries: List<OtpEntryUiModel> = emptyList(),
    val groups: List<OtpGroupUiModel> = emptyList(),
    val query: String = "",
    val sort: CodeSort = CodeSort.Ascending,
    val groupFilter: GroupFilter = GroupFilter.All,
    val selectedIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val errorStatus: Int? = null,
    val errorServerMessage: Boolean = false,
    val errorClientCode: String? = null,
    val retryAfterSeconds: Long? = null,
    val operation: BatchOperationUiModel? = null,
) {
    val visibleEntries: List<OtpEntryUiModel>
        get() = entries.asSequence()
            .filter { entry ->
                query.isBlank() || entry.label.contains(query, ignoreCase = true) ||
                    entry.issuer.contains(query, ignoreCase = true)
            }
            .filter { entry ->
                when (val filter = groupFilter) {
                    GroupFilter.All -> true
                    GroupFilter.Ungrouped -> entry.groupId == null
                    is GroupFilter.Group -> entry.groupId == filter.id
                }
            }
            .sortedWith(compareBy<OtpEntryUiModel> { it.issuer.lowercase() }.thenBy { it.label.lowercase() })
            .let { sequence -> if (sort == CodeSort.Ascending) sequence else sequence.toList().asReversed().asSequence() }
            .toList()

    val selectionMode: Boolean get() = selectedIds.isNotEmpty()
}

enum class BatchAction { Delete, Move }

@Immutable
data class BatchOperationUiModel(val action: BatchAction, val succeeded: Int, val failed: Int)

class CodesViewModel(
    private val gateway: UiGateway,
    private val savedStateHandle: SavedStateHandle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private var codeRefreshJob: Job? = null
    private var cooldownJob: Job? = null
    private var active = false
    private val _state = MutableStateFlow(
        CodesUiState(
            query = savedStateHandle.get<String>(KEY_QUERY).orEmpty(),
            sort = CodeSort.entries.getOrElse(savedStateHandle.get<Int>(KEY_SORT) ?: 0) { CodeSort.Ascending },
            groupFilter = restoreFilter(savedStateHandle.get<Long>(KEY_GROUP)),
            selectedIds = (savedStateHandle.get<ArrayList<Long>>(KEY_SELECTED) ?: arrayListOf()).toSet(),
        ),
    )
    val state: StateFlow<CodesUiState> = _state.asStateFlow()

    init {
        refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = initial && it.entries.isEmpty(), isRefreshing = !initial, error = null) }
            try {
                val groups = gateway.groups()
                var entries = gateway.entries()
                val totpIds = entries.filter { it.enabled && it.kind == OtpKind.Totp }.map { it.id }
                if (totpIds.isNotEmpty()) {
                    val codes = gateway.refreshCodes(totpIds)
                    entries = entries.map { entry ->
                        codes[entry.id]?.let { (code, validUntil) ->
                            entry.copy(code = code, codeValidUntilEpochSeconds = validUntil)
                        } ?: entry
                    }
                }
                _state.update {
                    it.copy(
                        entries = entries,
                        groups = groups,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        selectedIds = it.selectedIds.intersect(entries.map(OtpEntryUiModel::id).toSet()),
                    )
                }
                persistSelection()
                if (active) scheduleCodeRefresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error) { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    fun setQuery(value: String) {
        savedStateHandle[KEY_QUERY] = value
        _state.update { it.copy(query = value) }
    }

    fun toggleSort() {
        val sort = if (_state.value.sort == CodeSort.Ascending) CodeSort.Descending else CodeSort.Ascending
        savedStateHandle[KEY_SORT] = sort.ordinal
        _state.update { it.copy(sort = sort) }
    }

    fun setGroupFilter(filter: GroupFilter) {
        savedStateHandle[KEY_GROUP] = when (filter) {
            GroupFilter.All -> FILTER_ALL
            GroupFilter.Ungrouped -> FILTER_UNGROUPED
            is GroupFilter.Group -> filter.id
        }
        _state.update { it.copy(groupFilter = filter) }
    }

    fun toggleSelection(id: Long) {
        _state.update {
            val selected = it.selectedIds.toMutableSet().apply {
                if (!add(id)) remove(id)
            }
            it.copy(selectedIds = selected)
        }
        persistSelection()
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
        persistSelection()
    }

    fun generateHotp(id: Long) {
        if (_state.value.retryAfterSeconds != null) return
        viewModelScope.launch {
            runUiCatching { gateway.generateHotp(id) }
                .onSuccess { updated -> replaceEntry(updated) }
                .onFailure(::showError)
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        if (_state.value.retryAfterSeconds != null) return
        viewModelScope.launch {
            runUiCatching { gateway.setEntryEnabled(id, enabled) }
                .onSuccess { updated -> replaceEntry(updated); if (active) scheduleCodeRefresh() }
                .onFailure(::showError)
        }
    }

    fun deleteSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.retryAfterSeconds != null) return
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            ids.forEach { id ->
                runUiCatching { gateway.deleteEntry(id) }
                    .onFailure { failures += it.userMessage(); if (it is UiGatewayException && it.retryAfterSeconds != null) showError(it) }
            }
            val failed = failures.size
            val succeeded = ids.size - failed
            _state.update {
                it.copy(
                    entries = if (failed == 0) it.entries.filterNot { entry -> entry.id in ids } else it.entries,
                    selectedIds = emptySet(),
                    operation = BatchOperationUiModel(BatchAction.Delete, succeeded, failed),
                    error = failures.takeIf { errors -> errors.isNotEmpty() }?.take(3)?.joinToString("\n"),
                )
            }
            persistSelection()
            if (failed > 0) refresh()
        }
    }

    fun moveSelected(groupId: Long?) {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty() || _state.value.retryAfterSeconds != null) return
        viewModelScope.launch {
            val updated = mutableMapOf<Long, OtpEntryUiModel>()
            val failures = mutableListOf<String>()
            ids.forEach { id ->
                runUiCatching { gateway.moveEntry(id, groupId) }
                    .onSuccess { updated[id] = it }
                    .onFailure { failures += it.userMessage(); if (it is UiGatewayException && it.retryAfterSeconds != null) showError(it) }
            }
            _state.update {
                it.copy(
                    entries = it.entries.map { entry -> updated[entry.id] ?: entry },
                    selectedIds = emptySet(),
                    operation = BatchOperationUiModel(BatchAction.Move, updated.size, failures.size),
                    error = failures.takeIf { errors -> errors.isNotEmpty() }?.take(3)?.joinToString("\n"),
                )
            }
            persistSelection()
        }
    }

    fun dismissMessage() {
        cooldownJob?.cancel()
        _state.update { it.copy(error = null, errorStatus = null, errorServerMessage = false, errorClientCode = null, operation = null, retryAfterSeconds = null) }
    }

    private fun replaceEntry(updated: OtpEntryUiModel) {
        _state.update { state ->
            state.copy(entries = state.entries.map { if (it.id == updated.id) updated else it })
        }
    }

    private fun scheduleCodeRefresh() {
        codeRefreshJob?.cancel()
        codeRefreshJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = _state.value.entries
                val active = snapshot.filter { it.enabled && it.kind == OtpKind.Totp }
                if (active.isEmpty()) return@launch
                val nowSeconds = nowMillis() / 1_000
                val nextExpiry = active.mapNotNull { it.codeValidUntilEpochSeconds }.minOrNull() ?: (nowSeconds + 1)
                delay(((nextExpiry - nowSeconds).coerceAtLeast(1)) * 1_000)
                try {
                    val codes = gateway.refreshCodes(active.map { it.id })
                    val retryAt = nowMillis() / 1_000 + 5
                    _state.update { state ->
                        state.copy(entries = state.entries.map { entry ->
                            codes[entry.id]?.let { (code, validUntil) ->
                                entry.copy(code = code, codeValidUntilEpochSeconds = validUntil)
                            } ?: if (entry.enabled && entry.kind == OtpKind.Totp) entry.copy(codeValidUntilEpochSeconds = retryAt) else entry
                        })
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    showError(error)
                    delay(5_000)
                }
            }
        }
    }

    internal fun cancelScheduledRefresh() {
        codeRefreshJob?.cancel()
        codeRefreshJob = null
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) scheduleCodeRefresh() else cancelScheduledRefresh()
    }

    private fun showError(error: Throwable, extra: (CodesUiState) -> CodesUiState = { it }) {
        val gateway = error as? UiGatewayException
        _state.update { state -> extra(state).copy(
            error = error.userMessage(), errorStatus = gateway?.status,
            errorServerMessage = gateway?.serverMessage == true,
            errorClientCode = gateway?.clientCode,
            retryAfterSeconds = gateway?.retryAfterSeconds,
        ) }
        cooldownJob?.cancel()
        gateway?.retryAfterSeconds?.takeIf { it > 0 }?.let { seconds ->
            cooldownJob = viewModelScope.launch {
                var remaining = seconds
                while (remaining > 0) { _state.update { it.copy(retryAfterSeconds = remaining) }; delay(1_000); remaining-- }
                _state.update { it.copy(retryAfterSeconds = null) }
            }
        }
    }

    private fun persistSelection() {
        savedStateHandle[KEY_SELECTED] = ArrayList(_state.value.selectedIds)
    }

    private fun restoreFilter(value: Long?): GroupFilter = when (value) {
        null, FILTER_ALL -> GroupFilter.All
        FILTER_UNGROUPED -> GroupFilter.Ungrouped
        else -> GroupFilter.Group(value)
    }

    private fun Throwable.userMessage(): String =
        (this as? UiGatewayException)?.message ?: message.orEmpty()

    private companion object {
        const val KEY_QUERY = "codes.query"
        const val KEY_SORT = "codes.sort"
        const val KEY_GROUP = "codes.group"
        const val KEY_SELECTED = "codes.selected"
        const val FILTER_ALL = Long.MIN_VALUE
        const val FILTER_UNGROUPED = Long.MIN_VALUE + 1
    }
}
