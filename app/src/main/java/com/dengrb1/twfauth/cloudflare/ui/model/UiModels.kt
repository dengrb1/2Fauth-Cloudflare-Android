package com.dengrb1.twfauth.cloudflare.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class CapabilityUiModel(
    val apiVersion: String = "",
    val compatibleClients: Set<String> = emptySet(),
    val turnstileRequired: Boolean = false,
    val turnstileSiteKey: String? = null,
    val endpoints: Set<String> = emptySet(),
    val limits: Map<String, Long> = emptyMap(),
)

@Immutable
data class UserUiModel(
    val id: String,
    val username: String,
    val role: String,
)

enum class OtpKind { Totp, Hotp }

enum class OtpAlgorithm(val wireName: String) {
    Sha1("SHA-1"),
    Sha256("SHA-256"),
    Sha512("SHA-512"),
}

@Immutable
data class OtpEntryUiModel(
    val id: Long,
    val label: String,
    val issuer: String = "",
    val secret: String = "",
    val otpauthUri: String? = null,
    val kind: OtpKind = OtpKind.Totp,
    val algorithm: OtpAlgorithm = OtpAlgorithm.Sha1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long = 0,
    val groupId: Long? = null,
    val groupName: String? = null,
    val groupColor: Long? = null,
    val enabled: Boolean = true,
    val code: String? = null,
    val codeValidUntilEpochSeconds: Long? = null,
)

@Immutable
data class OtpEntryDraft(
    val id: Long? = null,
    val label: String = "",
    val issuer: String = "",
    val secret: String = "",
    val otpauthUri: String = "",
    val kind: OtpKind = OtpKind.Totp,
    val algorithm: OtpAlgorithm = OtpAlgorithm.Sha1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long = 0,
    val groupId: Long? = null,
    val enabled: Boolean = true,
) {
    val isEditing: Boolean get() = id != null
}

@Immutable
data class OtpGroupUiModel(
    val id: Long,
    val name: String,
    val color: Long,
)

@Immutable
data class ImportSummaryUiModel(
    val found: Int,
    val imported: Int,
    val importedIds: List<Long> = emptyList(),
    val failed: Int,
    val errors: List<String> = emptyList(),
)

@Immutable
data class EncryptedImportSummaryUiModel(
    val groups: Int,
    val entries: Int,
)

@Immutable
data class PartialOperationUiModel(
    val succeeded: Int,
    val failed: Int,
    val errors: List<String> = emptyList(),
)

enum class ThemePreference { System, Light, Dark }

enum class LanguagePreference { System, English, Chinese }

@Immutable
data class AppPreferencesUiModel(
    val theme: ThemePreference = ThemePreference.System,
    val language: LanguagePreference = LanguagePreference.System,
    val appLockEnabled: Boolean = true,
)

class UiGatewayException(
    val status: Int? = null,
    override val message: String,
    val retryAfterSeconds: Long? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val serverMessage: Boolean = false,
    val clientCode: String? = null,
) : Exception(message)

/**
 * UI-facing boundary implemented by the application container. It intentionally mirrors only the
 * v1 endpoints consumed by the screens and prevents Compose/ViewModels from depending on transport
 * DTOs or legacy cookie routes.
 */
interface UiGateway {
    val serverUrl: String
    val appVersion: String

    suspend fun capabilities(): CapabilityUiModel
    suspend fun hasSavedSession(): Boolean
    suspend fun login(username: String, password: String, turnstileToken: String?): UserUiModel
    suspend fun currentUser(): UserUiModel
    suspend fun logout()

    suspend fun entries(): List<OtpEntryUiModel>
    suspend fun groups(): List<OtpGroupUiModel>
    suspend fun refreshCodes(entryIds: List<Long>): Map<Long, Pair<String, Long?>>
    suspend fun generateHotp(entryId: Long): OtpEntryUiModel
    suspend fun createEntry(draft: OtpEntryDraft): OtpEntryUiModel
    suspend fun updateEntry(draft: OtpEntryDraft): OtpEntryUiModel
    suspend fun deleteEntry(entryId: Long)
    suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): OtpEntryUiModel
    suspend fun moveEntry(entryId: Long, groupId: Long?): OtpEntryUiModel

    suspend fun createGroup(name: String, color: Long): OtpGroupUiModel
    suspend fun updateGroup(id: Long, name: String, color: Long): OtpGroupUiModel
    suspend fun deleteGroup(id: Long)

    suspend fun importOtpAuth(content: String, groupId: Long?): ImportSummaryUiModel
    suspend fun importEncrypted(content: String, passphrase: CharArray): EncryptedImportSummaryUiModel
    suspend fun exportEncrypted(passphrase: CharArray): String

    suspend fun preferences(): AppPreferencesUiModel
    suspend fun setTheme(theme: ThemePreference)
    suspend fun setLanguage(language: LanguagePreference)
    suspend fun setAppLock(enabled: Boolean)
    suspend fun changePassword(currentPassword: String, newPassword: String)
}
