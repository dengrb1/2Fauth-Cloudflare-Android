package com.dengrb1.twfauth.cloudflare.data

import com.dengrb1.twfauth.cloudflare.data.remote.ApiFactory
import com.dengrb1.twfauth.cloudflare.data.session.SessionStore
import com.dengrb1.twfauth.cloudflare.domain.ApiException
import com.dengrb1.twfauth.cloudflare.domain.ApiSession
import com.dengrb1.twfauth.cloudflare.domain.CreateEntryInput
import com.dengrb1.twfauth.cloudflare.domain.EntryPatch
import com.dengrb1.twfauth.cloudflare.domain.EncryptedBackup
import com.dengrb1.twfauth.cloudflare.domain.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.domain.UserProfile
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DefaultTwoFactorRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var store: FakeStore
    private lateinit var repository: DefaultTwoFactorRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = FakeStore()
        repository = DefaultTwoFactorRepository(ApiFactory.create(server.url("/").toString()), store) { 1_000_000L }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun capabilitiesAndTurnstileLoginUseV1Contract() = runTest {
        server.enqueue(json(CAPABILITIES))
        server.enqueue(json(SESSION))
        val session = repository.login("alice", "Password-123!", "challenge-token")
        assertEquals("access-one", session.accessToken)
        assertEquals("/api/v1/capabilities", server.takeRequest().path)
        val login = server.takeRequest()
        assertEquals("/api/v1/auth/login", login.path)
        val body = login.body.readUtf8()
        assertTrue(body.contains("\"clientType\":\"android\""))
        assertTrue(body.contains("\"turnstileToken\":\"challenge-token\""))
        assertNull(login.getHeader("Cookie"))
    }

    @Test fun authenticatedRequestRefreshesAndReplaysOnlyOnceWithRotatedToken() = runTest {
        store.value = savedSession("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
        server.enqueue(json(SESSION.replace("access-one", "new-access").replace("refresh-one", "new-refresh")))
        server.enqueue(json("""{"entries":[]}"""))
        assertTrue(repository.entries().isEmpty())
        val first = server.takeRequest(); val refresh = server.takeRequest(); val replay = server.takeRequest()
        assertEquals("/api/v1/entries", first.path)
        assertEquals("Bearer old-access", first.getHeader("Authorization"))
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("old-refresh"))
        assertEquals("/api/v1/entries", replay.path)
        assertEquals("Bearer new-access", replay.getHeader("Authorization"))
        assertNull(replay.getHeader("Cookie"))
        assertEquals("new-refresh", store.value?.refreshToken)
        assertEquals(3, server.requestCount)
    }

    @Test fun failedRefreshAtomicallyClearsSession() = runTest {
        store.value = savedSession("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"refresh rejected\"}"))
        runCatching { repository.entries() }
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test fun retryAfterHeaderWinsOverBody() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).addHeader("Retry-After", "17")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"slow down\",\"retryAfterSeconds\":4}"),
        )
        val error = runCatching { repository.capabilities() }.exceptionOrNull() as ApiException
        assertEquals(429, error.error.status)
        assertEquals(17L, error.error.retryAfterSeconds)
    }

    @Test fun sha1AliasesDefaultAndExistingPatchArePreserved() = runTest {
        assertEquals(OtpAlgorithm.SHA1, OtpAlgorithm.fromWire(null))
        assertEquals(OtpAlgorithm.SHA1, OtpAlgorithm.fromWire("SHA1"))
        store.value = savedSession()
        server.enqueue(json("""{"id":7}""", 201))
        repository.createEntry(CreateEntryInput(label = "mail", secret = "JBSWY3DPEHPK3PXP", algorithm = null))
        val createBody = server.takeRequest().body.readUtf8()
        assertFalse(createBody.contains("algorithm"))
        server.enqueue(json("""{"ok":true}"""))
        repository.patchEntry(7, EntryPatch(algorithm = OtpAlgorithm.SHA1))
        val patchBody = server.takeRequest().body.readUtf8()
        assertTrue(patchBody.contains("\"algorithm\":\"SHA-1\""))
    }

    @Test fun clientBlocksSecretAndOtpAuthUriTogether() = runTest {
        store.value = savedSession()
        val error = runCatching {
            repository.createEntry(CreateEntryInput(label = "bad", secret = "ABC", otpauthUri = "otpauth://totp/x?secret=ABC"))
        }.exceptionOrNull() as ApiException
        assertEquals(400, error.error.status)
        assertEquals(0, server.requestCount)
    }

    @Test fun everyUsedEndpointStaysOnTheV1BearerSurface() = runTest {
        store.value = savedSession()
        val responses = listOf(
            """{"user":{"id":1,"username":"alice","role":"user"}}""",
            """{"entries":[]}""", """{"groups":[]}""", """{"id":3}""",
            """{"ok":true}""", """{"ok":true}""", """{"id":4}""",
            """{"ok":true}""", """{"ok":true}""",
            """{"serverTime":100,"items":[{"id":3,"otpType":"totp","code":"123456","expiresIn":20}]}""",
            """{"code":"654321","counter":0,"nextCounter":1}""",
            """{"found":1,"imported":1,"importedIds":[8],"failed":0,"errors":[]}""",
            """{"encrypted":{"format":"worker-2fauth-encrypted-v1","kdf":"PBKDF2-SHA-256","iterations":180000,"salt":"s","iv":"i","ciphertext":"c"}}""",
            """{"imported":{"groups":1,"entries":2}}""", """{"ok":true}""",
        )
        responses.forEachIndexed { index, body -> server.enqueue(json(body, if (index == 3 || index == 6) 201 else 200)) }
        repository.me(); repository.entries(); repository.groups()
        repository.createEntry(CreateEntryInput(label = "mail", secret = "JBSWY3DPEHPK3PXP"))
        repository.patchEntry(3, EntryPatch(label = "renamed")); repository.deleteEntry(3)
        repository.createGroup("Work", "#0f766e"); repository.patchGroup(4, "Office", "#112233"); repository.deleteGroup(4)
        repository.codesBatch(listOf(3)); repository.consumeHotp(3); repository.importOtpAuth("otpauth://totp/x?secret=ABC")
        val backup = repository.exportEncrypted("Long passphrase")
        repository.importEncrypted(backup, "Long passphrase"); repository.changePassword("Old-password-1!", "New-password-2!")
        val requests = List(responses.size) { server.takeRequest() }
        assertEquals(
            listOf(
                "/api/v1/me", "/api/v1/entries", "/api/v1/groups", "/api/v1/entries",
                "/api/v1/entries/3", "/api/v1/entries/3", "/api/v1/groups", "/api/v1/groups/4",
                "/api/v1/groups/4", "/api/v1/codes/batch", "/api/v1/entries/3/hotp",
                "/api/v1/import/otpauth", "/api/v1/export/encrypted", "/api/v1/import/encrypted", "/api/v1/me/password",
            ),
            requests.map { it.path },
        )
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer access" })
        assertTrue(requests.all { it.getHeader("Cookie") == null })
    }

    @Test fun mutationImportExportAndPasswordBodiesMatchTheV1Contract() = runTest {
        store.value = savedSession()
        listOf(
            """{"ok":true}""",
            """{"serverTime":100,"items":[]}""",
            """{"found":2,"imported":1,"importedIds":[8],"failed":1,"errors":["bad"]}""",
            """{"encrypted":{"format":"worker-v1","kdf":"PBKDF2-SHA-256","iterations":180000,"salt":"s","iv":"i","ciphertext":"c"}}""",
            """{"imported":{"groups":1,"entries":2}}""",
            """{"ok":true}""",
        ).forEach { server.enqueue(json(it)) }
        val backup = EncryptedBackup("worker-v1", "PBKDF2-SHA-256", 180000, "s", "i", "c")

        repository.patchGroup(4, "Office", "#112233")
        repository.codesBatch(listOf(3, 5))
        repository.importOtpAuth("otpauth://totp/x?secret=ABC", groupId = 4)
        repository.exportEncrypted("Long passphrase")
        repository.importEncrypted(backup, "Long passphrase")
        repository.changePassword("Old-password-1!", "New-password-2!")

        val requests = List(6) { server.takeRequest() }
        val group = ApiFactory.json.parseToJsonElement(requests[0].body.readUtf8()).jsonObject
        assertEquals("Office", group.getValue("name").jsonPrimitive.content)
        assertEquals("#112233", group.getValue("color").jsonPrimitive.content)
        val codes = ApiFactory.json.parseToJsonElement(requests[1].body.readUtf8()).jsonObject
        assertEquals(listOf("3", "5"), codes.getValue("entryIds").jsonArray.map { it.jsonPrimitive.content })
        val otpAuth = ApiFactory.json.parseToJsonElement(requests[2].body.readUtf8()).jsonObject
        assertEquals("otpauth://totp/x?secret=ABC", otpAuth.getValue("text").jsonPrimitive.content)
        assertEquals("4", otpAuth.getValue("groupId").jsonPrimitive.content)
        val export = ApiFactory.json.parseToJsonElement(requests[3].body.readUtf8()).jsonObject
        assertEquals("Long passphrase", export.getValue("passphrase").jsonPrimitive.content)
        val encryptedImport = ApiFactory.json.parseToJsonElement(requests[4].body.readUtf8()).jsonObject
        assertEquals("Long passphrase", encryptedImport.getValue("passphrase").jsonPrimitive.content)
        assertEquals("c", encryptedImport.getValue("encrypted").jsonObject.getValue("ciphertext").jsonPrimitive.content)
        val password = ApiFactory.json.parseToJsonElement(requests[5].body.readUtf8()).jsonObject
        assertEquals("Old-password-1!", password.getValue("currentPassword").jsonPrimitive.content)
        assertEquals("New-password-2!", password.getValue("newPassword").jsonPrimitive.content)
    }

    @Test fun structuredErrorsCoverConflictPayloadAndServiceFailures() = runTest {
        for (status in listOf(400, 401, 403, 404, 409, 413, 503)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody("{\"error\":\"status-$status\"}"))
            val error = runCatching { repository.capabilities() }.exceptionOrNull() as ApiException
            assertEquals(status, error.error.status)
            assertEquals("status-$status", error.error.message)
        }
    }

    @Test fun retryAfterBodyIsUsedWhenHeaderIsMissing() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json").setBody("{\"error\":\"slow\",\"retryAfterSeconds\":6}"))
        val error = runCatching { repository.capabilities() }.exceptionOrNull() as ApiException
        assertEquals(6L, error.error.retryAfterSeconds)
    }

    @Test fun replayed401DoesNotTriggerASecondRefresh() = runTest {
        store.value = savedSession("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
        server.enqueue(json(SESSION.replace("access-one", "new-access").replace("refresh-one", "new-refresh")))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"still unauthorized\"}"))
        val error = runCatching { repository.entries() }.exceptionOrNull() as ApiException
        assertEquals(401, error.error.status)
        assertEquals(3, server.requestCount)
        assertEquals("new-refresh", store.value?.refreshToken)
    }

    @Test fun backupPassphraseLengthIsBlockedBeforeNetwork() = runTest {
        store.value = savedSession()
        val backup = EncryptedBackup("f", "k", 1, "s", "i", "c")
        assertEquals(400, (runCatching { repository.importEncrypted(backup, "short") }.exceptionOrNull() as ApiException).error.status)
        assertEquals(0, server.requestCount)
    }

    @Test fun concurrentUnauthorizedCallsShareOneRefreshRotation() = runTest {
        store.value = savedSession("old-access", "old-refresh")
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/v1/auth/refresh" -> {
                    refreshes.incrementAndGet(); json(SESSION.replace("access-one", "new-access").replace("refresh-one", "new-refresh"))
                }
                request.path == "/api/v1/entries" && request.getHeader("Authorization") == "Bearer old-access" ->
                    MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}")
                request.path == "/api/v1/entries" && request.getHeader("Authorization") == "Bearer new-access" -> json("""{"entries":[]}""")
                else -> MockResponse().setResponseCode(500)
            }
        }
        awaitAll(async { repository.entries() }, async { repository.entries() })
        assertEquals(1, refreshes.get())
        assertEquals("new-refresh", store.value?.refreshToken)
    }

    @Test fun logoutUsesV1BearerRouteAndClearsLocalSession() = runTest {
        store.value = savedSession()
        server.enqueue(json("""{"ok":true}"""))
        repository.logout()
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/logout", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
        assertNull(store.value)
    }

    @Test fun successfulPasswordChangeClearsTheLocalSession() = runTest {
        store.value = savedSession()
        server.enqueue(json("""{"ok":true}"""))
        repository.changePassword("Old-password-1!", "New-password-2!")
        val request = server.takeRequest()
        assertEquals("/api/v1/me/password", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("New-password-2!"))
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test fun batchCodesHonorTheAdvertisedCapabilityLimit() = runTest {
        server.enqueue(json(CAPABILITIES.replace("\"extensionBatchMaxIds\":100", "\"extensionBatchMaxIds\":2")))
        repository.capabilities()
        server.takeRequest()
        store.value = savedSession()
        server.enqueue(json("""{"serverTime":100,"items":[]}"""))
        server.enqueue(json("""{"serverTime":101,"items":[]}"""))
        server.enqueue(json("""{"serverTime":102,"items":[]}"""))
        repository.codesBatch(listOf(1, 2, 3, 4, 5))
        val bodies = List(3) { server.takeRequest().body.readUtf8() }
        assertTrue(bodies[0].contains("[1,2]")); assertTrue(bodies[1].contains("[3,4]")); assertTrue(bodies[2].contains("[5]"))
    }

    @Test fun emptyRefreshBodyPreservesSessionForRetry() = runTest {
        store.value = savedSession("old-access", "old-refresh")
        val clearsBefore = store.clearCount
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))
        runCatching { repository.entries() }
        assertEquals("old-refresh", store.value?.refreshToken)
        assertEquals(clearsBefore, store.clearCount)
    }

    @Test fun refreshNetworkAndServerErrorsPreserveSession() = runTest {
        suspend fun exercise(refreshResponse: MockResponse) {
            store.value = savedSession("old-access", "old-refresh")
            val clearsBefore = store.clearCount
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
            server.enqueue(refreshResponse)
            runCatching { repository.entries() }
            assertEquals("old-refresh", store.value?.refreshToken)
            assertEquals(clearsBefore, store.clearCount)
        }
        exercise(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        exercise(MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}"))
        exercise(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("{\"error\":\"slow\"}"))
    }

    @Test fun refreshPersistenceFailureDoesNotClearExistingSession() = runTest {
        store.value = savedSession("old-access", "old-refresh")
        store.failSave = true
        val clearsBefore = store.clearCount
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
        server.enqueue(json(SESSION))
        runCatching { repository.entries() }
        assertEquals("old-refresh", store.value?.refreshToken)
        assertEquals(clearsBefore, store.clearCount)
        store.failSave = false
    }


    @Test fun cancellingRefreshDoesNotClearAStillValidStoredSession() = runBlocking {
        store.value = savedSession("old-access", "old-refresh")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"expired\"}"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { repository.entries() }
        withTimeout(5_000) { while (server.requestCount < 2) delay(10) }
        job.cancelAndJoin()
        assertEquals("old-refresh", store.value?.refreshToken)
        assertEquals(0, store.clearCount)
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun savedSession(access: String = "access", refresh: String = "refresh") = ApiSession(
        access, refresh, Long.MAX_VALUE, user = UserProfile(1, "alice", "user"),
    )

    private class FakeStore : SessionStore {
        var value: ApiSession? = null
        var clearCount = 0
        var failSave = false
        override fun load(): ApiSession? = value
        override fun save(session: ApiSession) { if (failSave) error("disk full"); value = session }
        override fun clear() { value = null; clearCount++ }
    }

    companion object {
        private val CAPABILITIES = """
            {"apiVersion":"v1","compatibleClients":["android","browser_extension"],
             "auth":{"scheme":"Bearer","accessTokenExpiresIn":604800,"refreshTokenExpiresIn":7776000,"refreshTokenRotation":true,"turnstileRequired":true,"turnstileSiteKey":"site"},
             "limits":{"extensionBatchMaxIds":100},
             "endpoints":{"login":"/api/v1/auth/login","refresh":"/api/v1/auth/refresh","logout":"/api/v1/auth/logout","me":"/api/v1/me","changePassword":"/api/v1/me/password","entries":"/api/v1/entries","groups":"/api/v1/groups","codesBatch":"/api/v1/codes/batch","importOtpAuth":"/api/v1/import/otpauth","exportEncrypted":"/api/v1/export/encrypted","importEncrypted":"/api/v1/import/encrypted"}}
        """.trimIndent()
        private val SESSION = """
            {"ok":true,"user":{"id":1,"username":"alice","role":"user"},"accessToken":"access-one","refreshToken":"refresh-one","expiresIn":3600,"refreshExpiresIn":7200,"sessionId":9}
        """.trimIndent()
    }
}
