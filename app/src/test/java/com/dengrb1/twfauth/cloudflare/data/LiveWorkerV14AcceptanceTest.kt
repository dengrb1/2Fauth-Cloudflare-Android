package com.dengrb1.twfauth.cloudflare.data

import com.dengrb1.twfauth.cloudflare.data.remote.ApiFactory
import com.dengrb1.twfauth.cloudflare.data.session.SessionStore
import com.dengrb1.twfauth.cloudflare.domain.ApiSession
import com.dengrb1.twfauth.cloudflare.domain.CreateEntryInput
import com.dengrb1.twfauth.cloudflare.domain.EntryPatch
import com.dengrb1.twfauth.cloudflare.domain.OtpAlgorithm
import com.dengrb1.twfauth.cloudflare.domain.OtpType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional end-to-end acceptance against an actual v1.4 Worker. CI skips it unless the three
 * LIVE_WORKER_* environment variables are provided; local release verification can point it at a
 * disposable Wrangler deployment without embedding credentials in source or Gradle files.
 */
class LiveWorkerV14AcceptanceTest {
    @Test fun androidRepositoryCompletesTheV14AcceptanceFlow(): Unit = runBlocking {
        val baseUrl = System.getenv("LIVE_WORKER_URL")?.trim().orEmpty()
        val username = System.getenv("LIVE_WORKER_USERNAME")?.trim().orEmpty()
        val password = System.getenv("LIVE_WORKER_PASSWORD").orEmpty()
        assumeTrue("LIVE_WORKER_URL, LIVE_WORKER_USERNAME and LIVE_WORKER_PASSWORD are required", baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank())

        val store = MemoryStore()
        val repository = DefaultTwoFactorRepository(ApiFactory.create(baseUrl), store)
        val prefix = "android-${UUID.randomUUID().toString().take(8)}"
        val replacementPassword = "Temp-${UUID.randomUUID().toString().take(12)}-aA1!"
        val backupPassphrase = "Backup-${UUID.randomUUID()}"
        var activePassword = password

        try {
            val capabilities = repository.requireCompatibleCapabilities()
            assertEquals("v1", capabilities.apiVersion)
            assertTrue("android" in capabilities.compatibleClients)
            assertEquals("Bearer", capabilities.auth.scheme)

            repository.login(username, password)
            assertEquals(username, repository.me().username)

            val groupId = repository.createGroup("$prefix-group", "#0F766E")
            repository.patchGroup(groupId, "$prefix-renamed", "#112233")
            assertEquals("$prefix-renamed", repository.groups().single { it.id == groupId }.name)

            val totpId = repository.createEntry(
                CreateEntryInput(
                    label = "$prefix-totp", issuer = "Codex", secret = "JBSWY3DPEHPK3PXP",
                    otpType = OtpType.TOTP, algorithm = OtpAlgorithm.SHA1, groupId = groupId,
                ),
            )
            val hotpId = repository.createEntry(
                CreateEntryInput(
                    label = "$prefix-hotp", issuer = "Codex", secret = "JBSWY3DPEHPK3PXP",
                    otpType = OtpType.HOTP, algorithm = OtpAlgorithm.SHA256, hotpCounter = 0,
                    groupId = groupId,
                ),
            )
            repository.patchEntry(totpId, EntryPatch(label = "$prefix-totp-edited"))
            repository.setEntryEnabled(totpId, false)
            assertFalse(repository.entries().single { it.id == totpId }.enabled)
            repository.setEntryEnabled(totpId, true)

            val code = repository.codesBatch(listOf(totpId)).items.single()
            assertEquals(totpId, code.id)
            assertTrue(code.code?.matches(Regex("\\d{6}")) == true)
            assertNotNull(code.expiresIn)
            val hotp = repository.consumeHotp(hotpId)
            assertTrue(hotp.code.matches(Regex("\\d{6}")))
            assertEquals(1L, hotp.nextCounter)

            val imported = repository.importOtpAuth(
                "otpauth://totp/Codex:$prefix-import?secret=JBSWY3DPEHPK3PXP&issuer=Codex&algorithm=SHA1&digits=6&period=30",
                groupId,
            )
            assertEquals(1, imported.found)
            assertEquals(1, imported.imported)
            assertEquals(0, imported.failed)

            val backup = repository.exportEncrypted(backupPassphrase)
            assertTrue(backup.ciphertext.isNotBlank())
            val encryptedImport = repository.importEncrypted(backup, backupPassphrase)
            assertTrue(encryptedImport.entries >= 3)

            repository.changePassword(password, replacementPassword)
            activePassword = replacementPassword
            assertNull(store.value)
            repository.login(username, replacementPassword)
            assertEquals(username, repository.me().username)
            repository.changePassword(replacementPassword, password)
            activePassword = password
            assertNull(store.value)
            repository.login(username, password)
        } finally {
            if (repository.session() == null) {
                runCatching { repository.login(username, activePassword) }
                    .recoverCatching { repository.login(username, password) }
            }
            repository.session()?.let {
                runCatching {
                    repository.entries().filter { it.label.startsWith(prefix) }.forEach { repository.deleteEntry(it.id) }
                    repository.groups().filter { it.name.startsWith(prefix) }.forEach { repository.deleteGroup(it.id) }
                }
                if (activePassword != password) {
                    runCatching { repository.changePassword(activePassword, password) }
                    runCatching { repository.login(username, password) }
                }
                runCatching { repository.logout() }
            }
        }
    }

    private class MemoryStore : SessionStore {
        var value: ApiSession? = null
        override fun load(): ApiSession? = value
        override fun save(session: ApiSession) { value = session }
        override fun clear() { value = null }
    }
}
