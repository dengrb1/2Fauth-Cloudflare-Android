package com.dengrb1.twfauth.cloudflare.data

import com.dengrb1.twfauth.cloudflare.data.remote.ApiFactory
import com.dengrb1.twfauth.cloudflare.data.remote.ChangePasswordRequest
import com.dengrb1.twfauth.cloudflare.data.remote.CodesRequest
import com.dengrb1.twfauth.cloudflare.data.remote.CreateEntryRequest
import com.dengrb1.twfauth.cloudflare.data.remote.EncryptedBackupDto
import com.dengrb1.twfauth.cloudflare.data.remote.ExportEncryptedRequest
import com.dengrb1.twfauth.cloudflare.data.remote.GroupRequest
import com.dengrb1.twfauth.cloudflare.data.remote.ImportEncryptedRequest
import com.dengrb1.twfauth.cloudflare.data.remote.ImportOtpAuthRequest
import com.dengrb1.twfauth.cloudflare.data.remote.LoginRequest
import com.dengrb1.twfauth.cloudflare.data.remote.RefreshRequest
import com.dengrb1.twfauth.cloudflare.data.remote.TwoFactorApi
import com.dengrb1.twfauth.cloudflare.data.session.TokenStore
import com.dengrb1.twfauth.cloudflare.data.session.SessionStore
import com.dengrb1.twfauth.cloudflare.domain.ApiEndpoints
import com.dengrb1.twfauth.cloudflare.domain.ApiError
import com.dengrb1.twfauth.cloudflare.domain.ApiException
import com.dengrb1.twfauth.cloudflare.domain.ApiLimits
import com.dengrb1.twfauth.cloudflare.domain.ApiSession
import com.dengrb1.twfauth.cloudflare.domain.AuthCapabilities
import com.dengrb1.twfauth.cloudflare.domain.BatchMutationResult
import com.dengrb1.twfauth.cloudflare.domain.Capabilities
import com.dengrb1.twfauth.cloudflare.domain.CodeBatchResult
import com.dengrb1.twfauth.cloudflare.domain.CodeResult
import com.dengrb1.twfauth.cloudflare.domain.CreateEntryInput
import com.dengrb1.twfauth.cloudflare.domain.EncryptedBackup
import com.dengrb1.twfauth.cloudflare.domain.EncryptedImportResult
import com.dengrb1.twfauth.cloudflare.domain.EntryPatch
import com.dengrb1.twfauth.cloudflare.domain.HotpResult
import com.dengrb1.twfauth.cloudflare.domain.ImportResult
import com.dengrb1.twfauth.cloudflare.domain.MutationFailure
import com.dengrb1.twfauth.cloudflare.domain.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.domain.OtpEntry
import com.dengrb1.twfauth.cloudflare.domain.OtpGroup
import com.dengrb1.twfauth.cloudflare.domain.OtpType
import com.dengrb1.twfauth.cloudflare.domain.TwoFactorRepository
import com.dengrb1.twfauth.cloudflare.domain.UserProfile
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import retrofit2.Response

class DefaultTwoFactorRepository(
    private val api: TwoFactorApi,
    private val tokenStore: SessionStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TwoFactorRepository {
    private val refreshMutex = Mutex()
    @Volatile private var batchMaxIds: Int = 100

    override fun session(): ApiSession? = tokenStore.load()

    override suspend fun capabilities(): Capabilities = callPublic { api.capabilities() }.let { dto ->
        batchMaxIds = dto.limits.extensionBatchMaxIds.coerceAtLeast(1)
        Capabilities(
            apiVersion = dto.apiVersion,
            compatibleClients = dto.compatibleClients,
            auth = AuthCapabilities(
                scheme = dto.auth.scheme,
                accessTokenExpiresIn = dto.auth.accessTokenExpiresIn,
                refreshTokenExpiresIn = dto.auth.refreshTokenExpiresIn,
                refreshTokenRotation = dto.auth.refreshTokenRotation,
                turnstileRequired = dto.auth.turnstileRequired,
                turnstileSiteKey = dto.auth.turnstileSiteKey,
            ),
            endpoints = ApiEndpoints(
                login = dto.endpoints.login,
                refresh = dto.endpoints.refresh,
                logout = dto.endpoints.logout,
                me = dto.endpoints.me,
                changePassword = dto.endpoints.changePassword,
                entries = dto.endpoints.entries,
                groups = dto.endpoints.groups,
                codesBatch = dto.endpoints.codesBatch,
                importOtpAuth = dto.endpoints.importOtpAuth,
                exportEncrypted = dto.endpoints.exportEncrypted,
                importEncrypted = dto.endpoints.importEncrypted,
            ),
            limits = ApiLimits(dto.limits.extensionBatchMaxIds),
        )
    }

    override suspend fun requireCompatibleCapabilities(): Capabilities = capabilities().also {
        if (it.apiVersion != "v1") throw ApiException(ApiError(503, "Server does not advertise API v1", clientCode = "api_version"))
        if (it.compatibleClients.none { client -> client.equals("android", true) }) {
            throw ApiException(ApiError(503, "Server does not advertise Android client compatibility", clientCode = "android_client"))
        }
        if (!it.auth.scheme.equals("Bearer", true)) throw ApiException(ApiError(503, "Server does not advertise Bearer authentication", clientCode = "bearer_auth"))
        val routes = mapOf(
            it.endpoints.login to "/api/v1/auth/login", it.endpoints.refresh to "/api/v1/auth/refresh",
            it.endpoints.logout to "/api/v1/auth/logout", it.endpoints.me to "/api/v1/me",
            it.endpoints.changePassword to "/api/v1/me/password", it.endpoints.entries to "/api/v1/entries",
            it.endpoints.groups to "/api/v1/groups", it.endpoints.codesBatch to "/api/v1/codes/batch",
            it.endpoints.importOtpAuth to "/api/v1/import/otpauth",
            it.endpoints.exportEncrypted to "/api/v1/export/encrypted",
            it.endpoints.importEncrypted to "/api/v1/import/encrypted",
        )
        if (routes.any { (actual, expected) -> actual != expected }) {
            throw ApiException(ApiError(503, "Server capabilities contain incompatible API routes", clientCode = "api_routes"))
        }
    }

    override suspend fun login(username: String, password: String, turnstileToken: String?): ApiSession {
        requireCompatibleCapabilities()
        val dto = callPublic { api.login(LoginRequest(username, password, turnstileToken = turnstileToken)) }
        return dto.toSession(nowMillis()).also(tokenStore::save)
    }

    override suspend fun logout() {
        try { session()?.let { authenticated { api.logout(it) } } } finally { tokenStore.clear() }
    }

    override suspend fun me(): UserProfile = authenticated(api::me).user.toDomain().also { user ->
        session()?.let { tokenStore.save(it.copy(user = user)) }
    }

    override suspend fun entries(): List<OtpEntry> = authenticated(api::entries).entries.map { dto ->
        OtpEntry(
            id = dto.id, label = dto.label, issuer = dto.issuer,
            otpType = OtpType.fromWire(dto.otpType), period = dto.period, digits = dto.digits,
            algorithm = OtpAlgorithm.fromWire(dto.algorithm) ?: OtpAlgorithm.SHA1,
            groupId = dto.groupId, groupName = dto.groupName, groupColor = dto.groupColor,
            hotpCounter = dto.hotpCounter, enabled = dto.enabled != 0,
            ownerId = dto.ownerId, ownerUsername = dto.username, createdAt = dto.createdAt,
        )
    }

    override suspend fun groups(): List<OtpGroup> = authenticated(api::groups).groups.map {
        OtpGroup(it.id, it.name, it.color, it.ownerId, it.username, it.createdAt)
    }

    override suspend fun createEntry(input: CreateEntryInput): Long {
        val hasSecret = !input.secret.isNullOrBlank()
        val hasUri = !input.otpauthUri.isNullOrBlank()
        if (hasSecret == hasUri) throw ApiException(ApiError(400, "Provide exactly one of secret or otpauthUri"))
        return authenticated {
            api.createEntry(
                it,
                CreateEntryRequest(
                    label = input.label.takeIf { value -> value.isNotBlank() },
                    issuer = input.issuer.takeIf { value -> value.isNotBlank() },
                    secret = input.secret?.takeIf(String::isNotBlank),
                    otpauthUri = input.otpauthUri?.takeIf(String::isNotBlank),
                    otpType = input.otpType.wireValue,
                    algorithm = input.algorithm?.wireValue,
                    digits = input.digits,
                    period = input.period,
                    hotpCounter = input.hotpCounter,
                    groupId = input.groupId,
                    enabled = input.enabled,
                ),
            )
        }.id
    }

    override suspend fun patchEntry(entryId: Long, patch: EntryPatch) {
        val body = buildJsonObject {
            patch.label?.let { put("label", JsonPrimitive(it)) }
            patch.issuer?.let { put("issuer", JsonPrimitive(it)) }
            patch.secret?.let { put("secret", JsonPrimitive(it)) }
            patch.otpType?.let { put("otpType", JsonPrimitive(it.wireValue)) }
            patch.algorithm?.let { put("algorithm", JsonPrimitive(it.wireValue)) }
            patch.digits?.let { put("digits", JsonPrimitive(it)) }
            patch.period?.let { put("period", JsonPrimitive(it)) }
            patch.hotpCounter?.let { put("hotpCounter", JsonPrimitive(it)) }
            if (patch.updateGroup) put("groupId", patch.groupId?.let(::JsonPrimitive) ?: JsonNull)
            patch.enabled?.let { put("enabled", JsonPrimitive(it)) }
        }
        authenticated { api.patchEntry(it, entryId, body) }
    }

    override suspend fun deleteEntry(entryId: Long) { authenticated { api.deleteEntry(it, entryId) } }
    override suspend fun setEntryEnabled(entryId: Long, enabled: Boolean) = patchEntry(entryId, EntryPatch(enabled = enabled))
    override suspend fun createGroup(name: String, color: String): Long = authenticated { api.createGroup(it, GroupRequest(name, color)) }.id
    override suspend fun patchGroup(groupId: Long, name: String?, color: String?) { authenticated { api.patchGroup(it, groupId, GroupRequest(name, color)) } }
    override suspend fun deleteGroup(groupId: Long) { authenticated { api.deleteGroup(it, groupId) } }

    override suspend fun codesBatch(entryIds: List<Long>): CodeBatchResult {
        val chunks = entryIds.distinct().chunked(batchMaxIds)
        var serverTime = nowMillis() / 1_000
        val items = mutableListOf<CodeResult>()
        for (chunk in chunks) {
            val result = authenticated { api.codesBatch(it, CodesRequest(chunk)) }
            serverTime = result.serverTime
            items += result.items.map { CodeResult(it.id, OtpType.fromWire(it.otpType), it.code, it.expiresIn, it.period, it.enabled, it.counter, it.error) }
        }
        return CodeBatchResult(serverTime, items)
    }

    override suspend fun consumeHotp(entryId: Long): HotpResult = authenticated { api.consumeHotp(it, entryId) }.let { HotpResult(it.code, it.counter, it.nextCounter) }
    override suspend fun importOtpAuth(text: String, groupId: Long?): ImportResult = authenticated { api.importOtpAuth(it, ImportOtpAuthRequest(text, groupId)) }.let { ImportResult(it.found, it.imported, it.importedIds, it.failed, it.errors.take(5)) }
    override suspend fun exportEncrypted(passphrase: String): EncryptedBackup {
        validatePassphrase(passphrase)
        return authenticated { api.exportEncrypted(it, ExportEncryptedRequest(passphrase)) }.encrypted.toDomain()
    }
    override suspend fun importEncrypted(encrypted: EncryptedBackup, passphrase: String): EncryptedImportResult {
        validatePassphrase(passphrase)
        return authenticated { api.importEncrypted(it, ImportEncryptedRequest(encrypted.toDto(), passphrase)) }.imported.let { EncryptedImportResult(it.groups, it.entries) }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) {
        authenticated { api.changePassword(it, ChangePasswordRequest(currentPassword, newPassword)) }
        tokenStore.clear()
    }

    override suspend fun deleteEntries(entryIds: List<Long>): BatchMutationResult = mutate(entryIds) { deleteEntry(it) }
    override suspend fun moveEntries(entryIds: List<Long>, groupId: Long?): BatchMutationResult = mutate(entryIds) { patchEntry(it, EntryPatch(groupId = groupId, updateGroup = true)) }

    private suspend fun mutate(ids: List<Long>, block: suspend (Long) -> Unit): BatchMutationResult {
        val success = mutableListOf<Long>()
        val failures = mutableListOf<MutationFailure>()
        ids.distinct().forEach { id ->
            try { block(id); success += id } catch (e: ApiException) { failures += MutationFailure(id, e.error) }
        }
        return BatchMutationResult(success, failures)
    }

    private fun validatePassphrase(passphrase: String) {
        if (passphrase.length !in 12..256) throw ApiException(ApiError(400, "Backup passphrase must be 12 to 256 characters"))
    }

    private suspend fun <T> authenticated(call: suspend (String) -> Response<T>): T {
        val firstSession = session() ?: throw ApiException(ApiError(401, "Authentication required"))
        val first = safeCall { call(firstSession.bearer()) }
        if (first.code() != 401) return first.requireBody()
        first.errorBody()?.close()
        val refreshed = refreshMutex.withLock {
            val current = session() ?: throw ApiException(ApiError(401, "Session expired"))
            if (current.accessToken != firstSession.accessToken) current else refreshSession(current)
        }
        return safeCall { call(refreshed.bearer()) }.requireBody()
    }

    private suspend fun refreshSession(old: ApiSession): ApiSession {
        val dto = fetchRefreshedSessionDto(old)
        return try {
            dto.toSession(nowMillis(), old.user).also(tokenStore::save)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Local persistence failures must not wipe a still-usable session.
            throw ApiException(ApiError(503, error.message ?: "Session refresh failed", clientCode = "network"), error)
        }
    }

    private suspend fun fetchRefreshedSessionDto(
        old: ApiSession,
    ): com.dengrb1.twfauth.cloudflare.data.remote.SessionDto {
        val response = try {
            safeCall { api.refresh(RefreshRequest(old.refreshToken)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiException) {
            // Includes transport/network failures from safeCall; keep the local session.
            throw error
        } catch (error: Exception) {
            // Unexpected local/transport failures keep the session so the UI can retry.
            throw ApiException(ApiError(503, error.message ?: "Session refresh failed", clientCode = "network"), error)
        }
        if (!response.isSuccessful) {
            val error = response.toException()
            if (shouldInvalidateSession(error)) {
                runCatching { tokenStore.clear() }.onFailure(error::addSuppressed)
            }
            throw error
        }
        val body = response.body()
        if (body == null) {
            val error = ApiException(ApiError(401, "Invalid empty refresh response"))
            runCatching { tokenStore.clear() }.onFailure(error::addSuppressed)
            throw error
        }
        return body
    }

    private fun shouldInvalidateSession(error: ApiException): Boolean =
        error.error.status == 401 || error.error.status == 403

    private suspend fun <T> callPublic(call: suspend () -> Response<T>): T = safeCall(call).requireBody()
    private suspend fun <T> safeCall(call: suspend () -> Response<T>): Response<T> = try { call() } catch (e: IOException) {
        throw ApiException(ApiError(503, e.message ?: "Network unavailable", clientCode = "network"), e)
    }

    private fun <T> Response<T>.requireBody(): T {
        if (!isSuccessful) throw toException()
        @Suppress("UNCHECKED_CAST")
        return body() ?: (Unit as T)
    }

    private fun Response<*>.toException(): ApiException {
        val text = runCatching { errorBody()?.string().orEmpty() }.getOrDefault("")
        val root = runCatching { ApiFactory.json.parseToJsonElement(text).jsonObject }.getOrNull()
        val retryHeader = headers()["Retry-After"]?.trim()?.toLongOrNull()
        val retryBody = root?.get("retryAfterSeconds")?.jsonPrimitive?.longOrNull
        val serverMessage = root?.get("error")?.jsonPrimitive?.contentOrNull
            ?: root?.get("message")?.jsonPrimitive?.contentOrNull
        val message = serverMessage
            ?: when (code()) {
                400 -> "Invalid request"; 401 -> "Authentication required"; 403 -> "Operation forbidden"
                404 -> "Resource not found"; 409 -> "Conflicting update"; 413 -> "Payload too large"
                429 -> "Too many requests"; 503 -> "Service unavailable"; else -> "Request failed (${code()})"
            }
        val fields = root?.get("fieldErrors")?.let(::fieldErrors).orEmpty()
        return ApiException(ApiError(code(), message, retryHeader ?: retryBody, fields, serverMessage = serverMessage != null))
    }

    private fun fieldErrors(element: JsonElement): Map<String, List<String>> = (element as? JsonObject).orEmpty().mapValues { (_, value) ->
        when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> listOfNotNull(value.jsonPrimitive.contentOrNull)
        }
    }
}

private fun ApiSession.bearer() = "Bearer $accessToken"
private fun com.dengrb1.twfauth.cloudflare.data.remote.SessionDto.toSession(now: Long, fallbackUser: UserProfile? = null) = ApiSession(
    accessToken, refreshToken, now + expiresIn * 1000,
    refreshExpiresIn?.let { now + it * 1000 }, user?.toDomain() ?: fallbackUser, sessionId,
)
private fun com.dengrb1.twfauth.cloudflare.data.remote.UserDto.toDomain() = UserProfile(id, username, role)
private fun EncryptedBackupDto.toDomain() = EncryptedBackup(format, kdf, iterations, salt, iv, ciphertext)
private fun EncryptedBackup.toDto() = EncryptedBackupDto(format, kdf, iterations, salt, iv, ciphertext)
