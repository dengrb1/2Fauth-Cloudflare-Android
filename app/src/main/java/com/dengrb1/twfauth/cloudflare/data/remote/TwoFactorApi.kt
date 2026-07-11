package com.dengrb1.twfauth.cloudflare.data.remote

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface TwoFactorApi {
    @GET("api/v1/capabilities") suspend fun capabilities(): Response<CapabilitiesDto>
    @POST("api/v1/auth/login") suspend fun login(@Body body: LoginRequest): Response<SessionDto>
    @POST("api/v1/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): Response<SessionDto>
    @POST("api/v1/auth/logout") suspend fun logout(@Header("Authorization") authorization: String): Response<Unit>
    @GET("api/v1/me") suspend fun me(@Header("Authorization") authorization: String): Response<UserEnvelopeDto>
    @PATCH("api/v1/me/password") suspend fun changePassword(@Header("Authorization") authorization: String, @Body body: ChangePasswordRequest): Response<Unit>
    @GET("api/v1/entries") suspend fun entries(@Header("Authorization") authorization: String): Response<EntriesEnvelopeDto>
    @POST("api/v1/entries") suspend fun createEntry(@Header("Authorization") authorization: String, @Body body: CreateEntryRequest): Response<IdResponseDto>
    @PATCH("api/v1/entries/{id}") suspend fun patchEntry(@Header("Authorization") authorization: String, @Path("id") id: Long, @Body body: JsonObject): Response<Unit>
    @DELETE("api/v1/entries/{id}") suspend fun deleteEntry(@Header("Authorization") authorization: String, @Path("id") id: Long): Response<Unit>
    @POST("api/v1/entries/{id}/hotp") suspend fun consumeHotp(@Header("Authorization") authorization: String, @Path("id") id: Long): Response<HotpDto>
    @GET("api/v1/groups") suspend fun groups(@Header("Authorization") authorization: String): Response<GroupsEnvelopeDto>
    @POST("api/v1/groups") suspend fun createGroup(@Header("Authorization") authorization: String, @Body body: GroupRequest): Response<IdResponseDto>
    @PATCH("api/v1/groups/{id}") suspend fun patchGroup(@Header("Authorization") authorization: String, @Path("id") id: Long, @Body body: GroupRequest): Response<Unit>
    @DELETE("api/v1/groups/{id}") suspend fun deleteGroup(@Header("Authorization") authorization: String, @Path("id") id: Long): Response<Unit>
    @POST("api/v1/codes/batch") suspend fun codesBatch(@Header("Authorization") authorization: String, @Body body: CodesRequest): Response<CodeBatchDto>
    @POST("api/v1/import/otpauth") suspend fun importOtpAuth(@Header("Authorization") authorization: String, @Body body: ImportOtpAuthRequest): Response<ImportResultDto>
    @POST("api/v1/export/encrypted") suspend fun exportEncrypted(@Header("Authorization") authorization: String, @Body body: ExportEncryptedRequest): Response<ExportEncryptedResponse>
    @POST("api/v1/import/encrypted") suspend fun importEncrypted(@Header("Authorization") authorization: String, @Body body: ImportEncryptedRequest): Response<ImportEncryptedResponse>
}
