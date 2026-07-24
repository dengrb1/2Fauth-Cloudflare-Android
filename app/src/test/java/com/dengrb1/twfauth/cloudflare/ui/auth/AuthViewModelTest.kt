package com.dengrb1.twfauth.cloudflare.ui.auth

import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.FakeUiGateway
import com.dengrb1.twfauth.cloudflare.ui.MainDispatcherRule
import com.dengrb1.twfauth.cloudflare.ui.model.CapabilityUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.SavedLoginCredentials
import com.dengrb1.twfauth.cloudflare.ui.model.UiGatewayException
import com.dengrb1.twfauth.cloudflare.ui.model.UserUiModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun turnstileTokenIsRequestedAndSent() = runTest(main.dispatcher) {
        val gateway = object : FakeUiGateway() {
            override suspend fun capabilities() = CapabilityUiModel("v1", setOf("android"), true, "site-key")
        }
        val vm = AuthViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        vm.setUsername("alice"); vm.setPassword("Password-123!")
        vm.login { assertEquals("site-key", it); "turnstile-token" }
        advanceUntilIdle()
        assertEquals("turnstile-token", gateway.lastTurnstileToken)
        assertEquals("alice", vm.state.value.signedInUser?.username)
    }

    @Test fun rateLimitCountdownDisablesThenReenablesLogin() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply { loginError = UiGatewayException(429, "slow down", 2) }
        val vm = AuthViewModel(gateway, SavedStateHandle())
        advanceUntilIdle(); vm.setUsername("alice"); vm.setPassword("Password-123!"); vm.login { null }
        runCurrent()
        assertEquals(2L, vm.state.value.retryAfterSeconds)
        advanceTimeBy(2_100); runCurrent()
        assertNull(vm.state.value.retryAfterSeconds)
    }

    @Test fun failedTurnstileLoginRequestsAFreshChallengeOnRetry() = runTest(main.dispatcher) {
        var loginAttempts = 0
        val tokens = mutableListOf<String?>()
        val gateway = object : FakeUiGateway() {
            override suspend fun capabilities() = CapabilityUiModel("v1", setOf("android"), true, "site-key")
            override suspend fun login(username: String, password: String, turnstileToken: String?): UserUiModel {
                tokens += turnstileToken
                if (loginAttempts++ == 0) throw UiGatewayException(401, "bad challenge")
                return UserUiModel("1", username, "user")
            }
        }
        val vm = AuthViewModel(gateway, SavedStateHandle()); advanceUntilIdle()
        vm.setUsername("alice"); vm.setPassword("Password-123!")
        var challenges = 0
        vm.login { "challenge-${++challenges}" }; advanceUntilIdle()
        assertEquals(1, challenges); assertNull(vm.state.value.signedInUser)
        vm.login { "challenge-${++challenges}" }; advanceUntilIdle()
        assertEquals(listOf("challenge-1", "challenge-2"), tokens)
        assertEquals("alice", vm.state.value.signedInUser?.username)
    }


    @Test fun prefillsRememberedCredentialsOnLoginScreen() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply {
            loginCredentialsValue = SavedLoginCredentials("alice", "Password-123!", rememberPassword = true)
        }
        val vm = AuthViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(AuthMode.Login, vm.state.value.mode)
        assertEquals("alice", vm.state.value.username)
        assertEquals("Password-123!", vm.state.value.password)
        assertTrue(vm.state.value.rememberPassword)
    }

    @Test fun successfulLoginPersistsCredentialsWhenRememberEnabled() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway()
        val vm = AuthViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        vm.setUsername("alice"); vm.setPassword("Password-123!"); vm.setRememberPassword(true)
        vm.login { null }
        advanceUntilIdle()
        assertEquals("alice", gateway.loginCredentialsValue.username)
        assertEquals("Password-123!", gateway.loginCredentialsValue.password)
        assertTrue(gateway.loginCredentialsValue.rememberPassword)
    }

    @Test fun successfulLoginSkipsPasswordWhenRememberDisabled() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply {
            loginCredentialsValue = SavedLoginCredentials("old", "secret", rememberPassword = true)
        }
        val vm = AuthViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        vm.setUsername("alice"); vm.setPassword("Password-123!"); vm.setRememberPassword(false)
        advanceUntilIdle()
        vm.login { null }
        advanceUntilIdle()
        assertEquals("alice", gateway.loginCredentialsValue.username)
        assertEquals("", gateway.loginCredentialsValue.password)
        assertFalse(gateway.loginCredentialsValue.rememberPassword)
    }

    @Test fun authenticationRequestIsCancelledWhenTheScreenStops() = runTest(main.dispatcher) {
        val cancelled = CompletableDeferred<Unit>()
        val gateway = object : FakeUiGateway() {
            override suspend fun capabilities(): CapabilityUiModel =
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val vm = AuthViewModel(gateway, SavedStateHandle()); runCurrent()
        assertTrue(vm.state.value.mode == AuthMode.Loading)
        vm.setActive(false); runCurrent()
        assertTrue(cancelled.isCompleted)
        assertFalse(vm.state.value.isSubmitting)
    }
}
