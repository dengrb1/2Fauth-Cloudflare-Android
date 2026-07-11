package com.dengrb1.twfauth.cloudflare.domain

interface TwoFactorRepository {
    fun session(): ApiSession?

    suspend fun capabilities(): Capabilities

    suspend fun requireCompatibleCapabilities(): Capabilities

    suspend fun login(
        username: String,
        password: String,
        turnstileToken: String? = null,
    ): ApiSession

    suspend fun logout()

    suspend fun me(): UserProfile

    suspend fun entries(): List<OtpEntry>

    suspend fun groups(): List<OtpGroup>

    suspend fun createEntry(input: CreateEntryInput): Long

    suspend fun patchEntry(entryId: Long, patch: EntryPatch)

    suspend fun deleteEntry(entryId: Long)

    suspend fun setEntryEnabled(entryId: Long, enabled: Boolean)

    suspend fun createGroup(name: String, color: String): Long

    suspend fun patchGroup(groupId: Long, name: String? = null, color: String? = null)

    suspend fun deleteGroup(groupId: Long)

    suspend fun codesBatch(entryIds: List<Long>): CodeBatchResult

    suspend fun consumeHotp(entryId: Long): HotpResult

    suspend fun importOtpAuth(text: String, groupId: Long? = null): ImportResult

    suspend fun exportEncrypted(passphrase: String): EncryptedBackup

    suspend fun importEncrypted(encrypted: EncryptedBackup, passphrase: String): EncryptedImportResult

    suspend fun changePassword(currentPassword: String, newPassword: String)

    suspend fun deleteEntries(entryIds: List<Long>): BatchMutationResult

    suspend fun moveEntries(entryIds: List<Long>, groupId: Long?): BatchMutationResult
}
