package com.dengrb1.twfauth.cloudflare.ui

import androidx.lifecycle.SavedStateHandle
import com.dengrb1.twfauth.cloudflare.ui.backups.BackupResult
import com.dengrb1.twfauth.cloudflare.ui.backups.BackupsViewModel
import com.dengrb1.twfauth.cloudflare.ui.entry.EntryEditorViewModel
import com.dengrb1.twfauth.cloudflare.ui.entry.EntryValidationError
import com.dengrb1.twfauth.cloudflare.ui.groups.GroupsViewModel
import com.dengrb1.twfauth.cloudflare.ui.model.AppPreferencesUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.LanguagePreference
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryDraft
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.ThemePreference
import com.dengrb1.twfauth.cloudflare.ui.model.UiGatewayException
import com.dengrb1.twfauth.cloudflare.ui.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun entryDraftRestoresNonSecretsButNeverSerializesOtpCredentials() = runTest(main.dispatcher) {
        val handle = SavedStateHandle()
        val first = EntryEditorViewModel(FakeUiGateway(), handle, OtpEntryDraft())
        first.beginSession(1, OtpEntryDraft())
        first.update { it.copy(label = "Mail", issuer = "Example", secret = "TOPSECRET") }
        assertFalse(handle.contains("entry.secret")); assertFalse(handle.contains("entry.uri"))
        val restored = EntryEditorViewModel(FakeUiGateway(), handle, OtpEntryDraft())
        assertEquals("Mail", restored.state.value.draft.label)
        assertEquals("Example", restored.state.value.draft.issuer)
        assertEquals("", restored.state.value.draft.secret)
        assertEquals("", restored.state.value.draft.otpauthUri)
    }

    @Test fun entryValidationAnd429CooldownLiveInViewModelState() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply { createEntryError = UiGatewayException(429, "slow", 2, serverMessage = true) }
        val vm = EntryEditorViewModel(gateway, SavedStateHandle(), OtpEntryDraft())
        vm.save()
        assertEquals(EntryValidationError.SecretOrUri, vm.state.value.validationError)
        vm.update { it.copy(label = "Mail", secret = "ABC") }; vm.save(); runCurrent()
        assertEquals(2L, vm.state.value.retryAfterSeconds)
        advanceTimeBy(2_100); runCurrent()
        assertNull(vm.state.value.retryAfterSeconds)
    }

    @Test fun groupsCrudAndCountsAreStateFlowDriven() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply { groupValues += OtpGroupUiModel(1, "Work", 0xFF000000) }
        val vm = GroupsViewModel(gateway, SavedStateHandle()); runCurrent()
        vm.openEdit(gateway.groupValues.single()); vm.setName("Office"); vm.save(); runCurrent()
        assertEquals("Office", vm.state.value.groups.single().name)
        vm.requestDelete(1); vm.confirmDelete(); runCurrent()
        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test fun groupConflictAndPermissionErrorsKeepThePendingUserDecision() = runTest(main.dispatcher) {
        val conflictGateway = object : FakeUiGateway() {
            override suspend fun updateGroup(id: Long, name: String, color: Long): OtpGroupUiModel =
                throw UiGatewayException(409, "duplicate")
        }.apply { groupValues += OtpGroupUiModel(1, "Work", 0xFF000000) }
        val conflictVm = GroupsViewModel(conflictGateway, SavedStateHandle()); runCurrent()
        conflictVm.openEdit(conflictGateway.groupValues.single()); conflictVm.setName("Existing"); conflictVm.save(); runCurrent()
        assertEquals(409, conflictVm.state.value.error?.status)
        assertEquals("Existing", conflictVm.state.value.draft?.name)
        assertFalse(conflictVm.state.value.saving)

        val forbiddenGateway = object : FakeUiGateway() {
            override suspend fun deleteGroup(id: Long): Unit = throw UiGatewayException(403, "forbidden")
        }.apply { groupValues += OtpGroupUiModel(2, "Personal", 0xFF000000) }
        val forbiddenVm = GroupsViewModel(forbiddenGateway, SavedStateHandle()); runCurrent()
        forbiddenVm.requestDelete(2); forbiddenVm.confirmDelete(); runCurrent()
        assertEquals(403, forbiddenVm.state.value.error?.status)
        assertEquals(2L, forbiddenVm.state.value.deleteTargetId)
        assertFalse(forbiddenVm.state.value.saving)
    }

    @Test fun backupImportSummariesAndExportEventAreStateFlowDrivenWithoutSavingSecrets() = runTest(main.dispatcher) {
        val handle = SavedStateHandle()
        var selectedGroup: Long? = null
        val gateway = object : FakeUiGateway() {
            override suspend fun importOtpAuth(content: String, groupId: Long?): com.dengrb1.twfauth.cloudflare.ui.model.ImportSummaryUiModel {
                selectedGroup = groupId
                return com.dengrb1.twfauth.cloudflare.ui.model.ImportSummaryUiModel(
                    found = 8, imported = 2, importedIds = listOf(10, 11), failed = 6,
                    errors = listOf("one", "two", "three", "four", "five", "six"),
                )
            }
        }
        val vm = BackupsViewModel(gateway, handle); runCurrent()
        vm.setGroup(4); vm.setImportText("otpauth://totp/x?secret=ABC"); vm.setPassphrase("Long passphrase")
        vm.importOtpAuth(); runCurrent()
        val result = vm.state.value.result as BackupResult.OtpAuth
        assertEquals(8, result.found); assertEquals(2, result.imported); assertEquals(6, result.failed)
        assertEquals(5, result.errors.size); assertEquals(4L, selectedGroup)
        assertFalse(handle.contains("backups.import_text"))
        assertFalse(handle.contains("backups.passphrase"))
        vm.importEncrypted("{\"encrypted\":{}}"); runCurrent()
        val encrypted = vm.state.value.result as BackupResult.EncryptedImport
        assertEquals(1, encrypted.groups); assertEquals(2, encrypted.entries)
        vm.exportEncrypted(); runCurrent()
        assertEquals("{}", vm.state.value.pendingExport?.content)
        vm.exportHandled(true)
        assertTrue(vm.state.value.result is BackupResult.Exported)
    }

    @Test fun settingsPersistChoicesChangePasswordAndAlwaysLockAfterLogoutFailure() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply { preferencesValue = AppPreferencesUiModel() }
        val vm = SettingsViewModel(gateway, SavedStateHandle(), AppPreferencesUiModel()); runCurrent()
        vm.setTheme(ThemePreference.Dark); vm.setLanguage(LanguagePreference.Chinese); vm.setAppLock(false); runCurrent()
        assertEquals(ThemePreference.Dark, vm.state.value.preferences.theme)
        assertEquals(LanguagePreference.Chinese, vm.state.value.preferences.language)
        assertFalse(vm.state.value.preferences.appLockEnabled)
        vm.openPasswordDialog(); vm.setCurrentPassword("Old-password-1!"); vm.setNewPassword("New-password-2!"); vm.changePassword(); runCurrent()
        assertTrue(gateway.passwordChanged); assertTrue(vm.state.value.signedOut)
        assertEquals("", vm.state.value.currentPassword); assertEquals("", vm.state.value.newPassword)

        val failingGateway = FakeUiGateway().apply { logoutError = UiGatewayException(503, "offline") }
        val logoutVm = SettingsViewModel(failingGateway, SavedStateHandle(), AppPreferencesUiModel()); runCurrent()
        logoutVm.logout(); runCurrent()
        assertTrue(logoutVm.state.value.signedOut)
    }

    @Test fun nonSensitiveDialogAndSelectionStateRestoresAcrossViewModelRecreation() = runTest(main.dispatcher) {
        val gateway = FakeUiGateway().apply { groupValues += OtpGroupUiModel(7, "Work", 0xFF123456) }

        val groupsHandle = SavedStateHandle()
        val firstGroups = GroupsViewModel(gateway, groupsHandle); runCurrent()
        firstGroups.openEdit(gateway.groupValues.single()); firstGroups.setName("Office"); firstGroups.setColor(0xFF654321)
        firstGroups.requestDelete(7)
        val restoredGroups = GroupsViewModel(gateway, groupsHandle); runCurrent()
        assertEquals(7L, restoredGroups.state.value.draft?.id)
        assertEquals("Office", restoredGroups.state.value.draft?.name)
        assertEquals(0xFF654321, restoredGroups.state.value.draft?.color)
        assertEquals(7L, restoredGroups.state.value.deleteTargetId)

        val backupsHandle = SavedStateHandle()
        BackupsViewModel(gateway, backupsHandle).also { it.setGroup(7) }
        val restoredBackups = BackupsViewModel(gateway, backupsHandle); runCurrent()
        assertEquals(7L, restoredBackups.state.value.groupId)
        assertEquals("", restoredBackups.state.value.importText)
        assertEquals("", restoredBackups.state.value.passphrase)

        val settingsHandle = SavedStateHandle()
        SettingsViewModel(gateway, settingsHandle, AppPreferencesUiModel()).also {
            it.openPasswordDialog(); it.setCurrentPassword("Old-password-1!"); it.setNewPassword("New-password-2!")
        }
        val restoredSettings = SettingsViewModel(gateway, settingsHandle, AppPreferencesUiModel()); runCurrent()
        assertTrue(restoredSettings.state.value.passwordDialogOpen)
        assertEquals("", restoredSettings.state.value.currentPassword)
        assertEquals("", restoredSettings.state.value.newPassword)
    }

    @Test fun visibleScreenRequestsAreCancelledAndProgressIsResetOnStop() = runTest(main.dispatcher) {
        val entryCancelled = CompletableDeferred<Unit>()
        val entryGateway = object : FakeUiGateway() {
            override suspend fun createEntry(draft: OtpEntryDraft): com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryUiModel =
                try { awaitCancellation() } finally { entryCancelled.complete(Unit) }
        }
        val entryVm = EntryEditorViewModel(entryGateway, SavedStateHandle(), OtpEntryDraft())
        entryVm.update { it.copy(label = "Mail", secret = "ABC") }; entryVm.save(); runCurrent()
        assertTrue(entryVm.state.value.saving)
        entryVm.setActive(false); runCurrent()
        assertTrue(entryCancelled.isCompleted); assertFalse(entryVm.state.value.saving)

        val groupsCancelled = CompletableDeferred<Unit>()
        val groupsGateway = object : FakeUiGateway() {
            override suspend fun groups(): List<OtpGroupUiModel> =
                try { awaitCancellation() } finally { groupsCancelled.complete(Unit) }
        }
        val groupsVm = GroupsViewModel(groupsGateway, SavedStateHandle()); runCurrent()
        groupsVm.setActive(false); runCurrent()
        assertTrue(groupsCancelled.isCompleted); assertFalse(groupsVm.state.value.loading)

        val backupCancelled = CompletableDeferred<Unit>()
        val backupGateway = object : FakeUiGateway() {
            override suspend fun importOtpAuth(content: String, groupId: Long?): com.dengrb1.twfauth.cloudflare.ui.model.ImportSummaryUiModel =
                try { awaitCancellation() } finally { backupCancelled.complete(Unit) }
        }
        val backupsVm = BackupsViewModel(backupGateway, SavedStateHandle()); runCurrent()
        backupsVm.setImportText("otpauth://totp/x?secret=ABC"); backupsVm.importOtpAuth(); runCurrent()
        assertTrue(backupsVm.state.value.busy)
        backupsVm.setActive(false); runCurrent()
        assertTrue(backupCancelled.isCompleted); assertFalse(backupsVm.state.value.busy)

        val settingsCancelled = CompletableDeferred<Unit>()
        val settingsGateway = object : FakeUiGateway() {
            override suspend fun changePassword(currentPassword: String, newPassword: String): Unit =
                try { awaitCancellation() } finally { settingsCancelled.complete(Unit) }
        }
        val settingsVm = SettingsViewModel(settingsGateway, SavedStateHandle(), AppPreferencesUiModel()); runCurrent()
        settingsVm.openPasswordDialog(); settingsVm.setCurrentPassword("Old-password-1!"); settingsVm.setNewPassword("New-password-2!")
        settingsVm.changePassword(); runCurrent()
        assertTrue(settingsVm.state.value.busy)
        settingsVm.setActive(false); runCurrent()
        assertTrue(settingsCancelled.isCompleted); assertFalse(settingsVm.state.value.busy)
        assertFalse(settingsVm.state.value.signedOut)
    }
}
