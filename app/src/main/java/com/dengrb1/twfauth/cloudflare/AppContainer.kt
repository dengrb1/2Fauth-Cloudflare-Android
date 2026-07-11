package com.dengrb1.twfauth.cloudflare

import android.app.KeyguardManager
import android.content.Context
import com.dengrb1.twfauth.cloudflare.data.DefaultTwoFactorRepository
import com.dengrb1.twfauth.cloudflare.data.remote.ApiFactory
import com.dengrb1.twfauth.cloudflare.data.remote.EncryptedBackupDto
import com.dengrb1.twfauth.cloudflare.data.session.TokenStore
import com.dengrb1.twfauth.cloudflare.domain.ApiException
import com.dengrb1.twfauth.cloudflare.domain.CreateEntryInput
import com.dengrb1.twfauth.cloudflare.domain.EncryptedBackup
import com.dengrb1.twfauth.cloudflare.domain.EntryPatch
import com.dengrb1.twfauth.cloudflare.domain.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.domain.OtpEntry
import com.dengrb1.twfauth.cloudflare.domain.OtpGroup
import com.dengrb1.twfauth.cloudflare.domain.OtpType
import com.dengrb1.twfauth.cloudflare.domain.TwoFactorRepository
import com.dengrb1.twfauth.cloudflare.domain.UserProfile
import com.dengrb1.twfauth.cloudflare.migration.LegacyThemePreferenceMigration
import com.dengrb1.twfauth.cloudflare.migration.LegacyInstallDetector
import com.dengrb1.twfauth.cloudflare.migration.LegacyLanguagePreferenceMigration
import com.dengrb1.twfauth.cloudflare.ui.model.AppPreferencesUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.CapabilityUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.EncryptedImportSummaryUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.ImportSummaryUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.LanguagePreference
import com.dengrb1.twfauth.cloudflare.ui.model.OtpAlgorithm as UiAlgorithm
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryDraft
import com.dengrb1.twfauth.cloudflare.ui.model.OtpEntryUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpGroupUiModel
import com.dengrb1.twfauth.cloudflare.ui.model.OtpKind
import com.dengrb1.twfauth.cloudflare.ui.model.ThemePreference
import com.dengrb1.twfauth.cloudflare.ui.model.UiGateway
import com.dengrb1.twfauth.cloudflare.ui.model.UiGatewayException
import com.dengrb1.twfauth.cloudflare.ui.model.UserUiModel
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val deviceSecure = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    val tokenStore = TokenStore(appContext, persistenceAllowed = deviceSecure)
    val repository: TwoFactorRepository = DefaultTwoFactorRepository(ApiFactory.create(BuildConfig.WORKER_URL), tokenStore)
    val uiGateway: UiGateway = AndroidUiGateway(appContext, repository)
}

private class AndroidUiGateway(
    private val context: Context,
    private val repository: TwoFactorRepository,
) : UiGateway {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init { migratePreferences() }

    override val serverUrl: String = BuildConfig.WORKER_URL
    override val appVersion: String = BuildConfig.VERSION_NAME

    override suspend fun capabilities(): CapabilityUiModel = api { repository.requireCompatibleCapabilities() }.let {
        CapabilityUiModel(
            apiVersion = it.apiVersion,
            compatibleClients = it.compatibleClients.toSet(),
            turnstileRequired = it.auth.turnstileRequired,
            turnstileSiteKey = it.auth.turnstileSiteKey.takeIf(String::isNotBlank),
            endpoints = setOf(
                it.endpoints.login, it.endpoints.refresh, it.endpoints.logout, it.endpoints.me,
                it.endpoints.changePassword, it.endpoints.entries, it.endpoints.groups,
                it.endpoints.codesBatch, it.endpoints.importOtpAuth,
                it.endpoints.exportEncrypted, it.endpoints.importEncrypted,
            ),
            limits = mapOf("batchMaxIds" to it.limits.extensionBatchMaxIds.toLong()),
        )
    }

    override suspend fun hasSavedSession(): Boolean = repository.session() != null
    override suspend fun login(username: String, password: String, turnstileToken: String?): UserUiModel =
        api { repository.login(username, password, turnstileToken).user ?: repository.me() }.toUi()
    override suspend fun currentUser(): UserUiModel = api { repository.session()?.user ?: repository.me() }.toUi()
    override suspend fun logout() = api { repository.logout() }

    override suspend fun entries(): List<OtpEntryUiModel> = api { repository.entries() }.map(OtpEntry::toUi)
    override suspend fun groups(): List<OtpGroupUiModel> = api { repository.groups() }.map(OtpGroup::toUi)
    override suspend fun refreshCodes(entryIds: List<Long>): Map<Long, Pair<String, Long?>> = api {
        repository.codesBatch(entryIds)
    }.let { batch ->
        val deviceNow = System.currentTimeMillis() / 1000
        batch.items.mapNotNull { item ->
            item.code?.let { code ->
                item.id to (code to item.expiresIn?.let { seconds -> correctedExpiryEpochSeconds(batch.serverTimeEpochSeconds, seconds, deviceNow) })
            }
        }.toMap()
    }

    override suspend fun generateHotp(entryId: Long): OtpEntryUiModel {
        val hotp = api { repository.consumeHotp(entryId) }
        val entry = api { repository.entries().first { it.id == entryId } }
        return entry.toUi().copy(code = hotp.code, counter = hotp.nextCounter)
    }

    override suspend fun createEntry(draft: OtpEntryDraft): OtpEntryUiModel {
        val id = api {
            repository.createEntry(
                CreateEntryInput(
                    label = draft.label.trim(), issuer = draft.issuer.trim(),
                    secret = draft.secret.trim().takeIf(String::isNotBlank),
                    otpauthUri = draft.otpauthUri.trim().takeIf(String::isNotBlank),
                    otpType = draft.kind.toDomain(), algorithm = draft.algorithm.toDomain(),
                    digits = draft.digits, period = draft.period, hotpCounter = draft.counter,
                    groupId = draft.groupId, enabled = draft.enabled,
                ),
            )
        }
        return api { repository.entries().first { it.id == id } }.toUi()
    }

    override suspend fun updateEntry(draft: OtpEntryDraft): OtpEntryUiModel {
        val id = requireNotNull(draft.id)
        api {
            repository.patchEntry(
                id,
                EntryPatch(
                    label = draft.label.trim(), issuer = draft.issuer.trim(),
                    secret = draft.secret.trim().takeIf(String::isNotBlank),
                    otpType = draft.kind.toDomain(), algorithm = draft.algorithm.toDomain(),
                    digits = draft.digits, period = draft.period, hotpCounter = draft.counter,
                    groupId = draft.groupId, updateGroup = true, enabled = draft.enabled,
                ),
            )
        }
        return api { repository.entries().first { it.id == id } }.toUi()
    }

    override suspend fun deleteEntry(entryId: Long) = api { repository.deleteEntry(entryId) }
    override suspend fun setEntryEnabled(entryId: Long, enabled: Boolean): OtpEntryUiModel {
        api { repository.setEntryEnabled(entryId, enabled) }
        return api { repository.entries().first { it.id == entryId } }.toUi()
    }
    override suspend fun moveEntry(entryId: Long, groupId: Long?): OtpEntryUiModel {
        api { repository.patchEntry(entryId, EntryPatch(groupId = groupId, updateGroup = true)) }
        return api { repository.entries().first { it.id == entryId } }.toUi()
    }

    override suspend fun createGroup(name: String, color: Long): OtpGroupUiModel {
        val id = api { repository.createGroup(name.trim(), color.toHex()) }
        return api { repository.groups().first { it.id == id } }.toUi()
    }
    override suspend fun updateGroup(id: Long, name: String, color: Long): OtpGroupUiModel {
        api { repository.patchGroup(id, name.trim(), color.toHex()) }
        return api { repository.groups().first { it.id == id } }.toUi()
    }
    override suspend fun deleteGroup(id: Long) = api { repository.deleteGroup(id) }

    override suspend fun importOtpAuth(content: String, groupId: Long?): ImportSummaryUiModel =
        api { repository.importOtpAuth(content, groupId) }.let { ImportSummaryUiModel(it.found, it.imported, it.importedIds, it.failed, it.errors.take(5)) }

    override suspend fun importEncrypted(content: String, passphrase: CharArray): EncryptedImportSummaryUiModel = try {
        val root = ApiFactory.json.parseToJsonElement(content).jsonObject
        val encryptedElement = root["encrypted"] ?: root
        val dto = ApiFactory.json.decodeFromString<EncryptedBackupDto>(encryptedElement.toString())
        api { repository.importEncrypted(dto.toDomain(), passphrase.concatToString()) }.let { EncryptedImportSummaryUiModel(it.groups, it.entries) }
    } finally { passphrase.fill('\u0000') }

    override suspend fun exportEncrypted(passphrase: CharArray): String = try {
        val backup = api { repository.exportEncrypted(passphrase.concatToString()) }
        ApiFactory.json.encodeToString(backup.toDto())
    } finally { passphrase.fill('\u0000') }

    override suspend fun preferences(): AppPreferencesUiModel = AppPreferencesUiModel(
        theme = when (preferences.getString(KEY_THEME, LegacyThemePreferenceMigration.SYSTEM)) {
            LegacyThemePreferenceMigration.DARK -> ThemePreference.Dark
            LegacyThemePreferenceMigration.LIGHT -> ThemePreference.Light
            else -> ThemePreference.System
        },
        language = when (preferences.getString(KEY_LANGUAGE, "system")) {
            "en" -> LanguagePreference.English
            "zh" -> LanguagePreference.Chinese
            else -> LanguagePreference.System
        },
        appLockEnabled = preferences.getBoolean(KEY_APP_LOCK, true),
    )

    override suspend fun setTheme(theme: ThemePreference) {
        preferences.edit().putString(KEY_THEME, theme.name.lowercase(Locale.ROOT)).apply()
    }
    override suspend fun setLanguage(language: LanguagePreference) {
        val value = when (language) { LanguagePreference.English -> "en"; LanguagePreference.Chinese -> "zh"; LanguagePreference.System -> "system" }
        preferences.edit().putString(KEY_LANGUAGE, value).apply()
        LocaleHelper.setLanguage(context, value)
    }
    override suspend fun setAppLock(enabled: Boolean) { preferences.edit().putBoolean(KEY_APP_LOCK, enabled).apply() }
    override suspend fun changePassword(currentPassword: String, newPassword: String) = api { repository.changePassword(currentPassword, newPassword) }

    @Suppress("DEPRECATION")
    private fun migratePreferences() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val legacyLocalePrefs = context.getSharedPreferences("locale_prefs", Context.MODE_PRIVATE)
        val legacyDetected = LegacyInstallDetector.isLegacyInstall(
            packageInfo.firstInstallTime, packageInfo.lastUpdateTime,
            repository.session() != null, legacyLocalePrefs.contains("app_language"),
        )
        val result = LegacyThemePreferenceMigration.resolve(
            preferences.getString(KEY_THEME, null), preferences.getBoolean(KEY_THEME_MIGRATED, false), legacyDetected,
        )
        val editor = preferences.edit()
        if (result.shouldPersistTheme) editor.putString(KEY_THEME, result.theme)
        if (result.shouldMarkMigrationComplete) editor.putBoolean(KEY_THEME_MIGRATED, true)
        val language = LegacyLanguagePreferenceMigration.resolve(
            preferences.getString(KEY_LANGUAGE, null), legacyLocalePrefs.getString("app_language", null),
        )
        editor.putString(KEY_LANGUAGE, language)
        editor.apply()
    }

    private suspend fun <T> api(block: suspend () -> T): T = try { block() } catch (error: ApiException) {
        throw UiGatewayException(
            status = error.error.status, message = error.error.message,
            retryAfterSeconds = error.error.retryAfterSeconds,
            fieldErrors = error.error.fieldErrors.mapValues { it.value.joinToString("\n") },
            serverMessage = error.error.serverMessage,
            clientCode = error.error.clientCode,
        )
    }

    companion object {
        private const val PREFERENCES = "app_preferences"
        private const val KEY_THEME = "theme"
        private const val KEY_THEME_MIGRATED = "theme_migrated_v2"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_APP_LOCK = "app_lock"
    }
}

private fun UserProfile.toUi() = UserUiModel(id.toString(), username, role)
private fun OtpEntry.toUi() = OtpEntryUiModel(
    id = id, label = label, issuer = issuer, kind = if (otpType == OtpType.HOTP) OtpKind.Hotp else OtpKind.Totp,
    algorithm = when (algorithm) { OtpAlgorithm.SHA1 -> UiAlgorithm.Sha1; OtpAlgorithm.SHA256 -> UiAlgorithm.Sha256; OtpAlgorithm.SHA512 -> UiAlgorithm.Sha512 },
    digits = digits, period = period, counter = hotpCounter, groupId = groupId,
    groupName = groupName, groupColor = groupColor?.toColorLong(), enabled = enabled,
)
private fun OtpGroup.toUi() = OtpGroupUiModel(id, name, color.toColorLong())
private fun OtpKind.toDomain() = if (this == OtpKind.Hotp) OtpType.HOTP else OtpType.TOTP
private fun UiAlgorithm.toDomain() = when (this) { UiAlgorithm.Sha1 -> OtpAlgorithm.SHA1; UiAlgorithm.Sha256 -> OtpAlgorithm.SHA256; UiAlgorithm.Sha512 -> OtpAlgorithm.SHA512 }
private fun String.toColorLong(): Long = runCatching { 0xFF000000L or removePrefix("#").toLong(16) }.getOrDefault(0xFF0F766EL)
private fun Long.toHex(): String = "#%06X".format(Locale.US, this and 0xFFFFFF)
private fun EncryptedBackupDto.toDomain() = EncryptedBackup(format, kdf, iterations, salt, iv, ciphertext)
private fun EncryptedBackup.toDto() = EncryptedBackupDto(format, kdf, iterations, salt, iv, ciphertext)

internal fun correctedExpiryEpochSeconds(serverTime: Long, expiresIn: Int, deviceNow: Long): Long {
    val serverOffset = serverTime - deviceNow
    val expiresAtServer = serverTime + expiresIn
    return expiresAtServer - serverOffset
}
