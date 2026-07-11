package com.dengrb1.twfauth.cloudflare.data.session

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dengrb1.twfauth.cloudflare.domain.ApiSession

/** Keeps the existing encrypted preference file and key names so installed users stay signed in. */
interface SessionStore {
    fun load(): ApiSession?
    fun save(session: ApiSession)
    fun clear()
}

@SuppressLint("ApplySharedPref") // commit is intentional: token rotation/logout must be atomic before requests continue.
class TokenStore(
    context: Context,
    private val persistenceAllowed: Boolean,
) : SessionStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Volatile private var memorySession: ApiSession? = null

    init {
        if (!persistenceAllowed) prefs.edit().clear().commit()
    }

    @Synchronized
    override fun load(): ApiSession? {
        memorySession?.let { return it }
        if (!persistenceAllowed) return null
        val access = prefs.getString(KEY_ACCESS, null)
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) return null
        return ApiSession(
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAtMillis = prefs.getLong(KEY_ACCESS_EXPIRES, 0),
            refreshExpiresAtMillis = prefs.getLong(KEY_REFRESH_EXPIRES, 0).takeIf { it > 0 },
            user = prefs.getLong(KEY_USER_ID, 0).takeIf { it > 0 }?.let { id ->
                com.dengrb1.twfauth.cloudflare.domain.UserProfile(
                    id = id,
                    username = prefs.getString(KEY_USERNAME, "").orEmpty(),
                    role = prefs.getString(KEY_ROLE, "user").orEmpty(),
                )
            },
            sessionId = prefs.getLong(KEY_SESSION_ID, 0).takeIf { it > 0 },
        ).also { memorySession = it }
    }

    @Synchronized
    override fun save(session: ApiSession) {
        if (!persistenceAllowed) {
            memorySession = session
            return
        }
        val editor = prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_ACCESS_EXPIRES, session.accessExpiresAtMillis)
        session.refreshExpiresAtMillis?.let { editor.putLong(KEY_REFRESH_EXPIRES, it) } ?: editor.remove(KEY_REFRESH_EXPIRES)
        session.sessionId?.let { editor.putLong(KEY_SESSION_ID, it) } ?: editor.remove(KEY_SESSION_ID)
        session.user?.let {
            editor.putLong(KEY_USER_ID, it.id)
                .putString(KEY_USERNAME, it.username)
                .putString(KEY_ROLE, it.role)
        } ?: editor.remove(KEY_USER_ID).remove(KEY_USERNAME).remove(KEY_ROLE)
        check(editor.commit()) { "Unable to atomically persist API session" }
        memorySession = session
    }

    @Synchronized
    override fun clear() {
        if (!persistenceAllowed) {
            memorySession = null
            return
        }
        check(prefs.edit().clear().commit()) { "Unable to atomically clear API session" }
        memorySession = null
    }

    companion object {
        const val FILE_NAME = "api_session"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCESS_EXPIRES = "access_expires_at"
        private const val KEY_REFRESH_EXPIRES = "refresh_expires_at"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
    }
}
