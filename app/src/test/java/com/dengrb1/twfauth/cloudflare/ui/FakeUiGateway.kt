package com.dengrb1.twfauth.cloudflare.ui

import com.dengrb1.twfauth.cloudflare.ui.model.*

open class FakeUiGateway : UiGateway {
    override val serverUrl = "https://example.workers.dev"
    override val appVersion = "test"
    var savedSession = false
    var entryValues = mutableListOf<OtpEntryUiModel>()
    var groupValues = mutableListOf<OtpGroupUiModel>()
    var deleteFailures = emptySet<Long>()
    var loginError: Throwable? = null
    var lastTurnstileToken: String? = null
    var preferencesValue = AppPreferencesUiModel()
    var createEntryError: Throwable? = null
    var logoutError: Throwable? = null
    var passwordChanged = false

    override suspend fun capabilities() = CapabilityUiModel("v1", setOf("android"), false)
    override suspend fun hasSavedSession() = savedSession
    override suspend fun login(username: String, password: String, turnstileToken: String?): UserUiModel {
        loginError?.let { throw it }; lastTurnstileToken = turnstileToken; return UserUiModel("1", username, "user")
    }
    override suspend fun currentUser() = UserUiModel("1", "alice", "user")
    override open suspend fun logout() { savedSession = false; logoutError?.let { throw it } }
    override open suspend fun entries() = entryValues.toList()
    override open suspend fun groups() = groupValues.toList()
    override open suspend fun refreshCodes(entryIds: List<Long>): Map<Long, Pair<String, Long?>> =
        entryIds.associateWith { "123456" to (System.currentTimeMillis() / 1_000 + 30) }
    override suspend fun generateHotp(entryId: Long) = entryValues.first { it.id == entryId }.copy(code = "654321", counter = 1)
    override open suspend fun createEntry(draft: OtpEntryDraft): OtpEntryUiModel { createEntryError?.let { throw it }; return OtpEntryUiModel(99, draft.label) }
    override open suspend fun updateEntry(draft: OtpEntryDraft) = entryValues.first { it.id == draft.id }.copy(label = draft.label)
    override open suspend fun deleteEntry(entryId: Long) { if (entryId in deleteFailures) error("delete failed $entryId") else entryValues.removeAll { it.id == entryId } }
    override suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): OtpEntryUiModel = entryValues.first { it.id == entryId }.copy(enabled = enabled)
    override suspend fun moveEntry(entryId: Long, groupId: Long?): OtpEntryUiModel = entryValues.first { it.id == entryId }.copy(groupId = groupId)
    override open suspend fun createGroup(name: String, color: Long) = OtpGroupUiModel(99, name, color).also { groupValues += it }
    override open suspend fun updateGroup(id: Long, name: String, color: Long) = OtpGroupUiModel(id, name, color).also { updated -> groupValues.replaceAll { if (it.id == id) updated else it } }
    override open suspend fun deleteGroup(id: Long) { groupValues.removeAll { it.id == id } }
    override open suspend fun importOtpAuth(content: String, groupId: Long?) = ImportSummaryUiModel(1, 1, listOf(1), 0)
    override open suspend fun importEncrypted(content: String, passphrase: CharArray) = EncryptedImportSummaryUiModel(1, 2)
    override open suspend fun exportEncrypted(passphrase: CharArray) = "{}"
    override open suspend fun preferences() = preferencesValue
    override open suspend fun setTheme(theme: ThemePreference) { preferencesValue = preferencesValue.copy(theme = theme) }
    override open suspend fun setLanguage(language: LanguagePreference) { preferencesValue = preferencesValue.copy(language = language) }
    override open suspend fun setAppLock(enabled: Boolean) { preferencesValue = preferencesValue.copy(appLockEnabled = enabled) }
    override open suspend fun changePassword(currentPassword: String, newPassword: String) { passwordChanged = true }
}
