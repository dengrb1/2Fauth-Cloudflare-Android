package com.dengrb1.twfauth.cloudflare.data.session

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Last successful login credentials kept separately from the API session tokens. */
data class SavedLoginCredentials(
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
)

interface CredentialStore {
    fun load(): SavedLoginCredentials
    fun saveLogin(username: String, password: String?, rememberPassword: Boolean)
    fun clearPassword()
}

@SuppressLint("ApplySharedPref")
class EncryptedCredentialStore(
    context: Context,
) : CredentialStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    override fun load(): SavedLoginCredentials {
        val username = prefs.getString(KEY_USERNAME, "").orEmpty()
        val rememberPassword = prefs.getBoolean(KEY_REMEMBER_PASSWORD, true)
        val password = if (rememberPassword) prefs.getString(KEY_PASSWORD, "").orEmpty() else ""
        return SavedLoginCredentials(username = username, password = password, rememberPassword = rememberPassword)
    }

    @Synchronized
    override fun saveLogin(username: String, password: String?, rememberPassword: Boolean) {
        val editor = prefs.edit()
            .putString(KEY_USERNAME, username.trim())
            .putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
        if (rememberPassword && !password.isNullOrEmpty()) {
            editor.putString(KEY_PASSWORD, password)
        } else {
            editor.remove(KEY_PASSWORD)
        }
        check(editor.commit()) { "Unable to persist login credentials" }
    }

    @Synchronized
    override fun clearPassword() {
        check(prefs.edit().remove(KEY_PASSWORD).commit()) { "Unable to clear remembered password" }
    }

    companion object {
        const val FILE_NAME = "login_credentials"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
    }
}
