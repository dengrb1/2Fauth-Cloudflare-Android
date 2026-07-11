package com.dengrb1.twfauth.cloudflare.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CapabilitiesDto(
    val apiVersion: String,
    val compatibleClients: List<String> = emptyList(),
    val auth: AuthCapabilitiesDto,
    val endpoints: ApiEndpointsDto,
    val limits: ApiLimitsDto = ApiLimitsDto(),
)

@Serializable
data class AuthCapabilitiesDto(
    val scheme: String = "",
    val accessTokenExpiresIn: Long = 0,
    val refreshTokenExpiresIn: Long = 0,
    val refreshTokenRotation: Boolean = false,
    val turnstileRequired: Boolean = false,
    val turnstileSiteKey: String = "",
)

@Serializable
data class ApiEndpointsDto(
    val login: String = "",
    val refresh: String = "",
    val logout: String = "",
    val me: String = "",
    val changePassword: String = "",
    val entries: String = "",
    val groups: String = "",
    val codesBatch: String = "",
    val importOtpAuth: String = "",
    val exportEncrypted: String = "",
    val importEncrypted: String = "",
)

@Serializable
data class ApiLimitsDto(val extensionBatchMaxIds: Int = 100)

@Serializable
data class UserDto(val id: Long, val username: String, val role: String)

@Serializable
data class UserEnvelopeDto(val user: UserDto)

@Serializable
data class SessionDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val refreshExpiresIn: Long? = null,
    val user: UserDto? = null,
    val sessionId: Long? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val clientType: String = "android",
    val turnstileToken: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String, val clientType: String = "android")

@Serializable
data class EntriesEnvelopeDto(val entries: List<EntryDto> = emptyList())

@Serializable
data class EntryDto(
    val id: Long,
    val label: String = "",
    val issuer: String = "",
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: String? = null,
    @SerialName("otp_type") val otpType: String? = null,
    @SerialName("hotp_counter") val hotpCounter: Long = 0,
    val enabled: Int = 1,
    @SerialName("group_id") val groupId: Long? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("group_color") val groupColor: String? = null,
    @SerialName("user_id") val ownerId: Long? = null,
    val username: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class GroupsEnvelopeDto(val groups: List<GroupDto> = emptyList())

@Serializable
data class GroupDto(
    val id: Long,
    val name: String,
    val color: String = "#0f766e",
    @SerialName("user_id") val ownerId: Long? = null,
    val username: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class CreateEntryRequest(
    val label: String? = null,
    val issuer: String? = null,
    val secret: String? = null,
    val otpauthUri: String? = null,
    val otpType: String? = null,
    val algorithm: String? = null,
    val digits: Int? = null,
    val period: Int? = null,
    val hotpCounter: Long? = null,
    val groupId: Long? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class EntryPatchRequest(
    val label: String? = null,
    val issuer: String? = null,
    val secret: String? = null,
    val otpType: String? = null,
    val algorithm: String? = null,
    val digits: Int? = null,
    val period: Int? = null,
    val hotpCounter: Long? = null,
    val groupId: Long? = null,
    val enabled: Boolean? = null,
)

@Serializable data class IdResponseDto(val id: Long)
@Serializable data class GroupRequest(val name: String? = null, val color: String? = null)
@Serializable data class CodesRequest(val entryIds: List<Long>)

@Serializable
data class CodeBatchDto(val serverTime: Long, val items: List<CodeItemDto> = emptyList())

@Serializable
data class CodeItemDto(
    val id: Long,
    val otpType: String? = null,
    val code: String? = null,
    val expiresIn: Int? = null,
    val period: Int? = null,
    val enabled: Boolean? = null,
    val counter: Long? = null,
    val error: String? = null,
)

@Serializable data class HotpDto(val code: String, val counter: Long, val nextCounter: Long)
@Serializable data class ImportOtpAuthRequest(val text: String, val groupId: Long? = null)

@Serializable
data class ImportResultDto(
    val found: Int = 0,
    val imported: Int = 0,
    val importedIds: List<Long> = emptyList(),
    val failed: Int = 0,
    val errors: List<String> = emptyList(),
)

@Serializable
data class EncryptedBackupDto(
    val format: String,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable data class ExportEncryptedRequest(val passphrase: String)
@Serializable data class ExportEncryptedResponse(val encrypted: EncryptedBackupDto)
@Serializable data class ImportEncryptedRequest(val encrypted: EncryptedBackupDto, val passphrase: String)
@Serializable data class ImportedCountsDto(val groups: Int = 0, val entries: Int = 0)
@Serializable data class ImportEncryptedResponse(val imported: ImportedCountsDto = ImportedCountsDto())
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

