package com.dengrb1.twfauth.cloudflare.domain

data class Capabilities(
    val apiVersion: String,
    val compatibleClients: List<String>,
    val auth: AuthCapabilities,
    val endpoints: ApiEndpoints,
    val limits: ApiLimits,
)

data class AuthCapabilities(
    val scheme: String,
    val accessTokenExpiresIn: Long,
    val refreshTokenExpiresIn: Long,
    val refreshTokenRotation: Boolean,
    val turnstileRequired: Boolean,
    val turnstileSiteKey: String,
)

data class ApiEndpoints(
    val login: String,
    val refresh: String,
    val logout: String,
    val me: String,
    val changePassword: String,
    val entries: String,
    val groups: String,
    val codesBatch: String,
    val importOtpAuth: String,
    val exportEncrypted: String,
    val importEncrypted: String,
)

data class ApiLimits(
    val extensionBatchMaxIds: Int,
)

data class UserProfile(
    val id: Long,
    val username: String,
    val role: String,
)

data class ApiSession(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long? = null,
    val user: UserProfile? = null,
    val sessionId: Long? = null,
)

enum class OtpAlgorithm(val wireValue: String) {
    SHA1("SHA-1"),
    SHA256("SHA-256"),
    SHA512("SHA-512");

    companion object {
        fun fromWire(value: String?): OtpAlgorithm? = when (
            value.orEmpty().trim().uppercase().replace("_", "-")
        ) {
            "", "SHA-1", "SHA1" -> SHA1
            "SHA-256", "SHA256" -> SHA256
            "SHA-512", "SHA512" -> SHA512
            else -> null
        }
    }
}

enum class OtpType(val wireValue: String) {
    TOTP("totp"),
    HOTP("hotp");

    companion object {
        fun fromWire(value: String?): OtpType = if (value.equals("hotp", ignoreCase = true)) HOTP else TOTP
    }
}

data class OtpEntry(
    val id: Long,
    val label: String,
    val issuer: String,
    val otpType: OtpType,
    val period: Int,
    val digits: Int,
    val algorithm: OtpAlgorithm,
    val groupId: Long?,
    val groupName: String?,
    val groupColor: String?,
    val hotpCounter: Long,
    val enabled: Boolean,
    val ownerId: Long? = null,
    val ownerUsername: String? = null,
    val createdAt: String? = null,
)

data class OtpGroup(
    val id: Long,
    val name: String,
    val color: String,
    val ownerId: Long? = null,
    val ownerUsername: String? = null,
    val createdAt: String? = null,
)

data class CreateEntryInput(
    val label: String = "",
    val issuer: String = "",
    val secret: String? = null,
    val otpauthUri: String? = null,
    val otpType: OtpType = OtpType.TOTP,
    val algorithm: OtpAlgorithm? = null,
    val digits: Int = 6,
    val period: Int = 30,
    val hotpCounter: Long = 0,
    val groupId: Long? = null,
    val enabled: Boolean = true,
)

data class EntryPatch(
    val label: String? = null,
    val issuer: String? = null,
    val secret: String? = null,
    val otpType: OtpType? = null,
    val algorithm: OtpAlgorithm? = null,
    val digits: Int? = null,
    val period: Int? = null,
    val hotpCounter: Long? = null,
    val groupId: Long? = null,
    val updateGroup: Boolean = false,
    val enabled: Boolean? = null,
)

data class ImportResult(
    val found: Int,
    val imported: Int,
    val importedIds: List<Long>,
    val failed: Int,
    val errors: List<String>,
)

data class EncryptedImportResult(
    val groups: Int,
    val entries: Int,
)

data class EncryptedBackup(
    val format: String,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

data class CodeBatchResult(
    val serverTimeEpochSeconds: Long,
    val items: List<CodeResult>,
)

data class CodeResult(
    val id: Long,
    val otpType: OtpType,
    val code: String? = null,
    val expiresIn: Int? = null,
    val period: Int? = null,
    val enabled: Boolean? = null,
    val counter: Long? = null,
    val error: String? = null,
)

data class HotpResult(
    val code: String,
    val counter: Long,
    val nextCounter: Long,
)

data class ApiError(
    val status: Int,
    val message: String,
    val retryAfterSeconds: Long? = null,
    val fieldErrors: Map<String, List<String>> = emptyMap(),
    val serverMessage: Boolean = false,
    val clientCode: String? = null,
)

class ApiException(
    val error: ApiError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)

data class BatchMutationResult(
    val succeededIds: List<Long>,
    val failures: List<MutationFailure>,
) {
    val isCompleteSuccess: Boolean get() = failures.isEmpty()
}

data class MutationFailure(
    val id: Long,
    val error: ApiError,
)
