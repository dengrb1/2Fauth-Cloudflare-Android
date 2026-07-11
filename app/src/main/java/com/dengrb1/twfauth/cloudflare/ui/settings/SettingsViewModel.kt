package com.dengrb1.twfauth.cloudflare.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.model.AppPreferencesUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.CapabilityUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.CooldownViewModel
import com.dengrb1.twfauth.cloudflare.ui.model.LanguagePreference
import com.dengrb1.twfauth.cloudflare.ui.model.ThemePreference
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiOperationError
import com.dengrb1.twfauth.cloudflare.ui.model.UserUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.toOperationError
import com.dengrb1.twfauth.cloudflare.ui.model.runUiCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Immutable
data class SettingsUiState(
    val user: UserUiModel? = null,
    val capabilities: CapabilityUiModel? = null,
    val preferences: AppPreferencesUiModel = AppPreferencesUiModel(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val passwordDialogOpen: Boolean = false,
    val currentPassword: String = "",
    val newPassword: String = "",
    val error: UiOperationError? = null,
    val retryAfterSeconds: Long? = null,
    val signedOut: Boolean = false,
)

class SettingsViewModel(
    private val gateway: UiGateway,
    private val savedStateHandle: SavedStateHandle,
    initialPreferences: AppPreferencesUiModel,
) : CooldownViewModel() {
    private var active = true
    private val _state = MutableStateFlow(
        SettingsUiState(preferences = initialPreferences, passwordDialogOpen = savedStateHandle.get<Boolean>(PASSWORD_DIALOG) == true),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) refresh() else {
            cancelScreenTasks()
            _state.update { it.copy(loading = false, busy = false) }
        }
    }

    fun refresh() = launchScreenTask {
        runUiCatching { Triple(gateway.currentUser(), gateway.capabilities(), gateway.preferences()) }
            .onSuccess { (user, capabilities, preferences) -> _state.update { it.copy(user = user, capabilities = capabilities, preferences = preferences, loading = false) } }
            .onFailure(::showError)
    }

    fun openPasswordDialog() { savedStateHandle[PASSWORD_DIALOG] = true; _state.update { it.copy(passwordDialogOpen = true, error = null) } }
    fun closePasswordDialog() { savedStateHandle[PASSWORD_DIALOG] = false; _state.update { it.copy(passwordDialogOpen = false, currentPassword = "", newPassword = "") } }
    fun setCurrentPassword(value: String) { _state.update { it.copy(currentPassword = value, error = null) } }
    fun setNewPassword(value: String) { _state.update { it.copy(newPassword = value, error = null) } }

    fun setTheme(value: ThemePreference) = updatePreference({ gateway.setTheme(value) }) { it.copy(theme = value) }
    fun setLanguage(value: LanguagePreference) = updatePreference({ gateway.setLanguage(value) }) { it.copy(language = value) }
    fun setAppLock(value: Boolean) = updatePreference({ gateway.setAppLock(value) }) { it.copy(appLockEnabled = value) }

    fun changePassword() {
        if (!validPassword(_state.value.newPassword) || _state.value.currentPassword.isBlank()) return
        runOperation {
            gateway.changePassword(_state.value.currentPassword, _state.value.newPassword)
            _state.update { it.copy(signedOut = true, passwordDialogOpen = false, currentPassword = "", newPassword = "") }
        }
    }

    fun logout() {
        if (_state.value.busy) return
        launchScreenTask {
            _state.update { it.copy(busy = true) }
            val failure = runUiCatching { gateway.logout() }.exceptionOrNull()
            _state.update { it.copy(busy = false, signedOut = true, error = failure?.toOperationError()) }
        }
    }
    fun consumeSignedOut() { _state.update { it.copy(signedOut = false) } }
    fun dismissError() { _state.update { it.copy(error = null) } }

    private fun updatePreference(call: suspend () -> Unit, update: (AppPreferencesUiModel) -> AppPreferencesUiModel) {
        runOperation { call(); _state.update { it.copy(preferences = update(it.preferences)) } }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (_state.value.busy || _state.value.retryAfterSeconds != null) return
        launchScreenTask {
            _state.update { it.copy(busy = true, error = null) }
            runUiCatching { block() }.onSuccess { _state.update { it.copy(busy = false) } }.onFailure(::showError)
        }
    }

    private fun showError(error: Throwable) {
        val uiError = error.toOperationError()
        _state.update { it.copy(loading = false, busy = false, error = uiError, retryAfterSeconds = uiError.retryAfterSeconds) }
        beginCooldown(uiError.retryAfterSeconds) { remaining -> _state.update { it.copy(retryAfterSeconds = remaining) } }
    }

    private fun validPassword(value: String) = value.length in 12..256 && value.any(Char::isUpperCase) &&
        value.any(Char::isLowerCase) && value.any(Char::isDigit) && value.any { !it.isLetterOrDigit() }

    private companion object { const val PASSWORD_DIALOG = "settings.password_dialog" }
}
