package com.dengrb1.twfauth.cloudflare.ui

import androidx.compose.runtime.Immutable

@Immutable
data class PlatformActions(
    val requestDeviceUnlock: suspend () -> Boolean = { true },
    val requestTurnstileToken: suspend (siteKey: String) -> String? = { null },
    val scanOtpAuth: suspend () -> String? = { null },
    val readTextFile: suspend (mimeTypes: Array<String>) -> String? = { null },
    val writeTextFile: suspend (
        suggestedName: String,
        mimeType: String,
        content: String,
    ) -> Boolean = { _, _, _ -> false },
    val copyToClipboard: (label: String, text: String) -> Unit = { _, _ -> },
)

object UiTestTags {
    const val AUTH_FORM = "auth_form"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val REMEMBER_PASSWORD = "remember_password"
    const val LOGIN = "login"
    const val UNLOCK = "unlock"
    const val BOTTOM_NAV = "bottom_nav"
    const val CODES_LIST = "codes_list"
    const val CODES_SEARCH = "codes_search"
    const val GROUP_FILTERS = "group_filters"
    const val ADD_ENTRY = "add_entry"
    const val SCAN_FAB = "scan_fab"
    const val ENTRY_FORM = "entry_form"
    const val ENTRY_LABEL = "entry_label"
    const val ENTRY_SECRET = "entry_secret"
    const val ENTRY_SAVE = "entry_save"
    const val OTPAUTH_URI = "otpauth_uri"
    const val SCAN_ENTRY = "scan_entry"
    const val ENTRY_CANCEL = "entry_cancel"
    const val GROUPS_LIST = "groups_list"
    const val ADD_GROUP = "add_group"
    const val GROUP_NAME = "group_name"
    const val GROUP_SAVE = "group_save"
    const val BACKUP_SCREEN = "backup_screen"
    const val IMPORT_CONTENT = "import_content"
    const val PICK_IMPORT_FILE = "pick_import_file"
    const val BACKUP_PASSPHRASE = "backup_passphrase"
    const val EXPORT_ENCRYPTED = "export_encrypted"
    const val SETTINGS_SCREEN = "settings_screen"
    const val CHANGE_PASSWORD = "change_password"
    const val CURRENT_PASSWORD = "current_password"
    const val NEW_PASSWORD = "new_password"
    const val PASSWORD_SAVE = "password_save"
    const val ERROR_BANNER = "error_banner"

    fun destination(route: String) = "destination_$route"
    fun destinationTab(route: String) = "destination_tab_$route"
    fun entry(id: Long) = "entry_$id"
    fun entryCode(id: Long) = "entry_code_$id"
    fun entrySelect(id: Long) = "entry_select_$id"
    fun group(id: Long) = "group_$id"
    fun themeOption(name: String) = "theme_${name.lowercase()}"
    fun languageOption(name: String) = "language_${name.lowercase()}"
    const val APP_LOCK = "app_lock"
}
