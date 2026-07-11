package com.dengrb1.twfauth.cloudflare.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dengrb1.twfauth.cloudflare.R
import com.dengrb1.twfauth.cloudflare.ui.UiTestTags
import com.dengrb1.twfauth.cloudflare.ui.components.ErrorBanner
import com.dengrb1.twfauth.cloudflare.ui.components.LoadingPane

@Composable
fun AuthScreen(
    state: AuthUiState,
    serverUrl: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onUnlock: () -> Unit,
    onUsePassword: () -> Unit,
    onDismissError: () -> Unit,
) {
    val localError = state.localError?.let {
        stringResource(when (it) {
            AuthLocalError.MissingTurnstileSiteKey -> R.string.error_missing_turnstile_site_key
            AuthLocalError.ChallengeCancelled -> R.string.error_challenge_cancelled
            AuthLocalError.UnlockFailed -> R.string.error_unlock_failed
            AuthLocalError.Unknown -> R.string.unknown_error
        })
    }
    val gatewayError = state.error?.let { message ->
        if (state.errorServerMessage && message.isNotBlank()) message else stringResource(
            when (state.errorClientCode) {
                "api_version" -> R.string.error_api_version
                "android_client" -> R.string.error_android_client
                "bearer_auth" -> R.string.error_bearer_auth
                "api_routes" -> R.string.error_api_routes
                "network" -> R.string.error_network_unavailable
                else -> when (state.errorStatus) {
                    400 -> R.string.error_invalid_request; 401 -> R.string.error_auth_required
                    403 -> R.string.error_forbidden; 404 -> R.string.error_not_found
                    409 -> R.string.error_conflict; 413 -> R.string.error_payload_too_large
                    429 -> R.string.error_rate_limited; 503 -> R.string.error_service_unavailable
                    else -> R.string.unknown_error
                }
            },
        )
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.mode == AuthMode.Loading) {
            LoadingPane(stringResource(R.string.checking_compatibility))
            return@Box
        }
        Card(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .testTag(UiTestTags.AUTH_FORM),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "2FAuth",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = if (state.mode == AuthMode.Unlock) stringResource(R.string.unlock_codes) else stringResource(R.string.sign_in_continue),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                (gatewayError ?: localError)?.let {
                    ErrorBanner(
                        message = it,
                        retryAfterSeconds = state.retryAfterSeconds,
                        onDismiss = onDismissError,
                        modifier = Modifier.testTag(UiTestTags.ERROR_BANNER),
                    )
                }
                if (state.mode == AuthMode.Unlock) {
                    Button(
                        onClick = onUnlock,
                        enabled = !state.isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag(UiTestTags.UNLOCK),
                    ) { Text(if (state.isSubmitting) stringResource(R.string.unlocking) else stringResource(R.string.unlock)) }
                    TextButton(
                        onClick = onUsePassword,
                        enabled = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
                    ) { Text(stringResource(R.string.use_password_instead)) }
                } else {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = onUsernameChange,
                        modifier = Modifier.fillMaxWidth().testTag(UiTestTags.USERNAME),
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth().testTag(UiTestTags.PASSWORD),
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onLogin() }),
                    )
                    Button(
                        onClick = onLogin,
                        enabled = state.canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = 48.dp)
                            .testTag(UiTestTags.LOGIN),
                    ) { Text(if (state.isSubmitting) stringResource(R.string.signing_in) else stringResource(R.string.sign_in)) }
                    if (state.capabilities?.turnstileRequired == true) {
                        Text(
                            stringResource(R.string.turnstile_required_notice),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
