package com.dengrb1.twfauth.cloudflare.data

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dengrb1.twfauth.cloudflare.data.session.TokenStore
import com.dengrb1.twfauth.cloudflare.domain.ApiSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class TokenStoreMigrationTest {
    @Test fun legacyEncryptedKeysRemainReadable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        TokenStore(context, true).clear()
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            context, TokenStore.FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.edit().putString("access_token", "legacy-access").putString("refresh_token", "legacy-refresh")
            .putLong("access_expires_at", 1234L).commit()
        val restored = TokenStore(context, true).load()
        assertNotNull(restored)
        assertEquals("legacy-access", restored?.accessToken)
        assertEquals("legacy-refresh", restored?.refreshToken)
        assertEquals(1234L, restored?.accessExpiresAtMillis)
    }

    @Test fun deviceWithoutSecureLockKeepsAndClearsOnlyTheMemorySession() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TokenStore(context, false)
        store.save(ApiSession("memory-access", "memory-refresh", Long.MAX_VALUE))
        assertEquals("memory-access", store.load()?.accessToken)
        store.clear()
        assertNull(store.load())
        assertNull(TokenStore(context, false).load())
    }
}
