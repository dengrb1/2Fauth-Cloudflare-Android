package com.dengrb1.twfauth.cloudflare.ui.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.model.CapabilityUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiGatewayException
import com.dengrb1.twfauth.cloudflare.ui.model.UserUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import com.dengrb1.twfauth.cloudflare.ui.model.CooldownViewModel
import com.dengrb1.twfauth.cloudflare.ui.model.runUiCatching

enum class AuthMode { Loading, Login, Unlock }
enum class AuthLocalError { MissingTurnstileSiteKey, ChallengeCancelled, UnlockFailed, Unknown }
private class LocalAuthException(val kind: AuthLocalError) : Exception()

@Immutable
data class AuthUiState(
    val mode: AuthMode = AuthMode.Loading,
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val capabilities: CapabilityUiModel? = null,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val errorStatus: Int? = null,
    val errorServerMessage: Boolean = false,
    val errorClientCode: String? = null,
    val localError: AuthLocalError? = null,
    val retryAfterSeconds: Long? = null,
    val signedInUser: UserUiModel? = null,
) {
    val canSubmit: Boolean
        get() = username.isNotBlank() && password.isNotBlank() && !isSubmitting && retryAfterSeconds == null
}

class AuthViewModel(
    private val gateway: UiGateway,
    private val savedStateHandle: SavedStateHandle,
) : CooldownViewModel() {
    private var active = true
    private val _state = MutableStateFlow(
        AuthUiState(
            username = savedStateHandle.get<String>(KEY_USERNAME).orEmpty(),
            rememberPassword = savedStateHandle.get<Boolean>(KEY_REMEMBER_PASSWORD) ?: true,
        ),
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        bootstrap()
    }

    fun bootstrap() {
        launchScreenTask {
            _state.update { it.copy(mode = AuthMode.Loading, error = null, localError = null) }
            try {
                val capabilities = gateway.capabilities()
                _state.update { it.copy(capabilities = capabilities) }
                if (gateway.hasSavedSession()) {
                    if (gateway.preferences().appLockEnabled) {
                        _state.update { it.copy(mode = AuthMode.Unlock) }
                    } else {
                        _state.update { it.copy(mode = AuthMode.Unlock, signedInUser = gateway.currentUser()) }
                    }
                } else {
                    applySavedCredentials(AuthMode.Login)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                runCatching { applySavedCredentials(AuthMode.Login) }
                showError(error, AuthMode.Login)
            }
        }
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) bootstrap() else {
            cancelScreenTasks()
            _state.update { it.copy(isSubmitting = false) }
        }
    }

    private suspend fun loadCapabilities() {
        runUiCatching { gateway.capabilities() }
            .onSuccess { capabilities ->
                _state.update { it.copy(capabilities = capabilities, error = null, localError = null, errorStatus = null, errorServerMessage = false, errorClientCode = null) }
                applySavedCredentials(AuthMode.Login)
            }
            .onFailure { showError(it, AuthMode.Login) }
    }

    private suspend fun applySavedCredentials(mode: AuthMode) {
        val credentials = runUiCatching { gateway.loginCredentials() }.getOrDefault(
            com.dengrb1.twfauth.cloudflare.ui.model.SavedLoginCredentials(
                username = _state.value.username,
                password = _state.value.password,
                rememberPassword = _state.value.rememberPassword,
            ),
        )
        val username = _state.value.username.ifBlank { credentials.username }
        savedStateHandle[KEY_USERNAME] = username
        savedStateHandle[KEY_REMEMBER_PASSWORD] = credentials.rememberPassword
        _state.update {
            it.copy(
                mode = mode,
                username = username,
                password = if (credentials.rememberPassword) credentials.password else it.password,
                rememberPassword = credentials.rememberPassword,
            )
        }
    }

    fun setUsername(value: String) {
        savedStateHandle[KEY_USERNAME] = value
        _state.update { it.copy(username = value, error = null, localError = null) }
    }

    fun setPassword(value: String) {
        _state.update { it.copy(password = value, error = null, localError = null) }
    }

    fun setRememberPassword(value: Boolean) {
        savedStateHandle[KEY_REMEMBER_PASSWORD] = value
        _state.update { it.copy(rememberPassword = value, error = null, localError = null) }
        if (!value) {
            launchScreenTask {
                runUiCatching { gateway.clearRememberedPassword() }
            }
        }
    }

    fun login(requestTurnstileToken: suspend (String) -> String?) {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return
        launchScreenTask {
            _state.update { it.copy(isSubmitting = true, error = null, localError = null) }
            try {
                val capability = _state.value.capabilities ?: gateway.capabilities().also { loaded ->
                    _state.update { it.copy(capabilities = loaded) }
                }
                val token = if (capability.turnstileRequired) {
                    val siteKey = capability.turnstileSiteKey?.takeIf(String::isNotBlank)
                        ?: throw LocalAuthException(AuthLocalError.MissingTurnstileSiteKey)
                    requestTurnstileToken(siteKey)
                        ?: throw LocalAuthException(AuthLocalError.ChallengeCancelled)
                } else {
                    null
                }
                val user = gateway.login(snapshot.username.trim(), snapshot.password, token)
                runUiCatching {
                    gateway.rememberLogin(
                        username = snapshot.username.trim(),
                        password = snapshot.password.takeIf { snapshot.rememberPassword },
                        rememberPassword = snapshot.rememberPassword,
                    )
                }
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        password = if (snapshot.rememberPassword) snapshot.password else "",
                        signedInUser = user,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error, AuthMode.Login)
            }
        }
    }

    fun unlock(requestDeviceUnlock: suspend () -> Boolean) {
        if (_state.value.isSubmitting) return
        launchScreenTask {
            _state.update { it.copy(isSubmitting = true, error = null, localError = null) }
            try {
                if (!requestDeviceUnlock()) {
                    throw LocalAuthException(AuthLocalError.UnlockFailed)
                }
                val user = gateway.currentUser()
                _state.update { it.copy(isSubmitting = false, signedInUser = user) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error, AuthMode.Unlock)
            }
        }
    }

    fun usePasswordInstead() {
        launchScreenTask { loadCapabilities() }
    }

    fun clearError() {
        beginCooldown(null) { }
        _state.update { it.copy(error = null, localError = null, errorStatus = null, errorServerMessage = false, errorClientCode = null, retryAfterSeconds = null) }
    }

    fun consumeSignedInUser() {
        _state.update { it.copy(signedInUser = null) }
    }

    private fun showError(error: Throwable, mode: AuthMode) {
        val gatewayError = error as? UiGatewayException
        val local = (error as? LocalAuthException)?.kind
        startRetryCountdown(gatewayError?.retryAfterSeconds)
        _state.update {
            it.copy(
                mode = mode,
                isSubmitting = false,
                error = gatewayError?.message ?: error.message,
                errorStatus = gatewayError?.status,
                errorServerMessage = gatewayError?.serverMessage == true,
                errorClientCode = gatewayError?.clientCode,
                localError = local ?: if (gatewayError == null && error.message == null) AuthLocalError.Unknown else null,
                retryAfterSeconds = gatewayError?.retryAfterSeconds,
            )
        }
    }

    private fun startRetryCountdown(seconds: Long?) {
        beginCooldown(seconds) { remaining -> _state.update { it.copy(retryAfterSeconds = remaining) } }
    }

    private companion object {
        const val KEY_USERNAME = "auth.username"
        const val KEY_REMEMBER_PASSWORD = "auth.remember_password"
    }
}
